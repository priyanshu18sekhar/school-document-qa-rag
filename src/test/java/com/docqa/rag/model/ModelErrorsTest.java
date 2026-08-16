package com.docqa.rag.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Classification of provider failures.
 *
 * <p>Worth testing carefully because both wrong answers are expensive and
 * silent: classifying an outage as a client error means we stop retrying
 * through a blip that retrying would have survived, and classifying a bad API
 * key as an outage means three pointless retries, an open circuit breaker, and
 * a health endpoint blaming the provider for our own configuration.
 */
class ModelErrorsTest {

    /**
     * Stands in for a vendor SDK exception. Spring AI 2 delegates to the
     * provider's own SDK ({@code com.openai.errors.*} and friends), which share
     * no supertype - so the real code probes for a {@code statusCode()}
     * accessor rather than importing a vendor class. This is that shape.
     */
    static class VendorSdkException extends RuntimeException {
        private final int status;

        VendorSdkException(int status, String message) {
            super(message);
            this.status = status;
        }

        @SuppressWarnings("unused")   // read reflectively, which is the point
        public int statusCode() {
            return status;
        }
    }

    @Test
    @DisplayName("4xx from a vendor SDK is a client error: do not retry")
    void vendorClientErrors() {
        assertThat(ModelErrors.isClientError(new VendorSdkException(401, "Unauthorized"))).isTrue();
        assertThat(ModelErrors.isClientError(new VendorSdkException(403, "Forbidden"))).isTrue();
        assertThat(ModelErrors.isClientError(new VendorSdkException(404, "Unknown"))).isTrue();
        assertThat(ModelErrors.isClientError(new VendorSdkException(400, "Bad request"))).isTrue();
    }

    @Test
    @DisplayName("429 is transient even though it is a 4xx - backing off is the right response")
    void rateLimitIsTransient() {
        assertThat(ModelErrors.isClientError(new VendorSdkException(429, "Rate limited")))
                .isFalse();
        assertThat(ModelErrors.isClientError(
                HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,
                        "Too Many Requests", null, null, null)))
                .isFalse();
    }

    @Test
    @DisplayName("5xx and network failures are transient: retry and let the breaker watch")
    void serverAndNetworkErrorsAreTransient() {
        assertThat(ModelErrors.isClientError(new VendorSdkException(503, "Overloaded"))).isFalse();
        assertThat(ModelErrors.isClientError(
                HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway",
                        null, null, null)))
                .isFalse();
        assertThat(ModelErrors.isClientError(
                new ResourceAccessException("connect timed out", new IOException())))
                .isFalse();
    }

    @Test
    @DisplayName("Spring's own 4xx type is classified without reflection")
    void springHttpStatusException() {
        assertThat(ModelErrors.isClientError(
                HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized",
                        null, null, null)))
                .isTrue();
    }

    @Test
    @DisplayName("Spring AI's NonTransientAiException is taken at its word")
    void springAiClassification() {
        assertThat(ModelErrors.isClientError(new NonTransientAiException("bad request"))).isTrue();
    }

    @Test
    @DisplayName("the status is found through a wrapped cause chain")
    void unwrapsCauses() {
        Throwable wrapped = new IllegalStateException("embedding failed",
                new RuntimeException("provider call",
                        new VendorSdkException(401, "Unauthorized")));
        assertThat(ModelErrors.isClientError(wrapped)).isTrue();
    }

    @Test
    @DisplayName("an unrecognised exception is treated as transient, which is the safe default")
    void unknownDefaultsToTransient() {
        // Costs two extra retries; the alternative would silently stop retrying
        // through a real outage.
        assertThat(ModelErrors.isClientError(new RuntimeException("something odd"))).isFalse();
    }

    @Test
    @DisplayName("a self-referencing cause does not hang the classifier")
    void selfReferencingCauseTerminates() {
        Exception looping = new Exception("loop") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertThat(ModelErrors.isClientError(looping)).isFalse();
        assertThat(ModelErrors.describe(looping)).contains("loop");
    }

    @Test
    @DisplayName("describe renders the chain on one line and truncates long messages")
    void describeRendersTheChain() {
        String description = ModelErrors.describe(new IllegalStateException("outer",
                new VendorSdkException(401, "Unauthorized")));

        assertThat(description)
                .contains("IllegalStateException: outer")
                .contains("<-")
                .contains("VendorSdkException: Unauthorized")
                .doesNotContain("\n");

        String longMessage = ModelErrors.describe(new RuntimeException("x".repeat(500)));
        assertThat(longMessage).hasSizeLessThan(300).endsWith("…");
    }
}
