package io.github.sanskar121543.mcp;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.stereotype.Component;

/**
 * Refuses to answer any tool call until the service has confirmed that this server's key cannot write. A
 * misconfiguration (the operator key pasted into MCP_API_KEY) then disables the server instead of quietly
 * handing an AI client write access.
 *
 * <p>The probe is a POST to {@code /v1/runs} with an empty JSON object: the service checks the key's role
 * before it reads the body, so a read-only key gets 403, and any other key gets 400 because the body has no
 * label. Either way nothing is created. Passing is remembered; failing is checked again on the next call.
 */
@Component
public class KeyCheck {

    private final ServiceClient service;
    private volatile boolean confirmedReadOnly;

    public KeyCheck(ServiceClient service) {
        this.service = service;
    }

    public void ensureReadOnly() {
        if (confirmedReadOnly) {
            return;
        }
        HttpResponse<byte[]> response = service.send(service.request("/v1/runs")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build());
        switch (response.statusCode()) {
            case 403 -> confirmedReadOnly = true;
            case 401 -> throw new ServiceClient.ServiceException(401, "Key refused",
                    "The matching service does not recognise MCP_API_KEY.");
            default -> throw new ServiceClient.ServiceException(response.statusCode(), "Key can write",
                    "MCP_API_KEY is allowed to change data (the service answered " + response.statusCode()
                            + " instead of 403). This server only runs with a read-only key; use READONLY_API_KEY.");
        }
    }
}
