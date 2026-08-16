package com.docqa.rag.chat.dto;

import com.docqa.rag.chat.ConversationMessage;
import com.docqa.rag.retrieval.RetrievedChunk;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * One citation (FR-4).
 *
 * <p>{@code rank} matches the {@code [n]} markers in the answer text, so a
 * reader can map any sentence back to the excerpt it came from.
 */
public record SourceDto(
        int rank,
        @Nullable UUID documentId,
        String documentTitle,
        @Nullable Integer pageNumber,
        @Nullable String section,
        double similarity,
        String snippet,
        boolean available
) {
    private static final int SNIPPET_CHARS = 400;

    public static SourceDto from(RetrievedChunk chunk, int rank) {
        return new SourceDto(
                rank,
                chunk.documentId(),
                chunk.documentTitle(),
                chunk.pageNumber(),
                chunk.section(),
                round(chunk.similarity()),
                chunk.snippet(SNIPPET_CHARS),
                true);
    }

    public static SourceDto from(ConversationMessage.StoredSource source) {
        return new SourceDto(
                source.rank(),
                source.documentId(),
                source.documentTitle(),
                source.pageNumber(),
                null,
                round(source.similarity()),
                source.snippet(),
                // False once the document has been deleted. The historical
                // answer is preserved, but the citation is explicitly marked as
                // no longer verifiable rather than silently dropped.
                source.sourceStillExists());
    }

    private static double round(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }
}
