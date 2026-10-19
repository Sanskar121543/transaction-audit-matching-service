package io.github.sanskar121543.reconciliation.generator;

/**
 * What happens to one generated payment between our ledger and the bank. The weight is per thousand
 * payments. The last two are hard cases on purpose: the matcher cannot get all of them right, so the
 * measured precision and recall show its real limits instead of reaching 100% by construction.
 */
public enum Scenario {
    /** Same reference, same amount, the bank posts it 0-2 days later. Expected: EXACT. */
    CLEAN(800),
    /** The bank deducted a fee within the tolerance. Expected: TOLERANCE. */
    FEE_DEDUCTED(40),
    /** The bank reformatted the reference and posted on time. Expected: TOLERANCE. */
    REFORMATTED_ON_TIME(30),
    /** The bank reformatted the reference and posted after the date window. Expected: NORMALIZED_REFERENCE. */
    REFORMATTED_LATE(30),
    /** The bank never received it. Expected: a MISSING_IN_BANK break. */
    MISSING_IN_BANK(20),
    /** A bank-only line such as a fee or interest. Expected: a MISSING_IN_LEDGER break. */
    MISSING_IN_LEDGER(20),
    /** The bank received a different amount (beyond the tolerance). Expected: an AMOUNT_MISMATCH break. */
    AMOUNT_MISMATCH(20),
    /** The bank posted it twice. Expected: one match and a DUPLICATE break for the second line. */
    DUPLICATE_IN_BANK(10),
    /** We booked it twice. Expected: one match and a DUPLICATE break for the second ledger record. */
    DUPLICATE_IN_LEDGER(10),
    /**
     * Hard: the bank dropped the reference and posted after the window. No rule can tie the two together,
     * so the truth (one payment) comes out as two breaks.
     */
    REFERENCE_LOST_LATE(10),
    /**
     * Hard: two payments with the same amount a day apart; the bank shows only the second, without a
     * reference, sometimes dated on the first one's day. The tolerance rule then picks the wrong one.
     */
    LOOKALIKE(10);

    private final int weightPerThousand;

    Scenario(int weightPerThousand) {
        this.weightPerThousand = weightPerThousand;
    }

    public int weightPerThousand() {
        return weightPerThousand;
    }
}
