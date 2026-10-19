package io.github.sanskar121543.reconciliation.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Turns an uploaded body into {@link RawRow}s, one at a time, without holding the whole file. */
final class RowReader {

    private static final CSVFormat CSV = CSVFormat.RFC4180.builder().setIgnoreEmptyLines(true).get();
    private static final int MAX_IGNORED_NAMES = 20;

    private RowReader() {
    }

    /** Returns the header columns that were ignored because this side does not use them. */
    static List<String> readCsv(InputStream body, Side side, int maxRows, Consumer<RawRow> sink) {
        Reader reader = new InputStreamReader(body, StandardCharsets.UTF_8);
        try (CSVParser parser = CSVParser.parse(reader, CSV)) {
            Iterator<CSVRecord> records = parser.iterator();
            if (!hasNext(records, 0)) {
                throw new MalformedFileException("The file is empty: the first line must be a header");
            }
            CSVRecord header = records.next();
            Column[] columns = new Column[header.size()];
            Set<Column> seen = EnumSet.noneOf(Column.class);
            Set<String> ignored = new LinkedHashSet<>();
            Set<Column> allowed = Column.allowed(side);
            for (int i = 0; i < header.size(); i++) {
                String name = header.get(i).replace("﻿", "").strip().toLowerCase(Locale.ROOT);
                Optional<Column> column = Column.byCsvName(name).filter(allowed::contains);
                if (column.isEmpty()) {
                    ignore(ignored, name);
                } else if (!seen.add(column.get())) {
                    throw new MalformedFileException("The header lists column " + name + " twice");
                } else {
                    columns[i] = column.get();
                }
            }
            requireColumns(side, seen);

            int row = 0;
            while (hasNext(records, row)) {
                CSVRecord record = records.next();
                row = checkRowLimit(row + 1, maxRows);
                List<Rejection> shapeErrors = new ArrayList<>();
                if (record.size() != header.size()) {
                    shapeErrors.add(new Rejection(row, null,
                            "has " + record.size() + " fields but the header has " + header.size()));
                }
                Map<Column, String> values = new EnumMap<>(Column.class);
                for (int i = 0; i < Math.min(record.size(), columns.length); i++) {
                    if (columns[i] != null) {
                        values.put(columns[i], record.get(i));
                    }
                }
                sink.accept(new RawRow(row, values, shapeErrors));
            }
            return List.copyOf(ignored);
        } catch (UncheckedIOException e) {
            throw new MalformedFileException("The CSV could not be read: " + e.getCause().getMessage());
        } catch (IOException e) {
            throw new MalformedFileException("The CSV could not be read: " + e.getMessage());
        }
    }

    /** The body must be a JSON array of objects whose keys are the camelCase column names. */
    static List<String> readJson(InputStream body, Side side, int maxRows, JsonMapper mapper, Consumer<RawRow> sink) {
        Set<Column> allowed = Column.allowed(side);
        Set<String> ignored = new LinkedHashSet<>();
        try (JsonParser parser = mapper.createParser(body)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new MalformedFileException("The body must be a JSON array of row objects");
            }
            int row = 0;
            for (JsonToken token = parser.nextToken(); token != JsonToken.END_ARRAY; token = parser.nextToken()) {
                if (token == null) {
                    throw new MalformedFileException("The JSON array is not closed");
                }
                row = checkRowLimit(row + 1, maxRows);
                if (token != JsonToken.START_OBJECT) {
                    parser.skipChildren();
                    sink.accept(new RawRow(row, Map.of(), List.of(new Rejection(row, null, "must be a JSON object"))));
                    continue;
                }
                JsonNode node = parser.readValueAsTree();
                Map<Column, String> values = new EnumMap<>(Column.class);
                List<Rejection> shapeErrors = new ArrayList<>();
                for (Map.Entry<String, JsonNode> property : node.properties()) {
                    Optional<Column> column = Column.byJsonName(property.getKey()).filter(allowed::contains);
                    JsonNode value = property.getValue();
                    if (column.isEmpty()) {
                        ignore(ignored, property.getKey());
                    } else if (value.isString()) {
                        values.put(column.get(), value.stringValue());
                    } else if (!value.isNull()) {
                        // Amounts as JSON numbers would go through binary floating point; text is exact.
                        shapeErrors.add(new Rejection(row, column.get().csvName(), "must be a JSON string"));
                    }
                }
                sink.accept(new RawRow(row, values, shapeErrors));
            }
            if (parser.nextToken() != null) {
                throw new MalformedFileException("Unexpected content after the JSON array");
            }
            return List.copyOf(ignored);
        } catch (JacksonException e) {
            throw new MalformedFileException("The body is not valid JSON: " + e.getOriginalMessage());
        }
    }

    private static void requireColumns(Side side, Set<Column> present) {
        Set<Column> missing = EnumSet.noneOf(Column.class);
        missing.addAll(Column.required(side));
        missing.removeAll(present);
        if (!missing.isEmpty()) {
            throw new MalformedFileException("The header is missing required columns: "
                    + missing.stream().map(Column::csvName).collect(Collectors.joining(", ")));
        }
    }

    private static int checkRowLimit(int row, int maxRows) {
        if (row > maxRows) {
            throw new PayloadTooLargeException("The upload has more than " + maxRows + " rows");
        }
        return row;
    }

    // Commons CSV reports broken quoting from hasNext(), so the row number is added here.
    private static boolean hasNext(Iterator<CSVRecord> records, int rowsSoFar) {
        try {
            return records.hasNext();
        } catch (UncheckedIOException e) {
            throw new MalformedFileException("The CSV could not be read after row " + rowsSoFar + ": "
                    + e.getCause().getMessage());
        }
    }

    private static void ignore(Set<String> ignored, String name) {
        if (ignored.size() < MAX_IGNORED_NAMES) {
            ignored.add(name.length() > 64 ? name.substring(0, 64) : name);
        }
    }
}
