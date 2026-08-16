package com.docqa.rag.tenant;

/** Raised when a request arrives without a usable tenant header. Mapped to 400. */
public class MissingTenantException extends RuntimeException {

    public MissingTenantException(String message) {
        super(message);
    }
}
