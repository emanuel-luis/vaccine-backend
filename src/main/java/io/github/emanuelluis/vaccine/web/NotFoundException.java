package io.github.emanuelluis.vaccine.web;

/** The requested resource does not exist (answered with 404). */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
