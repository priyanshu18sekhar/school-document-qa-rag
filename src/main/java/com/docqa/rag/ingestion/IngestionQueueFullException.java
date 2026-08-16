package com.docqa.rag.ingestion;

/**
 * The bounded ingestion queue rejected a job. Mapped to 503 with
 * {@code Retry-After}.
 */
public class IngestionQueueFullException extends RuntimeException {

    public IngestionQueueFullException(String message, Throwable cause) {
        super(message, cause);
    }
}
