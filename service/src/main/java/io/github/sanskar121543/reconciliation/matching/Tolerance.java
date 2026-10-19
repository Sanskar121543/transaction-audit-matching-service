package io.github.sanskar121543.reconciliation.matching;

/** How far apart two records may be and still count as the same payment. Copied into each run. */
public record Tolerance(long amountCents, int dateWindowDays) {

    public static final long MAX_AMOUNT_CENTS = 100_000;
    public static final int MAX_DATE_WINDOW_DAYS = 31;

    public Tolerance {
        if (amountCents < 0 || amountCents > MAX_AMOUNT_CENTS) {
            throw new IllegalArgumentException("amount tolerance must be between 0 and " + MAX_AMOUNT_CENTS + " cents");
        }
        if (dateWindowDays < 0 || dateWindowDays > MAX_DATE_WINDOW_DAYS) {
            throw new IllegalArgumentException("date window must be between 0 and " + MAX_DATE_WINDOW_DAYS + " days");
        }
    }
}
