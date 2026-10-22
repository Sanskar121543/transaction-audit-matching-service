package io.github.sanskar121543.reconciliation.support;

import io.github.sanskar121543.reconciliation.generator.cli.ServiceClient;
import io.github.sanskar121543.reconciliation.generator.scoring.Predictions;
import java.util.List;

/**
 * A run's whole outcome, read through the API and keyed by ledger external id and statement row, so two runs
 * of the same files compare equal exactly when every match (and its rule) and every break (and its type) agree.
 */
public record RunResults(List<String> matches, List<String> breaks) {

    public static RunResults of(ApiClient api, long runId) {
        Predictions predictions = new ServiceClient(api.baseUrl(), ApiClient.OPERATOR_KEY).predictions(runId);
        return new RunResults(
                predictions.matches().stream().map(m -> m.ledgerExternalId() + "~" + m.statementRow() + ":" + m.rule())
                        .sorted().toList(),
                predictions.breaks().stream().map(b -> b.type() + " " + b.ledgerExternalId() + " " + b.statementRow())
                        .sorted().toList());
    }
}
