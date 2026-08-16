-- ---------------------------------------------------------------------------
-- V2: conversation memory.
--
-- The interesting decision here is message_sources. The obvious design is a
-- foreign key to document_chunks and a join at read time. That breaks the
-- moment a document is deleted: FR-3 says answers must stop citing a deleted
-- document, and a cascade delete would also erase the citation from the
-- historical answer that legitimately used it at the time.
--
-- So message_sources stores a *snapshot* of the citation (title, page,
-- snippet, score) and keeps a nullable chunk_id that is nulled out on delete.
-- Retrieval always queries live chunks, so a deleted document stops being
-- cited immediately; conversation history stays readable and honest, and a
-- null chunk_id is an explicit "the source this answer used no longer exists"
-- marker rather than a dangling pointer.
-- ---------------------------------------------------------------------------

CREATE TABLE conversations (
    id              UUID        PRIMARY KEY,
    tenant_id       TEXT        NOT NULL,
    title           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_message_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT conversations_tenant_not_blank_chk
        CHECK (length(btrim(tenant_id)) > 0)
);

-- Referenced by the composite FK on messages: same trick as document_chunks,
-- a message can never belong to another tenant's conversation.
ALTER TABLE conversations
    ADD CONSTRAINT conversations_id_tenant_uq UNIQUE (id, tenant_id);

CREATE INDEX conversations_tenant_recent_idx
    ON conversations (tenant_id, last_message_at DESC);

CREATE TABLE messages (
    -- BIGSERIAL rather than created_at for ordering: two turns written inside
    -- the same millisecond must still have a defined order, and history
    -- assembly walks backwards from the newest turn.
    seq             BIGSERIAL   PRIMARY KEY,
    id              UUID        NOT NULL UNIQUE,
    conversation_id UUID        NOT NULL,
    tenant_id       TEXT        NOT NULL,
    role            TEXT        NOT NULL,
    content         TEXT        NOT NULL,
    token_count     INTEGER,
    model           TEXT,
    latency_ms      INTEGER,
    -- True when the grounding gate fired and no model call was made. Lets us
    -- measure the refusal rate straight out of the database.
    refused         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT messages_role_chk CHECK (role IN ('USER', 'ASSISTANT')),
    CONSTRAINT messages_conversation_fk
        FOREIGN KEY (conversation_id, tenant_id)
        REFERENCES conversations (id, tenant_id)
        ON DELETE CASCADE
);

CREATE INDEX messages_conversation_seq_idx
    ON messages (conversation_id, seq);

CREATE TABLE message_sources (
    id               UUID    PRIMARY KEY,
    message_id       UUID    NOT NULL REFERENCES messages (id) ON DELETE CASCADE,

    -- Nullable on purpose: see header comment.
    chunk_id         UUID    REFERENCES document_chunks (id) ON DELETE SET NULL,
    document_id      UUID,

    -- Snapshot columns. Deliberately not a join.
    document_title   TEXT    NOT NULL,
    page_number      INTEGER,
    similarity_score REAL    NOT NULL,
    snippet          TEXT    NOT NULL,
    rank_position    INTEGER NOT NULL,

    CONSTRAINT message_sources_rank_chk CHECK (rank_position >= 1)
);

CREATE INDEX message_sources_message_idx
    ON message_sources (message_id, rank_position);
