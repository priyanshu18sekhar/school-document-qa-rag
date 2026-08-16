package com.docqa.rag.tenant;

import java.util.Objects;

/**
 * The tenant a request belongs to.
 *
 * <p>This exists as a type rather than a bare {@code String} deliberately. Every
 * repository method that touches tenant-scoped data takes a {@code TenantId} as
 * its first parameter, so "did I remember to scope this query?" becomes a
 * compile-time question rather than a code-review question. A method that
 * queries {@code document_chunks} and does not accept a {@code TenantId} is
 * visibly wrong at the signature level.
 *
 * <p>The alternative - stashing the tenant in a {@code ThreadLocal} or
 * {@code ScopedValue} and reading it deep in the data layer - reads more
 * cleanly at the call site and is how this kind of leak usually happens: the
 * ingestion executor runs on a different thread, the context is empty there,
 * and the "current tenant" quietly becomes null or, worse, whatever the
 * previous task on that pooled thread left behind. Explicit parameters do not
 * have a thread-affinity failure mode.
 *
 * @see com.docqa.rag.retrieval.VectorSearchRepository
 */
public record TenantId(String value) {

    public TenantId {
        Objects.requireNonNull(value, "tenant id must not be null");
        value = value.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("tenant id must not be blank");
        }
    }

    public static TenantId of(String value) {
        return new TenantId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
