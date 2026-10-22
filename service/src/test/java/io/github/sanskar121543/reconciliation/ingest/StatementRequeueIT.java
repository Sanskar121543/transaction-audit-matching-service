package io.github.sanskar121543.reconciliation.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import io.github.sanskar121543.reconciliation.generator.Dataset;
import io.github.sanskar121543.reconciliation.generator.Generator;
import io.github.sanskar121543.reconciliation.generator.GeneratorConfig;
import io.github.sanskar121543.reconciliation.support.ApiClient;
import io.github.sanskar121543.reconciliation.support.ApiResponse;
import io.github.sanskar121543.reconciliation.support.IntegrationTest;
import io.github.sanskar121543.reconciliation.support.RunInvariants;
import io.github.sanskar121543.reconciliation.support.RunResults;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/**
 * An upload whose lines only partly reach Kafka, like a broker going away in the middle of an upload: one
 * line in five is lost, so later lines of an account arrive without the line before them. The workers store
 * each account only up to its first lost line, the run waits, and uploading the same file again finishes it
 * with exactly the outcome of an upload that never failed.
 */
@IntegrationTest
class StatementRequeueIT {

    @Value("${local.server.port}")
    int port;

    @MockitoSpyBean
    StatementPublisher publisher;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void anUploadThatOnlyPartlyReachedKafkaIsFinishedByUploadingTheSameFileAgain() {
        ApiClient api = ApiClient.forPort(port);
        Dataset data = Generator.generate(GeneratorConfig.defaults(5, 400));
        long reference = api.createRun("never interrupted");
        api.uploadLedger(reference, data.ledgerCsv());
        api.uploadStatement(reference, data.statementCsv());
        api.awaitCompleted(reference);

        long runId = api.createRun("interrupted");
        api.uploadLedger(runId, data.ledgerCsv());
        doAnswer(invocation -> {
            long id = invocation.getArgument(0);
            List<StatementLineMessage> all = StatementPublisher.messages(id, invocation.getArgument(1),
                    invocation.getArgument(2));
            publisher.send(id, IntStream.range(0, all.size()).filter(i -> i % 5 != 2).mapToObj(all::get).toList());
            throw new StatementNotQueuedException(id, new TimeoutException("simulated: the broker went away"));
        }).doCallRealMethod().when(publisher).publish(anyLong(), anyString(), anyList(), anyMap());

        ApiResponse interrupted = api.uploadStatement(runId, data.statementCsv());

        assertThat(interrupted.status()).isEqualTo(503);
        assertThat(interrupted.problemType()).isEqualTo("/problems/statement-not-queued");
        JsonNode waiting = Awaitility.await("the workers to store what they can")
                .during(Duration.ofSeconds(1))
                .atMost(Duration.ofSeconds(30))
                .until(() -> api.get("/v1/runs/" + runId).body(), run -> "MATCHING".equals(run.get("status").asString()));
        assertThat(waiting.at("/summary/statementLines").asLong())
                .isLessThan(waiting.at("/summary/statementLinesExpected").asLong());

        ApiResponse resumed = api.uploadStatement(runId, data.statementCsv());
        api.awaitCompleted(runId);

        assertThat(resumed.status()).isEqualTo(200);
        assertThat(resumed.body().get("replayed").asBoolean()).isTrue();
        assertThat(RunResults.of(api, runId)).isEqualTo(RunResults.of(api, reference));
        RunInvariants.assertLinesStoredOnceInFileOrder(jdbc, runId, data.statement().size());
    }
}
