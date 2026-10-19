package io.github.sanskar121543.reconciliation.generator;

import java.time.LocalDate;

/**
 * Everything that decides the generated files: the same config always produces the same bytes.
 * {@code toleranceCents} and {@code dateWindowDays} must equal the run's settings, because the generator
 * places fees inside the tolerance, mismatches outside it, and late postings past the window.
 */
public record GeneratorConfig(long seed, int payments, int accounts, LocalDate firstDay, int days,
        long toleranceCents, int dateWindowDays) {

    public static GeneratorConfig defaults(long seed, int payments) {
        return new GeneratorConfig(seed, payments, 12, LocalDate.of(2026, 9, 1), 30, 100, 3);
    }

    public GeneratorConfig {
        if (payments < 1 || accounts < 1 || days < 1 || toleranceCents < 1 || dateWindowDays < 0) {
            throw new IllegalArgumentException("payments, accounts, days and toleranceCents must be positive");
        }
    }
}
