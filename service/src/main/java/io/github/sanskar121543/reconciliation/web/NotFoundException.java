package io.github.sanskar121543.reconciliation.web;

public class NotFoundException extends RuntimeException {

    private final String type;

    public NotFoundException(String type, String message) {
        super(message);
        this.type = type;
    }

    public String type() {
        return type;
    }
}
