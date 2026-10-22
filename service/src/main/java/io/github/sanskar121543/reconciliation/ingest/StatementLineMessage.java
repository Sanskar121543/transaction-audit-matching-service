package io.github.sanskar121543.reconciliation.ingest;

import java.time.LocalDate;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One accepted statement line on the {@code statement.lines} topic, keyed by account so that one account's
 * lines stay in file order on one partition.
 * <p>
 * {@code sha256} is the statement file's hash: a worker only stores lines of the file the run imported.
 * {@code previousRow} is the row of the line before this one in the same account (0 for the account's first
 * line). A worker matches a line only once its predecessor is stored, so every account is matched in file
 * order whatever Kafka redelivers, and whichever upload attempt the line came from.
 */
public record StatementLineMessage(long runId, String sha256, int row, int previousRow, String accountId,
        LocalDate valueDate, long amountCents, String reference, String description, String bankTransactionId) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public StatementLineInput toInput() {
        return new StatementLineInput(row, accountId, valueDate, amountCents, reference, description, bankTransactionId);
    }

    /**
     * Why a worker could never store this message, if so. Lines this service published always pass; anything
     * else on the topic (written by hand, or by an incompatible version) is dead-lettered instead of being
     * retried forever.
     */
    public Optional<String> invalidBecause() {
        if (runId < 1) {
            return Optional.of("runId must be positive");
        }
        if (sha256 == null || !SHA256.matcher(sha256).matches()) {
            return Optional.of("sha256 must be 64 lowercase hex digits");
        }
        if (row < 1 || previousRow < 0 || previousRow >= row) {
            return Optional.of("row must be positive and previousRow between 0 and row - 1");
        }
        if (accountId == null || accountId.isBlank() || valueDate == null || amountCents == 0) {
            return Optional.of("accountId, valueDate and a non-zero amountCents are required");
        }
        if (reference == null || description == null) {
            return Optional.of("reference and description are required (empty is allowed)");
        }
        return Optional.empty();
    }
}
