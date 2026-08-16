package com.docqa.rag.chat;

import com.docqa.rag.config.RagProperties;
import com.docqa.rag.model.TokenCounter;
import com.docqa.rag.retrieval.RetrievedChunk;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles the prompt: system instructions, budgeted history, numbered context,
 * question.
 *
 * <h2>The system prompt</h2>
 *
 * <p>Every line in {@link #SYSTEM_PROMPT} is there because of a specific failure
 * mode, and none of it is decoration:
 *
 * <ul>
 *   <li><b>"only from the CONTEXT"</b> is the baseline instruction FR-6 asks
 *       for. On its own it is not enough, which is why the similarity threshold
 *       exists in front of it - a prompt instruction is a request, not a
 *       guarantee, and a model that has been handed weakly-related context will
 *       often answer from it anyway.</li>
 *   <li><b>Quote figures exactly.</b> Models paraphrase numbers, and "about five
 *       thousand rupees" is a wrong answer when the policy says 5,200.</li>
 *   <li><b>Cite with [n].</b> Forces the model to commit each claim to a
 *       specific source, which makes an ungrounded sentence visible to the
 *       reader rather than hidden inside a fluent paragraph.</li>
 *   <li><b>Do not answer from general knowledge.</b> Models know a great deal
 *       about school fee structures in general. That knowledge is exactly what
 *       must not reach a parent as though it were this school's policy.</li>
 *   <li><b>Say when the context is partial.</b> The dangerous answer is not "I
 *       don't know", it is a confident answer to the half of the question the
 *       context covered.</li>
 * </ul>
 *
 * <h2>Token budgets</h2>
 *
 * <p>Two independent budgets, both enforced with real BPE counts. Context is
 * filled best-first and truncated at the budget - a chunk that does not fit is
 * dropped rather than cut in half, because half a fee clause is worse than none.
 * Whatever survives truncation is exactly what gets reported in {@code sources},
 * so the citations always describe what the model actually saw.
 */
@Component
public class PromptBuilder {

    static final String SYSTEM_PROMPT = """
            You are a school office assistant. You answer questions using ONLY the numbered \
            excerpts in the CONTEXT section of the user's message.

            Rules:
            1. Use only the CONTEXT. Never use general knowledge about schools, fees, or \
            policies, even when you are confident it is correct.
            2. Cite every factual claim with the bracketed number of the excerpt it came from, \
            like [1] or [2][3]. A sentence with a fact and no citation is an error.
            3. Quote amounts, dates, deadlines, percentages and class names exactly as they \
            appear. Never round, convert, or restate a figure in words.
            4. If the CONTEXT covers only part of the question, answer that part and state \
            plainly which part is not covered by the available documents.
            5. If the CONTEXT does not answer the question at all, reply with exactly: \
            NOT_FOUND_IN_DOCUMENTS
            6. Do not mention "context", "excerpts", "chunks" or "documents provided" in your \
            answer. Write as if answering a parent at the front desk: direct and brief.
            7. Prefer two or three sentences. Use a short list only when the answer genuinely \
            has several parts.
            """;

    /** Sentinel the model is told to emit when the context does not answer the question. */
    public static final String MODEL_REFUSAL_SENTINEL = "NOT_FOUND_IN_DOCUMENTS";

    private final RagProperties.Chat config;
    private final TokenCounter tokens;

    public PromptBuilder(RagProperties properties, TokenCounter tokens) {
        this.config = properties.chat();
        this.tokens = tokens;
    }

    /**
     * @param messages    ready to send to the model
     * @param usedContext the chunks that actually fit inside the budget; this,
     *                    not the full retrieval result, is what may be cited
     */
    public record BuiltPrompt(List<Message> messages, List<RetrievedChunk> usedContext) {}

    public BuiltPrompt build(String question,
                             List<RetrievedChunk> retrieved,
                             List<ConversationMessage> historyNewestFirst) {

        List<RetrievedChunk> context = withinContextBudget(retrieved);

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(SYSTEM_PROMPT));
        messages.addAll(historyWithinBudget(historyNewestFirst));
        messages.add(new UserMessage(renderContextAndQuestion(question, context)));

        return new BuiltPrompt(messages, context);
    }

    /** Rendered separately so tests can assert on the exact text sent. */
    String renderContextAndQuestion(String question, List<RetrievedChunk> context) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("CONTEXT\n");
        for (int i = 0; i < context.size(); i++) {
            RetrievedChunk chunk = context.get(i);
            sb.append('[').append(i + 1).append("] ")
              .append(chunk.citationLabel()).append('\n')
              .append(chunk.content().strip()).append("\n\n");
        }
        sb.append("QUESTION\n").append(question.strip());
        return sb.toString();
    }

    private List<RetrievedChunk> withinContextBudget(List<RetrievedChunk> retrieved) {
        List<RetrievedChunk> kept = new ArrayList<>(retrieved.size());
        int used = 0;
        for (RetrievedChunk chunk : retrieved) {
            int cost = tokens.count(chunk.content()) + 16;   // ~ the [n] header line
            if (!kept.isEmpty() && used + cost > config.contextTokenBudget()) {
                break;
            }
            kept.add(chunk);
            used += cost;
        }
        return kept;
    }

    /**
     * FR-7: history capped by <em>token budget</em>, not only by turn count.
     *
     * <p>Turn count alone is not a budget. Six turns of "what time does the bus
     * leave?" is 60 tokens; six turns where the assistant quoted a full fee
     * table is several thousand, and the difference lands on the same fixed
     * context window. Under load the second case pushes the retrieved context -
     * the part that actually grounds the answer - out of the window, and the
     * model starts answering from conversation history instead of from
     * documents. So both limits apply and whichever binds first wins.
     *
     * <p>Walking backwards from the newest turn matters too: when the budget
     * runs out we drop the <em>oldest</em> turns, which is where a follow-up
     * like "what about for class 9?" gets its referent from. Truncating from the
     * other end would keep ancient history and discard the turn the question
     * actually refers to.
     *
     * <p>Turns are dropped in whole user/assistant pairs where possible; a
     * dangling assistant message with no preceding question reads to the model
     * as an unprompted assertion.
     */
    List<Message> historyWithinBudget(List<ConversationMessage> newestFirst) {
        List<Message> selected = new ArrayList<>();
        int budget = config.historyTokenBudget();
        int used = 0;
        int turns = 0;

        for (ConversationMessage message : newestFirst) {
            if (turns >= config.maxHistoryTurns() * 2) {
                break;
            }
            // A refusal carries no information the model can use and costs
            // tokens; worse, several refusals in a row bias it toward refusing.
            if (message.refused()) {
                continue;
            }
            int cost = message.tokenCount() != null && message.tokenCount() > 0
                    ? message.tokenCount()
                    : tokens.count(message.content());
            if (used + cost > budget) {
                break;
            }
            used += cost;
            turns++;
            selected.add(toSpringMessage(message));
        }

        // Collected newest-first; the model needs chronological order.
        java.util.Collections.reverse(selected);
        if (!selected.isEmpty() && selected.getFirst() instanceof AssistantMessage) {
            selected.removeFirst();
        }
        return selected;
    }

    private static Message toSpringMessage(ConversationMessage message) {
        return message.role() == ConversationMessage.MessageRole.USER
                ? new UserMessage(message.content())
                : new AssistantMessage(message.content());
    }
}
