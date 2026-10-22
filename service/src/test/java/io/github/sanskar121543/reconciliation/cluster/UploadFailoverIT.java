package io.github.sanskar121543.reconciliation.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanskar121543.reconciliation.generator.Dataset;
import io.github.sanskar121543.reconciliation.generator.Generator;
import io.github.sanskar121543.reconciliation.generator.cli.AccuracyBenchmark;
import io.github.sanskar121543.reconciliation.generator.cli.AccuracyReport;
import io.github.sanskar121543.reconciliation.generator.cli.ServiceClient;
import io.github.sanskar121543.reconciliation.support.ApiClient;
import io.github.sanskar121543.reconciliation.support.ApiResponse;
import io.github.sanskar121543.reconciliation.support.RunInvariants;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The instance handling a statement upload dies after recording the statement and before its lines are on
 * Kafka. Kafka is paused (docker pause) so the upload is certainly stuck in that window, then the instance
 * is SIGKILLed. nginx resends the buffered upload to the other instance (uploads are idempotent), which finds
 * the statement recorded and queues its lines. The client sees one upload succeed, and the run's result is
 * exactly the committed accuracy result.
 */
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class UploadFailoverIT {

    private static final Logger log = LoggerFactory.getLogger(UploadFailoverIT.class);

    static MatchingTopology topology;

    @BeforeAll
    static void start() {
        topology = MatchingTopology.start(MatchingTopology.apps(Map.of(), Map.of()));
    }

    @AfterAll
    static void stop() {
        topology.close();
    }

    @Test
    void anUploadWhoseInstanceDiesBeforeQueueingIsFinishedByTheOtherInstance() throws Exception {
        AccuracyReport.Recorded committed = AccuracyReport.read(Path.of("..", "results", "accuracy", "latest.json"));
        Dataset data = Generator.generate(committed.config());
        ApiClient api = topology.viaNginx();
        long runId = api.createRun("upload failover");
        api.uploadLedger(runId, data.ledgerCsv());

        topology.pauseKafka();
        CompletableFuture<ApiResponse> upload =
                CompletableFuture.supplyAsync(() -> api.uploadStatement(runId, data.statementCsv()));
        String queueing = "Run " + runId + ": queueing";
        String publisher = Awaitility.await("an instance queueing the statement's lines")
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(20))
                .until(() -> List.of("app1", "app2").stream().filter(app -> topology.logs(app).contains(queueing))
                        .findFirst().orElse(null), app -> app != null);
        topology.kill(publisher);
        topology.unpauseKafka();
        log.info("SIGKILLed {} while it was queueing the statement, with Kafka paused", publisher);

        ApiResponse response = upload.get(2, TimeUnit.MINUTES);
        String other = publisher.equals("app1") ? "app2" : "app1";

        assertThat(response.status()).as(response.rawBody()).isEqualTo(200);
        assertThat(response.body().get("replayed").asBoolean()).isTrue();
        assertThat(response.header("X-Served-By")).isEqualTo(other);
        assertThat(topology.nginxAccessLog().lines()
                .filter(line -> line.contains("\"uri\":\"/v1/runs/" + runId + "/statement\""))
                .filter(line -> line.matches(".*\"upstream\":\"[^\"]*, .*")))
                .as("nginx sent the upload to a second instance").hasSize(1);

        ServiceClient service = new ServiceClient(api.baseUrl(), ApiClient.OPERATOR_KEY);
        AccuracyBenchmark.Result result = AccuracyBenchmark.score(service,
                new AccuracyBenchmark.Submitted(committed.config(), data, runId));
        assertThat(AccuracyReport.record(result, committed.gitCommit(), Instant.parse(committed.recordedAt())))
                .isEqualTo(committed);
        RunInvariants.assertLinesStoredOnceInFileOrder(topology.jdbc(), runId, data.statement().size());
        topology.kafka().awaitNoLag(Duration.ofSeconds(30));
    }
}
