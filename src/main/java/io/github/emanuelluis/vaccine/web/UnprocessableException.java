package io.github.emanuelluis.vaccine.web;

/** A well-formed request that breaks a business rule (answered with 422 and a machine-readable code). */
public class UnprocessableException extends RuntimeException {
    private final String code;
    private final String field;

    public UnprocessableException(String code, String field, String detail) {
        super(detail);
        this.code = code;
        this.field = field;
    }

    public String code() {
        return code;
    }

    /** Request field the rule refers to, or null. */
    public String field() {
        return field;
    }
}
