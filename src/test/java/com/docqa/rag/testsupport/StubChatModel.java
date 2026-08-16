package com.docqa.rag.testsupport;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Offline chat model that records what it was asked.
 *
 * <p>The recording is the point. The single most important assertion in the
 * suite is that a question with no grounding produces <b>zero</b> calls to this
 * class - a system that refuses only after paying for a model call has not
 * implemented FR-6, and the only way to prove it did not call is to count.
 */
public class StubChatModel implements ChatModel {

    private final AtomicInteger callCount = new AtomicInteger();
    private final List<Prompt> prompts = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> nextResponse =
            new AtomicReference<>("Answer from the supplied context [1].");
    private final AtomicReference<RuntimeException> nextError = new AtomicReference<>();
    private final AtomicReference<Duration> streamDelay = new AtomicReference<>(Duration.ZERO);
    private final java.util.concurrent.atomic.AtomicBoolean streamCancelled =
            new java.util.concurrent.atomic.AtomicBoolean();

    @Override
    public ChatResponse call(Prompt prompt) {
        callCount.incrementAndGet();
        prompts.add(prompt);

        RuntimeException error = nextError.get();
        if (error != null) {
            throw error;
        }
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(nextResponse.get()))),
                ChatResponseMetadata.builder().model("stub-chat").build());
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        callCount.incrementAndGet();
        prompts.add(prompt);

        RuntimeException error = nextError.get();
        if (error != null) {
            return Flux.error(error);
        }

        // Split into word-sized deltas so the SSE test sees several events
        // rather than one, which is what actually exercises the token stream.
        List<ChatResponse> deltas = new ArrayList<>();
        for (String word : nextResponse.get().split("(?<= )")) {
            deltas.add(new ChatResponse(List.of(new Generation(new AssistantMessage(word)))));
        }
        Flux<ChatResponse> flux = Flux.fromIterable(deltas);
        Duration delay = streamDelay.get();
        return (delay.isZero() ? flux : flux.delayElements(delay))
                // Stands in for the upstream HTTP connection to the provider.
                // If this never fires when a client disconnects, the real
                // implementation is leaking requests to the model vendor.
                .doOnCancel(() -> streamCancelled.set(true));
    }

    public void respondWith(String response) {
        nextResponse.set(response);
    }

    public void failWith(RuntimeException error) {
        nextError.set(error);
    }

    /** Slows the stream so a cancellation test has time to disconnect mid-flight. */
    public void streamDelay(Duration delay) {
        streamDelay.set(delay);
    }

    public int callCount() {
        return callCount.get();
    }

    /** True once a subscriber cancelled the stream - i.e. the client hung up. */
    public boolean streamCancelled() {
        return streamCancelled.get();
    }

    public List<Prompt> prompts() {
        return List.copyOf(prompts);
    }

    public Prompt lastPrompt() {
        return prompts.getLast();
    }

    /** The rendered text of every message in the last prompt. */
    public String lastPromptText() {
        StringBuilder sb = new StringBuilder();
        for (Message message : lastPrompt().getInstructions()) {
            sb.append(message.getText()).append('\n');
        }
        return sb.toString();
    }

    public void reset() {
        callCount.set(0);
        prompts.clear();
        nextError.set(null);
        nextResponse.set("Answer from the supplied context [1].");
        streamDelay.set(Duration.ZERO);
        streamCancelled.set(false);
    }
}
