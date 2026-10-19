package io.github.sanskar121543.reconciliation.ingest;

import io.github.sanskar121543.reconciliation.matching.ReferenceNormalizer;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class ImportStore {

    private static final int CHUNK = 5_000;
    private static final TypeReference<List<Rejection>> REJECTIONS = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> NAMES = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;

    public ImportStore(JdbcTemplate jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    public Optional<ImportReport> find(long runId, Side side) {
        return jdbc.query("SELECT * FROM imports WHERE run_id = ? AND side = ?", this::toReport, runId, side.name())
                .stream().findFirst();
    }

    public List<ImportReport> forRun(long runId) {
        return jdbc.query("SELECT * FROM imports WHERE run_id = ? ORDER BY id", this::toReport, runId);
    }

    public ImportReport insert(long runId, Side side, ParsedFile<?> file, String importedBy) {
        Long id = jdbc.queryForObject("""
                INSERT INTO imports (run_id, side, sha256, rows_total, rows_accepted, rows_rejected, rejections,
                                     rejections_truncated, ignored_columns, imported_by)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?)
                RETURNING id
                """, Long.class, runId, side.name(), file.sha256(), file.rowsTotal(), file.accepted().size(),
                file.rowsRejected(), jsonMapper.writeValueAsString(file.rejections()), file.rejectionsTruncated(),
                jsonMapper.writeValueAsString(file.ignoredColumns()), importedBy);
        return jdbc.queryForObject("SELECT * FROM imports WHERE id = ?", this::toReport, id);
    }

    public void insertLedger(long runId, List<LedgerRecordInput> records) {
        for (int from = 0; from < records.size(); from += CHUNK) {
            List<LedgerRecordInput> chunk = records.subList(from, Math.min(records.size(), from + CHUNK));
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO ledger_records (run_id, row_number, external_id, account_id, booking_date,
                                                    amount_cents, currency, reference, normalized_reference,
                                                    description)
                        SELECT ?, r, e, a, d, c, 'USD', ref, nref, descr
                        FROM unnest(?::int[], ?::text[], ?::text[], ?::date[], ?::bigint[], ?::text[], ?::text[],
                                    ?::text[]) AS t(r, e, a, d, c, ref, nref, descr)
                        """);
                ps.setLong(1, runId);
                ps.setArray(2, connection.createArrayOf("int4", chunk.stream().map(LedgerRecordInput::row).toArray()));
                ps.setArray(3, connection.createArrayOf("text",
                        chunk.stream().map(LedgerRecordInput::externalId).toArray()));
                ps.setArray(4, connection.createArrayOf("text",
                        chunk.stream().map(LedgerRecordInput::accountId).toArray()));
                ps.setArray(5, connection.createArrayOf("date",
                        chunk.stream().map(r -> Date.valueOf(r.bookingDate())).toArray()));
                ps.setArray(6, connection.createArrayOf("int8",
                        chunk.stream().map(LedgerRecordInput::amountCents).toArray()));
                ps.setArray(7, connection.createArrayOf("text",
                        chunk.stream().map(LedgerRecordInput::reference).toArray()));
                ps.setArray(8, connection.createArrayOf("text",
                        chunk.stream().map(r -> ReferenceNormalizer.normalize(r.reference())).toArray()));
                ps.setArray(9, connection.createArrayOf("text",
                        chunk.stream().map(LedgerRecordInput::description).toArray()));
                return ps;
            });
        }
    }

    private ImportReport toReport(ResultSet rs, int rowNum) throws SQLException {
        return new ImportReport(rs.getLong("id"), rs.getLong("run_id"), Side.valueOf(rs.getString("side")),
                rs.getString("sha256"), rs.getInt("rows_total"), rs.getInt("rows_accepted"),
                rs.getInt("rows_rejected"), jsonMapper.readValue(rs.getString("rejections"), REJECTIONS),
                rs.getBoolean("rejections_truncated"), jsonMapper.readValue(rs.getString("ignored_columns"), NAMES),
                rs.getString("imported_by"), rs.getTimestamp("created_at").toInstant(), false);
    }
}
