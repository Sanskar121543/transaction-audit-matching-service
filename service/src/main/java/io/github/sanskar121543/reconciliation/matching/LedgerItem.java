package io.github.sanskar121543.reconciliation.matching;

import java.time.LocalDate;

/** What matching needs to know about a ledger record. */
public record LedgerItem(long id, String accountId, LocalDate date, long amountCents, String reference,
        String normalizedReference) {
}
