package io.github.sanskar121543.reconciliation.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanskar121543.reconciliation.generator.BankSimulator;
import io.github.sanskar121543.reconciliation.generator.Dataset;
import io.github.sanskar121543.reconciliation.support.ApiClient;
import io.github.sanskar121543.reconciliation.support.ApiResponse;
import io.github.sanskar121543.reconciliation.support.IntegrationTest;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;

/**
 * The first 60 rows of a real scripts/export-payments-ledger.sh export (synthetic data from the Payments
 * Ledger Service), so the format stays covered in CI without that project running.
 */
@IntegrationTest
class PaymentsLedgerExportIT {

    @Value("${local.server.port}")
    int port;

    @Test
    void aPaymentsLedgerExportImportsWithoutRejectionsAndReconciles() throws IOException {
        String export = Files.readString(Path.of("src", "test", "resources", "payments-ledger-export-sample.csv"));
        Dataset bank = BankSimulator.simulate(BankSimulator.readLedger(new StringReader(export)), 7, 100, 3);
        ApiClient api = ApiClient.forPort(port);
        long runId = api.createRun("payments ledger export");

        ApiResponse ledger = api.uploadLedger(runId, export);
        ApiResponse statement = api.uploadStatement(runId, bank.statementCsv());
        api.awaitCompleted(runId);

        assertThat(ledger.body().get("rowsAccepted").asInt()).isEqualTo(60);
        assertThat(ledger.body().get("rowsRejected").asInt()).isZero();
        assertThat(statement.body().get("rowsRejected").asInt()).isZero();
        JsonNode summary = api.get("/v1/runs/" + runId).body().get("summary");
        long matches = summary.get("matches").asLong();
        long mismatches = summary.at("/breaksByType/AMOUNT_MISMATCH").asLong();
        // A match and an amount mismatch each hold two records; every other break holds one.
        assertThat(2 * matches + 2 * mismatches + (summary.get("breaks").asLong() - mismatches))
                .as("every record is matched or in exactly one break")
                .isEqualTo(60 + bank.statement().size());
        assertThat(matches).isPositive();
    }
}
