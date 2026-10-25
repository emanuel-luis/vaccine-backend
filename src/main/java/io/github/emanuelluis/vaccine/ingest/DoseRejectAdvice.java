package io.github.emanuelluis.vaccine.ingest;

import io.github.emanuelluis.vaccine.web.ApiExceptionHandler;
import io.github.emanuelluis.vaccine.web.ConflictException;
import io.github.emanuelluis.vaccine.web.InvalidRequestException;
import io.github.emanuelluis.vaccine.web.UnprocessableException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Answers the 4xx of POST /api/v1/doses and records each one in ops.ingest_reject (never the body).
 * It runs after the request transaction has rolled back, so the reject row is committed on its own.
 */
@RestControllerAdvice(assignableTypes = DoseController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class DoseRejectAdvice {

    private final DoseIngestRepository repository;
    private final ApiExceptionHandler handler;

    DoseRejectAdvice(DoseIngestRepository repository, ApiExceptionHandler handler) {
        this.repository = repository;
        this.handler = handler;
    }

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail invalidKey(InvalidRequestException ex, HttpServletRequest req) {
        return badRequest("invalid_idempotency_key", ex.getMessage(), req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidBody(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String fields = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .sorted()
                .reduce((a, b) -> a + "; " + b)
                .orElse("invalid body");
        return badRequest("validation_failed", fields, req);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        return badRequest("malformed_body", "the request body is not valid JSON for this endpoint", req);
    }

    @ExceptionHandler(UnprocessableException.class)
    ProblemDetail unprocessable(UnprocessableException ex, HttpServletRequest req) {
        record(req, 422, ex.code(), ex.getMessage());
        return handler.unprocessable(ex);
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(ConflictException ex, HttpServletRequest req) {
        record(req, 409, ex.code(), ex.getMessage());
        return handler.conflict(ex);
    }

    private ProblemDetail badRequest(String code, String detail, HttpServletRequest req) {
        record(req, 400, code, detail);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setProperty("code", code);
        return problem;
    }

    private void record(HttpServletRequest req, int status, String reason, String detail) {
        String key = req.getHeader("Idempotency-Key");
        if (key != null && !DoseController.KEY.matcher(key).matches()) {
            key = null;
        }
        String clientId = req.getUserPrincipal() instanceof JwtAuthenticationToken t
                ? DoseController.clientId(t.getToken())
                : "unknown";
        repository.reject(clientId, key, status, reason, detail);
    }
}
