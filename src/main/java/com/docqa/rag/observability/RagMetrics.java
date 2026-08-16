package com.docqa.rag.observability;

import com.docqa.rag.config.RagProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Per-request metrics required by FR-9: retrieval latency, model latency, token
 * counts in and out, estimated cost.
 *
 * <p><b>Tenant is not a metric tag.</b> It is tempting and it is a trap: tenant
 * id is unbounded caller-supplied input, so tagging with it lets any client
 * create unlimited time series and exhaust the registry - a denial of service
 * through the metrics backend. Per-tenant numbers, when needed, come from the
 * database (the {@code messages} table has token counts and latencies per row)
 * or from logs, both of which are designed for high cardinality. The same
 * reasoning excludes model names supplied per request.
 *
 * <p>Cost is a counter of USD, recorded as a double. It is an <em>estimate</em>:
 * derived from provider-reported token counts where available and the local
 * tokeniser otherwise, multiplied by prices from configuration. It is right for
 * spotting a runaway loop and wrong for reconciling an invoice, and the metric
 * name says so.
 */
@Component
public class RagMetrics {

    private final MeterRegistry registry;
    private final RagProperties.Cost prices;

    private final Timer retrievalLatency;
    private final Timer chatLatency;
    private final Timer embeddingLatency;
    private final Timer ingestionLatency;
    private final Counter refusals;
    private final Counter answered;
    private final DistributionSummary chunksRetrieved;

    public RagMetrics(MeterRegistry registry, RagProperties properties) {
        this.registry = registry;
        this.prices = properties.cost();

        this.retrievalLatency = Timer.builder("rag.retrieval.latency")
                .description("Query embedding plus vector search, end to end")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        this.chatLatency = Timer.builder("rag.model.chat.latency")
                .description("Time spent inside the chat model call")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        this.embeddingLatency = Timer.builder("rag.model.embedding.latency")
                .description("Time spent inside embedding calls")
                .publishPercentiles(0.5, 0.95)
                .register(registry);

        this.ingestionLatency = Timer.builder("rag.ingestion.latency")
                .description("Full document ingestion: extract, chunk, embed, persist")
                .publishPercentiles(0.5, 0.95)
                .register(registry);

        this.refusals = Counter.builder("rag.answers")
                .tag("outcome", "refused")
                .description("Questions where no chunk cleared the similarity threshold")
                .register(registry);

        this.answered = Counter.builder("rag.answers")
                .tag("outcome", "grounded")
                .description("Questions answered from retrieved context")
                .register(registry);

        this.chunksRetrieved = DistributionSummary.builder("rag.retrieval.chunks")
                .description("Chunks that cleared the threshold per question")
                .register(registry);
    }

    public Timer.Sample start() {
        return Timer.start(registry);
    }

    public void recordRetrieval(Duration duration, int chunkCount) {
        retrievalLatency.record(duration);
        chunksRetrieved.record(chunkCount);
    }

    public void recordChat(Duration duration, int promptTokens, int completionTokens, String model) {
        chatLatency.record(duration);
        recordTokens("chat", "input", promptTokens, model);
        recordTokens("chat", "output", completionTokens, model);
        recordCost(promptTokens * prices.chatInputPerMillion() / 1_000_000d
                + completionTokens * prices.chatOutputPerMillion() / 1_000_000d, "chat", model);
    }

    public void recordEmbedding(Duration duration, int tokens, int batchCount, String model) {
        embeddingLatency.record(duration);
        recordTokens("embedding", "input", tokens, model);
        recordCost(tokens * prices.embeddingPerMillion() / 1_000_000d, "embedding", model);
        Counter.builder("rag.model.embedding.batches")
                .description("Embedding API calls made; compare against chunk count to verify batching")
                .register(registry)
                .increment(batchCount);
    }

    public void recordIngestion(Duration duration, String outcome) {
        ingestionLatency.record(duration);
        Counter.builder("rag.ingestion.documents")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    public void recordRefusal() {
        refusals.increment();
    }

    public void recordGroundedAnswer() {
        answered.increment();
    }

    private void recordTokens(String call, String direction, int tokens, String model) {
        if (tokens <= 0) {
            return;
        }
        DistributionSummary.builder("rag.model.tokens")
                .tag("call", call)
                .tag("direction", direction)
                .tag("model", model)
                .description("Token counts, provider-reported where available")
                .register(registry)
                .record(tokens);
    }

    private void recordCost(double usd, String call, String model) {
        if (usd <= 0) {
            return;
        }
        Counter.builder("rag.model.cost.usd.estimated")
                .tag("call", call)
                .tag("model", model)
                .description("Estimated spend. Derived from token counts and configured prices; "
                        + "suitable for alerting, not for billing reconciliation.")
                .register(registry)
                .increment(usd);
    }
}
