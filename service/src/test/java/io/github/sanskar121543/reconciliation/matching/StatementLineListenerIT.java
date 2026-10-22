package io.github.sanskar121543.reconciliation.matching;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanskar121543.reconciliation.run.ReconciliationIT;
import io.github.sanskar121543.reconciliation.support.ApiClient;
import io.github.sanskar121543.reconciliation.support.IntegrationTest;
import io.github.sanskar121543.reconciliation.support.KafkaProbe;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.utils.Utils;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Records on the topic that are not statement lines (written by hand, or by an incompatible version) must
 * not stop the partition: they go to the dead-letter topic, and lines behind them on the same partition are
 * matched as usual.
 */
@IntegrationTest
class StatementLineListenerIT {

    static final String UNREADABLE = "not json";
    static final String INCOMPLETE = "{\"runId\": 1, \"row\": 1}";
    static final String INCONSISTENT = """
            {"runId": 1, "sha256": "%s", "row": 3, "previousRow": 3, "accountId": "ACC-1",
             "valueDate": "2026-10-01", "amountCents": 100, "reference": "", "description": "",
             "bankTransactionId": null}""".formatted("a".repeat(64));

    @Value("${local.server.port}")
    int port;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    KafkaConnectionDetails kafkaConnection;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void recordsThatAreNotStatementLinesAreDeadLetteredAndTheLinesBehindThemStillMatch() throws Exception {
        // Keyed like ACC-1's lines, so they land on the partition the run below uses.
        for (String value : List.of(UNREADABLE, INCOMPLETE, INCONSISTENT)) {
            kafka.send("statement.lines", "ACC-1", value).get();
        }
        ApiClient api = ApiClient.forPort(port);
        long runId = api.createRun("behind bad records");
        api.uploadLedger(runId, ReconciliationIT.LEDGER);
        api.uploadStatement(runId, ReconciliationIT.STATEMENT);

        assertThat(api.awaitCompleted(runId).at("/summary/statementLines").asLong()).isEqualTo(7);
        try (KafkaProbe probe = new KafkaProbe(String.join(",", kafkaConnection.getBootstrapServers()))) {
            List<ConsumerRecord<String, String>> dead = probe.deadLetters(3, Duration.ofSeconds(30));
            assertThat(dead).extracting(ConsumerRecord::value).contains(UNREADABLE, INCOMPLETE, INCONSISTENT);
            assertThat(dead).filteredOn(r -> r.value().equals(INCONSISTENT)).first()
                    .extracting(r -> new String(r.headers().lastHeader("dead-letter-reason").value(),
                            StandardCharsets.UTF_8))
                    .asString().contains("previousRow");
            probe.awaitNoLag(Duration.ofSeconds(30));
        }
    }

    @Test
    void aRunWhoseLinesKeepFailingWaitsWithItsProblemShownAndOtherRunsOnThePartitionGoOn() {
        // A test-only trigger makes every line of one account fail, like a bug would; the other run's account
        // hashes to the same partition, so it queues behind the failing lines.
        String failing = accountOnTheSamePartitionAs("ACC-1");
        jdbc.execute("""
                CREATE FUNCTION test_refuse_line() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'refused by the test'; END $$""");
        jdbc.execute("CREATE TRIGGER test_refuse_line BEFORE INSERT ON statement_lines FOR EACH ROW WHEN (NEW.account_id = '"
                + failing + "') EXECUTE FUNCTION test_refuse_line()");
        ApiClient api = ApiClient.forPort(port);
        String ledger = "external_id,account_id,booking_date,amount,reference\nL-1," + failing + ",2026-10-01,10.00,R-1\n";
        String statement = "account_id,value_date,amount,reference\n" + failing + ",2026-10-01,10.00,R-1\n";
        long stuck = api.createRun("lines keep failing");
        long behind = api.createRun("queued behind it");
        try {
            api.uploadLedger(stuck, ledger);
            api.uploadStatement(stuck, statement);
            api.uploadLedger(behind, ReconciliationIT.LEDGER);
            api.uploadStatement(behind, ReconciliationIT.STATEMENT);

            api.awaitCompleted(behind);
            JsonNode waiting = Awaitility.await("the failure recorded on the run").atMost(Duration.ofSeconds(30))
                    .until(() -> api.get("/v1/runs/" + stuck).body(), run -> !run.get("matchingProblem").isNull());
            assertThat(waiting.get("status").asString()).isEqualTo("MATCHING");
            assertThat(waiting.get("matchingProblem").asString()).contains("P0001").doesNotContain("refused by the test");
        } finally {
            jdbc.execute("DROP TRIGGER test_refuse_line ON statement_lines");
            jdbc.execute("DROP FUNCTION test_refuse_line()");
        }

        assertThat(api.uploadStatement(stuck, statement).status()).isEqualTo(200);
        JsonNode completed = api.awaitCompleted(stuck);
        assertThat(completed.at("/summary/matches").asLong()).isEqualTo(1);
        assertThat(completed.get("matchingProblem").isNull()).as("cleared once the run completed").isTrue();
    }

    private static String accountOnTheSamePartitionAs(String account) {
        int partition = partitionOf(account);
        for (int i = 0; ; i++) {
            if (partitionOf("ACC-FAIL-" + i) == partition) {
                return "ACC-FAIL-" + i;
            }
        }
    }

    // Kafka's default partitioner for a keyed record, over the topic's 6 partitions.
    private static int partitionOf(String key) {
        return Utils.toPositive(Utils.murmur2(key.getBytes(StandardCharsets.UTF_8))) % 6;
    }
}
