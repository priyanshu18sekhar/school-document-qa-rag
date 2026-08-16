package com.docqa.rag.model;

import com.docqa.rag.config.RagProperties;
import com.docqa.rag.observability.RagMetrics;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * Batched, retried, circuit-broken embedding.
 *
 * <h2>Batching (FR-2)</h2>
 *
 * <p>A 50-page PDF produces roughly 150 chunks. One HTTP call per chunk is 150
 * round trips at ~120 ms each - about 18 seconds of pure latency, plus 150
 * chances to hit a rate limit. Batching cuts it to three calls.
 *
 * <p>Batches are bounded by <em>both</em> a chunk count and a token total,
 * because the two limits bind in different situations: the count keeps request
 * bodies and memory sane for ordinary chunks, and the token cap is what the
 * provider actually enforces (OpenAI rejects an embedding request over ~300k
 * tokens outright). Sizing only by count works fine on a corpus of short
 * paragraphs and then fails the first time somebody uploads a dense table.
 *
 * <h2>Order</h2>
 *
 * <p>The response is re-sorted by {@code Embedding.getIndex()} before use.
 * Providers are documented to preserve input order and generally do, but the
 * consequence of an out-of-order batch is silent and severe: every chunk in the
 * document gets somebody else's vector, retrieval returns confident nonsense,
 * and nothing anywhere throws. A sort is cheap insurance against a failure mode
 * that would be extremely hard to diagnose from the symptom.
 */
@Component
public class ResilientEmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(ResilientEmbeddingModel.class);

    private final ObjectProvider<EmbeddingModel> delegate;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final RagProperties.Embedding config;
    private final TokenCounter tokenCounter;
    private final RagMetrics metrics;

    public ResilientEmbeddingModel(ObjectProvider<EmbeddingModel> delegate,
                                   CircuitBreaker embeddingCircuitBreaker,
                                   Retry embeddingRetry,
                                   RagProperties properties,
                                   TokenCounter tokenCounter,
                                   RagMetrics metrics) {
        this.delegate = delegate;
        this.circuitBreaker = embeddingCircuitBreaker;
        this.retry = embeddingRetry;
        this.config = properties.embedding();
        this.tokenCounter = tokenCounter;
        this.metrics = metrics;
    }

    /** True when an embedding provider is configured at all. Used by the health check. */
    public boolean isAvailable() {
        return delegate.getIfAvailable() != null;
    }

    public int dimensions() {
        return require().dimensions();
    }

    /** Embeds one query. Same protection, batch of one. */
    public float[] embedQuery(String text) {
        return embedAll(List.of(text)).getFirst();
    }

    /**
     * Embeds every text, in as few provider calls as the limits allow.
     *
     * @return one vector per input, in input order
     */
    public List<float[]> embedAll(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        EmbeddingModel model = require();

        List<float[]> result = new ArrayList<>(texts.size());
        long startNanos = System.nanoTime();
        int totalTokens = 0;
        int batches = 0;

        for (List<String> batch : partition(texts)) {
            EmbeddingResponse response = callProtected(model, batch);
            result.addAll(inInputOrder(response, batch.size()));
            totalTokens += reportedTokens(response, batch);
            batches++;
        }

        metrics.recordEmbedding(Duration.ofNanos(System.nanoTime() - startNanos),
                totalTokens, batches, modelName());
        log.debug("Embedded {} texts in {} provider call(s), {} tokens",
                texts.size(), batches, totalTokens);
        return result;
    }

    /**
     * Split by chunk count and token total, whichever binds first.
     *
     * <p>Package-private for the unit test that checks a single oversized text
     * still ends up in a batch of its own rather than being dropped.
     */
    List<List<String>> partition(List<String> texts) {
        List<List<String>> batches = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int currentTokens = 0;

        for (String text : texts) {
            int tokens = tokenCounter.count(text);
            boolean wouldExceed = !current.isEmpty()
                    && (current.size() >= config.batchSize()
                        || currentTokens + tokens > config.maxBatchTokens());
            if (wouldExceed) {
                batches.add(current);
                current = new ArrayList<>();
                currentTokens = 0;
            }
            current.add(text);
            currentTokens += tokens;
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }

    private EmbeddingResponse callProtected(EmbeddingModel model, List<String> batch) {
        Supplier<EmbeddingResponse> call = () -> model.call(new EmbeddingRequest(batch, null));

        // Breaker inside, retry outside: the breaker sees and counts every
        // individual attempt, and the retry stops immediately once the breaker
        // starts rejecting (CallNotPermittedException is on the ignore list).
        Supplier<EmbeddingResponse> guarded =
                Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker, call));

        try {
            return guarded.get();
        } catch (CallNotPermittedException e) {
            throw new ModelUnavailableException(
                    "The embedding provider is currently unavailable and requests are being "
                            + "rejected while it recovers. Try again shortly.", e, true);
        } catch (RuntimeException e) {
            throw new ModelUnavailableException(
                    "The embedding provider could not be reached after %d attempts."
                            .formatted(retry.getRetryConfig().getMaxAttempts()), e, false);
        }
    }

    private static List<float[]> inInputOrder(EmbeddingResponse response, int expected) {
        List<Embedding> results = new ArrayList<>(response.getResults());
        if (results.size() != expected) {
            throw new IllegalStateException(
                    "Embedding provider returned %d vectors for %d inputs"
                            .formatted(results.size(), expected));
        }
        results.sort(Comparator.comparing(
                embedding -> embedding.getIndex() == null ? 0 : embedding.getIndex()));
        return results.stream().map(Embedding::getOutput).toList();
    }

    private int reportedTokens(EmbeddingResponse response, List<String> batch) {
        var metadata = response.getMetadata();
        if (metadata != null && metadata.getUsage() != null
                && metadata.getUsage().getTotalTokens() != null) {
            return metadata.getUsage().getTotalTokens();
        }
        return tokenCounter.count(batch);
    }

    private String modelName() {
        EmbeddingModel model = delegate.getIfAvailable();
        return model == null ? "none" : model.getClass().getSimpleName();
    }

    private EmbeddingModel require() {
        EmbeddingModel model = delegate.getIfAvailable();
        if (model == null) {
            throw new ModelUnavailableException(
                    "No embedding model is configured. Set spring.ai.model.embedding and supply "
                            + "the corresponding API key.", null, false);
        }
        return model;
    }
}
