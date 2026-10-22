package io.github.sanskar121543.mcp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only way this server reaches the matching service, and it can only send GET. Together with the
 * read-only key ({@link KeyCheck}) that makes every tool read-only twice over: this class has no method that
 * could change anything, and the service would refuse the change anyway (403).
 */
@Component
public class ServiceClient {

    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private final McpProperties properties;
    private final JsonMapper jsonMapper;
    private final HttpClient http;

    public ServiceClient(McpProperties properties, JsonMapper jsonMapper) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.http = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
    }

    /** {@code pathAndQuery} is built by the tools from validated arguments only. */
    public JsonNode get(String pathAndQuery) {
        HttpResponse<byte[]> response = send(request(pathAndQuery).GET().build());
        if (response.statusCode() != 200) {
            throw ServiceException.of(response.statusCode(), parse(response.body()));
        }
        return parse(response.body());
    }

    HttpRequest.Builder request(String pathAndQuery) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new ServiceException(0, "Not configured", "Set MCP_API_KEY to the service's read-only API key.");
        }
        return HttpRequest.newBuilder(URI.create(properties.serviceUrl() + pathAndQuery))
                .timeout(properties.timeout())
                .header("Accept", "application/json")
                .header("X-API-Key", properties.apiKey());
    }

    HttpResponse<byte[]> send(HttpRequest request) {
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.body().length > MAX_RESPONSE_BYTES) {
                throw new ServiceException(response.statusCode(), "Response too large",
                        "The service answered with more than " + MAX_RESPONSE_BYTES + " bytes; ask for fewer items.");
            }
            return response;
        } catch (IOException e) {
            throw new ServiceException(0, "Service unreachable",
                    "Could not reach the matching service at " + properties.serviceUrl() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException(0, "Interrupted", "The request to the matching service was interrupted.");
        }
    }

    private JsonNode parse(byte[] body) {
        if (body.length == 0) {
            return null;
        }
        try {
            return jsonMapper.readTree(new String(body, StandardCharsets.UTF_8));
        } catch (JacksonException notJson) {
            return null;
        }
    }

    /** The service refused or failed: its problem title and detail, never a stack trace. */
    public static class ServiceException extends RuntimeException {

        private final int status;
        private final String title;

        ServiceException(int status, String title, String detail) {
            super(detail);
            this.status = status;
            this.title = title;
        }

        static ServiceException of(int status, JsonNode problem) {
            String title = problem != null && problem.hasNonNull("title") ? problem.get("title").asString() : "HTTP " + status;
            String detail = problem != null && problem.hasNonNull("detail") ? problem.get("detail").asString() : "";
            return new ServiceException(status, title, detail);
        }

        public int status() {
            return status;
        }

        public String title() {
            return title;
        }
    }
}
