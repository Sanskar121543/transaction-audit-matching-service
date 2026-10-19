package io.github.sanskar121543.reconciliation.matching;

import java.time.LocalDate;

/** Short builders for matching tests: dates are days of October 2026, amounts are cents. */
final class Items {

    static final String ACCOUNT = "ACC-1";

    private Items() {
    }

    static LedgerItem ledger(long id, int day, long cents, String reference) {
        return new LedgerItem(id, ACCOUNT, LocalDate.of(2026, 10, day), cents, reference,
                ReferenceNormalizer.normalize(reference));
    }

    static StatementItem line(int row, int day, long cents, String reference) {
        return new StatementItem(1000 + row, row, ACCOUNT, LocalDate.of(2026, 10, day), cents, reference,
                ReferenceNormalizer.normalize(reference));
    }
}
