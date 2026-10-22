package io.github.sanskar121543.reconciliation.breaks;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A break with both sides; {@code ledger} or {@code statement} is null for a one-sided break. {@code version}
 * goes back in any workflow request; {@code updatedAt} is null until the break is first changed.
 */
public record BreakView(long id, long runId, BreakType type, BreakStatus status, String assignee,
        BreakResolution resolution, String accountId, long exposureCents, LocalDate itemDate, long ageDays,
        AgingBucket agingBucket, LedgerSide ledger, StatementSide statement, Instant createdAt, long version,
        Instant updatedAt) {

    public record LedgerSide(long id, String externalId, LocalDate bookingDate, long amountCents, String reference,
            String description) {
    }

    public record StatementSide(long id, int rowNumber, LocalDate valueDate, long amountCents, String reference,
            String description, String bankTransactionId) {
    }
}
