package io.github.sanskar121543.reconciliation.matching;

/** The matching rules, in the order they are tried. Each match records the rule that made it. */
public enum Rule {
    /** (a) Same account, the same reference character for character, and the same amount to the cent. */
    EXACT,
    /** (b) Same account and sign, amount within the run's tolerance, dates within the run's window. */
    TOLERANCE,
    /** (c) Same account and sign, the same reference once normalised, amount within tolerance, any date. */
    NORMALIZED_REFERENCE
}
