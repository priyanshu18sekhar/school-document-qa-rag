package com.docqa.rag.model;

import com.docqa.rag.config.RagProperties;
import com.docqa.rag.observability.RagMetrics;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The chat provider, wrapped.
 *
 * <p>Two call shapes with deliberately different protection:
 *
 * <ul>
 *   <li>{@link #call} is blocking, retried and circuit-broken.</li>
 *   <li>{@link #stream} is <em>not retried</em>. Once the first token has
 *       reached the browser, a retry would restart the answer mid-sentence and
 *       the client would render two half-answers concatenated. The breaker still
 *       observes stream failures, so a broken provider still trips it - the
 *       stream just fails cleanly instead of being replayed.</li>
 * </ul>
 *
 * <h2>Cancellation (FR-5)</h2>
 *
 * <p>The stream deliberately does nothing clever about cancellation, because
 * the correct behaviour falls out of Reactor: when the SSE client disconnects,
 * Spring MVC cancels its subscription, the cancel signal propagates up through
 * this operator chain to the WebClient call underneath, and the HTTP connection
 * to the provider is closed. The one thing added here is
 * {@code doOnCancel} logging, so "did the upstream call actually stop?" is
 * answerable from the logs rather than from trust.
 */
@Component
public class ResilientChatModel {

    private static final Logger log = LoggerFactory.getLogger(ResilientChatModel.class);

    private final ObjectProvider<ChatModel> delegate;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final RagProperties.Chat config;
    private final TokenCounter tokenCounter;
    private final RagMetrics metrics;

    public ResilientChatModel(ObjectProvider<ChatModel> delegate,
                              CircuitBreaker chatCircuitBreaker,
                              Retry chatRetry,
                              RagProperties properties,
                              TokenCounter tokenCounter,
                              RagMetrics metrics) {
        this.delegate = delegate;
        this.circuitBreaker = chatCircuitBreaker;
        this.retry = chatRetry;
        this.config = properties.chat();
        this.tokenCounter = tokenCounter;
        this.metrics = metrics;
    }

    public boolean isAvailable() {
        return delegate.getIfAvailable() != null;
    }

    public record ChatResult(String text, int promptTokens, int completionTokens,
                             String model, Duration latency) {}

    /** Blocking completion. */
    public ChatResult call(List<Message> messages) {
        ChatModel model = require();
        Prompt prompt = new Prompt(messages);

        Supplier<ChatResponse> guarded = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(circuitBreaker, () -> model.call(prompt)));

        long startNanos = System.nanoTime();
        ChatResponse response;
        try {
            response = guarded.get();
        } catch (CallNotPermittedException e) {
            throw new ModelUnavailableException(
                    "The language model provider is currently unavailable and requests are being "
                            + "rejected while it recovers. Try again shortly.", e, true);
        } catch (RuntimeException e) {
            if (ModelErrors.isClientError(e)) {
                throw new ModelUnavailableException(
                        ModelErrors.clientErrorAdvice("language model"), e, false);
            }
            throw new ModelUnavailableException(
                    "The language model provider could not be reached after %d attempts."
                            .formatted(retry.getRetryConfig().getMaxAttempts()), e, false);
        }
        Duration latency = Duration.ofNanos(System.nanoTime() - startNanos);

        String text = textOf(response);
        int promptTokens = tokenCounter.countOrEstimate(
                usage(response, true), concatenate(messages));
        int completionTokens = tokenCounter.countOrEstimate(usage(response, false), text);
        String modelName = modelNameOf(response);

        metrics.recordChat(latency, promptTokens, completionTokens, modelName);
        return new ChatResult(text, promptTokens, completionTokens, modelName, latency);
    }

    /**
     * Streaming completion. Emits content deltas only; empty deltas (keepalives,
     * tool-call frames, the final usage-only frame) are filtered out so the SSE
     * consumer never receives an empty {@code token} event.
     */
    public Flux<String> stream(List<Message> messages) {
        ChatModel model;
        try {
            model = require();
        } catch (ModelUnavailableException e) {
            return Flux.error(e);
        }
        if (!circuitBreaker.tryAcquirePermission()) {
            return Flux.error(new ModelUnavailableException(
                    "The language model provider is currently unavailable and requests are being "
                            + "rejected while it recovers. Try again shortly.", null, true));
        }

        Prompt prompt = new Prompt(messages);
        long startNanos = System.nanoTime();
        AtomicInteger emitted = new AtomicInteger();

        return model.stream(prompt)
                .map(ResilientChatModel::textOf)
                .filter(text -> !text.isEmpty())
                .doOnNext(text -> emitted.incrementAndGet())
                .timeout(Duration.ofSeconds(config.modelTimeoutSeconds()))
                .doOnComplete(() -> {
                    long elapsed = System.nanoTime() - startNanos;
                    circuitBreaker.onSuccess(elapsed, java.util.concurrent.TimeUnit.NANOSECONDS);
                })
                .doOnError(error -> {
                    long elapsed = System.nanoTime() - startNanos;
                    circuitBreaker.onError(elapsed, java.util.concurrent.TimeUnit.NANOSECONDS, error);
                    log.warn("Streaming chat call failed after {} delta(s): {}",
                            emitted.get(), error.toString());
                })
                .doOnCancel(() -> {
                    // FR-5: proof that a client disconnect actually tears down
                    // the upstream request rather than leaving it running.
                    circuitBreaker.releasePermission();
                    log.info("Client disconnected after {} delta(s); upstream model call cancelled",
                            emitted.get());
                });
    }

    private static String textOf(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private static @Nullable Integer usage(ChatResponse response, boolean prompt) {
        if (response == null || response.getMetadata() == null
                || response.getMetadata().getUsage() == null) {
            return null;
        }
        var usage = response.getMetadata().getUsage();
        return prompt ? usage.getPromptTokens() : usage.getCompletionTokens();
    }

    private String modelNameOf(ChatResponse response) {
        if (response != null && response.getMetadata() != null
                && response.getMetadata().getModel() != null
                && !response.getMetadata().getModel().isBlank()) {
            return response.getMetadata().getModel();
        }
        ChatModel model = delegate.getIfAvailable();
        return model == null ? "none" : model.getClass().getSimpleName();
    }

    private static String concatenate(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message message : messages) {
            if (message.getText() != null) {
                sb.append(message.getText()).append('\n');
            }
        }
        return sb.toString();
    }

    private ChatModel require() {
        ChatModel model = delegate.getIfAvailable();
        if (model == null) {
            throw new ModelUnavailableException(
                    "No chat model is configured. Set spring.ai.model.chat and supply the "
                            + "corresponding API key.", null, false);
        }
        return model;
    }
}
