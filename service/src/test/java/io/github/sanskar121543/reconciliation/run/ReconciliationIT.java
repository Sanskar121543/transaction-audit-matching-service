package io.github.sanskar121543.reconciliation.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanskar121543.reconciliation.ingest.StatementLineInput;
import io.github.sanskar121543.reconciliation.ingest.StatementPublisher;
import io.github.sanskar121543.reconciliation.matching.StatementLineWorker;
import io.github.sanskar121543.reconciliation.support.ApiClient;
import io.github.sanskar121543.reconciliation.support.ApiResponse;
import io.github.sanskar121543.reconciliation.support.IntegrationTest;
import io.github.sanskar121543.reconciliation.support.RunInvariants;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import tools.jackson.databind.JsonNode;

/** One small run that produces every break type, checked through the public API. */
@IntegrationTest
public class ReconciliationIT {

    public static final String LEDGER = """
            external_id,account_id,booking_date,amount,currency,reference,description
            L-1,ACC-1,2026-10-01,100.00,USD,INV-1,matched exactly
            L-2,ACC-1,2026-10-02,250.00,USD,INV-2,never reaches the bank
            L-3,ACC-1,2026-10-03,500.00,USD,INV-000003,bank received less
            L-4,ACC-1,2026-10-04,75.00,USD,INV-4,bank posts it twice
            L-5,ACC-2,2026-10-05,-40.00,USD,PAY-5,matched exactly
            L-6,ACC-2,2026-10-06,-60.00,USD,PAY-6,booked twice
            L-7,ACC-2,2026-10-06,-60.00,USD,PAY-6,booked twice (copy)
            """;
    public static final String STATEMENT = """
            account_id,value_date,amount,reference,description
            ACC-1,2026-10-01,100.00,INV-1,
            ACC-1,2026-10-04,480.00,inv 3,
            ACC-1,2026-10-04,75.00,INV-4,
            ACC-1,2026-10-05,75.00,INV-4,
            ACC-1,2026-10-07,-2.50,,bank fee
            ACC-2,2026-10-05,-40.00,PAY-5,
            ACC-2,2026-10-06,-60.00,PAY-6,
            """;

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    KafkaListenerEndpointRegistry listeners;

    @Autowired
    StatementLineWorker worker;

    ApiClient api;

    @BeforeEach
    void client() {
        api = ApiClient.forPort(port);
    }

