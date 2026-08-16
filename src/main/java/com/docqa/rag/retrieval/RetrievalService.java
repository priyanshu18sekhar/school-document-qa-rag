package com.docqa.rag.retrieval;

import com.docqa.rag.config.RagProperties;
import com.docqa.rag.model.ResilientEmbeddingModel;
import com.docqa.rag.observability.RagMetrics;
import com.docqa.rag.tenant.TenantId;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Embed the question, search, decide whether we have grounding.
 *
 * <p>This class owns the decision that FR-6 is really about: whether the system
 * is allowed to answer at all. It returns a {@link RetrievalOutcome} whose
 * {@code grounded} flag is the gate, and the caller is expected to check it
 * before doing anything else.
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private final ResilientEmbeddingModel embeddings;
    private final VectorSearchRepository search;
    private final RagProperties.Retrieval config;
    private final RagMetrics metrics;

    public RetrievalService(ResilientEmbeddingModel embeddings,
                            VectorSearchRepository search,
                            RagProperties properties,
                            RagMetrics metrics) {
        this.embeddings = embeddings;
        this.search = search;
        this.config = properties.retrieval();
        this.metrics = metrics;
    }

    /**
     * @param chunks   what cleared the threshold, best first; empty when refused
     * @param grounded false means: do not call the model
     * @param nearMiss on a refusal, the closest chunk that did not make it -
     *                 diagnostic only, never shown to the user
     */
    public record RetrievalOutcome(
            List<RetrievedChunk> chunks,
            boolean grounded,
            Optional<VectorSearchRepository.NearMiss> nearMiss,
            Duration latency
    ) {
        public double topSimilarity() {
            return chunks.isEmpty() ? 0.0 : chunks.getFirst().similarity();
        }
    }

    public RetrievalOutcome retrieve(TenantId tenantId,
                                     String question,
                                     @Nullable String category) {
        long startNanos = System.nanoTime();

        // Both steps of NFR-1's budget: embedding the query, then the search.
        float[] queryVector = embeddings.embedQuery(question);

        List<RetrievedChunk> chunks = search.search(
                tenantId, queryVector, category, config.topK(), config.similarityThreshold());

        Duration latency = Duration.ofNanos(System.nanoTime() - startNanos);
        metrics.recordRetrieval(latency, chunks.size());

        if (!chunks.isEmpty()) {
            log.debug("Retrieved {} chunk(s) in {} ms, top similarity {}",
                    chunks.size(), latency.toMillis(),
                    String.format("%.4f", chunks.getFirst().similarity()));
            return new RetrievalOutcome(chunks, true, Optional.empty(), latency);
        }

        // Nothing cleared the bar. Find out how close we were, because
        // "no results" alone is untunable - it does not distinguish "the corpus
        // has nothing on this" from "the threshold is two points too high".
        Optional<VectorSearchRepository.NearMiss> nearMiss =
                search.findNearest(tenantId, queryVector, category);

        nearMiss.ifPresentOrElse(
                miss -> log.info("Refusing: no chunk cleared {}. Closest was {} at similarity {}",
                        config.similarityThreshold(),
                        miss.documentTitle() + (miss.pageNumber() != null
                                ? " p" + miss.pageNumber() : ""),
                        String.format("%.4f", miss.similarity())),
                () -> log.info("Refusing: no chunks available for this tenant"
                        + (category != null ? " in category " + category : "")));

        return new RetrievalOutcome(List.of(), false, nearMiss, latency);
    }

    public double threshold() {
        return config.similarityThreshold();
    }
}
