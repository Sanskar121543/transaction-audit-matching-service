package io.github.sanskar121543.reconciliation.generator;

import java.util.List;

/**
 * How the two files should reconcile. Every ledger record and every statement row appears exactly once:
 * either in a match or in a break.
 */
public record Truth(List<Match> matches, List<Break> breaks) {

    /** {@code expectedRule} is the rule a correct matcher would use; null for the hard cases. */
    public record Match(String ledgerExternalId, int statementRow, Scenario scenario, String expectedRule) {
    }

    /** One side may be null: MISSING_IN_BANK has no row, MISSING_IN_LEDGER no ledger id. */
    public record Break(String type, String ledgerExternalId, Integer statementRow, Scenario scenario) {
    }
}
