package io.github.sanskar121543.reconciliation.generator.scoring;

/**
 * {@code correct} predictions out of {@code predicted}, and out of {@code expected} (the truth).
 * Precision = correct / predicted, recall = correct / expected; null when the denominator is zero.
 */
public record Counts(long predicted, long expected, long correct) {

    public Double precision() {
        return predicted == 0 ? null : (double) correct / predicted;
    }

    public Double recall() {
        return expected == 0 ? null : (double) correct / expected;
    }
}
