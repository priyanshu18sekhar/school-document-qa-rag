package com.docqa.rag.ingestion.extract;

/**
 * The bytes could not be read as the declared document type.
 *
 * <p>The message on this exception is written to {@code documents.error_message}
 * and shown to the caller, so it must be safe to display: no stack traces, no
 * file content, no paths.
 */
public class DocumentExtractionException extends RuntimeException {

    public DocumentExtractionException(String message) {
        super(message);
    }

    public DocumentExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
