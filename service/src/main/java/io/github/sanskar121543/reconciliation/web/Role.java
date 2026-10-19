package io.github.sanskar121543.reconciliation.web;

public enum Role {
    OPERATOR,
    READ_ONLY;

    static Role parse(String value) {
        return switch (value) {
            case "operator" -> OPERATOR;
            case "readonly" -> READ_ONLY;
            default -> throw new IllegalStateException("Unknown API key role '" + value + "' (use operator or readonly)");
        };
    }
}
