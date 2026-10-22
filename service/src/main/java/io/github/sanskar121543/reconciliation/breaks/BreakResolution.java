package io.github.sanskar121543.reconciliation.breaks;

/** How a break was settled. Resolving a break needs one, and a note saying why. */
public enum BreakResolution {
    TIMING_DIFFERENCE,
    BANK_FEE,
    BANK_ERROR,
    LEDGER_CORRECTED,
    WRITTEN_OFF,
    OTHER
}
