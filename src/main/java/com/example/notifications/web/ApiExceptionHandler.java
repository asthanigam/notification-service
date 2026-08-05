package com.example.notifications.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Turns failures into RFC 9457 problem responses with a stable machine-readable
 * {@code error} code, so clients branch on a code rather than string-matching
 * prose.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Same idempotency key, different request. */
    @ExceptionHandler(ConflictException.class)
    public ProblemDetail onConflict(ConflictException e) {
        return problem(HttpStatus.CONFLICT, "Idempotency key conflict",
                e.getMessage(), "IDEMPOTENCY_KEY_CONFLICT");
    }

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail onNotFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage(), "NOT_FOUND");
    }

    /**
     * Unknown template, missing variable, oversized variables. All caller mistakes
     * that are fully described by their message, and none of which should page
     * anybody - so 400, not 500.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail onIllegalArgument(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request",
                e.getMessage(), "INVALID_REQUEST");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onBeanValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", message, "VALIDATION_FAILED");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail onUnreadableBody(HttpMessageNotReadableException e) {
        // The parser's message quotes the offending input back, which is a
        // reflection primitive if anything ever renders the error.
        return problem(HttpStatus.BAD_REQUEST, "Malformed request body",
                "Request body could not be parsed as JSON", "MALFORMED_JSON");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> onUnexpected(Exception e) {
        // Spring's own web exceptions already carry the right status. A catch-all
        // that ignores that manufactures 500s out of a missing favicon and turns a
        // clean dashboard into a fake incident.
        if (e instanceof ErrorResponse errorResponse) {
            ProblemDetail body = errorResponse.getBody();
            body.setProperty("error", errorCodeFor(errorResponse.getStatusCode()));
            return ResponseEntity.status(errorResponse.getStatusCode()).body(body);
        }
        // Stack trace to the log, never to the caller: exception messages leak
        // schema names, driver versions and occasionally connection strings.
        log.error("unhandled_exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error",
                        "Something went wrong. Quote the X-Correlation-Id from this response.",
                        "INTERNAL_ERROR"));
    }

    private static String errorCodeFor(HttpStatusCode status) {
        if (status.value() == HttpStatus.NOT_FOUND.value()) {
            return "NOT_FOUND";
        }
        if (status.value() == HttpStatus.METHOD_NOT_ALLOWED.value()) {
            return "METHOD_NOT_ALLOWED";
        }
        if (status.value() == HttpStatus.UNSUPPORTED_MEDIA_TYPE.value()) {
            return "UNSUPPORTED_MEDIA_TYPE";
        }
        return "REQUEST_REJECTED";
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail,
                                         String errorCode) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty("error", errorCode);
        return problem;
    }
}
