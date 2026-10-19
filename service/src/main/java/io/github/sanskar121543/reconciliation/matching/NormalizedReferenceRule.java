package io.github.sanskar121543.reconciliation.matching;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

/**
 * Rule (c): the same reference once normalised ("inv 123" = "INV-000123"), the same sign and the amount
 * within the tolerance, at any date. It catches a payment the bank booked outside the date window, where
 * only the reference ties the two together. The closest date wins, then the smallest amount difference,
 * then the lowest id.
 */
public final class NormalizedReferenceRule {

    private NormalizedReferenceRule() {
    }

    public static Optional<LedgerItem> pick(StatementItem line, Collection<LedgerItem> unmatchedLedger,
            Tolerance tolerance) {
        if (line.normalizedReference().isEmpty()) {
            return Optional.empty();
        }
        return unmatchedLedger.stream()
                .filter(l -> l.accountId().equals(line.accountId()))
                .filter(l -> l.normalizedReference().equals(line.normalizedReference()))
                .filter(l -> Long.signum(l.amountCents()) == Long.signum(line.amountCents()))
                .filter(l -> Math.abs(l.amountCents() - line.amountCents()) <= tolerance.amountCents())
                .min(Comparator.comparingLong((LedgerItem l) -> Dates.daysApart(l.date(), line.date()))
                        .thenComparingLong(l -> Math.abs(l.amountCents() - line.amountCents()))
                        .thenComparingLong(LedgerItem::id));
    }
}
