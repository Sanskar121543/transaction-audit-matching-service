package io.github.sanskar121543.reconciliation.generator.cli;

import io.github.sanskar121543.reconciliation.generator.Dataset;
import io.github.sanskar121543.reconciliation.generator.Generator;
import io.github.sanskar121543.reconciliation.generator.GeneratorConfig;
import io.github.sanskar121543.reconciliation.generator.scoring.Score;
import io.github.sanskar121543.reconciliation.generator.scoring.Scorer;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Times one large run end to end through the public API: the ledger upload, the statement upload (which
 * returns once every line is on Kafka), and the wait until the workers have matched every line and the run
 * is COMPLETED. It also scores the run, so a benchmark can never be fast by being wrong.
 */
public final class ThroughputBenchmark {

    // LF on every OS, like the accuracy results.
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .defaultPrettyPrinter(new DefaultPrettyPrinter().withObjectIndenter(new DefaultIndenter("  ", "\n")))
            .build();

    private ThroughputBenchmark() {
    }

    /**
     * Client-side wall-clock times in milliseconds. {@code serverMatchingMillis} is the server's own view:
     * from the statement import being recorded to the run's completion timestamp.
     */
    public record Timings(long ledgerUploadMillis, long statementUploadMillis, long untilCompletedMillis,
            long totalMillis, long serverMatchingMillis) {
    }

    public record Recorded(String kind, String recordedAt, String gitCommit, String topology, String hardwareNote,
            GeneratorConfig config, int ledgerRecords, int statementLines, long runId, int ledgerCsvBytes,
            int statementCsvBytes, Timings timings, double statementLinesPerSecond,
            AccuracyReport.CountsView matches, AccuracyReport.CountsView breaks) {
    }

    public static Recorded run(ServiceClient service, GeneratorConfig config, String gitCommit, String topology,
            String hardwareNote) {
        Dataset data = Generator.generate(config);
        String ledgerCsv = data.ledgerCsv();
        String statementCsv = data.statementCsv();
        long runId = service.createRun("benchmark seed=" + config.seed() + " payments=" + config.payments(),
                config.toleranceCents(), config.dateWindowDays());

        long start = System.nanoTime();
        service.upload(runId, "ledger", ledgerCsv);
        long ledgerDone = System.nanoTime();
        service.upload(runId, "statement", statementCsv);
        long statementDone = System.nanoTime();
        // Polled 4 times a second: each poll counts the run's matches and breaks, and polling faster would load
        // the database the workers are writing to. The server timestamps below give the precise figure.
        JsonNode run = service.waitForCompletion(runId, Duration.ofMinutes(30), Duration.ofMillis(250));
        long completed = System.nanoTime();

        Instant statementRecorded = Instant.parse(service.statementImport(runId).get("importedAt").asString());
        Instant runCompleted = Instant.parse(run.get("completedAt").asString());
        Timings timings = new Timings(millis(start, ledgerDone), millis(ledgerDone, statementDone),
                millis(statementDone, completed), millis(start, completed),
                Duration.between(statementRecorded, runCompleted).toMillis());
        double linesPerSecond = BigDecimal.valueOf(data.statement().size() * 1000.0
                        / (timings.statementUploadMillis() + timings.untilCompletedMillis()))
                .setScale(0, RoundingMode.HALF_EVEN).doubleValue();

        Score score = Scorer.score(data.truth(), service.predictions(runId));
        return new Recorded("throughput", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(), gitCommit, topology,
                hardwareNote, config, data.ledger().size(), data.statement().size(), runId,
                ledgerCsv.getBytes(StandardCharsets.UTF_8).length, statementCsv.getBytes(StandardCharsets.UTF_8).length,
                timings, linesPerSecond, AccuracyReport.CountsView.of(score.matches()),
                AccuracyReport.CountsView.of(score.breaks()));
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
        Timings t = r.timings();
        StringBuilder md = new StringBuilder();
        md.append("| Metric | Value |\n|---|---|\n");
        md.append("| Data | ").append(r.config().payments()).append(" synthetic payments (seed ").append(r.config().seed())
                .append(") over ").append(r.config().accounts()).append(" accounts: ").append(r.ledgerRecords())
                .append(" ledger records (").append(mb(r.ledgerCsvBytes())).append(" CSV), ").append(r.statementLines())
                .append(" statement lines (").append(mb(r.statementCsvBytes())).append(" CSV) |\n");
        md.append("| Topology | ").append(r.topology()).append(" |\n");
        md.append("| Ledger upload (validate + store) | ").append(seconds(t.ledgerUploadMillis())).append(" |\n");
        md.append("| Statement upload (validate + record + queue every line on Kafka) | ")
                .append(seconds(t.statementUploadMillis())).append(" |\n");
        md.append("| Then until the run is COMPLETED (workers store and match every line, then the end-of-run step) | ")
                .append(seconds(t.untilCompletedMillis())).append(" |\n");
        md.append("| Total, first byte of the ledger to COMPLETED | ").append(seconds(t.totalMillis())).append(" |\n");
        md.append("| Statement throughput (upload + matching) | ").append(String.format("%,.0f", r.statementLinesPerSecond()))
                .append(" lines/s |\n");
        md.append("| Server-side, statement recorded to run completed | ").append(seconds(t.serverMatchingMillis()))
                .append(" |\n");
        md.append("| Match precision / recall | ").append(AccuracyReport.percent(r.matches().precision())).append(" / ")
                .append(AccuracyReport.percent(r.matches().recall())).append(" |\n");
        md.append("| Break precision / recall | ").append(AccuracyReport.percent(r.breaks().precision())).append(" / ")
                .append(AccuracyReport.percent(r.breaks().recall())).append(" |\n");
        md.append("| Machine | ").append(r.hardwareNote()).append(" |\n");
        md.append("| Recorded | ").append(r.recordedAt()).append(", commit ").append(r.gitCommit()).append(" |\n");
        return md.toString();
    }

    private static long millis(long fromNanos, long toNanos) {
        return (toNanos - fromNanos) / 1_000_000;
    }

    private static String seconds(long millis) {
        return BigDecimal.valueOf(millis).divide(BigDecimal.valueOf(1000), 1, RoundingMode.HALF_EVEN).toPlainString()
                + " s";
    }

    private static String mb(int bytes) {
        return BigDecimal.valueOf(bytes).divide(BigDecimal.valueOf(1024 * 1024), 1, RoundingMode.HALF_EVEN)
                .toPlainString() + " MB";
    }
}
