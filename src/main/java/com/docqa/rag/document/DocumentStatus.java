package com.docqa.rag.document;

/**
 * Lifecycle of an uploaded document.
 *
 * <pre>
 *   upload accepted ──▶ PROCESSING ──┬──▶ READY   (chunks + embeddings committed)
 *                                    └──▶ FAILED  (error_message says why)
 * </pre>
 *
 * <p>There is no intermediate "EMBEDDING" or "CHUNKING" state. Everything
 * between PROCESSING and the terminal state happens inside a single database
 * transaction, so no other state is ever observable by a reader - inventing
 * finer-grained statuses would mean writing them from outside that transaction
 * and reintroducing the partial-write problem the transaction exists to
 * prevent. Progress reporting, if it were needed, belongs in a separate
 * progress table, not in this column.
 *
 * <p>A document stuck in PROCESSING after a crash is a real state and is
 * documented as a known limitation: the ingestion queue is in-memory, so a
 * process kill loses queued work. Re-uploading the same file recovers it.
 */
public enum DocumentStatus {
    PROCESSING,
    READY,
    FAILED;

    public boolean isTerminal() {
        return this != PROCESSING;
    }
}
