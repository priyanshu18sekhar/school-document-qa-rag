-- ---------------------------------------------------------------------------
-- V1: documents, chunks and the vector column.
--
-- Two things in here are load-bearing and worth reading carefully:
--
--  1. `document_chunks.tenant_id` is denormalised from `documents.tenant_id`
--     so that the vector query never needs a join to know who owns a row.
--     Denormalised columns drift, so it is NOT maintained by application code
--     alone: the composite foreign key (document_id, tenant_id) ->
--     (documents.id, documents.tenant_id) makes a chunk whose tenant differs
--     from its parent document physically unrepresentable. Postgres will
--     reject the INSERT. Tenant isolation therefore does not depend on anyone
--     remembering to write the right value.
--
--  2. The embedding dimension is a Flyway placeholder, not a literal. pgvector
--     fixes the dimension in the column type, so swapping embedding model
--     (OpenAI 1536 -> Ollama nomic 768) is a schema change, not just a config
--     change. Making it a placeholder means the same migration serves both,
--     and EmbeddingDimensionValidator fails fast at boot if the configured
--     model disagrees with what is actually in the database.
-- ---------------------------------------------------------------------------

CREATE EXTENSION IF NOT EXISTS vector;

-- ============================== documents ==================================

CREATE TABLE documents (
    id            UUID        PRIMARY KEY,
    tenant_id     TEXT        NOT NULL,
    title         TEXT        NOT NULL,
    category      TEXT,
    filename      TEXT        NOT NULL,
    content_type  TEXT        NOT NULL,
    content_hash  CHAR(64)    NOT NULL,
    size_bytes    BIGINT      NOT NULL,
    page_count    INTEGER,
    chunk_count   INTEGER     NOT NULL DEFAULT 0,
    status        TEXT        NOT NULL,
    error_message TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT documents_status_chk
        CHECK (status IN ('PROCESSING', 'READY', 'FAILED')),
    CONSTRAINT documents_tenant_not_blank_chk
        CHECK (length(btrim(tenant_id)) > 0),
    CONSTRAINT documents_size_chk
        CHECK (size_bytes > 0),

    -- Idempotency. Re-uploading identical bytes for the same tenant is a
    -- no-op, but two different tenants uploading the same fee policy are
    -- two independent documents.
    CONSTRAINT documents_tenant_hash_uq UNIQUE (tenant_id, content_hash)
);

-- Referenced by the composite FK on document_chunks. Redundant with the
-- primary key on its own, but Postgres requires a unique constraint that
-- exactly matches the referenced column list.
ALTER TABLE documents
    ADD CONSTRAINT documents_id_tenant_uq UNIQUE (id, tenant_id);

-- Serves GET /api/v1/documents, which is always tenant-scoped and newest-first.
CREATE INDEX documents_tenant_created_idx
    ON documents (tenant_id, created_at DESC);

-- =========================== document_chunks ===============================

CREATE TABLE document_chunks (
    id          UUID    PRIMARY KEY,
    document_id UUID    NOT NULL,
    tenant_id   TEXT    NOT NULL,
    category    TEXT,
    chunk_index INTEGER NOT NULL,
    page_number INTEGER,
    section     TEXT,
    content     TEXT    NOT NULL,
    token_count INTEGER NOT NULL,
    embedding   VECTOR(${embeddingDimensions}) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT document_chunks_index_chk CHECK (chunk_index >= 0),
    CONSTRAINT document_chunks_page_chk  CHECK (page_number IS NULL OR page_number >= 1),
    CONSTRAINT document_chunks_doc_index_uq UNIQUE (document_id, chunk_index),

    -- The isolation guarantee. Not "we remember to set tenant_id correctly" -
    -- "the database will not store a row where it is wrong".
    CONSTRAINT document_chunks_document_fk
        FOREIGN KEY (document_id, tenant_id)
        REFERENCES documents (id, tenant_id)
        ON DELETE CASCADE
);

-- The vector index. NFR-6: a sequential scan on this table is a fail.
--
-- HNSW over IVFFlat: IVFFlat needs a populated table to build meaningful
-- centroids, and this table starts empty on a clean clone. An IVFFlat index
-- built at migration time on zero rows is worse than no index at all. HNSW
-- builds incrementally and needs no training data.
--
-- vector_cosine_ops matches the `<=>` operator used by the retrieval query.
-- Using a different opclass here would silently produce a sequential scan.
--
-- m=16 / ef_construction=64 are pgvector's defaults, which are well matched to
-- a corpus of this size (hundreds of documents, ~10^4-10^5 chunks). Raising
-- them buys recall at the cost of build time; at this scale the default recall
-- is already ~0.99 and ingestion latency matters more.
CREATE INDEX document_chunks_embedding_hnsw_idx
    ON document_chunks USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- Supports the tenant/category pre-filter and, with pgvector's iterative
-- scan, lets the planner cheaply estimate filter selectivity.
CREATE INDEX document_chunks_tenant_category_idx
    ON document_chunks (tenant_id, category);

-- DELETE /documents/{id} cascades through this.
CREATE INDEX document_chunks_document_idx
    ON document_chunks (document_id);
