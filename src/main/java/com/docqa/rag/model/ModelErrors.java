package com.docqa.rag.model;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.web.client.HttpStatusCodeException;

import java.lang.reflect.Method;

/**
 * Tells a provider outage apart from a request the provider refused.
 *
 * <p>The distinction changes three behaviours, and getting it wrong produced
 * the worst first-run experience this service had - starting it with no API key
 * reported "the provider is unavailable, try again shortly", which is both
 * wrong and unactionable:
 *
 * <ul>
 *   <li><b>Retry.</b> A 401 fails identically three times. Retrying it spends
 *       eight seconds of backoff to reach the same answer.</li>
 *   <li><b>Circuit breaker.</b> A refused request is not evidence the provider
 *       is unhealthy, so it must not trip the breaker. Otherwise a missing API
 *       key opens the circuit, {@code /actuator/health} reports the provider
 *       DOWN, and after you fix the key the service keeps refusing for another
 *       30 seconds for no reason.</li>
 *   <li><b>The message.</b> "Try again shortly" is a lie when the real problem
 *       is an unset environment variable.</li>
 * </ul>
 *
 * <h2>Why the status code is read reflectively</h2>
 *
 * <p>Spring AI 2 delegates to each vendor's own SDK - OpenAI errors arrive as
 * {@code com.openai.errors.NotFoundException}, Anthropic's as its own
 * hierarchy - and those hierarchies share no common interface and no common
 * supertype. Compiling against {@code com.openai.errors.*} to read the status
 * would put a vendor name in the code, which is exactly what the "provider must
 * be swappable via config, not code changes" requirement forbids, and would
 * break the build the moment somebody removes the OpenAI starter.
 *
 * <p>What those SDKs <em>do</em> agree on is exposing the HTTP status through a
 * no-argument accessor. Probing for it reflectively keeps this class free of
 * vendor imports and degrades safely: an unrecognised exception is treated as
 * transient, which is the conservative choice - it costs two extra retries and
 * never silently swallows a real outage.
 *
 * <p>429 (rate limited) is deliberately transient despite being a 4xx: backing
 * off is the right response, and a sustained rate limit is a real capacity
 * signal the breaker should see.
 */
public final class ModelErrors {

    private static final String[] STATUS_ACCESSORS = {"statusCode", "getStatusCode"};

    private ModelErrors() {
    }

    /**
     * True when the provider understood the request and refused it - bad key,
     * unknown model, malformed request. Retrying will not change the outcome.
     */
    public static boolean isClientError(Throwable error) {
        for (Throwable cause = error; cause != null; cause = nextCause(cause)) {

            if (cause instanceof HttpStatusCodeException http) {
                return isNonRetryableStatus(http.getStatusCode().value());
            }
            Integer status = statusOf(cause);
            if (status != null) {
                return isNonRetryableStatus(status);
            }
            // Spring AI's own classification, where it does the mapping for us.
            if (cause instanceof NonTransientAiException) {
                return true;
            }
        }
        return false;
    }

    /** 4xx means "we understood and refused"; 429 is the exception, back off instead. */
    private static boolean isNonRetryableStatus(int status) {
        return status >= 400 && status < 500 && status != 429;
    }

    /** Advice the caller can act on, rather than "try again". */
    public static String clientErrorAdvice(String providerRole) {
        return ("The %s provider rejected the request. This usually means the API key is "
                + "missing or invalid, or the configured model name does not exist - check "
                + "the relevant *_API_KEY and *_MODEL environment variables.")
                .formatted(providerRole);
    }

    /**
     * The exception chain as one line, for logging.
     *
     * <p>Messages only, never a stack trace and never the request body: a
     * provider error can echo back what was sent, which for this service is
     * document text (NFR-5 - no document content in logs).
     */
    public static String describe(Throwable error) {
        StringBuilder sb = new StringBuilder();
        for (Throwable cause = error; cause != null; cause = nextCause(cause)) {
            if (!sb.isEmpty()) {
                sb.append(" <- ");
            }
            sb.append(cause.getClass().getName());
            String message = cause.getMessage();
            if (message != null && !message.isBlank()) {
                sb.append(": ").append(message.length() > 200
                        ? message.substring(0, 200) + "…"
                        : message);
            }
        }
        return sb.toString();
    }

    private static @Nullable Integer statusOf(Throwable cause) {
        for (String name : STATUS_ACCESSORS) {
            try {
                Method method = cause.getClass().getMethod(name);
                Object value = method.invoke(cause);
                if (value instanceof Integer status) {
                    return status;
                }
                if (value instanceof Number number) {
                    return number.intValue();
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // No such accessor, or it threw. Try the next name, then fall
                // back to treating the failure as transient.
            }
        }
        return null;
    }

    private static @Nullable Throwable nextCause(Throwable throwable) {
        Throwable cause = throwable.getCause();
        return cause == throwable ? null : cause;
    }
}
