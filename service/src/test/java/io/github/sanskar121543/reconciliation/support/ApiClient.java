package io.github.sanskar121543.reconciliation.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public class ApiClient {

    public static final String OPERATOR_KEY = "test-key";
    public static final String READ_ONLY_KEY = "readonly-key";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String baseUrl;
    private final String apiKey;

    public ApiClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    public static ApiClient forPort(int port) {
        return new ApiClient("http://localhost:" + port, OPERATOR_KEY);
    }

    public ApiClient withKey(String key) {
        return new ApiClient(baseUrl, key);
    }

    public String baseUrl() {
        return baseUrl;
    }

    public ApiResponse get(String path) {
        return send(request(path).GET().build());
    }

    public ApiResponse post(String path, Object body) {
        return send(path, "application/json", JSON.writeValueAsString(body));
    }

    public ApiResponse send(String path, String contentType, String body) {
        return send(request(path).header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    public long createRun(String label) {
        ApiResponse response = post("/v1/runs", Map.of("label", label));
        if (response.status() != 201) {
            throw new IllegalStateException("Could not create run: " + response.status() + " " + response.rawBody());
        }
        return response.longField("id");
    }

    public ApiResponse uploadLedger(long runId, String csv) {
        return send("/v1/runs/" + runId + "/ledger", "text/csv", csv);
    }

    public ApiResponse uploadStatement(long runId, String csv) {
        return send("/v1/runs/" + runId + "/statement", "text/csv", csv);
    }

    public JsonNode breaks(long runId) {
        return get("/v1/breaks?limit=200&runId=" + runId).body();
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(60));
        if (apiKey != null) {
            builder.header("X-API-Key", apiKey);
        }
        return builder;
    }

    private ApiResponse send(HttpRequest request) {
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            return new ApiResponse(response.statusCode(), response.headers(), response.body(), parseJson(response.body()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // nginx's own error pages are HTML; only JSON bodies are parsed.
    private static JsonNode parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(raw);
        } catch (JacksonException notJson) {
            return null;
        }
    }

    public static class UncheckedIOException extends RuntimeException {
        UncheckedIOException(IOException cause) {
            super(cause);
        }
    }
}
