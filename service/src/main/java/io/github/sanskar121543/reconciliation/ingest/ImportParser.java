package io.github.sanskar121543.reconciliation.ingest;

import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Reads and validates an upload in one streaming pass, hashing the exact bytes received. */
@Component
public class ImportParser {

    private final IngestProperties properties;
    private final JsonMapper jsonMapper;

    public ImportParser(IngestProperties properties, JsonMapper jsonMapper) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    public ParsedFile<LedgerRecordInput> ledger(InputStream body, Format format) {
        Map<String, Integer> firstRowByExternalId = new HashMap<>();
        return parse(body, format, Side.LEDGER, (raw, errors) -> RowValidator.ledger(raw, errors).filter(record -> {
            Integer first = firstRowByExternalId.putIfAbsent(record.externalId(), record.row());
            if (first != null) {
                errors.add(new Rejection(record.row(), Column.EXTERNAL_ID.csvName(), "repeats the id of row " + first));
                return false;
            }
            return true;
        }));
    }

    public ParsedFile<StatementLineInput> statement(InputStream body, Format format) {
        return parse(body, format, Side.STATEMENT, RowValidator::statement);
    }

    private <T> ParsedFile<T> parse(InputStream body, Format format, Side side,
            BiFunction<RawRow, List<Rejection>, Optional<T>> validate) {
        MessageDigest sha256 = sha256();
        InputStream in = new DigestInputStream(new LimitedInputStream(body, properties.maxBytes().toBytes()), sha256);
        Collector<T> collector = new Collector<>(validate, properties.maxRejectionsReported());
        // Both readers consume the body to its end (or fail), so the hash covers every byte received.
        List<String> ignored = format == Format.CSV
                ? RowReader.readCsv(in, side, properties.maxRows(), collector)
                : RowReader.readJson(in, side, properties.maxRows(), jsonMapper, collector);
        return new ParsedFile<>(HexFormat.of().formatHex(sha256.digest()), collector.total, collector.accepted,
                collector.rejectedRows, collector.reported, collector.rejectionCount > collector.reported.size(),
                ignored);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Collector<T> implements Consumer<RawRow> {

        final BiFunction<RawRow, List<Rejection>, Optional<T>> validate;
        final int maxReported;
        final List<T> accepted = new ArrayList<>();
        final List<Rejection> reported = new ArrayList<>();
        int total;
        int rejectedRows;
        long rejectionCount;

        Collector(BiFunction<RawRow, List<Rejection>, Optional<T>> validate, int maxReported) {
            this.validate = validate;
            this.maxReported = maxReported;
        }

        @Override
        public void accept(RawRow raw) {
            total++;
            List<Rejection> errors = new ArrayList<>();
            Optional<T> valid = validate.apply(raw, errors);
            if (valid.isPresent()) {
                accepted.add(valid.get());
                return;
            }
            rejectedRows++;
            rejectionCount += errors.size();
            for (Rejection error : errors) {
                if (reported.size() < maxReported) {
                    reported.add(error);
                }
            }
        }
    }
}
