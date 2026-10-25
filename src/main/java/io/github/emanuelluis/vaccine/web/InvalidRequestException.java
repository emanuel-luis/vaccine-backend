package io.github.emanuelluis.vaccine.web;

/** A request that is well formed but asks for something the API does not allow (answered with 400). */
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }
}
