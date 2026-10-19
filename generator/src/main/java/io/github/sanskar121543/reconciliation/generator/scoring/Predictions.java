package io.github.sanskar121543.reconciliation.generator.scoring;

import java.util.List;

/** What the service decided for one run, keyed the same way as the truth: ledger external id and statement row. */
public record Predictions(List<Match> matches, List<Break> breaks) {

    public record Match(String ledgerExternalId, int statementRow, String rule) {
    }

    public record Break(String type, String ledgerExternalId, Integer statementRow) {
    }
}
