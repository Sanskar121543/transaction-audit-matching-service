package io.github.sanskar121543.reconciliation.run;

import java.util.Map;

/**
 * Counts for one run. {@code matchRate} counts both sides: matched records / all records, where each match
 * matches two records. It is null until the run has records.
 */
public record RunSummary(long ledgerRecords, long statementLines, long matches, Map<String, Long> matchesByRule,
        long breaks, Map<String, Long> breaksByType, Double matchRate) {
}
