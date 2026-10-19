package io.github.sanskar121543.reconciliation.ingest;

import java.time.LocalDate;

public record LedgerRecordInput(int row, String externalId, String accountId, LocalDate bookingDate, long amountCents,
        String reference, String description) {
}
