package io.github.sanskar121543.reconciliation.breaks;

public enum BreakType {
    /** In our ledger, but no bank line matched it. */
    MISSING_IN_BANK,
    /** On the bank statement, but no ledger record matched it. */
    MISSING_IN_LEDGER,
    /** A ledger record and a bank line with the same reference whose amounts differ by more than the tolerance. */
    AMOUNT_MISMATCH,
    /** A second copy of a record that is already matched, on either side. */
    DUPLICATE
}
