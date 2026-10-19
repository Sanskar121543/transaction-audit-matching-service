package io.github.sanskar121543.reconciliation.matching;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

/**
 * Rule (b): the same sign, the amount within the tolerance and the dates within the window; the references
 * may differ completely. Among several candidates, one whose normalised reference also agrees wins, then
 * the smallest amount difference, then the closest date, then the lowest id.
 */
public final class ToleranceRule {

    private ToleranceRule() {
    }

    public static Optional<LedgerItem> pick(StatementItem line, Collection<LedgerItem> unmatchedLedger,
            Tolerance tolerance) {
        return unmatchedLedger.stream()
                .filter(l -> l.accountId().equals(line.accountId()))
                .filter(l -> Long.signum(l.amountCents()) == Long.signum(line.amountCents()))
                .filter(l -> Math.abs(l.amountCents() - line.amountCents()) <= tolerance.amountCents())
                .filter(l -> Dates.daysApart(l.date(), line.date()) <= tolerance.dateWindowDays())
                .min(Comparator.comparingInt((LedgerItem l) -> sameReference(l, line) ? 0 : 1)
                        .thenComparingLong(l -> Math.abs(l.amountCents() - line.amountCents()))
                        .thenComparingLong(l -> Dates.daysApart(l.date(), line.date()))
                        .thenComparingLong(LedgerItem::id));
    }

    private static boolean sameReference(LedgerItem ledger, StatementItem line) {
        return !line.normalizedReference().isEmpty() && line.normalizedReference().equals(ledger.normalizedReference());
    }
}
