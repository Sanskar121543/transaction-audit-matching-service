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

    public static Result run(ServiceClient service, GeneratorConfig config) {
        Dataset data = Generator.generate(config);
        long runId = service.createRun("accuracy seed=" + config.seed() + " payments=" + config.payments(),
                config.toleranceCents(), config.dateWindowDays());
        service.upload(runId, "ledger", data.ledgerCsv());
        service.upload(runId, "statement", data.statementCsv());
        service.waitForCompletion(runId, Duration.ofMinutes(10));
        Score score = Scorer.score(data.truth(), service.predictions(runId));
        return new Result(config, data.ledger().size(), data.statement().size(), runId, score);
    }
}
