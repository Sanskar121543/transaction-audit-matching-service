package io.github.sanskar121543.reconciliation.breaks;

/** How long a break has been outstanding, counted in days from its item date. */
public enum AgingBucket {
    DAYS_0_2(0, "0-2 days"),
    DAYS_3_7(3, "3-7 days"),
    DAYS_8_30(8, "8-30 days"),
    DAYS_31_PLUS(31, "31+ days");

    private final int fromDays;
    private final String label;

    AgingBucket(int fromDays, String label) {
        this.fromDays = fromDays;
        this.label = label;
    }

    public int fromDays() {
        return fromDays;
    }

    public String label() {
        return label;
    }

    /** A future-dated item (negative age) counts as brand new. */
    public static AgingBucket of(long ageDays) {
        AgingBucket bucket = DAYS_0_2;
        for (AgingBucket candidate : values()) {
            if (ageDays >= candidate.fromDays) {
                bucket = candidate;
            }
        }
        return bucket;
    }
}
