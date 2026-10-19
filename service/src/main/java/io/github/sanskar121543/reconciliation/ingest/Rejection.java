package io.github.sanskar121543.reconciliation.ingest;

/**
 * Why one row was not imported. Row 1 is the first row after the CSV header, or the first JSON element.
 * {@code field} is the CSV column name (also for JSON input), or null when the whole row is unreadable.
 */
public record Rejection(int row, String field, String reason) {
}
