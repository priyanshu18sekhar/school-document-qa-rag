package com.docqa.rag.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Every tunable in the system, in one place, validated at startup.
 *
 * <p>These are records with constraint annotations rather than loose {@code @Value}
 * injections for one practical reason: a typo in {@code application.yml} or a
 * nonsensical value (negative overlap, threshold of 4.0) fails the context
 * refresh with a readable message instead of surfacing three hours later as
 * bad retrieval that nobody can explain.
 *
 * <p>The defaults live in {@code application.yml}, not here, so that the YAML
 * remains the single readable description of how the service is configured.
 */
@Validated
@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        @Valid @NotNull Tenant tenant,
        @Valid @NotNull Ingestion ingestion,
        @Valid @NotNull Chunking chunking,
        @Valid @NotNull Embedding embedding,
        @Valid @NotNull Retrieval retrieval,
        @Valid @NotNull Chat chat,
        @Valid @NotNull Resilience resilience,
        @Valid @NotNull Cost cost
) {

    public record Tenant(
            @NotBlank String header,
            @Min(1) @Max(256) int maxLength
    ) {}

    public record Ingestion(
            @Min(1) @Max(64) int workerThreads,
            @Min(1) @Max(10_000) int queueCapacity,
            @Min(0) int shutdownGraceSeconds
    ) {}

    public record Chunking(
            @Min(32) @Max(8192) int maxTokens,
            @Min(0) int overlapTokens,
            @Min(1) int minTokens
    ) {
        public Chunking {
            // An overlap at or above the chunk size means each chunk re-emits
            // its predecessor entirely and the splitter never advances.
            if (overlapTokens >= maxTokens) {
                throw new IllegalArgumentException(
                        "rag.chunking.overlap-tokens (%d) must be smaller than max-tokens (%d), "
                                .formatted(overlapTokens, maxTokens)
                                + "otherwise chunking cannot make forward progress");
            }
            if (minTokens > maxTokens) {
                throw new IllegalArgumentException(
                        "rag.chunking.min-tokens (%d) cannot exceed max-tokens (%d)"
                                .formatted(minTokens, maxTokens));
            }
        }
    }

    public record Embedding(
            @Min(1) @Max(16_384) int dimensions,
            @Min(1) @Max(2048) int batchSize,
            @Min(1000) int maxBatchTokens
    ) {}

    public record Retrieval(
            @Min(1) @Max(100) int topK,
            @Min(0) @Max(1) double similarityThreshold,
            @Min(1) @Max(50) int candidateMultiplier,
            @Min(1) @Max(1000) int hnswEfSearch
    ) {
        /** Rows pulled from the index before the threshold cut. */
        public int candidateLimit() {
            return topK * candidateMultiplier;
        }
    }

    public record Chat(
            @Min(0) @Max(100) int maxHistoryTurns,
            @Min(0) int historyTokenBudget,
            @Min(100) int contextTokenBudget,
            @Min(16) int maxOutputTokens,
            @Min(1) int modelTimeoutSeconds,
            boolean queryRewritingEnabled,
            @NotBlank String refusalMessage
    ) {}

    public record Resilience(
            @Valid @NotNull Retry retry,
            @Valid @NotNull CircuitBreaker circuitBreaker
    ) {
        public record Retry(
                @Min(1) @Max(10) int maxAttempts,
                @Min(1) long initialBackoffMs,
                @Min(1) double backoffMultiplier,
                @Min(1) long maxBackoffMs
        ) {}

        public record CircuitBreaker(
                @Min(1) @Max(100) float failureRateThreshold,
                @Min(1) int slowCallDurationSeconds,
                @Min(1) @Max(100) float slowCallRateThreshold,
                @Min(1) int slidingWindowSize,
                @Min(1) int minimumNumberOfCalls,
                @Min(1) int waitDurationInOpenStateSeconds,
                @Min(1) int permittedCallsInHalfOpenState
        ) {}
    }

    public record Cost(
            double chatInputPerMillion,
            double chatOutputPerMillion,
            double embeddingPerMillion
    ) {}
}
