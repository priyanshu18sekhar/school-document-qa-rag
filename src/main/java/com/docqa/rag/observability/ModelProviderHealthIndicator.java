package com.docqa.rag.observability;

import com.docqa.rag.config.ResilienceConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import com.docqa.rag.model.ResilientChatModel;
import com.docqa.rag.model.ResilientEmbeddingModel;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports the model provider in {@code /actuator/health} (FR-9).
 *
 * <p><b>This does not call the provider.</b> A health endpoint that makes a paid
 * API request every time a load balancer polls it is a bill and a rate-limit
 * waiting to happen, and it makes health checks fail for reasons unrelated to
 * this service's health.
 *
 * <p>Instead it reports what we already know from real traffic: whether a
 * provider is configured, and what the circuit breakers - which observe every
 * actual call - currently think. That is strictly more informative than a
 * synthetic ping, because it reflects the requests users are really making.
 *
 * <p>An open breaker reports DOWN. A configured-but-never-called provider
 * reports UP with {@code calls: 0}, because "we have not tried yet" is not
 * evidence of a problem.
 */
@Component
public class ModelProviderHealthIndicator implements HealthIndicator {

    private final ResilientChatModel chatModel;
    private final ResilientEmbeddingModel embeddingModel;
    private final CircuitBreakerRegistry breakers;

    public ModelProviderHealthIndicator(ResilientChatModel chatModel,
                                        ResilientEmbeddingModel embeddingModel,
                                        CircuitBreakerRegistry breakers) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.breakers = breakers;
    }

    @Override
    public Health health() {
        CircuitBreaker chat = breakers.circuitBreaker(ResilienceConfig.CHAT_BREAKER);
        CircuitBreaker embedding = breakers.circuitBreaker(ResilienceConfig.EMBEDDING_BREAKER);

        boolean chatConfigured = chatModel.isAvailable();
        boolean embeddingConfigured = embeddingModel.isAvailable();
        boolean healthy = chatConfigured && embeddingConfigured
                && isClosedOrHalfOpen(chat) && isClosedOrHalfOpen(embedding);

        Health.Builder builder = healthy ? Health.up() : Health.down();
        return builder
                .withDetail("chatConfigured", chatConfigured)
                .withDetail("embeddingConfigured", embeddingConfigured)
                .withDetail("chatCircuit", describe(chat))
                .withDetail("embeddingCircuit", describe(embedding))
                .build();
    }

    private static boolean isClosedOrHalfOpen(CircuitBreaker breaker) {
        CircuitBreaker.State state = breaker.getState();
        return state == CircuitBreaker.State.CLOSED || state == CircuitBreaker.State.HALF_OPEN;
    }

    private static java.util.Map<String, Object> describe(CircuitBreaker breaker) {
        var metrics = breaker.getMetrics();
        return java.util.Map.of(
                "state", breaker.getState().name(),
                "calls", metrics.getNumberOfBufferedCalls(),
                "failureRate", metrics.getFailureRate() < 0 ? "n/a" : metrics.getFailureRate(),
                "slowCallRate", metrics.getSlowCallRate() < 0 ? "n/a" : metrics.getSlowCallRate());
    }
}
