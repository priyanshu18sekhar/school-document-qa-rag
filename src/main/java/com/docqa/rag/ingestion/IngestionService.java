package com.docqa.rag.ingestion;

import com.docqa.rag.document.DocumentRepository;
import com.docqa.rag.ingestion.chunk.Chunker;
import com.docqa.rag.ingestion.chunk.TextChunk;
import com.docqa.rag.ingestion.extract.ExtractionResult;
import com.docqa.rag.ingestion.extract.TextExtractor;
import com.docqa.rag.ingestion.extract.TextExtractorRegistry;
import com.docqa.rag.model.ResilientEmbeddingModel;
import com.docqa.rag.observability.RagMetrics;
import com.docqa.rag.observability.RequestContext;
import com.docqa.rag.tenant.TenantId;
import jakarta.annotation.PreDestroy;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Runs a staged upload through extract → chunk → embed → persist.
 *
 * <p>Everything here happens on an ingestion worker thread, never on an HTTP
 * thread. {@link #submit} is the only entry point and returns as soon as the
 * job is queued.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final ThreadPoolExecutor executor;
    private final UploadStagingStore staging;
    private final TextExtractorRegistry extractors;
    private final Chunker chunker;
    private final ResilientEmbeddingModel embeddings;
    private final IngestionWriter writer;
    private final DocumentRepository documents;
    private final RagMetrics metrics;
    private final int shutdownGraceSeconds;

    public IngestionService(ThreadPoolExecutor ingestionExecutor,
                            UploadStagingStore staging,
                            TextExtractorRegistry extractors,
                            Chunker chunker,
                            ResilientEmbeddingModel embeddings,
                            IngestionWriter writer,
                            DocumentRepository documents,
                            RagMetrics metrics,
                            com.docqa.rag.config.RagProperties properties) {
        this.executor = ingestionExecutor;
        this.staging = staging;
        this.extractors = extractors;
        this.chunker = chunker;
        this.embeddings = embeddings;
        this.writer = writer;
        this.documents = documents;
        this.metrics = metrics;
        this.shutdownGraceSeconds = properties.ingestion().shutdownGraceSeconds();
    }

    /** Everything the worker needs; no request-scoped state. */
    public record IngestionJob(
            TenantId tenantId,
            UUID documentId,
            Path stagedFile,
            String filename,
            String contentType,
            String title,
            @Nullable String category
    ) {}

    /**
     * Queues a document for ingestion.
     *
     * @throws IngestionQueueFullException when the bounded queue is saturated;
     *         the caller turns this into a 503 and the staged file is removed
     */
    public void submit(IngestionJob job) {
        RequestContext context = RequestContext.capture();
        try {
            executor.execute(context.wrap(() -> runWithDocumentContext(job)));
        } catch (RejectedExecutionException e) {
            // Be honest with the caller rather than accepting work we will not
            // do. See IngestionExecutorConfig for why AbortPolicy.
            documents.markFailed(job.tenantId(), job.documentId(),
                    "Ingestion queue is full; the upload was not processed. Please retry.");
            staging.deleteQuietly(job.stagedFile());
            throw new IngestionQueueFullException(
                    "Too many documents are queued for ingestion. Please retry shortly.", e);
        }
    }

    public int queueDepth() {
        return executor.getQueue().size();
    }

    public int activeWorkers() {
        return executor.getActiveCount();
    }

    private void runWithDocumentContext(IngestionJob job) {
        RequestContext.with(RequestContext.DOCUMENT_ID, job.documentId().toString(),
                () -> ingest(job));
    }

    void ingest(IngestionJob job) {
        long startNanos = System.nanoTime();
        String outcome = "failed";
        try {
            byte[] content = staging.read(job.stagedFile());

            // ---- 1. Extract, preserving page positions -----------------------
            TextExtractor extractor = extractors.resolve(job.filename(), job.contentType());
            ExtractionResult extraction = extractor.extract(content, job.filename());

            // ---- 2. Chunk ----------------------------------------------------
            List<TextChunk> chunks = chunker.chunk(extraction.segments(), job.title());
            if (chunks.isEmpty()) {
                writer.commitFailure(job.tenantId(), job.documentId(),
                        "No text could be extracted from the document.");
                log.warn("Ingestion produced no chunks for '{}'", job.filename());
                return;
            }

            // ---- 3. Embed, in batches, outside any transaction ---------------
            List<String> texts = chunks.stream().map(TextChunk::embeddingText).toList();
            List<float[]> vectors = embeddings.embedAll(texts);

            // ---- 4. Persist atomically --------------------------------------
            writer.commitChunks(job.tenantId(), job.documentId(), job.category(),
                    chunks, vectors, extraction.pageCount());

            outcome = "ready";
            log.info("Ingested '{}': {} page(s), {} chunk(s)",
                    job.filename(), extraction.pageCount(), chunks.size());

        } catch (Exception e) {
            // The message is written to documents.error_message and returned to
            // the caller, so it must be safe to display. getMessage() on our own
            // exceptions is written for exactly that; anything else is reduced
            // to a generic line and the detail goes to the log only.
            String reason = safeReason(e);
            log.error("Ingestion failed for '{}': {}", job.filename(), reason, e);
            try {
                writer.commitFailure(job.tenantId(), job.documentId(), reason);
            } catch (Exception nested) {
                log.error("Could not record the ingestion failure for document {}",
                        job.documentId(), nested);
            }
        } finally {
            staging.deleteQuietly(job.stagedFile());
            metrics.recordIngestion(Duration.ofNanos(System.nanoTime() - startNanos), outcome);
        }
    }

    private static String safeReason(Exception e) {
        if (e instanceof com.docqa.rag.ingestion.extract.DocumentExtractionException
                || e instanceof com.docqa.rag.ingestion.extract.UnsupportedDocumentTypeException
                || e instanceof com.docqa.rag.model.ModelUnavailableException) {
            return e.getMessage();
        }
        return "The document could not be processed. See the service logs for details.";
    }

    /**
     * Drain in-flight ingestion on shutdown rather than dropping it.
     *
     * <p>Without this, a rolling deploy leaves documents stuck in PROCESSING
     * forever: the executor's threads are killed mid-job, nothing writes a
     * terminal status, and the row is indistinguishable from one that is still
     * running. Queued-but-unstarted jobs are still lost - the queue is in
     * memory - which is written up as a known limitation.
     */
    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(shutdownGraceSeconds, TimeUnit.SECONDS)) {
                int abandoned = executor.shutdownNow().size();
                log.warn("Ingestion executor did not drain in {}s; {} queued job(s) abandoned",
                        shutdownGraceSeconds, abandoned);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
