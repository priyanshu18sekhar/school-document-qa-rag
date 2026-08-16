package com.docqa.rag.observability;

import com.docqa.rag.config.RagProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Puts a correlation id and the tenant into the logging context for the life of
 * the request, and echoes the correlation id back on the response.
 *
 * <p>Accepting a caller-supplied {@code X-Correlation-Id} matters more than it
 * looks: when the evaluation harness fires a question and gets a wrong answer,
 * being able to grep one id across the upload, the ingestion worker, the
 * retrieval query and the model call is the difference between diagnosing it in
 * a minute and guessing.
 *
 * <p>This filter deliberately does <em>not</em> reject requests without a tenant
 * header. Rejecting here would return a bare 400 from the filter chain, outside
 * {@code @RestControllerAdvice}, and the client would get a Tomcat error page
 * instead of a ProblemDetail. Validation belongs to
 * {@link com.docqa.rag.tenant.TenantIdArgumentResolver}, which runs inside the
 * handler chain where exceptions are translated properly.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    private static final int MAX_LENGTH = 64;

    private final String tenantHeader;

    public CorrelationIdFilter(RagProperties properties) {
        this.tenantHeader = properties.tenant().header();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String correlationId = sanitize(request.getHeader(HEADER));
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }
        String tenant = sanitize(request.getHeader(tenantHeader));

        MDC.put(RequestContext.CORRELATION_ID, correlationId);
        if (tenant != null) {
            MDC.put(RequestContext.TENANT_ID, tenant);
        }
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(RequestContext.CORRELATION_ID);
            MDC.remove(RequestContext.TENANT_ID);
        }
    }

    /**
     * Header values reach log files. A value containing CR/LF would let a caller
     * forge log lines, so anything outside a conservative charset is dropped
     * rather than escaped.
     */
    private static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_LENGTH) {
            trimmed = trimmed.substring(0, MAX_LENGTH);
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean ok = Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.';
            if (!ok) {
                return null;
            }
        }
        return trimmed;
    }
}
