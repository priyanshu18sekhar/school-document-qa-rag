package com.docqa.rag.chat;

import com.docqa.rag.chat.dto.ChatRequest;
import com.docqa.rag.chat.dto.ChatResponse;
import com.docqa.rag.chat.dto.SourceDto;
import com.docqa.rag.config.RagProperties;
import com.docqa.rag.model.ResilientChatModel;
import com.docqa.rag.model.TokenCounter;
import com.docqa.rag.observability.RagMetrics;
import com.docqa.rag.retrieval.RetrievalService;
import com.docqa.rag.retrieval.RetrievedChunk;
import com.docqa.rag.tenant.TenantId;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Answering a question, with the grounding gate in front of the model.
 *
 * <h2>The refusal path (FR-6)</h2>
 *
 * <p>There are two gates, and only the first one is a guarantee:
 *
 * <ol>
 *   <li><b>The threshold gate.</b> If nothing clears the similarity threshold,
 *       {@link #answer} returns the fixed refusal message and <b>never calls the
 *       model</b>. This is a plain {@code if} in the middle of this class with
 *       the model call on the other side of it - deliberately not a prompt
 *       instruction, not a post-hoc check on the model's output, and not
 *       something a clever question can talk its way past. It is also the reason
 *       an out-of-scope question is fast and free rather than costing a model
 *       call.</li>
 *   <li><b>The sentinel gate.</b> If chunks did clear the threshold but the
 *       model judges them insufficient, it is instructed to emit
 *       {@code NOT_FOUND_IN_DOCUMENTS} and we convert that to the same refusal.
 *       This is a best-effort second layer: it catches near-misses that scored
 *       above the threshold but do not actually contain the answer. Being a
 *       prompt instruction, it is a request rather than a guarantee - which is
 *       exactly why gate 1 exists in front of it.</li>
 * </ol>
 *
 * <p>Both refusal kinds are stored with {@code refused = true} so the refusal
 * rate is measurable from the database, and refused turns are excluded from
 * future history (see {@link PromptBuilder#historyWithinBudget}).
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final RetrievalService retrieval;
    private final ResilientChatModel chatModel;
    private final PromptBuilder promptBuilder;
    private final ConversationRepository conversations;
    private final TokenCounter tokens;
    private final RagMetrics metrics;
    private final RagProperties.Chat config;

    public ChatService(RetrievalService retrieval,
                       ResilientChatModel chatModel,
                       PromptBuilder promptBuilder,
                       ConversationRepository conversations,
                       TokenCounter tokens,
                       RagMetrics metrics,
                       RagProperties properties) {
        this.retrieval = retrieval;
        this.chatModel = chatModel;
        this.promptBuilder = promptBuilder;
        this.conversations = conversations;
        this.tokens = tokens;
        this.metrics = metrics;
        this.config = properties.chat();
    }

    public ChatResponse answer(TenantId tenantId, ChatRequest request) {
        UUID conversationId = resolveConversation(tenantId, request.conversationId());
        String question = request.question().strip();
        String category = normaliseCategory(request.category());

        var outcome = retrieval.retrieve(tenantId, question, category);

        // ---- Gate 1: no grounding, no model call. -------------------------
        if (!outcome.grounded()) {
            double nearest = outcome.nearMiss()
                    .map(com.docqa.rag.retrieval.VectorSearchRepository.NearMiss::similarity)
                    .orElse(0.0);
            return persistAndBuild(tenantId, conversationId, question,
                    config.refusalMessage(), true, List.of(),
                    null, null, null, outcome.latency().toMillis(), null, nearest);
        }

        var history = conversations.findRecentMessages(
                tenantId, conversationId, config.maxHistoryTurns() * 2);
        var prompt = promptBuilder.build(question, outcome.chunks(), history);

        var result = chatModel.call(prompt.messages());
        String answer = result.text().strip();

        // ---- Gate 2: the model says the context does not cover it. --------
        if (isModelRefusal(answer)) {
            log.info("Model reported insufficient context despite {} chunk(s) above threshold "
                    + "(top similarity {})", prompt.usedContext().size(),
                    String.format("%.4f", outcome.topSimilarity()));
            return persistAndBuild(tenantId, conversationId, question,
                    config.refusalMessage(), true, List.of(),
                    result.model(), result.promptTokens(), result.completionTokens(),
                    outcome.latency().toMillis(), result.latency().toMillis(),
                    outcome.topSimilarity());
        }

        metrics.recordGroundedAnswer();
        return persistAndBuild(tenantId, conversationId, question, answer, false,
                prompt.usedContext(), result.model(), result.promptTokens(),
                result.completionTokens(), outcome.latency().toMillis(),
                result.latency().toMillis(), outcome.topSimilarity());
    }

    /** Creates a conversation when none was supplied; validates ownership when one was. */
    public UUID resolveConversation(TenantId tenantId, @Nullable UUID conversationId) {
        if (conversationId == null) {
            return conversations.create(tenantId, null).id();
        }
        // Tenant-scoped lookup: quoting another tenant's conversation id is a
        // 404, not a cross-tenant read.
        return conversations.find(tenantId, conversationId)
                .map(ConversationRepository.Conversation::id)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId));
    }

    /** Shared by the streaming path, which needs the same retrieval and prompt. */
    public PreparedTurn prepare(TenantId tenantId, UUID conversationId, ChatRequest request) {
        String question = request.question().strip();
        var outcome = retrieval.retrieve(tenantId, question, normaliseCategory(request.category()));

        if (!outcome.grounded()) {
            return new PreparedTurn(question, outcome, null, List.of());
        }
        var history = conversations.findRecentMessages(
                tenantId, conversationId, config.maxHistoryTurns() * 2);
        var prompt = promptBuilder.build(question, outcome.chunks(), history);
        return new PreparedTurn(question, outcome, prompt, prompt.usedContext());
    }

    /** {@code prompt} is null exactly when {@code outcome.grounded()} is false. */
    public record PreparedTurn(
            String question,
            RetrievalService.RetrievalOutcome outcome,
            PromptBuilder.@Nullable BuiltPrompt prompt,
            List<RetrievedChunk> sources
    ) {
        public boolean grounded() {
            return outcome.grounded();
        }
    }

    /** Used by the streaming controller once the stream has completed. */
    public UUID persistStreamedTurn(TenantId tenantId,
                                    UUID conversationId,
                                    String question,
                                    String answer,
                                    boolean refused,
                                    List<RetrievedChunk> sources,
                                    long modelMs) {
        return conversations.appendExchange(tenantId, conversationId,
                question, tokens.count(question),
                answer, tokens.count(answer),
                refused ? null : "streamed", (int) modelMs, refused, sources);
    }

    public String refusalMessage() {
        return config.refusalMessage();
    }

    public boolean isModelRefusal(String answer) {
        return answer.toUpperCase(Locale.ROOT)
                .contains(PromptBuilder.MODEL_REFUSAL_SENTINEL);
    }

    private ChatResponse persistAndBuild(TenantId tenantId,
                                         UUID conversationId,
                                         String question,
                                         String answer,
                                         boolean refused,
                                         List<RetrievedChunk> sources,
                                         @Nullable String model,
                                         @Nullable Integer promptTokens,
                                         @Nullable Integer completionTokens,
                                         long retrievalMs,
                                         @Nullable Long modelMs,
                                         double topSimilarity) {
        if (refused) {
            metrics.recordRefusal();
        }
        UUID messageId = conversations.appendExchange(tenantId, conversationId,
                question, tokens.count(question),
                answer, completionTokens != null ? completionTokens : tokens.count(answer),
                model, modelMs == null ? null : modelMs.intValue(), refused, sources);

        List<SourceDto> sourceDtos = new java.util.ArrayList<>(sources.size());
        for (int i = 0; i < sources.size(); i++) {
            sourceDtos.add(SourceDto.from(sources.get(i), i + 1));
        }

        return new ChatResponse(conversationId, messageId, answer, refused, sourceDtos,
                new ChatResponse.Metadata(retrievalMs, modelMs, promptTokens, completionTokens,
                        model, round(topSimilarity), retrieval.threshold()));
    }

    private static @Nullable String normaliseCategory(@Nullable String raw) {
        return (raw == null || raw.isBlank()) ? null : raw.strip().toUpperCase(Locale.ROOT);
    }

    private static double round(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }
}
