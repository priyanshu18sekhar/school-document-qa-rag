package com.docqa.rag.ingestion;

import com.docqa.rag.document.DocumentRepository;
import com.docqa.rag.ingestion.chunk.TextChunk;
import com.docqa.rag.tenant.TenantId;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * The transactional tail of ingestion.
 *
 * <h2>Where the transaction starts, and why not earlier</h2>
 *
 * <p>FR-2 requires chunks and embeddings to be written "in a single transaction
 * per document". That single transaction is exactly this method - and
 * deliberately <em>only</em> this method.
 *
 * <p>Extraction, chunking and embedding all happen before the transaction
 * opens. It would be simpler to wrap the whole ingestion in one
 * {@code @Transactional} method, and it would be wrong: embedding a 50-page PDF
 * is several seconds of network I/O against a third-party API, and holding a
 * pooled database connection open across it means a burst of uploads exhausts
 * the connection pool and takes question answering down with it. Long
 * transactions across external calls are also how you end up with idle-in-
 * transaction connections pinning autovacuum.
 *
 * <p>The tradeoff is that a crash after embedding but before this commit loses
 * the embedding work (the document stays PROCESSING and must be re-uploaded).
 * That is the right side of the trade: the work is recomputable, the connection
 * pool is not.
 *
 * <p>Inside the transaction, all three statements are atomic together: clearing
 * any previous chunks, inserting the new ones, and flipping the status to
 * READY. That ordering matters for the reader's guarantee - because the status
 * flip commits with the chunks, a document is never observable as READY with
 * partial chunks, and never observable as PROCESSING with chunks already
 * queryable. Retrieval can therefore rely on "READY means complete" without
 * taking a lock.
 *
 * <p>This lives in its own bean rather than as a method on {@link IngestionService}
 * because Spring's {@code @Transactional} is proxy-based: a self-invocation from
 * another method of the same class bypasses the proxy and silently runs with no
 * transaction at all. Separating the bean makes the boundary real rather than
 * decorative.
 */
@Service
public class IngestionWriter {

    private final ChunkWriteRepository chunks;
    private final DocumentRepository documents;

    public IngestionWriter(ChunkWriteRepository chunks, DocumentRepository documents) {
        this.chunks = chunks;
        this.documents = documents;
    }

    @Transactional
    public void commitChunks(TenantId tenantId,
                             UUID documentId,
                             @Nullable String category,
                             List<TextChunk> textChunks,
                             List<float[]> embeddings,
                             @Nullable Integer pageCount) {
        chunks.deleteByDocument(tenantId, documentId);
        chunks.insertAll(tenantId, documentId, category, textChunks, embeddings);
        documents.markReady(tenantId, documentId, textChunks.size(), pageCount);
    }

    /**
     * Separate transaction from {@link #commitChunks}: it is called when that
     * one has already rolled back, so it must not enlist in a doomed
     * transaction.
     */
    @Transactional
    public void commitFailure(TenantId tenantId, UUID documentId, String reason) {
        chunks.deleteByDocument(tenantId, documentId);
        documents.markFailed(tenantId, documentId, reason);
    }
}
