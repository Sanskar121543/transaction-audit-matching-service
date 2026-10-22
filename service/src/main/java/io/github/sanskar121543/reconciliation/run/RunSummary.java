package io.github.sanskar121543.reconciliation.run;

import java.util.Map;

/**
 * Counts for one run. {@code statementLines} counts the lines the workers have stored so far and
 * {@code statementLinesExpected} the lines the statement file was accepted with (null until it is uploaded):
 * while the run is MATCHING, the two show its progress. {@code matchRate} counts both sides: matched records /
 * all records, where each match matches two records. It is null until the run has records.
 */
public record RunSummary(long ledgerRecords, long statementLines, Long statementLinesExpected, long matches,
        Map<String, Long> matchesByRule, long breaks, Map<String, Long> breaksByType, Double matchRate) {
}
