package io.github.sanskar121543.reconciliation.ingest;

import java.time.Instant;
import java.util.List;

/**
 * The rejection report for one uploaded file. {@code replayed} is true when the same file (same SHA-256)
 * was uploaded again: nothing new was imported and this is the original report.
 */
public record ImportReport(long importId, long runId, Side side, String sha256, int rowsTotal, int rowsAccepted,
        int rowsRejected, List<Rejection> rejections, boolean rejectionsTruncated, List<String> ignoredColumns,
        String importedBy, Instant importedAt, boolean replayed) {

    ImportReport asReplay() {
        return new ImportReport(importId, runId, side, sha256, rowsTotal, rowsAccepted, rowsRejected, rejections,
                rejectionsTruncated, ignoredColumns, importedBy, importedAt, true);
    }
}
