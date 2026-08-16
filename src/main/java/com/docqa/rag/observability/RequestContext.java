package com.docqa.rag.observability;

import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Carries the correlation id and tenant across a thread hand-off.
 *
 * <p>FR-9 asks for a correlation id that "flows through the async ingestion
 * path". SLF4J's MDC is a {@code ThreadLocal}, so the moment the upload handler
 * submits work to the ingestion executor the context is gone - the worker
 * thread has either an empty MDC or, on a pooled thread, the leftovers of a
 * previous job. Both are worse than useless when you are reading logs.
 *
 * <p>{@link #capture()} snapshots the current MDC on the submitting thread;
 * {@link #wrap} restores it on the worker thread and, critically, restores the
 * worker's *previous* MDC afterwards so nothing leaks into the next task that
 * pooled thread picks up.
 */
public final class RequestContext {

    public static final String CORRELATION_ID = "correlationId";
    public static final String TENANT_ID = "tenantId";
    public static final String DOCUMENT_ID = "documentId";

    private final Map<String, String> values;

    private RequestContext(@Nullable Map<String, String> values) {
        this.values = values == null ? Map.of() : Map.copyOf(values);
    }

    public static RequestContext capture() {
        return new RequestContext(MDC.getCopyOfContextMap());
    }

    public @Nullable String correlationId() {
        return values.get(CORRELATION_ID);
    }

    /** Wraps a task so it runs with this context installed, and cleans up after. */
    public Runnable wrap(Runnable task) {
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                MDC.setContextMap(values);
                task.run();
            } finally {
                restore(previous);
            }
        };
    }

    public <T> Callable<T> wrap(Callable<T> task) {
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                MDC.setContextMap(values);
                return task.call();
            } finally {
                restore(previous);
            }
        };
    }

    private static void restore(@Nullable Map<String, String> previous) {
        if (previous == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(previous);
        }
    }

    /** Adds a key for the duration of the given task. Used to tag per-document work. */
    public static void with(String key, String value, Runnable task) {
        String previous = MDC.get(key);
        try {
            MDC.put(key, value);
            task.run();
        } finally {
            if (previous == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, previous);
            }
        }
    }
}
