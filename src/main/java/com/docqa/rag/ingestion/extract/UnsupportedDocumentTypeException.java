package com.docqa.rag.ingestion.extract;

/** Mapped to HTTP 415. */
public class UnsupportedDocumentTypeException extends RuntimeException {

    public UnsupportedDocumentTypeException(String message) {
        super(message);
    }
}
