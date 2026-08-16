package com.docqa.rag.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * @param conversationId omit to start a new conversation; the id comes back in
 *                       the response
 * @param category       optional; when present, retrieval is restricted to that
 *                       category by the SQL query, not by filtering afterwards
 */
public record ChatRequest(
        @Nullable UUID conversationId,

        // Bounded because the question is embedded, and an unbounded question
        // is an unbounded embedding bill plus a way to blow the context window.
        @NotBlank(message = "question must not be blank")
        @Size(max = 2000, message = "question must be at most 2000 characters")
        String question,

        @Size(max = 64)
        @Nullable String category
) {}
