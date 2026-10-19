package io.github.sanskar121543.reconciliation.ingest;

import java.util.List;

/**
 * A validated upload. {@code rejections} lists at most the configured number of problems; the counts
 * are always complete.
 */
public record ParsedFile<T>(String sha256, int rowsTotal, List<T> accepted, int rowsRejected,
        List<Rejection> rejections, boolean rejectionsTruncated, List<String> ignoredColumns) {
}
