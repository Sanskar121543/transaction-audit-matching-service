package io.github.sanskar121543.reconciliation.accuracy;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanskar121543.reconciliation.generator.cli.AccuracyBenchmark;
import io.github.sanskar121543.reconciliation.generator.cli.AccuracyReport;
import io.github.sanskar121543.reconciliation.generator.cli.ServiceClient;
import io.github.sanskar121543.reconciliation.support.ApiClient;
import io.github.sanskar121543.reconciliation.support.IntegrationTest;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

/**
 * The README's precision and recall come from results/accuracy/latest.json. This re-runs that exact
 * configuration through the current code and requires the same counts, so the committed numbers can
 * never silently go stale: change the matcher, and this fails until the result is recorded again.
 */
@IntegrationTest
class AccuracyResultsIT {

    @Value("${local.server.port}")
    int port;

    @Test
    void theCommittedAccuracyResultIsReproducedExactly() throws IOException {
        AccuracyReport.Recorded committed = AccuracyReport.read(Path.of("..", "results", "accuracy", "latest.json"));

        AccuracyBenchmark.Result result = AccuracyBenchmark.run(
                new ServiceClient("http://localhost:" + port, ApiClient.OPERATOR_KEY), committed.config());
        AccuracyReport.Recorded now = AccuracyReport.record(result, committed.gitCommit(),
                Instant.parse(committed.recordedAt()));

        assertThat(now).isEqualTo(committed);
    }
}
