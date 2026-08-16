package com.docqa.rag.chat;

import com.docqa.rag.chat.dto.ChatRequest;
import com.docqa.rag.chat.dto.ChatResponse;
import com.docqa.rag.chat.dto.SourceDto;
import com.docqa.rag.model.ResilientChatModel;
import com.docqa.rag.observability.RagMetrics;
import com.docqa.rag.retrieval.RetrievedChunk;
import com.docqa.rag.tenant.TenantId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Question answering (FR-4) and streaming (FR-5).
 */
@RestController
@RequestMapping("/api/v1/chat")
@Tag(name = "Chat", description = "Ask grounded questions with citations")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    /**
     * The model is told to emit {@link PromptBuilder#MODEL_REFUSAL_SENTINEL}
     * when the context does not answer the question. In streaming mode that
     * would otherwise reach the browser as visible text before we could act on
     * it, so the first {@code GUARD_CHARS} characters are held back and
     * inspected before anything is emitted. The sentinel is 22 characters; 32
     * gives margin for leading whitespace and a partial first delta.
     *
     * <p>The cost is a few tens of milliseconds on first token - the guard is
     * released after roughly two or three deltas - which leaves NFR-2's
     * three-second budget intact.
     */
    private static final int GUARD_CHARS = 32;

    private final ChatService chatService;
    private final ResilientChatModel chatModel;
    private final RagMetrics metrics;

    public ChatController(ChatService chatService,
                          ResilientChatModel chatModel,
                          RagMetrics metrics) {
        this.chatService = chatService;
        this.chatModel = chatModel;
        this.metrics = metrics;
    }

    @PostMapping
    @Operation(summary = "Ask a question",
            description = """
                    Retrieves the top-K chunks for the tenant (optionally restricted to a
                    category), and answers from them with citations. If nothing clears the
                    similarity threshold, returns a fixed refusal without calling the model.""")
    public ChatResponse ask(TenantId tenantId, @Valid @RequestBody ChatRequest request) {
        return chatService.answer(tenantId, request);
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Ask a question, streaming the answer as Server-Sent Events",
            description = """
                    Emits `token` events as the model produces them, then a single `sources`
                    event, then `done`. Disconnecting cancels the upstream model call.""")
    public Flux<ServerSentEvent<Object>> stream(TenantId tenantId,
                                                @Valid @RequestBody ChatRequest request) {

        UUID conversationId = chatService.resolveConversation(tenantId, request.conversationId());
        ChatService.PreparedTurn turn = chatService.prepare(tenantId, conversationId, request);

        // Gate 1: refuse without ever opening a model connection.
        if (!turn.grounded()) {
            metrics.recordRefusal();
            String refusal = chatService.refusalMessage();
            UUID messageId = chatService.persistStreamedTurn(tenantId, conversationId,
                    turn.question(), refusal, true, List.of(), 0);
            return Flux.just(
                    event("token", new TokenEvent(refusal)),
                    event("sources", List.<SourceDto>of()),
                    event("done", new DoneEvent(conversationId, messageId, true)));
        }

        List<RetrievedChunk> sources = turn.sources();
        StringBuilder answer = new StringBuilder();
        StringBuilder guard = new StringBuilder(GUARD_CHARS + 16);
        AtomicBoolean guardReleased = new AtomicBoolean(false);
        AtomicBoolean modelRefused = new AtomicBoolean(false);
        long startNanos = System.nanoTime();

        Flux<ServerSentEvent<Object>> tokens = chatModel.stream(turn.prompt().messages())
                .concatMap(delta -> {
                    answer.append(delta);
                    if (guardReleased.get()) {
                        return Flux.just(event("token", new TokenEvent(delta)));
                    }
                    guard.append(delta);
                    if (guard.length() < GUARD_CHARS) {
                        return Flux.empty();     // still deciding
                    }
                    guardReleased.set(true);
                    if (containsSentinel(guard)) {
                        modelRefused.set(true);
                        return Flux.just(event("token",
                                new TokenEvent(chatService.refusalMessage())));
                    }
                    return Flux.just(event("token", new TokenEvent(guard.toString())));
                })
                // A very short answer never reaches GUARD_CHARS, so flush what
                // the guard is holding when the model stops.
                .concatWith(Flux.defer(() -> {
                    if (guardReleased.getAndSet(true) || guard.isEmpty()) {
                        return Flux.empty();
                    }
                    if (containsSentinel(guard)) {
                        modelRefused.set(true);
                        return Flux.just(event("token",
                                new TokenEvent(chatService.refusalMessage())));
                    }
                    return Flux.just(event("token", new TokenEvent(guard.toString())));
                }));

        Flux<ServerSentEvent<Object>> terminal = Flux.defer(() -> {
            boolean refused = modelRefused.get();
            String finalAnswer = refused ? chatService.refusalMessage() : answer.toString().strip();
            List<RetrievedChunk> cited = refused ? List.of() : sources;
            long modelMs = (System.nanoTime() - startNanos) / 1_000_000L;

            if (refused) {
                metrics.recordRefusal();
            } else {
                metrics.recordGroundedAnswer();
            }
            UUID messageId = chatService.persistStreamedTurn(tenantId, conversationId,
                    turn.question(), finalAnswer, refused, cited, modelMs);

            // FR-5: sources are their own terminal event, never interleaved
            // with the token stream.
            return Flux.just(
                    event("sources", toSourceDtos(cited)),
                    event("done", new DoneEvent(conversationId, messageId, refused)));
        });

        return tokens.concatWith(terminal)
                .onErrorResume(error -> {
                    log.warn("Streaming failed: {}", error.toString());
                    return Flux.just(event("error", new ErrorEvent(
                            "The answer could not be completed. Please try again.")));
                });
    }

    private static boolean containsSentinel(CharSequence text) {
        return text.toString().toUpperCase(Locale.ROOT)
                .contains(PromptBuilder.MODEL_REFUSAL_SENTINEL);
    }

    private static List<SourceDto> toSourceDtos(List<RetrievedChunk> chunks) {
        List<SourceDto> dtos = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            dtos.add(SourceDto.from(chunks.get(i), i + 1));
        }
        return dtos;
    }

    private static ServerSentEvent<Object> event(String name, Object data) {
        return ServerSentEvent.builder().event(name).data(data).build();
    }

    public record TokenEvent(String text) {}

    public record DoneEvent(UUID conversationId, UUID messageId, boolean refused) {}

    public record ErrorEvent(String message) {}
}
