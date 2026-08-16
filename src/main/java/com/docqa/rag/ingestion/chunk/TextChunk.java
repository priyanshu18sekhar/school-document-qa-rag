package com.docqa.rag.ingestion.chunk;

import org.jspecify.annotations.Nullable;

/**
 * One unit of retrieval.
 *
 * <p>Note that {@link #content} and {@link #embeddingText} are different
 * strings, and that is intentional.
 *
 * <ul>
 *   <li>{@code content} is what gets stored, shown back to the user as the
 *       citation snippet, and pasted into the prompt. It is exactly the text as
 *       it appears in the source document.</li>
 *   <li>{@code embeddingText} is what gets vectorised. It is {@code content}
 *       prefixed with the document title and section heading.</li>
 * </ul>
 *
 * <p>The reason for the split: a clause like <em>"A late fee of Rs 500 per week
 * applies after the due date."</em> is nearly unretrievable on its own, because
 * the words "term 2", "fee policy" and "class" - which is how a parent actually
 * asks the question - appear nowhere in it. They appear in the heading above it.
 * Embedding the heading along with the clause raises its similarity to the real
 * question substantially. But we must not <em>show</em> the synthesised prefix
 * as if it were document text, and we must not let the model quote it as though
 * the document said it. Hence two fields.
 */
public record TextChunk(
        int index,
        String content,
        String embeddingText,
        @Nullable Integer pageNumber,
        @Nullable String section,
        int tokenCount
) {
    public TextChunk {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("chunk content must not be blank");
        }
        if (index < 0) {
            throw new IllegalArgumentException("chunk index must not be negative");
        }
    }
}
