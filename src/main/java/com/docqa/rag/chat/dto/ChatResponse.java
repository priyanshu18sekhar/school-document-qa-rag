package com.docqa.rag.chat.dto;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Answer to a question.
 *
 * @param refused true when no chunk cleared the similarity threshold and no
 *                model call was made. The client can style a refusal
 *                differently from an answer, and the flag is what the
 *                evaluation harness asserts on.
 */
public record ChatResponse(
        UUID conversationId,
        UUID messageId,
        String answer,
        boolean refused,
        List<SourceDto> sources,
        Metadata metadata
) {
    /**
     * @param topSimilarity best similarity seen, or the near miss on a refusal.
     *                      Exposed because "why did it refuse that?" is the most
     *                      common question about a RAG system, and answering it
     *                      from a response field beats reading server logs.
     */
    public record Metadata(
            long retrievalMs,
            @Nullable Long modelMs,
            @Nullable Integer promptTokens,
            @Nullable Integer completionTokens,
            @Nullable String model,
            double topSimilarity,
            double threshold
    ) {}
}
