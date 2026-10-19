package io.github.sanskar121543.reconciliation.generator.cli;

import io.github.sanskar121543.reconciliation.generator.scoring.Predictions;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Talks to the service's public API, exactly like any other client: nothing here reads the database. */
public final class ServiceClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public ServiceClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
    }

    public long createRun(String label, long toleranceCents, int dateWindowDays) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("label", label);
        body.put("amountToleranceCents", toleranceCents);
        body.put("dateWindowDays", dateWindowDays);
        return expect(send(request("/v1/runs").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))), 201).get("id").asLong();
    }

    /** {@code side} is "ledger" or "statement". Returns the rejection report. */
    public JsonNode upload(long runId, String side, String csv) {
        HttpResponse<String> response = send(request("/v1/runs/" + runId + "/" + side)
                .header("Content-Type", "text/csv")
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString(csv)));
        return expect(response, response.statusCode() == 200 ? 200 : 201);
    }

    public JsonNode run(long runId) {
        return expect(send(request("/v1/runs/" + runId).GET()), 200);
    }

    public JsonNode waitForCompletion(long runId, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (true) {
            JsonNode run = run(runId);
            if ("COMPLETED".equals(run.get("status").asString())) {
                return run;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("Run " + runId + " did not complete within " + timeout);
            }
            sleep(Duration.ofMillis(200));
        }
    }

    public Predictions predictions(long runId) {
        List<Predictions.Match> matches = new ArrayList<>();
        long after = 0;
        while (true) {
            JsonNode page = expect(send(request("/v1/runs/" + runId + "/matches?limit=1000&afterId=" + after).GET()), 200);
            for (JsonNode m : page) {
                matches.add(new Predictions.Match(m.get("ledgerExternalId").asString(),
                        m.get("statementRowNumber").asInt(), m.get("rule").asString()));
                after = m.get("id").asLong();
            }
            if (page.size() < 1000) {
                break;
            }
        }
        List<Predictions.Break> breaks = new ArrayList<>();
        after = 0;
        while (true) {
            JsonNode page = expect(send(request("/v1/breaks?limit=200&runId=" + runId + "&afterId=" + after).GET()), 200);
            for (JsonNode b : page) {
                breaks.add(new Predictions.Break(b.get("type").asString(),
                        b.get("ledger").isNull() ? null : b.at("/ledger/externalId").asString(),
                        b.get("statement").isNull() ? null : b.at("/statement/rowNumber").asInt()));
                after = b.get("id").asLong();
            }
            if (page.size() < 200) {
                break;
            }
        }
        return new Predictions(matches, breaks);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30))
                .header("X-API-Key", apiKey);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("Request to " + baseUrl + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode expect(HttpResponse<String> response, int status) {
        if (response.statusCode() != status) {
            throw new IllegalStateException(response.request().method() + " " + response.request().uri() + " returned "
                    + response.statusCode() + ": " + response.body());
        }
        return JSON.readTree(response.body());
    }

    static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
