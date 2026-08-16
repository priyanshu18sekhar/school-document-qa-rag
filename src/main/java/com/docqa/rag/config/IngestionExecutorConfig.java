package com.docqa.rag.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The ingestion executor (NFR-3).
 *
 * <h2>Platform threads, not virtual threads</h2>
 *
 * <p>HTTP request handling in this service <em>does</em> run on virtual threads
 * ({@code spring.threads.virtual.enabled=true}), which is what actually
 * satisfies "ingesting a 50-page PDF does not block an HTTP request thread" -
 * the upload handler returns 202 immediately and never waits for ingestion.
 *
 * <p>Ingestion workers are deliberately platform threads in a small fixed pool,
 * for a reason that runs opposite to the usual advice. Virtual threads are the
 * right answer when you want unbounded concurrency for blocking I/O. Here,
 * unbounded concurrency is precisely the bug: the slow part of ingestion is the
 * embedding provider, which rate-limits per account. Ten documents ingesting
 * concurrently on virtual threads would fire ten parallel batches, collect
 * 429s, and drive the retry logic into a self-inflicted outage. A pool of four
 * is a deliberate concurrency limit on outbound embedding traffic. PDF parsing
 * is also genuinely CPU-bound, and virtual threads do nothing for CPU-bound
 * work.
 *
 * <h2>Bounded queue, and what happens when it fills</h2>
 *
 * <p>{@link ThreadPoolExecutor.AbortPolicy} - the default - is the right policy
 * and the alternatives are all worse here:
 * <ul>
 *   <li>An unbounded queue converts a load spike into an OutOfMemoryError,
 *       taking down question answering along with ingestion.</li>
 *   <li>{@code CallerRunsPolicy} would run a 30-second ingestion on the HTTP
 *       request thread, which is exactly what NFR-3 prohibits.</li>
 *   <li>{@code DiscardPolicy} would accept the upload, return 202, and silently
 *       never process it - the worst possible outcome for a school
 *       administrator who now believes the fee policy is searchable.</li>
 * </ul>
 * Aborting lets the upload endpoint answer 503 honestly, and the caller can
 * retry - which is safe, because ingestion is idempotent on content hash.
 */
@Configuration
public class IngestionExecutorConfig {

    private static final Logger log = LoggerFactory.getLogger(IngestionExecutorConfig.class);

    @Bean(name = "ingestionExecutor", destroyMethod = "")
    public ThreadPoolExecutor ingestionExecutor(RagProperties properties) {
        var config = properties.ingestion();

        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "ingest-" + counter.getAndIncrement());
                thread.setDaemon(false);   // let graceful shutdown drain in-flight work
                thread.setUncaughtExceptionHandler((t, e) ->
                        log.error("Uncaught error on ingestion worker {}", t.getName(), e));
                return thread;
            }
        };

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                config.workerThreads(),
                config.workerThreads(),
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.queueCapacity()),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
        executor.prestartAllCoreThreads();

        log.info("Ingestion executor started: {} worker(s), queue capacity {}",
                config.workerThreads(), config.queueCapacity());
        return executor;
    }
}
