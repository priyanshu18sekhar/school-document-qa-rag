package com.docqa.rag.document.dto;

import com.docqa.rag.document.DocumentStatus;
import com.docqa.rag.document.StoredDocument;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Public view of a document.
 *
 * <p>Note the absence of {@code tenantId} and {@code contentHash}. The tenant is
 * something the caller already told us, so echoing it back adds nothing and
 * would leak into logs and browser caches. The content hash would let a caller
 * probe whether another tenant holds a particular file by uploading it and
 * watching for a duplicate response - a small oracle, but a free one to close.
 */
public record DocumentResponse(
        UUID id,
        String title,
        @Nullable String category,
        String filename,
        String contentType,
        long sizeBytes,
        @Nullable Integer pageCount,
        int chunkCount,
        DocumentStatus status,
        @Nullable String errorMessage,
        Instant uploadedAt,
        Instant updatedAt
) {
    public static DocumentResponse from(StoredDocument document) {
        return new DocumentResponse(
                document.id(),
                document.title(),
                document.category(),
                document.filename(),
                document.contentType(),
                document.sizeBytes(),
                document.pageCount(),
                document.chunkCount(),
                document.status(),
                document.errorMessage(),
                document.createdAt(),
                document.updatedAt());
    }
}
