package com.docqa.rag.chat;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One stored turn. */
public record ConversationMessage(
        UUID id,
        MessageRole role,
        String content,
        @Nullable Integer tokenCount,
        @Nullable String model,
        @Nullable Integer latencyMs,
        boolean refused,
        Instant createdAt,
        List<StoredSource> sources
) {

    public enum MessageRole { USER, ASSISTANT }

    /**
     * A citation as it was at answer time.
     *
     * @param chunkId {@code null} once the underlying document has been deleted.
     *                The rest of the fields survive, so the historical answer
     *                still shows what it was based on, flagged as no longer
     *                available. See the V2 migration.
     */
    public record StoredSource(
            @Nullable UUID chunkId,
            @Nullable UUID documentId,
            String documentTitle,
            @Nullable Integer pageNumber,
            double similarity,
            String snippet,
            int rank
    ) {
        public boolean sourceStillExists() {
            return chunkId != null;
        }
    }
}
