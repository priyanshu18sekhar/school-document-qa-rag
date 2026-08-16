package com.docqa.rag.config;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Retry and circuit breaking for outbound model calls.
 *
 * <p><b>Why programmatic rather than {@code @CircuitBreaker} annotations.</b>
 * The annotation-driven Resilience4j starter needs AOP proxies, which means the
 * protection only applies when the call crosses a Spring proxy boundary - a
 * self-invocation silently loses it, and that failure is invisible until
 * production. There are exactly two protected call sites here
 * ({@link com.docqa.rag.model.ResilientChatModel} and
 * {@link com.docqa.rag.model.ResilientEmbeddingModel}); wiring them explicitly
 * is a few more lines and removes a whole class of "why didn't the breaker
 * trip" question. It also keeps the dependency to two small jars rather than an
 * autoconfiguration that has to agree with Spring Boot 4's changes.
 *
 * <p><b>Chat and embeddings get separate breakers.</b> They are different
 * endpoints with different failure modes and, under a swapped provider, can be
 * different vendors entirely (Claude for chat, OpenAI for embeddings). A shared
 * breaker would let an embedding rate-limit take down question answering, which
 * is the more important of the two.
 */
@Configuration
public class ResilienceConfig {

    private static final Logger log = LoggerFactory.getLogger(ResilienceConfig.class);

    public static final String CHAT_BREAKER = "chat-model";
    public static final String EMBEDDING_BREAKER = "embedding-model";

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry(RagProperties properties,
                                                         MeterRegistry meterRegistry) {
        var cfg = properties.resilience().circuitBreaker();

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(cfg.failureRateThreshold())
                // A model provider rarely fails outright; it goes slow first.
                // Tripping on slow calls is what actually protects the service,
                // because a pile-up of 60-second requests exhausts the pool long
                // before the error rate moves.
                .slowCallDurationThreshold(Duration.ofSeconds(cfg.slowCallDurationSeconds()))
                .slowCallRateThreshold(cfg.slowCallRateThreshold())
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(cfg.slidingWindowSize())
                // Without a minimum, the first failed call on a cold service is
                // a 100% failure rate and opens the breaker immediately.
                .minimumNumberOfCalls(cfg.minimumNumberOfCalls())
                .waitDurationInOpenState(Duration.ofSeconds(cfg.waitDurationInOpenStateSeconds()))
                .permittedNumberOfCallsInHalfOpenState(cfg.permittedCallsInHalfOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        registry.circuitBreaker(CHAT_BREAKER);
        registry.circuitBreaker(EMBEDDING_BREAKER);

        registry.getEventPublisher().onEntryAdded(event ->
                event.getAddedEntry().getEventPublisher().onStateTransition(transition ->
                        log.warn("Circuit breaker '{}' {} -> {}",
                                transition.getCircuitBreakerName(),
                                transition.getStateTransition().getFromState(),
                                transition.getStateTransition().getToState())));

        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
        return registry;
    }

    @Bean
    public RetryRegistry retryRegistry(RagProperties properties, MeterRegistry meterRegistry) {
        var cfg = properties.resilience().retry();

        RetryConfig config = RetryConfig.custom()
                .maxAttempts(cfg.maxAttempts())
                .intervalFunction(io.github.resilience4j.core.IntervalFunction
                        .ofExponentialRandomBackoff(
                                Duration.ofMillis(cfg.initialBackoffMs()),
                                cfg.backoffMultiplier(),
                                // Jitter. Without it, every request that failed
                                // during a provider blip retries at the same
                                // instant and re-creates the blip.
                                0.5,
                                Duration.ofMillis(cfg.maxBackoffMs())))
                // Retrying a call the breaker already rejected defeats the
                // breaker: it would turn one fail-fast into N sleeps.
                .ignoreExceptions(CallNotPermittedException.class)
                .failAfterMaxAttempts(true)
                .build();

        RetryRegistry registry = RetryRegistry.of(config);
        registry.retry(CHAT_BREAKER);
        registry.retry(EMBEDDING_BREAKER);

        TaggedRetryMetrics.ofRetryRegistry(registry).bindTo(meterRegistry);
        return registry;
    }

    @Bean
    public CircuitBreaker chatCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker(CHAT_BREAKER);
    }

    @Bean
    public CircuitBreaker embeddingCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker(EMBEDDING_BREAKER);
    }

    @Bean
    public Retry chatRetry(RetryRegistry registry) {
        return registry.retry(CHAT_BREAKER);
    }

    @Bean
    public Retry embeddingRetry(RetryRegistry registry) {
        return registry.retry(EMBEDDING_BREAKER);
    }
}
