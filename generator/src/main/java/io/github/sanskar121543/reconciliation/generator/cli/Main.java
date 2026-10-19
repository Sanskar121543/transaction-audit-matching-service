package io.github.sanskar121543.reconciliation.generator.cli;

import io.github.sanskar121543.reconciliation.generator.Dataset;
import io.github.sanskar121543.reconciliation.generator.Generator;
import io.github.sanskar121543.reconciliation.generator.GeneratorConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * <pre>
 * generate  [--seed 42] [--payments 10000] [--out data/generated]     write ledger.csv, statement.csv, truth.json
 * accuracy  [--seed 42] [--payments 10000] [--results results/accuracy]
 * </pre>
 * The service URL comes from BASE_URL (default http://localhost:8089) and the key from OPERATOR_API_KEY, so
 * the key never appears in a process list.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        Map<String, String> options = options(args);
        long seed = Long.parseLong(options.getOrDefault("seed", "42"));
        int payments = Integer.parseInt(options.getOrDefault("payments", "10000"));
        GeneratorConfig config = GeneratorConfig.defaults(seed, payments);
        switch (args[0]) {
            case "generate" -> generate(config, Path.of(options.getOrDefault("out", "data/generated")));
            case "accuracy" -> {
                AccuracyBenchmark.Result result = AccuracyBenchmark.run(service(), config);
                AccuracyReport.Recorded recorded = AccuracyReport.record(result, env("GIT_SHA", "unknown"), Instant.now());
                Path results = Path.of(options.getOrDefault("results", "results/accuracy"));
                AccuracyReport.write(recorded, results);
                System.out.println(AccuracyReport.markdown(recorded));
                System.out.println("Written to " + results.resolve("latest.md"));
            }
            default -> usage();
        }
    }

    private static void generate(GeneratorConfig config, Path out) throws Exception {
        Dataset data = Generator.generate(config);
        Files.createDirectories(out);
        Files.writeString(out.resolve("ledger.csv"), data.ledgerCsv());
        Files.writeString(out.resolve("statement.csv"), data.statementCsv());
        Files.writeString(out.resolve("truth.json"), JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT)
                .build().writeValueAsString(data.truth()));
        System.out.println("Wrote " + data.ledger().size() + " ledger records and " + data.statement().size()
                + " statement lines to " + out);
    }

    private static ServiceClient service() {
        String key = System.getenv("OPERATOR_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("Set OPERATOR_API_KEY (see .env.example)");
        }
        return new ServiceClient(env("BASE_URL", "http://localhost:8089"), key);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i + 1 < args.length; i += 2) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("Expected --option value, got " + args[i]);
            }
            options.put(args[i].substring(2), args[i + 1]);
        }
        return options;
    }

    private static void usage() {
        System.out.println("usage: generate|accuracy [--seed N] [--payments N] [--out DIR] [--results DIR]");
    }
}
