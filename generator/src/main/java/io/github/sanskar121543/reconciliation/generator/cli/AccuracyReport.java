package io.github.sanskar121543.reconciliation.generator.cli;

import io.github.sanskar121543.reconciliation.generator.Amounts;
import io.github.sanskar121543.reconciliation.generator.GeneratorConfig;
import io.github.sanskar121543.reconciliation.generator.scoring.Counts;
import io.github.sanskar121543.reconciliation.generator.scoring.Score;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Writes an accuracy result as JSON (the record) and Markdown (what the README quotes). */
public final class AccuracyReport {

    // LF on every OS, so a result recorded on Windows and one recorded on Linux differ only in their numbers.
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .defaultPrettyPrinter(new DefaultPrettyPrinter().withObjectIndenter(new DefaultIndenter("  ", "\n")))
            .build();

    private AccuracyReport() {
    }

    /** The JSON layout; the reproduce test reads it back with the same record. */
    public record Recorded(String kind, String recordedAt, String gitCommit, GeneratorConfig config, int ledgerRecords,
            int statementLines, CountsView matches, CountsView breaks, Map<String, CountsView> breaksByType,
            Map<String, Score.ScenarioResult> scenarios, Score.RuleAgreement expectedRuleUsed) {
    }

    public record CountsView(long predicted, long expected, long correct, Double precision, Double recall) {

        static CountsView of(Counts counts) {
            return new CountsView(counts.predicted(), counts.expected(), counts.correct(), counts.precision(),
                    counts.recall());
        }
    }

    public static Recorded record(AccuracyBenchmark.Result result, String gitCommit, Instant now) {
        Score score = result.score();
        Map<String, CountsView> byType = new LinkedHashMap<>();
        score.breaksByType().forEach((type, counts) -> byType.put(type, CountsView.of(counts)));
        return new Recorded("accuracy", now.truncatedTo(ChronoUnit.SECONDS).toString(), gitCommit, result.config(),
                result.ledgerRecords(), result.statementLines(), CountsView.of(score.matches()),
                CountsView.of(score.breaks()), byType, score.scenarios(), score.expectedRuleUsed());
    }

    public static void write(Recorded recorded, Path directory) throws IOException {
        Files.createDirectories(directory);
        String json = JSON.writeValueAsString(recorded) + "\n";
        Files.writeString(directory.resolve(recorded.recordedAt().replace(":", "-") + ".json"), json);
        Files.writeString(directory.resolve("latest.json"), json);
        Files.writeString(directory.resolve("latest.md"), markdown(recorded));
    }

    public static Recorded read(Path file) throws IOException {
        return JSON.readValue(Files.readString(file), Recorded.class);
    }

    static String markdown(Recorded r) {
        GeneratorConfig c = r.config();
        StringBuilder md = new StringBuilder();
        md.append("| Metric | Value |\n|---|---|\n");
        md.append("| Data | ").append(c.payments()).append(" synthetic payments (seed ").append(c.seed()).append(") over ")
                .append(c.accounts()).append(" accounts: ").append(r.ledgerRecords()).append(" ledger records, ")
                .append(r.statementLines()).append(" statement lines |\n");
        md.append("| Run settings | amount tolerance ").append(Amounts.format(c.toleranceCents())).append(", date window ")
                .append(c.dateWindowDays()).append(" days |\n");
        md.append("| Match precision | ").append(percent(r.matches().precision())).append(" (")
                .append(r.matches().correct()).append(" of ").append(r.matches().predicted())
                .append(" matches made pair the right two records) |\n");
        md.append("| Match recall | ").append(percent(r.matches().recall())).append(" (")
                .append(r.matches().correct()).append(" of ").append(r.matches().expected())
                .append(" true matches found) |\n");
        md.append("| Break precision | ").append(percent(r.breaks().precision())).append(" (")
                .append(r.breaks().correct()).append(" of ").append(r.breaks().predicted())
                .append(" reported breaks have the right type and records) |\n");
        md.append("| Break recall | ").append(percent(r.breaks().recall())).append(" (")
                .append(r.breaks().correct()).append(" of ").append(r.breaks().expected())
                .append(" injected breaks found exactly) |\n");
        r.breaksByType().forEach((type, counts) -> md.append("| ").append(type).append(" precision / recall | ")
                .append(percent(counts.precision())).append(" / ").append(percent(counts.recall())).append(" (")
                .append(counts.correct()).append(" right; ").append(counts.predicted()).append(" reported, ")
                .append(counts.expected()).append(" injected) |\n"));
        md.append("| Correct matches made by the expected rule | ").append(r.expectedRuleUsed().madeByExpectedRule())
                .append(" of ").append(r.expectedRuleUsed().correctMatchesWithExpectedRule()).append(" |\n");
        md.append("| Recorded | ").append(r.recordedAt()).append(", commit ").append(r.gitCommit()).append(" |\n");
        md.append("\n| Scenario | Truth entries | Exactly right |\n|---|---|---|\n");
        r.scenarios().forEach((scenario, result) -> md.append("| ").append(scenario).append(" | ")
                .append(result.truthEntries()).append(" | ").append(result.correct()).append(" |\n"));
        return md.toString();
    }

    static String percent(Double ratio) {
        if (ratio == null) {
            return "n/a";
        }
        return BigDecimal.valueOf(ratio * 100).setScale(2, RoundingMode.HALF_EVEN).toPlainString() + "%";
    }
}
