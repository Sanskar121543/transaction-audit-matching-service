package io.github.sanskar121543.reconciliation.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanskar121543.reconciliation.run.ReconciliationIT;
import io.github.sanskar121543.reconciliation.support.ApiClient;
import io.github.sanskar121543.reconciliation.support.ApiResponse;
import io.github.sanskar121543.reconciliation.support.IntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The dashboard page, its event stream and the per-run breakdown it charts. */
@IntegrationTest
class DashboardIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Value("${local.server.port}")
    int port;

    ApiClient api;

    @BeforeEach
    void client() {
        api = ApiClient.forPort(port);
    }

    @Test
    void thePageAndItsScriptsNeedNoKeyButItsDataDoes() {
        ApiClient anonymous = api.withKey(null);

        ApiResponse page = anonymous.get("/");
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.rawBody()).contains("<title>Reconciliation Dashboard</title>");
        assertThat(page.header("Content-Security-Policy")).contains("script-src 'self'").contains("connect-src 'self'");
        assertThat(page.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(anonymous.get("/app.js").status()).isEqualTo(200);
        assertThat(anonymous.get("/styles.css").status()).isEqualTo(200);
        assertThat(anonymous.get("/vendor/chart.umd.min.js").rawBody()).contains("Chart.js v4.5.1");

        assertThat(anonymous.get("/v1/stream").status()).isEqualTo(401);
        assertThat(anonymous.get("/v1/runs/1/breakdown").status()).isEqualTo(401);
    }

    @Test
    void theStreamSendsTheLatestRunsAtOnceThenEachChangeFromAnyInstance() throws Exception {
        long runId = api.createRun("streamed");
        HttpResponse<Stream<String>> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/stream"))
                        .header("X-API-Key", ApiClient.READ_ONLY_KEY).build(),
                HttpResponse.BodyHandlers.ofLines());
        try {
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                    type -> assertThat(type).startsWith("text/event-stream"));
            assertThat(response.headers().firstValue("X-Accel-Buffering")).contains("no");
            BlockingQueue<JsonNode> events = readRunsEvents(response.body());

            JsonNode first = events.poll(10, TimeUnit.SECONDS);
            assertThat(first).isNotNull();
            assertThat(first.get("runs").get(0).get("id").asLong()).isEqualTo(runId);
            assertThat(first.get("runs").get(0).get("status").asString()).isEqualTo("RECEIVING");
            assertThat(first.get("runs").size()).isLessThanOrEqualTo(DashboardStream.RUNS);

            api.uploadLedger(runId, ReconciliationIT.LEDGER);
            api.uploadStatement(runId, ReconciliationIT.STATEMENT);
            long breaks = api.awaitCompleted(runId).at("/summary/breaks").asLong();

            List<String> seen = new ArrayList<>();
            JsonNode latest;
            do {
                latest = events.poll(10, TimeUnit.SECONDS);
                assertThat(latest).as("an event after %s", seen).isNotNull();
                seen.add(latest.get("runs").get(0).get("status").asString());
            } while (!"COMPLETED".equals(seen.getLast()));
            assertThat(latest.get("runs").get(0).at("/summary/breaks").asLong()).isEqualTo(breaks);
            assertThat(latest.get("runs").get(0).at("/summary/breaksByStatus/OPEN").asLong()).isEqualTo(breaks);
        } finally {
            response.body().close();
        }
    }

    @Test
    void theBreakdownCountsTheRunsBreaksByTypeAgeStatusAndAccount() {
        long runId = api.createRun("breakdown");
        api.uploadLedger(runId, ReconciliationIT.LEDGER);
        api.uploadStatement(runId, ReconciliationIT.STATEMENT);
        api.awaitCompleted(runId);
        long missingInBank = api.get("/v1/breaks?runId=" + runId + "&type=MISSING_IN_BANK").body().get(0)
                .get("id").asLong();
        api.post("/v1/breaks/" + missingInBank + "/investigate", Map.of("version", 0));
        api.post("/v1/breaks/" + missingInBank + "/resolve",
                Map.of("version", 1, "resolution", "BANK_ERROR", "note", "bank confirmed"));

        ApiResponse response = api.withKey(ApiClient.READ_ONLY_KEY).get("/v1/runs/" + runId + "/breakdown");

        assertThat(response.status()).isEqualTo(200);
        JsonNode b = response.body();
        assertThat(rows(b.get("byType"))).containsExactlyInAnyOrder(
                "MISSING_IN_BANK 1 25000 0 0",
                "MISSING_IN_LEDGER 1 250 1 250",
                "AMOUNT_MISMATCH 1 2000 1 2000",
                "DUPLICATE 2 13500 2 13500");
        assertThat(b.get("byStatus").get("OPEN").asLong()).isEqualTo(4);
        assertThat(b.get("byStatus").get("RESOLVED").asLong()).isEqualTo(1);
        assertThat(b.get("resolutions").get("BANK_ERROR").asLong()).isEqualTo(1);
        // Item dates 2026-10-02..07, "today" 2026-10-31 (FixedClockConfiguration): all 24 to 29 days old.
        assertThat(b.get("byAge")).extracting(a -> a.get("bucket").asString() + " " + a.get("open").asLong() + " "
                        + a.get("investigating").asLong() + " " + a.get("resolved").asLong())
                .containsExactly("DAYS_0_2 0 0 0", "DAYS_3_7 0 0 0", "DAYS_8_30 4 0 1", "DAYS_31_PLUS 0 0 0");
        assertThat(b.get("topUnresolvedAccounts")).extracting(a -> a.get("accountId").asString() + " "
                        + a.get("unresolved").asLong() + " " + a.get("unresolvedExposureCents").asLong())
                .containsExactly("ACC-1 3 9750", "ACC-2 1 6000");

        assertThat(api.get("/v1/runs/999999999/breakdown").status()).isEqualTo(404);
    }

    private static List<String> rows(JsonNode byType) {
        List<String> rows = new ArrayList<>();
        byType.forEach(t -> rows.add(t.get("type").asString() + " " + t.get("breaks").asLong() + " "
                + t.get("exposureCents").asLong() + " " + t.get("unresolved").asLong() + " "
                + t.get("unresolvedExposureCents").asLong()));
        return rows;
    }

    /** Parses "event:runs" / "data:..." blocks off the stream on a background thread. */
    private static BlockingQueue<JsonNode> readRunsEvents(Stream<String> lines) {
        BlockingQueue<JsonNode> events = new LinkedBlockingQueue<>();
        Thread.ofVirtual().start(() -> {
            String event = null;
            StringBuilder data = new StringBuilder();
            try {
                Iterator<String> it = lines.iterator();
                while (it.hasNext()) {
                    String line = it.next();
                    if (line.isEmpty()) {
                        if ("runs".equals(event)) {
                            events.add(JSON.readTree(data.toString()));
                        }
                        event = null;
                        data.setLength(0);
                    } else if (line.startsWith("event:")) {
                        event = line.substring("event:".length()).strip();
                    } else if (line.startsWith("data:")) {
                        data.append(line.substring("data:".length()));
                    }
                }
            } catch (RuntimeException closed) {
                // The test closed the stream.
            }
        });
        return events;
    }
}
