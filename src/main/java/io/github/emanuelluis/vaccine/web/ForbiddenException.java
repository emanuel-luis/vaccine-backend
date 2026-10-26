package io.github.emanuelluis.vaccine.web;

/** The caller is authenticated but not allowed to read this region (answered with 403 and a {@code code}). */
public class ForbiddenException extends RuntimeException {

    private final String code;

    public ForbiddenException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
