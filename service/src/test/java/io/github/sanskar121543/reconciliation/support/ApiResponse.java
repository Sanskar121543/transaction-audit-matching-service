package io.github.sanskar121543.reconciliation.support;

import java.net.http.HttpHeaders;
import tools.jackson.databind.JsonNode;

public record ApiResponse(int status, HttpHeaders headers, String rawBody, JsonNode body) {

    public long longField(String name) {
        return body.get(name).asLong();
    }

    public String textField(String name) {
        JsonNode node = body.get(name);
        return node == null || node.isNull() ? null : node.asString();
    }

    public String header(String name) {
        return headers.firstValue(name).orElse(null);
    }

    public String problemType() {
        return textField("type");
    }
}
