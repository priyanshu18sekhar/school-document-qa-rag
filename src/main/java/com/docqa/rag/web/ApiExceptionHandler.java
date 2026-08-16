package com.docqa.rag.web;

import com.docqa.rag.chat.ConversationNotFoundException;
import com.docqa.rag.document.DocumentNotFoundException;
import com.docqa.rag.document.EmptyUploadException;
import com.docqa.rag.ingestion.IngestionQueueFullException;
import com.docqa.rag.ingestion.extract.UnsupportedDocumentTypeException;
import com.docqa.rag.model.ModelUnavailableException;
import com.docqa.rag.observability.RequestContext;
import com.docqa.rag.tenant.MissingTenantException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.stream.Collectors;

/**
 * Turns every exception into an RFC 9457 {@code application/problem+json} body.
 *
 * <p>Two rules govern what goes in these responses:
 *
 * <ol>
 *   <li><b>No stack traces, no internal detail, ever.</b> Framework messages
 *       leak class names, SQL fragments and file paths. The client gets a
 *       sentence they can act on; the detail goes to the log.</li>
 *   <li><b>Every response carries the correlation id.</b> When someone reports
 *       "it returned a 503", that id is the difference between finding the
 *       failure in one grep and guessing from timestamps.</li>
 * </ol>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    // ---- 400 --------------------------------------------------------------

    @ExceptionHandler(MissingTenantException.class)
    public ResponseEntity<ProblemDetail> handleMissingTenant(MissingTenantException e) {
        return problem(HttpStatus.BAD_REQUEST, "Tenant required", e.getMessage());
    }

    @ExceptionHandler(EmptyUploadException.class)
    public ResponseEntity<ProblemDetail> handleEmptyUpload(EmptyUploadException e) {
        return problem(HttpStatus.BAD_REQUEST, "Empty upload", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .distinct()
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, "Invalid request",
                detail.isBlank() ? "The request body failed validation." : detail);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ProblemDetail> handleMalformedRequest(Exception e) {
        // Deliberately generic: the framework's own message for these names
        // internal types and would confuse a caller more than it helps.
        log.debug("Malformed request: {}", e.toString());
        return problem(HttpStatus.BAD_REQUEST, "Malformed request",
                "The request could not be read. Check the required fields and their types.");
    }

    // ---- 404 --------------------------------------------------------------

    @ExceptionHandler({DocumentNotFoundException.class, ConversationNotFoundException.class})
    public ResponseEntity<ProblemDetail> handleNotFound(RuntimeException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    // ---- 413 / 415 --------------------------------------------------------

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> handleTooLarge(MaxUploadSizeExceededException e) {
        // FR-1 asks for a 413 rather than a stack trace. Tomcat is configured
        // with a swallow size slightly above the limit so the body can be
        // drained and this response actually reaches the client.
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "File too large",
                "The uploaded file exceeds the maximum size of 20 MB.");
    }

    @ExceptionHandler(UnsupportedDocumentTypeException.class)
    public ResponseEntity<ProblemDetail> handleUnsupportedType(UnsupportedDocumentTypeException e) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported file type", e.getMessage());
    }

    // ---- 503 --------------------------------------------------------------

    @ExceptionHandler(ModelUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleModelUnavailable(ModelUnavailableException e) {
        log.warn("Model provider unavailable (circuit open: {}): {}",
                e.isCircuitOpen(), rootMessage(e));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, e.isCircuitOpen() ? "30" : "5")
                .body(detail(HttpStatus.SERVICE_UNAVAILABLE, "Model provider unavailable",
                        e.getMessage()));
    }

    @ExceptionHandler(IngestionQueueFullException.class)
    public ResponseEntity<ProblemDetail> handleQueueFull(IngestionQueueFullException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "30")
                .body(detail(HttpStatus.SERVICE_UNAVAILABLE, "Ingestion queue full", e.getMessage()));
    }

    // ---- 500 --------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception e) {
        String correlationId = MDC.get(RequestContext.CORRELATION_ID);
        log.error("Unhandled exception [correlationId={}]", correlationId, e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error",
                "Something went wrong. Quote the correlationId in this response when reporting it.");
    }

    // ---- helpers ----------------------------------------------------------

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status,
                                                         String title,
                                                         String detail) {
        return ResponseEntity.status(status).body(detail(status, title, detail));
    }

    private static ProblemDetail detail(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        String correlationId = MDC.get(RequestContext.CORRELATION_ID);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
        return problem;
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.toString();
    }
}
