package io.github.sanskar121543.reconciliation.run;

public enum RunStatus {
    /** Waiting for the ledger file, then the statement file. */
    RECEIVING,
    /** The statement is in; lines are being matched. */
    MATCHING,
    /** Every line has been processed and every unmatched record is a break. */
    COMPLETED
}
