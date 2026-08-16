package com.docqa.rag.document;

/** Mapped to 400. A zero-byte upload is a client mistake, not a server error. */
public class EmptyUploadException extends RuntimeException {

    public EmptyUploadException(String message) {
        super(message);
    }
}
