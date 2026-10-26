package io.github.emanuelluis.vaccine.web;

import org.springframework.http.HttpStatus;

/** A conditional request failed: 412 (stale {@code If-Match}) or 428 (precondition header missing). */
public class PreconditionException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public PreconditionException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
