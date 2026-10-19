package io.github.sanskar121543.reconciliation.ingest;

import java.util.List;
import java.util.Map;

/**
 * One row as text, before validation: CSV and JSON input both become this, so one validator serves both.
 * Shape errors are problems found while reading (a JSON number where text was expected, a CSV row with
 * the wrong number of fields).
 */
record RawRow(int row, Map<Column, String> values, List<Rejection> shapeErrors) {

    String get(Column column) {
        String value = values.get(column);
        return value == null ? "" : value.strip();
    }
}
