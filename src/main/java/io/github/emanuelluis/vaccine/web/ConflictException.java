package io.github.emanuelluis.vaccine.web;

/** The request conflicts with existing state (answered with 409 and a machine-readable code). */
public class ConflictException extends RuntimeException {
    private final String code;

    public ConflictException(String code, String detail) {
        super(detail);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
