package io.github.sanskar121543.reconciliation.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanskar121543.reconciliation.run.ReconciliationIT;
import io.github.sanskar121543.reconciliation.support.ApiClient;
import io.github.sanskar121543.reconciliation.support.IntegrationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;

/** What Prometheus scrapes, and that every metric the Grafana dashboard charts actually exists. */
@IntegrationTest
class MetricsIT {

    private static final Path GRAFANA_DASHBOARD = Path.of("..", "observability", "grafana", "dashboards",
            "reconciliation.json");
    private static final Pattern METRIC_NAME = Pattern.compile("\\b((?:matching|kafka|http_server|jvm)_[a-z_]+)\\b");

    @Value("${local.server.port}")
    int port;

    @Autowired
    BacklogGauges backlog;

    @Test
    void aRunsWorkIsCountedOnceItCommits() {
        ApiClient api = ApiClient.forPort(port);
        String before = scrape(api);
        long runId = api.createRun("metrics");
        api.uploadLedger(runId, ReconciliationIT.LEDGER);
        api.uploadStatement(runId, ReconciliationIT.STATEMENT);
        JsonNode summary = api.awaitCompleted(runId).get("summary");
        // Counts are added just after the commit that the API already shows: give the last one a moment.
        String completed = "matching_runs_completed_total{app_instance=\"test\"}";
        String after = Awaitility.await().atMost(Duration.ofSeconds(5))
                .until(() -> scrape(api), metrics -> delta(before, metrics, completed) == 1);

        assertThat(delta(before, after, "matching_statement_lines_stored_total{app_instance=\"test\"}"))
                .isEqualTo(summary.get("statementLines").asDouble());
        summary.get("matchesByRule").properties().forEach(rule -> assertThat(delta(before, after,
                "matching_matches_total{app_instance=\"test\",rule=\"" + rule.getKey() + "\"}"))
                .as(rule.getKey()).isEqualTo(rule.getValue().asDouble()));
        summary.get("breaksByType").properties().forEach(type -> assertThat(delta(before, after,
                "matching_breaks_total{app_instance=\"test\",type=\"" + type.getKey() + "\"}"))
                .as(type.getKey()).isEqualTo(type.getValue().asDouble()));
        assertThat(delta(before, after, "matching_import_rows_total{app_instance=\"test\",outcome=\"accepted\",side=\"ledger\"}"))
                .isEqualTo(summary.get("ledgerRecords").asDouble());
    }

    @Test
    void theBacklogGaugesDescribeTheDatabase() {
        ApiClient api = ApiClient.forPort(port);
        long runId = api.createRun("backlog");
        api.uploadLedger(runId, ReconciliationIT.LEDGER);
        api.uploadStatement(runId, ReconciliationIT.STATEMENT);
        api.awaitCompleted(runId);
        long breakId = api.breaks(runId).get(0).get("id").asLong();
        api.post("/v1/breaks/" + breakId + "/investigate", Map.of("version", 0));

        backlog.refresh();
        String metrics = scrape(api);

        assertThat(value(metrics, "matching_breaks_backlog{app_instance=\"test\",status=\"INVESTIGATING\"}"))
                .isGreaterThanOrEqualTo(1);
        assertThat(value(metrics, "matching_breaks_backlog{app_instance=\"test\",status=\"OPEN\"}"))
                .isGreaterThanOrEqualTo(4);
        assertThat(value(metrics, "matching_runs{app_instance=\"test\",status=\"COMPLETED\"}")).isGreaterThanOrEqualTo(1);
        assertThat(value(metrics, "matching_break_changes_total{action=\"STATUS_CHANGED\",app_instance=\"test\","
                + "to_status=\"INVESTIGATING\"}")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void everyMetricTheGrafanaDashboardChartsExists() throws IOException {
        ApiClient api = ApiClient.forPort(port);
        long runId = api.createRun("grafana");
        api.uploadLedger(runId, ReconciliationIT.LEDGER);
        api.uploadStatement(runId, ReconciliationIT.STATEMENT);
        api.awaitCompleted(runId);
        long breakId = api.breaks(runId).get(0).get("id").asLong();
        api.post("/v1/breaks/" + breakId + "/investigate", Map.of("version", 0));
        String metrics = scrape(api);

        Set<String> charted = new TreeSet<>();
        Matcher m = METRIC_NAME.matcher(Files.readString(GRAFANA_DASHBOARD));
        while (m.find()) {
            charted.add(m.group(1));
        }

        assertThat(charted).hasSizeGreaterThan(8);
        assertThat(charted).allSatisfy(name -> assertThat(metrics).as(name).containsPattern("(?m)^" + name + "[{ ]"));
    }

    private static String scrape(ApiClient api) {
        return api.withKey(null).get("/actuator/prometheus").rawBody();
    }

    private static double delta(String before, String after, String series) {
        return value(after, series) - value(before, series);
    }

    /** The sample's value, or 0 if the series does not exist yet (a counter not yet incremented). */
    private static double value(String metrics, String series) {
        for (String line : metrics.split("\n")) {
            if (line.startsWith(series + " ")) {
                return Double.parseDouble(line.substring(series.length() + 1).strip());
            }
        }
        return 0;
    }
}
