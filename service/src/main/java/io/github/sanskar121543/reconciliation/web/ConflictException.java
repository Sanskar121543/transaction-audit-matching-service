package io.github.sanskar121543.reconciliation.web;

/** The request is well-formed but the resource is in a state that does not allow it (409). */
public class ConflictException extends RuntimeException {

    private final String type;

    public ConflictException(String type, String message) {
        super(message);
        this.type = type;
    }

    public String type() {
        return type;
    }
}
