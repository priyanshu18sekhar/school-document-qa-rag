package com.docqa.rag.chat;

import com.docqa.rag.config.RagProperties;
import com.docqa.rag.model.ResilientChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites a follow-up question into a standalone one before retrieval.
 *
 * <h2>Why this is required, not a nicety</h2>
 *
 * <p>FR-7 asks that follow-ups like <em>"what about for class 9?"</em> work.
 * Putting conversation history in the prompt is necessary but not sufficient,
 * and the reason is a sequencing problem that is easy to miss: <b>retrieval
 * happens before the model is involved.</b> The string that gets embedded is
 * the raw follow-up - "And for Class 11 Science?" - eight words that mention
 * neither fees nor terms. Its nearest neighbours are whatever happens to
 * mention Class 11, and against a similarity threshold it usually retrieves
 * nothing at all.
 *
 * <p>This was measured, not assumed. Before this class existed, the exchange
 * "What is the tuition fee for Class 9 in term 2?" followed by "And for Class
 * 11 Science?" answered the first correctly and <em>refused</em> the second -
 * the history was sitting in the prompt, unread, because the refusal fired
 * before the model was called.
 *
 * <h2>Cost and failure behaviour</h2>
 *
 * <p>One extra model call, only on turns that have history. It is small - a few
 * dozen output tokens - but it is on the critical path, so the failure mode
 * matters: <b>if the rewrite fails for any reason, we fall back to the user's
 * original question rather than failing the request.</b> Degraded retrieval
 * beats no answer, and the refusal path still protects correctness if the
 * degraded retrieval finds nothing.
 *
 * <p>The rewritten query is used <b>for retrieval only</b>. The user's own
 * wording is what gets stored in history and shown back to them; rewriting what
 * somebody asked and then displaying it as their question is disorienting.
 */
@Component
public class QueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriter.class);

    private static final String SYSTEM_PROMPT = """
            Rewrite the user's latest message into a single self-contained search query.

            Rules:
            - Resolve references to the conversation above: pronouns, and elliptical \
            follow-ups like "and for class 9?" or "what about term 3?".
            - Keep the user's own vocabulary. Do not add words nobody mentioned.
            - If the latest message is already self-contained, return it unchanged.
            - Output the query and nothing else. No quotes, no preamble, no explanation.
            """;

    /** Long enough for any real query; a longer reply means the model is explaining itself. */
    private static final int MAX_REWRITE_LENGTH = 300;

    /**
     * Only the last two turns. More context makes the rewriter drag in topics
     * the user has moved on from, which is worse than no rewrite at all - it
     * pulls retrieval toward the previous subject.
     */
    private static final int HISTORY_MESSAGES = 4;

    private final ResilientChatModel chatModel;
    private final RagProperties.Chat config;

    public QueryRewriter(ResilientChatModel chatModel, RagProperties properties) {
        this.chatModel = chatModel;
        this.config = properties.chat();
    }

    /**
     * @param historyNewestFirst prior turns; an empty list short-circuits with no model call
     * @return the query to embed - either a rewrite, or the original question unchanged
     */
    public String rewrite(String question, List<ConversationMessage> historyNewestFirst) {
        if (!config.queryRewritingEnabled() || historyNewestFirst.isEmpty()) {
            return question;
        }

        List<ConversationMessage> recent = historyNewestFirst.stream()
                // A refused turn tells the rewriter nothing about what the user
                // is talking about, and its refusal text would pollute the query.
                .filter(message -> !message.refused())
                .limit(HISTORY_MESSAGES)
                .toList();
        if (recent.isEmpty()) {
            return question;
        }

        try {
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(SYSTEM_PROMPT));
            for (int i = recent.size() - 1; i >= 0; i--) {          // oldest first
                ConversationMessage message = recent.get(i);
                messages.add(message.role() == ConversationMessage.MessageRole.USER
                        ? new UserMessage(message.content())
                        : new AssistantMessage(message.content()));
            }
            messages.add(new UserMessage(question));

            String rewritten = chatModel.call(messages).text().strip();

            if (rewritten.isBlank() || rewritten.length() > MAX_REWRITE_LENGTH) {
                log.debug("Discarding implausible rewrite of length {}", rewritten.length());
                return question;
            }
            if (!rewritten.equalsIgnoreCase(question)) {
                log.debug("Rewrote follow-up for retrieval: '{}' -> '{}'", question, rewritten);
            }
            return rewritten;

        } catch (RuntimeException e) {
            // Never fail the question because an optimisation failed.
            log.warn("Query rewriting failed, using the original question: {}", e.toString());
            return question;
        }
    }
}
