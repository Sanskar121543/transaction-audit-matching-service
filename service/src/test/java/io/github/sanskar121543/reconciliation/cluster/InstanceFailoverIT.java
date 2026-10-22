package io.github.sanskar121543.reconciliation.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanskar121543.reconciliation.generator.cli.AccuracyBenchmark;
import io.github.sanskar121543.reconciliation.generator.cli.AccuracyReport;
import io.github.sanskar121543.reconciliation.generator.cli.ServiceClient;
import io.github.sanskar121543.reconciliation.matching.MatchStore;
import io.github.sanskar121543.reconciliation.support.ApiClient;
import io.github.sanskar121543.reconciliation.support.RunInvariants;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * SIGKILLs app2 while one of its workers is inside a transaction. Its partitions move to app1, which resumes
 * from the last committed offsets: the batch app2 had open is rolled back and delivered again. The run must
 * still end with exactly the committed accuracy result (results/accuracy/latest.json): no line lost, none
 * matched twice, every account matched in file order.
 * <p>
 * To kill inside a transaction for certain, the test takes the per-account advisory locks the workers take
 * (MatchStore.lockAccounts), waits until an app2 worker is blocked on one, and kills app2 there.
 */
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class InstanceFailoverIT {

    private static final Logger log = LoggerFactory.getLogger(InstanceFailoverIT.class);

    static MatchingTopology topology;

    @BeforeAll
    static void start() {
        // Small batches stretch the run over many transactions, so app2 still has work when it is killed.
        Map<String, String> smallBatches = Map.of("SPRING_KAFKA_CONSUMER_MAX_POLL_RECORDS", "10");
        topology = MatchingTopology.start(MatchingTopology.apps(smallBatches, smallBatches));
    }

    @AfterAll
    static void stop() {
        topology.close();
    }

    @Test
    void killingAnInstanceMidTransactionLeavesTheResultExactlyAsCommitted() throws Exception {
        AccuracyReport.Recorded committed = AccuracyReport.read(Path.of("..", "results", "accuracy", "latest.json"));
        ServiceClient service = new ServiceClient(topology.viaNginx().baseUrl(), ApiClient.OPERATOR_KEY);
        JdbcTemplate jdbc = topology.jdbc();
        Map<String, Set<Integer>> assignment = topology.kafka().assignment();
        assertThat(assignment.get("app2")).as("app2 owns partitions: %s", assignment).isNotEmpty();

        CompletableFuture<AccuracyBenchmark.Submitted> submitting =
                CompletableFuture.supplyAsync(() -> AccuracyBenchmark.submit(service, committed.config()));
        Awaitility.await("app2 storing statement lines")
                .atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofMillis(10))
                .until(() -> storedByInstance(jdbc).getOrDefault("app2", 0L) >= 100);
        long runId = jdbc.queryForObject("SELECT max(run_id) FROM statement_lines", Long.class);
        // In the workers' key order (MatchStore.lockAccounts), so taking them cannot deadlock with a worker.
        List<String> accounts = jdbc.queryForList("SELECT DISTINCT account_id FROM ledger_records WHERE run_id = ?",
                String.class, runId).stream().sorted(Comparator.comparingInt(MatchStore::accountLockKey)).toList();

        Map<String, Long> atKill;
        try (Connection locks = topology.connection()) {
            for (String account : accounts) {
                try (PreparedStatement lock = locks.prepareStatement("SELECT pg_advisory_lock(?, ?)")) {
                    lock.setInt(1, (int) runId);
                    lock.setInt(2, MatchStore.accountLockKey(account));
                    lock.execute();
                }
            }
            Awaitility.await("an app2 worker blocked inside its transaction")
                    .atMost(Duration.ofSeconds(30))
                    .until(() -> jdbc.queryForObject("""
                            SELECT count(*) FROM pg_stat_activity
                            WHERE application_name = 'app2' AND wait_event_type = 'Lock' AND wait_event = 'advisory'
                            """, Long.class) > 0);
            topology.kill("app2");
            atKill = storedByInstance(jdbc);
        }
        log.info("SIGKILLed app2 inside a transaction; lines stored by instance at that moment: {}", atKill);

        AccuracyBenchmark.Submitted submitted = submitting.get();
        int expected = submitted.data().statement().size();
        assertThat(total(atKill)).as("the kill landed before the last line was stored").isLessThan(expected);

        AccuracyBenchmark.Result result = AccuracyBenchmark.score(service, submitted);
        Map<String, Long> after = storedByInstance(jdbc);
        log.info("Run {} completed; lines stored by instance: {}; partitions after the kill: {}", runId, after,
                topology.kafka().assignment());

        assertThat(AccuracyReport.record(result, committed.gitCommit(), Instant.parse(committed.recordedAt())))
                .isEqualTo(committed);
        assertThat(after.get("app2")).as("app2's lines from before the kill are kept").isEqualTo(atKill.get("app2"));
        assertThat(after.get("app1") - atKill.getOrDefault("app1", 0L)).as("app1 stored the rest").isPositive();
        assertThat(topology.kafka().assignment()).as("app1 owns every partition now")
                .isEqualTo(Map.of("app1", Set.of(0, 1, 2, 3, 4, 5)));
        RunInvariants.assertLinesStoredOnceInFileOrder(jdbc, runId, expected);
        RunInvariants.assertEveryRecordMatchedOrBrokenOnce(jdbc, runId);
        assertAccountsHandedOverPartWay(jdbc, runId);
        topology.kafka().awaitNoLag(Duration.ofSeconds(30));
    }

    // app2 stored the start of some accounts, app1 the rest of them: every app2 row comes before every app1 row.
    private static void assertAccountsHandedOverPartWay(JdbcTemplate jdbc, long runId) {
        List<Map<String, Object>> handedOver = jdbc.queryForList("""
                SELECT account_id,
                       max(row_number) FILTER (WHERE ingested_by = 'app2') AS last_by_app2,
                       min(row_number) FILTER (WHERE ingested_by = 'app1') AS first_by_app1
                FROM statement_lines WHERE run_id = ? GROUP BY account_id
                HAVING count(*) FILTER (WHERE ingested_by = 'app2') > 0
                   AND count(*) FILTER (WHERE ingested_by = 'app1') > 0
                """, runId);
        assertThat(handedOver).as("accounts app1 took over part-way").isNotEmpty();
        assertThat(handedOver).allSatisfy(account -> assertThat((Integer) account.get("last_by_app2"))
                .as("account %s", account.get("account_id")).isLessThan((Integer) account.get("first_by_app1")));
    }

    // The topology holds one run at a time, so every stored line belongs to the run under test.
    private static Map<String, Long> storedByInstance(JdbcTemplate jdbc) {
        Map<String, Long> stored = new TreeMap<>();
        jdbc.query("SELECT ingested_by, count(*) FROM statement_lines GROUP BY ingested_by",
                (RowCallbackHandler) rs -> stored.put(rs.getString(1), rs.getLong(2)));
        return stored;
    }

    private static long total(Map<String, Long> stored) {
        return stored.values().stream().mapToLong(Long::longValue).sum();
    }
}
