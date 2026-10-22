package io.github.sanskar121543.reconciliation.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanskar121543.reconciliation.generator.Dataset;
import io.github.sanskar121543.reconciliation.generator.Generator;
import io.github.sanskar121543.reconciliation.generator.GeneratorConfig;
import io.github.sanskar121543.reconciliation.support.ApiClient;
import io.github.sanskar121543.reconciliation.support.ApiResponse;
import io.github.sanskar121543.reconciliation.support.Concurrently;
import io.github.sanskar121543.reconciliation.support.RunInvariants;
import io.github.sanskar121543.reconciliation.support.RunResults;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Two instances behind nginx, one consumer group. The same files are sent to both instances at the same
 * moment: while the run is still matching, every statement upload queues its lines again, so the workers
 * get lines several times over, interleaved on the partitions. The outcome must be exactly that of one upload.
 */
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class SameFileConcurrencyIT {

    private static final Logger log = LoggerFactory.getLogger(SameFileConcurrencyIT.class);

    static MatchingTopology topology;
    static ApiClient viaNginx;
    static JdbcTemplate jdbc;

    @BeforeAll
    static void start() {
        // Slow workers keep a run MATCHING for a few seconds, so every concurrent upload of the statement
        // finds it still matching and queues the lines again.
        Map<String, String> slowWorkers = Map.of("SPRING_KAFKA_CONSUMER_MAX_POLL_RECORDS", "50",
                "SPRING_KAFKA_LISTENER_IDLE_BETWEEN_POLLS", "200ms");
        topology = MatchingTopology.start(MatchingTopology.apps(slowWorkers, slowWorkers));
        viaNginx = topology.viaNginx();
        jdbc = topology.jdbc();
    }

    @AfterAll
    static void stop() {
        topology.close();
    }

    @Test
    void theSameFilesSentToBothInstancesAtOnceGiveTheOutcomeOfOneUpload() {
        Dataset data = Generator.generate(GeneratorConfig.defaults(7, 2_000));
        long reference = viaNginx.createRun("uploaded once");
        viaNginx.uploadLedger(reference, data.ledgerCsv());
        viaNginx.uploadStatement(reference, data.statementCsv());
        viaNginx.awaitCompleted(reference);

        long runId = viaNginx.createRun("uploaded four times at once");
        List<ApiClient> instances = List.of(topology.direct("app1"), topology.direct("app2"));
        List<ApiResponse> ledgers = Concurrently.run(4, i -> instances.get(i % 2).uploadLedger(runId, data.ledgerCsv()));
        long publishedBefore = topology.kafka().publishedRecords();
        List<ApiResponse> statements = Concurrently.run(4,
                i -> instances.get(i % 2).uploadStatement(runId, data.statementCsv()));
        viaNginx.awaitCompleted(runId);
        long published = topology.kafka().publishedRecords() - publishedBefore;
        log.info("{} lines, {} records published by four concurrent uploads", data.statement().size(), published);

        assertThat(ledgers).extracting(ApiResponse::status).containsExactlyInAnyOrder(201, 200, 200, 200);
        assertThat(statements).extracting(ApiResponse::status).containsExactlyInAnyOrder(201, 200, 200, 200);
        assertThat(statements).extracting(r -> r.header("X-Served-By")).containsOnly("app1", "app2")
                .contains("app1", "app2");
        assertThat(published).as("more than one upload queued the lines").isGreaterThan(data.statement().size());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM imports WHERE run_id = ?", Long.class, runId))
                .isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT DISTINCT ingested_by FROM statement_lines WHERE run_id = ?", String.class,
                runId)).as("both instances' workers stored lines").containsExactlyInAnyOrder("app1", "app2");
        RunInvariants.assertLinesStoredOnceInFileOrder(jdbc, runId, data.statement().size());
        RunInvariants.assertEveryRecordMatchedOrBrokenOnce(jdbc, runId);
        assertThat(RunResults.of(viaNginx, runId)).isEqualTo(RunResults.of(viaNginx, reference));
        topology.kafka().awaitNoLag(Duration.ofSeconds(30));
    }

    @Test
    void twoDifferentStatementsAtOnceOneIsTakenTheOtherRefused() {
        Dataset data = Generator.generate(GeneratorConfig.defaults(8, 500));
        String other = data.statementCsv() + "ACC-001,2026-09-30,12.34,USD,EXTRA-1,one more line,\n";
        long runId = viaNginx.createRun("two statements");
        viaNginx.uploadLedger(runId, data.ledgerCsv());

        List<ApiResponse> responses = Concurrently.run(2, i -> i == 0
                ? topology.direct("app1").uploadStatement(runId, data.statementCsv())
                : topology.direct("app2").uploadStatement(runId, other));
        viaNginx.awaitCompleted(runId);

        assertThat(responses).extracting(ApiResponse::status).containsExactlyInAnyOrder(201, 409);
        ApiResponse taken = responses.stream().filter(r -> r.status() == 201).findFirst().orElseThrow();
        assertThat(responses.stream().filter(r -> r.status() == 409).findFirst().orElseThrow().problemType())
                .isEqualTo("/problems/already-imported");
        assertThat(jdbc.queryForObject("SELECT sha256 FROM imports WHERE run_id = ? AND side = 'STATEMENT'",
                String.class, runId)).isEqualTo(taken.textField("sha256"));
        RunInvariants.assertLinesStoredOnceInFileOrder(jdbc, runId, taken.body().get("rowsAccepted").asLong());
    }

    @Test
    void nginxSpreadsRequestsOverBothInstancesAndHidesManagementEndpoints() {
        ApiClient noKey = viaNginx.withKey(null);
        Set<String> servedBy = IntStream.range(0, 10)
                .mapToObj(i -> noKey.get("/actuator/health").header("X-Served-By"))
                .collect(Collectors.toSet());

        assertThat(servedBy).containsExactlyInAnyOrder("app1", "app2");
        assertThat(noKey.get("/actuator/prometheus").status()).isEqualTo(404);
        assertThat(noKey.get("/actuator").status()).isEqualTo(404);
        assertThat(noKey.get("/%61ctuator/prometheus").status()).isEqualTo(404);
        assertThat(noKey.get("/actuator;x/prometheus").status()).isEqualTo(400);
        assertThat(noKey.get("/v1;x/runs").status()).isEqualTo(400);
        assertThat(noKey.get("/%761/runs").status()).isEqualTo(401);
    }

    @Test
    void aLargeUploadRefusedBeforeItsBodyIsReadGetsTheRealAnswerThroughNginx() {
        // Tomcat answers 404 or 401 before reading the body. Unless it reads (swallows) the rest, it closes
        // the connection; nginx, still sending, would see a reset, retry the other instance, return 502 and
        // mark both instances down for 5 seconds.
        String tenMegabytes = "external_id,account_id,booking_date,amount,reference\n"
                + "L-1,ACC-1,2026-10-01,1.00,R\n".repeat(370_000);

        ApiResponse unknownRun = viaNginx.uploadLedger(999_999, tenMegabytes);
        ApiResponse noKey = viaNginx.withKey(null).uploadLedger(999_999, tenMegabytes);

        assertThat(unknownRun.status()).isEqualTo(404);
        assertThat(noKey.status()).isEqualTo(401);
        assertThat(viaNginx.get("/v1/runs?limit=1").status()).isEqualTo(200);
    }
}
