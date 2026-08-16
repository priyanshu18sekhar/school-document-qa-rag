package com.docqa.rag.document;

import com.docqa.rag.tenant.TenantId;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code documents}.
 *
 * <p>Carries {@link TenantId} rather than a bare string so that a document
 * handed to a service cannot have its owner quietly confused with a title or a
 * category. Every read path that produces one of these has already filtered on
 * tenant in SQL.
 */
public record StoredDocument(
        UUID id,
        TenantId tenantId,
        String title,
        @Nullable String category,
        String filename,
        String contentType,
        String contentHash,
        long sizeBytes,
        @Nullable Integer pageCount,
        int chunkCount,
        DocumentStatus status,
        @Nullable String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {}
