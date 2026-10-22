package io.github.sanskar121543.reconciliation.generator.cli;

import io.github.sanskar121543.reconciliation.generator.BankSimulator;
import io.github.sanskar121543.reconciliation.generator.Dataset;
import io.github.sanskar121543.reconciliation.generator.Generator;
import io.github.sanskar121543.reconciliation.generator.GeneratorConfig;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * <pre>
 * generate       [--seed 42] [--payments 10000] [--out data/generated]  write ledger.csv, statement.csv, truth.json
 * statement-for  --ledger FILE [--seed 42] [--out data/simulated]       play the bank for an existing ledger file
 * accuracy       [--seed 42] [--payments 10000] [--results results/accuracy]
 * benchmark      [--seed 42] [--payments 101000] [--results results/benchmark]   at least 100,000 rows per file
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
            case "generate" -> write(Generator.generate(config), Path.of(options.getOrDefault("out", "data/generated")));
            case "statement-for" -> {
                Path ledgerFile = Path.of(required(options, "ledger"));
                List<Dataset.LedgerRow> ledger;
                try (Reader reader = Files.newBufferedReader(ledgerFile)) {
                    ledger = BankSimulator.readLedger(reader);
                }
                Path out = Path.of(options.getOrDefault("out", "data/simulated"));
                write(BankSimulator.simulate(ledger, seed, config.toleranceCents(), config.dateWindowDays()), out);
                Files.copy(ledgerFile, out.resolve("ledger.csv"), StandardCopyOption.REPLACE_EXISTING);
            }
            case "accuracy" -> {
                AccuracyBenchmark.Result result = AccuracyBenchmark.run(service(), config);
                AccuracyReport.Recorded recorded = AccuracyReport.record(result, env("GIT_SHA", "unknown"), Instant.now());
                Path results = Path.of(options.getOrDefault("results", "results/accuracy"));
                AccuracyReport.write(recorded, results);
                System.out.println(AccuracyReport.markdown(recorded));
                System.out.println("Written to " + results.resolve("latest.md"));
            }
            case "benchmark" -> {
                GeneratorConfig large = GeneratorConfig.defaults(seed,
                        Integer.parseInt(options.getOrDefault("payments", "101000")));
                ThroughputBenchmark.Recorded recorded = ThroughputBenchmark.run(service(), large,
                        env("GIT_SHA", "unknown"), env("TOPOLOGY", "unknown"), env("HARDWARE_NOTE", "not recorded"));
                Path results = Path.of(options.getOrDefault("results", "results/benchmark"));
                ThroughputBenchmark.write(recorded, results);
                System.out.println(ThroughputBenchmark.markdown(recorded));
                System.out.println("Written to " + results.resolve("latest.md"));
            }
            default -> usage();
        }
    }

    private static void write(Dataset data, Path out) throws Exception {
        Files.createDirectories(out);
        Files.writeString(out.resolve("ledger.csv"), data.ledgerCsv());
        Files.writeString(out.resolve("statement.csv"), data.statementCsv());
        Files.writeString(out.resolve("truth.json"), JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT)
                .build().writeValueAsString(data.truth()));
        System.out.println("Wrote " + data.ledger().size() + " ledger records and " + data.statement().size()
                + " statement lines to " + out);
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null) {
            throw new IllegalArgumentException("--" + name + " is required");
        }
        return value;
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
        System.out.println("usage: generate|statement-for|accuracy|benchmark [--seed N] [--payments N] [--ledger FILE]"
                + " [--out DIR] [--results DIR]");
    }
}
