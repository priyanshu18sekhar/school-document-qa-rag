package com.docqa.rag.model;

/**
 * The model provider could not be reached, timed out, or the circuit breaker is
 * open. Mapped to 503 with a clean message (NFR-4).
 *
 * <p>Deliberately distinct from every other failure in the system: a 503 tells
 * the caller "this will probably work if you try again", which is true for a
 * provider outage and false for a malformed request. Collapsing both into 500
 * removes the caller's ability to decide whether to retry.
 */
public class ModelUnavailableException extends RuntimeException {

    private final boolean circuitOpen;

    public ModelUnavailableException(String message, Throwable cause, boolean circuitOpen) {
        super(message, cause);
        this.circuitOpen = circuitOpen;
    }

    /** True when we failed fast without attempting a call. */
    public boolean isCircuitOpen() {
        return circuitOpen;
    }
}
