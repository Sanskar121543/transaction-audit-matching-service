package io.github.sanskar121543.reconciliation.matching;

import java.time.LocalDate;

/** What matching needs to know about a bank statement line. */
public record StatementItem(long id, int rowNumber, String accountId, LocalDate date, long amountCents,
        String reference, String normalizedReference) {
}