    @Test
    void findsEveryKindOfBreakAndMatchesTheRestExactly() {
        long runId = api.createRun("october");
        assertThat(api.uploadLedger(runId, LEDGER).status()).isEqualTo(201);
        ApiResponse statement = api.uploadStatement(runId, STATEMENT);
        assertThat(statement.status()).isEqualTo(201);
        assertThat(statement.body().get("rowsAccepted").asInt()).isEqualTo(7);
        api.awaitCompleted(runId);

        List<Map<String, Object>> matches = jdbc.queryForList("""
                SELECT l.external_id, s.row_number, m.rule FROM matches m
                JOIN ledger_records l ON l.id = m.ledger_record_id JOIN statement_lines s ON s.id = m.statement_line_id
                WHERE m.run_id = ? ORDER BY l.external_id
                """, runId);
        assertThat(matches).extracting(m -> m.get("external_id") + "~" + m.get("row_number") + ":" + m.get("rule"))
                .containsExactly("L-1~1:EXACT", "L-4~3:EXACT", "L-5~6:EXACT", "L-6~7:EXACT");

        assertThat(describe(api.breaks(runId))).containsExactlyInAnyOrder(
                "MISSING_IN_BANK L-2 -",
                "AMOUNT_MISMATCH L-3 2",
                "DUPLICATE - 4",
                "MISSING_IN_LEDGER - 5",
                "DUPLICATE L-7 -");

        JsonNode run = api.get("/v1/runs/" + runId).body();
        assertThat(run.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(run.at("/summary/matches").asLong()).isEqualTo(4);
        assertThat(run.at("/summary/breaks").asLong()).isEqualTo(5);
        assertThat(run.at("/summary/matchRate").asDouble()).isEqualTo(0.5714);
    }

    @Test
    void aFuzzyRuleNeverTakesALedgerRecordThatALaterLineMatchesExactly() {
        long runId = api.createRun("no stealing");
        api.uploadLedger(runId, """
                external_id,account_id,booking_date,amount,reference
                L-1,ACC-1,2026-10-10,100.00,INV-1
                """);
        // Row 1 is within the amount tolerance of L-1, but row 2 is L-1 exactly. Matching row 1 greedily
        // by tolerance would leave the real payment unmatched.
        api.uploadStatement(runId, """
                account_id,value_date,amount,reference
                ACC-1,2026-10-10,100.50,WIRE 55
                ACC-1,2026-10-10,100.00,INV-1
                """);
        api.awaitCompleted(runId);

        JsonNode matches = api.get("/v1/runs/" + runId + "/matches").body();
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).get("statementRowNumber").asInt()).isEqualTo(2);
        assertThat(matches.get(0).get("rule").asString()).isEqualTo("EXACT");
        assertThat(describe(api.breaks(runId))).containsExactly("MISSING_IN_LEDGER - 1");
    }

    @Test
    void theFuzzyRulesMatchFeesAndLateReformattedReferences() {
        long runId = api.createRun("fuzzy");
        api.uploadLedger(runId, """
                external_id,account_id,booking_date,amount,reference
                L-1,ACC-1,2026-10-01,250.00,INV-000777
                L-2,ACC-1,2026-10-02,980.00,PO-12
                """);
        api.uploadStatement(runId, """
                account_id,value_date,amount,reference
                ACC-1,2026-10-02,249.25,WIRE 1188
                ACC-1,2026-10-20,980.00,po 0012
                """);
        api.awaitCompleted(runId);

        JsonNode matches = api.get("/v1/runs/" + runId + "/matches").body();
        assertThat(matches).extracting(m -> m.get("ledgerExternalId").asString() + ":" + m.get("rule").asString())
                .containsExactlyInAnyOrder("L-1:TOLERANCE", "L-2:NORMALIZED_REFERENCE");
        assertThat(api.breaks(runId)).isEmpty();
        assertThat(api.get("/v1/runs/" + runId).body().at("/summary/matchRate").asDouble()).isEqualTo(1.0);
    }

    @Test
    void matchesArePagedInIdOrder() {
        long runId = completedRun();
        JsonNode first = api.get("/v1/runs/" + runId + "/matches?limit=3").body();
        long lastId = first.get(2).get("id").asLong();
        JsonNode rest = api.get("/v1/runs/" + runId + "/matches?afterId=" + lastId).body();

        assertThat(first).hasSize(3);
        assertThat(rest).hasSize(1);
        assertThat(rest.get(0).get("id").asLong()).isGreaterThan(lastId);
        assertThat(api.get("/v1/runs/999999/matches").status()).isEqualTo(404);
    }

    @Test
    void aBreakShowsBothSidesAgeAndWhyItDidNotMatch() {
        long runId = completedRun();
        JsonNode mismatch = find(api.breaks(runId), "AMOUNT_MISMATCH");

        JsonNode detail = api.get("/v1/breaks/" + mismatch.get("id").asLong()).body();

        assertThat(detail.at("/break/ledger/externalId").asString()).isEqualTo("L-3");
        assertThat(detail.at("/break/ledger/amountCents").asLong()).isEqualTo(500_00);
        assertThat(detail.at("/break/statement/rowNumber").asInt()).isEqualTo(2);
        assertThat(detail.at("/break/statement/amountCents").asLong()).isEqualTo(480_00);
        assertThat(detail.at("/break/exposureCents").asLong()).isEqualTo(20_00);
        // The test clock says today is 2026-10-31; the ledger booked it on 2026-10-03.
        assertThat(detail.at("/break/ageDays").asLong()).isEqualTo(28);
        assertThat(detail.at("/break/agingBucket").asString()).isEqualTo("DAYS_8_30");
        assertThat(detail.at("/explanation/verdict").asString()).contains("differ by 20.00");
        assertThat(detail.at("/explanation/checks/0/rule").asString()).isEqualTo("EXACT");
        assertThat(detail.at("/explanation/checks/0/result").asString()).isEqualTo("NO_CANDIDATE");
    }

    @Test
    void filtersBreaksByTypeStatusAmountAndAge() {
        long runId = completedRun();
        String base = "/v1/breaks?runId=" + runId;

        assertThat(describe(api.get(base + "&type=DUPLICATE").body())).containsExactlyInAnyOrder(
                "DUPLICATE - 4", "DUPLICATE L-7 -");
        assertThat(describe(api.get(base + "&minExposureCents=7500").body())).containsExactlyInAnyOrder(
                "MISSING_IN_BANK L-2 -", "DUPLICATE - 4");
        assertThat(describe(api.get(base + "&minAgeDays=29").body())).containsExactly("MISSING_IN_BANK L-2 -");
        assertThat(describe(api.get(base + "&status=RESOLVED").body())).isEmpty();
        assertThat(api.get(base + "&type=NOPE").status()).isEqualTo(400);
        assertThat(api.get(base + "&limit=1000").status()).isEqualTo(400);
    }

    @Test
    void uploadingTheSameFileAgainChangesNothing() {
        long runId = completedRun();
        long breaks = count("breaks", runId);

        ApiResponse again = api.uploadStatement(runId, STATEMENT);
        ApiResponse ledgerAgain = api.uploadLedger(runId, LEDGER);

        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().get("replayed").asBoolean()).isTrue();
        assertThat(ledgerAgain.status()).isEqualTo(200);
        assertThat(count("breaks", runId)).isEqualTo(breaks);
        assertThat(count("statement_lines", runId)).isEqualTo(7);
    }

    @Test
    void aDifferentFileForTheSameRunIsRefused() {
        long runId = completedRun();

        ApiResponse other = api.uploadStatement(runId, STATEMENT + "ACC-1,2026-10-08,1.00,X,\n");

        assertThat(other.status()).isEqualTo(409);
        assertThat(other.problemType()).isEqualTo("/problems/already-imported");
    }

    @Test
    void theStatementNeedsTheLedgerFirst() {
        long runId = api.createRun("no ledger yet");

        ApiResponse statement = api.uploadStatement(runId, STATEMENT);

        assertThat(statement.status()).isEqualTo(409);
        assertThat(statement.problemType()).isEqualTo("/problems/ledger-required-first");
    }

    @Test
    void unknownRunsAndBreaksAre404() {
        assertThat(api.uploadLedger(999_999, LEDGER).status()).isEqualTo(404);
        assertThat(api.get("/v1/runs/999999").status()).isEqualTo(404);
        assertThat(api.get("/v1/breaks/999999").status()).isEqualTo(404);
    }

    @Test
    void runSettingsAreValidated() {
        assertThat(api.post("/v1/runs", Map.of("label", "")).status()).isEqualTo(400);
        assertThat(api.post("/v1/runs", Map.of("label", "x", "amountToleranceCents", -1)).status()).isEqualTo(400);
        assertThat(api.post("/v1/runs", Map.of("label", "x", "dateWindowDays", 32)).status()).isEqualTo(400);
        assertThat(api.post("/v1/runs", Map.of("label", "x", "unknown", 1)).status()).isEqualTo(400);
        ApiResponse custom = api.post("/v1/runs", Map.of("label", "x", "amountToleranceCents", 0, "dateWindowDays", 5));
        assertThat(custom.longField("amountToleranceCents")).isZero();
        assertThat(custom.longField("dateWindowDays")).isEqualTo(5);
    }

    @Test
    void aRunIsMatchingUntilItsLinesAreStoredAndUploadingTheStatementMeanwhileQueuesThemAgain() {
        long runId = api.createRun("paused workers");
        api.uploadLedger(runId, LEDGER);
        MessageListenerContainer workers = listeners.getListenerContainer("statement-lines");
        workers.pause();
        try {
            Awaitility.await().atMost(Duration.ofSeconds(30)).until(workers::isContainerPaused);
            ApiResponse first = api.uploadStatement(runId, STATEMENT);
            JsonNode matching = api.get("/v1/runs/" + runId).body();
            ApiResponse again = api.uploadStatement(runId, STATEMENT);

            assertThat(first.status()).isEqualTo(201);
            assertThat(matching.get("status").asString()).isEqualTo("MATCHING");
            assertThat(matching.get("statementQueuedAt").isNull()).as("every line acknowledged by Kafka").isFalse();
            assertThat(matching.at("/summary/statementLines").asLong()).isZero();
            assertThat(matching.at("/summary/statementLinesExpected").asLong()).isEqualTo(7);
            assertThat(again.status()).isEqualTo(200);
            assertThat(again.body().get("replayed").asBoolean()).isTrue();
        } finally {
            workers.resume();
        }
        api.awaitCompleted(runId);

        assertThat(api.get("/v1/runs/" + runId).body().at("/summary/breaks").asLong()).isEqualTo(5);
        assertThat(describe(api.breaks(runId))).containsExactlyInAnyOrder("MISSING_IN_BANK L-2 -",
                "AMOUNT_MISMATCH L-3 2", "DUPLICATE - 4", "MISSING_IN_LEDGER - 5", "DUPLICATE L-7 -");
        RunInvariants.assertLinesStoredOnceInFileOrder(jdbc, runId, 7);
    }

    @Test
    void aBatchDeliveredAgainAfterTheRunCompletedChangesNothing() {
        long runId = completedRun();
        long matches = count("matches", runId);
        long breaks = count("breaks", runId);
        String sha256 = jdbc.queryForObject("SELECT sha256 FROM imports WHERE run_id = ? AND side = 'STATEMENT'",
                String.class, runId);
        List<StatementLineInput> lines = List.of(
                new StatementLineInput(1, "ACC-1", LocalDate.of(2026, 10, 1), 100_00, "INV-1", "", null),
                new StatementLineInput(2, "ACC-1", LocalDate.of(2026, 10, 4), 480_00, "inv 3", "", null));

        worker.handle(runId, StatementPublisher.messages(runId, sha256, lines));

        assertThat(count("matches", runId)).isEqualTo(matches);
        assertThat(count("breaks", runId)).isEqualTo(breaks);
        assertThat(api.get("/v1/runs/" + runId).body().get("status").asString()).isEqualTo("COMPLETED");
        RunInvariants.assertLinesStoredOnceInFileOrder(jdbc, runId, 7);
    }

    private long completedRun() {
        long runId = api.createRun("october");
        api.uploadLedger(runId, LEDGER);
        api.uploadStatement(runId, STATEMENT);
        api.awaitCompleted(runId);
        return runId;
    }

    private long count(String table, long runId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE run_id = ?", Long.class, runId);
    }

    static List<String> describe(JsonNode breaks) {
        List<String> result = new ArrayList<>();
        for (JsonNode b : breaks) {
            String ledger = b.get("ledger").isNull() ? "-" : b.at("/ledger/externalId").asString();
            String line = b.get("statement").isNull() ? "-" : b.at("/statement/rowNumber").asString();
            result.add(b.get("type").asString() + " " + ledger + " " + line);
        }
        return result;
    }

    private static JsonNode find(JsonNode breaks, String type) {
        for (JsonNode b : breaks) {
            if (type.equals(b.get("type").asString())) {
                return b;
            }
        }
        throw new AssertionError("No " + type + " break");
    }
}
