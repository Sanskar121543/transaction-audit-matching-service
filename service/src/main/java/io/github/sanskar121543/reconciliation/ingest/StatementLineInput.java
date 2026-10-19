package io.github.sanskar121543.reconciliation.ingest;

import java.time.LocalDate;

/** {@code row} is the line's position in the statement file; together with the run it identifies the line. */
public record StatementLineInput(int row, String accountId, LocalDate valueDate, long amountCents, String reference,
        String description, String bankTransactionId) {
}
