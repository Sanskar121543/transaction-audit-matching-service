package io.github.sanskar121543.reconciliation.matching;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

/**
 * Rule (a): the same reference and the same amount. Runs on each statement line as it arrives. If several
 * unmatched ledger records qualify, the closest booking date wins, then the lowest id, so the result never
 * depends on the order the database happens to return rows in.
 */
public final class ExactRule {

    private ExactRule() {
    }

    public static Optional<LedgerItem> pick(StatementItem line, Collection<LedgerItem> unmatchedLedger) {
        if (line.reference().isEmpty()) {
            return Optional.empty();
        }
        return unmatchedLedger.stream()
                .filter(l -> l.accountId().equals(line.accountId()))
                .filter(l -> l.reference().equals(line.reference()))
                .filter(l -> l.amountCents() == line.amountCents())
                .min(Comparator.comparingLong((LedgerItem l) -> Dates.daysApart(l.date(), line.date()))
                        .thenComparingLong(LedgerItem::id));
    }
}
