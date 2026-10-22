package io.github.sanskar121543.reconciliation.generator.cli;

import io.github.sanskar121543.reconciliation.generator.Dataset;
import io.github.sanskar121543.reconciliation.generator.Generator;
import io.github.sanskar121543.reconciliation.generator.GeneratorConfig;
import io.github.sanskar121543.reconciliation.generator.scoring.Score;
import io.github.sanskar121543.reconciliation.generator.scoring.Scorer;
import java.time.Duration;

/**
 * Generates a dataset with known breaks, reconciles it through the service's API and scores the result.
 * The generator and the matcher are both deterministic, so a given config always gives the same score.
 */
public final class AccuracyBenchmark {

    private AccuracyBenchmark() {
    }

    public record Result(GeneratorConfig config, int ledgerRecords, int statementLines, long runId, Score score) {
    }

    /** A run whose files are uploaded; the service may still be matching its statement lines. */
    public record Submitted(GeneratorConfig config, Dataset data, long runId) {
    }

    public static Result run(ServiceClient service, GeneratorConfig config) {
        return score(service, submit(service, config));
    }

    public static Submitted submit(ServiceClient service, GeneratorConfig config) {
        Dataset data = Generator.generate(config);
        long runId = service.createRun("accuracy seed=" + config.seed() + " payments=" + config.payments(),
                config.toleranceCents(), config.dateWindowDays());
        service.upload(runId, "ledger", data.ledgerCsv());
        service.upload(runId, "statement", data.statementCsv());
        return new Submitted(config, data, runId);
    }

    /** Waits for the run to complete, then scores it against the dataset's ground truth. */
    public static Result score(ServiceClient service, Submitted submitted) {
        service.waitForCompletion(submitted.runId(), Duration.ofMinutes(10));
        Score score = Scorer.score(submitted.data().truth(), service.predictions(submitted.runId()));
        return new Result(submitted.config(), submitted.data().ledger().size(), submitted.data().statement().size(),
                submitted.runId(), score);
    }
}
