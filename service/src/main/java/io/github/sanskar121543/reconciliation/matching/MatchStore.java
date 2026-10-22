package io.github.sanskar121543.reconciliation.matching;

import io.github.sanskar121543.reconciliation.ingest.StatementLineInput;
import java.sql.Array;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bulk reads and writes for matching, in plain JDBC: one INSERT ... SELECT FROM unnest(arrays) writes a
 * whole batch in a single round trip, which JPA cannot do with identity ids.
 */
@Repository
public class MatchStore {

    private static final String LEDGER_COLUMNS =
            "l.id, l.account_id, l.booking_date, l.amount_cents, l.reference, l.normalized_reference";
    private static final String LINE_COLUMNS =
            "s.id, s.row_number, s.account_id, s.value_date, s.amount_cents, s.reference, s.normalized_reference";

    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;

    public MatchStore(JdbcTemplate jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    /** Inserts the lines not seen before and returns only those, in row order. A line seen before is skipped. */
    public List<StatementItem> insertLines(long runId, List<StatementLineInput> lines, String instanceId) {
        if (lines.isEmpty()) {
            return List.of();
        }
        List<StatementItem> inserted = jdbc.query(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO statement_lines (run_id, row_number, account_id, value_date, amount_cents, currency,
                                                 reference, normalized_reference, description, bank_transaction_id,
                                                 ingested_by)
                    SELECT ?, r, a, d, c, 'USD', ref, nref, descr, bank_id, ?
                    FROM unnest(?::int[], ?::text[], ?::date[], ?::bigint[], ?::text[], ?::text[], ?::text[], ?::text[])
                         AS t(r, a, d, c, ref, nref, descr, bank_id)
                    ON CONFLICT (run_id, row_number) DO NOTHING
                    RETURNING id, row_number, account_id, value_date, amount_cents, reference, normalized_reference
                    """);
            ps.setLong(1, runId);
            ps.setString(2, instanceId);
            ps.setArray(3, array(connection, "int4", lines.stream().map(StatementLineInput::row).toArray()));
            ps.setArray(4, array(connection, "text", lines.stream().map(StatementLineInput::accountId).toArray()));
            ps.setArray(5, array(connection, "date", lines.stream().map(l -> Date.valueOf(l.valueDate())).toArray()));
            ps.setArray(6, array(connection, "int8", lines.stream().map(StatementLineInput::amountCents).toArray()));
            ps.setArray(7, array(connection, "text", lines.stream().map(StatementLineInput::reference).toArray()));
            ps.setArray(8, array(connection, "text",
                    lines.stream().map(l -> ReferenceNormalizer.normalize(l.reference())).toArray()));
            ps.setArray(9, array(connection, "text", lines.stream().map(StatementLineInput::description).toArray()));
            ps.setArray(10, array(connection, "text", lines.stream().map(StatementLineInput::bankTransactionId).toArray()));
            return ps;
        }, (rs, i) -> new StatementItem(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getDate(4).toLocalDate(),
                rs.getLong(5), rs.getString(6), rs.getString(7)));
        List<StatementItem> sorted = new ArrayList<>(inserted);
        sorted.sort(Comparator.comparingInt(StatementItem::rowNumber));
        return sorted;
    }

    /**
     * Until the transaction ends, no other worker can store or match lines of these accounts in this run.
     * The keys are taken in ascending order, so two workers whose accounts overlap cannot deadlock.
     */
    public void lockAccounts(long runId, Collection<String> accountIds) {
        int[] keys = accountIds.stream().mapToInt(MatchStore::accountLockKey).distinct().sorted().toArray();
        for (int key : keys) {
            jdbc.query("SELECT pg_advisory_xact_lock(?, ?)", (RowCallbackHandler) rs -> {
            }, (int) runId, key);
        }
    }

    /**
     * The second key of an account's lock (the first is the run id, truncated to an int). Two accounts with the
     * same hash only make their workers wait for each other.
     */
    public static int accountLockKey(String accountId) {
        return accountId.hashCode();
    }

    public Set<Integer> storedRows(long runId, Collection<Integer> rows) {
        return new HashSet<>(jdbc.query(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "SELECT row_number FROM statement_lines WHERE run_id = ? AND row_number = ANY(?)");
            ps.setLong(1, runId);
            ps.setArray(2, array(connection, "int4", rows.toArray()));
            return ps;
        }, (rs, i) -> rs.getInt(1)));
    }

    /**
     * The end-of-run step computes for seconds between two statements on a large run, which the pool's 15 s
     * idle-in-transaction limit (meant for sessions whose instance died) must not cut short. SET LOCAL ends
     * with the transaction.
     */
    public void allowLongComputationInThisTransaction() {
        jdbc.execute("SET LOCAL idle_in_transaction_session_timeout = '5min'");
    }

    /**
     * Adds to the run's count of stored lines and returns the new total, or nothing if the run is no longer
     * MATCHING. Holds the run's row lock until commit. Waiting for that lock re-reads the row, so a line
     * stored while another transaction completes the run is seen here as arriving after completion.
     */
    public OptionalInt addReceivedLines(long runId, int lines) {
        List<Integer> total = jdbc.queryForList("""
                UPDATE runs SET statement_lines_received = statement_lines_received + ?
                WHERE id = ? AND status = 'MATCHING'
                RETURNING statement_lines_received
                """, Integer.class, lines, runId);
        return total.isEmpty() ? OptionalInt.empty() : OptionalInt.of(total.getFirst());
    }

    /** Runs outside any transaction: the worker's own transaction has already rolled back. */
    public void recordMatchingProblem(long runId, String problem) {
        jdbc.update("UPDATE runs SET matching_problem = ?, matching_problem_at = now() WHERE id = ?", problem, runId);
    }

    public void clearMatchingProblem(long runId) {
        jdbc.update("""
                UPDATE runs SET matching_problem = NULL, matching_problem_at = NULL
                WHERE id = ? AND matching_problem IS NOT NULL
                """, runId);
    }

    public List<LedgerItem> unmatchedLedgerWithReferences(long runId, String accountId, Collection<String> references) {
        return jdbc.query(connection -> {
            PreparedStatement ps = connection.prepareStatement("SELECT " + LEDGER_COLUMNS + """
                     FROM ledger_records l
                    WHERE l.run_id = ? AND l.account_id = ? AND l.reference = ANY(?)
                      AND NOT EXISTS (SELECT 1 FROM matches m WHERE m.run_id = l.run_id AND m.ledger_record_id = l.id)
                    """);
            ps.setLong(1, runId);
            ps.setString(2, accountId);
            ps.setArray(3, array(connection, "text", references.toArray()));
            return ps;
        }, LEDGER);
    }

    public void insertMatches(long runId, List<MatchDecision> matches, String instanceId) {
        if (matches.isEmpty()) {
            return;
        }
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO matches (run_id, account_id, ledger_record_id, statement_line_id, rule, matched_by)
                    SELECT ?, a, l, s, r, ? FROM unnest(?::text[], ?::bigint[], ?::bigint[], ?::text[]) AS t(a, l, s, r)
                    """);
            ps.setLong(1, runId);
            ps.setString(2, instanceId);
            ps.setArray(3, array(connection, "text", matches.stream().map(m -> m.line().accountId()).toArray()));
            ps.setArray(4, array(connection, "int8", matches.stream().map(m -> m.ledger().id()).toArray()));
            ps.setArray(5, array(connection, "int8", matches.stream().map(m -> m.line().id()).toArray()));
            ps.setArray(6, array(connection, "text", matches.stream().map(m -> m.rule().name()).toArray()));
            return ps;
        });
    }

    public List<LedgerItem> unmatchedLedger(long runId) {
        return jdbc.query("SELECT " + LEDGER_COLUMNS + """
                 FROM ledger_records l
                WHERE l.run_id = ?
                  AND NOT EXISTS (SELECT 1 FROM matches m WHERE m.run_id = l.run_id AND m.ledger_record_id = l.id)
                ORDER BY l.id
                """, LEDGER, runId);
    }

    public List<StatementItem> unmatchedLines(long runId) {
        return jdbc.query("SELECT " + LINE_COLUMNS + """
                 FROM statement_lines s
                WHERE s.run_id = ?
                  AND NOT EXISTS (SELECT 1 FROM matches m WHERE m.run_id = s.run_id AND m.statement_line_id = s.id)
                ORDER BY s.row_number
                """, LINE, runId);
    }

    /**
     * Matches in which either side has one of these normalised references, or one of these raw references
     * that normalise to nothing. Because normalising is idempotent and each normalised column is the
     * normalised raw one, this finds exactly the matches whose raw or normalised reference on either side
     * equals any raw or normalised reference of the given records, with far fewer values to compare.
     */
    public List<MatchDecision> matchesSharingReferences(long runId, Collection<String> normalizedReferences,
            Collection<String> rawReferencesWithoutLettersOrDigits) {
        if (normalizedReferences.isEmpty() && rawReferencesWithoutLettersOrDigits.isEmpty()) {
            return List.of();
        }
        return jdbc.query(connection -> {
            PreparedStatement ps = connection.prepareStatement("SELECT " + LEDGER_COLUMNS + ", " + LINE_COLUMNS + """
                    , m.rule
                     FROM matches m
                     JOIN ledger_records l ON l.id = m.ledger_record_id
                     JOIN statement_lines s ON s.id = m.statement_line_id
                    WHERE m.run_id = ?
                      AND (l.normalized_reference = ANY(?) OR s.normalized_reference = ANY(?)
                           OR l.reference = ANY(?) OR s.reference = ANY(?))
                    ORDER BY m.id
                    """);
            Array normalized = array(connection, "text", normalizedReferences.toArray());
            Array raw = array(connection, "text", rawReferencesWithoutLettersOrDigits.toArray());
            ps.setLong(1, runId);
            ps.setArray(2, normalized);
            ps.setArray(3, normalized);
            ps.setArray(4, raw);
            ps.setArray(5, raw);
            return ps;
        }, (rs, i) -> new MatchDecision(LEDGER.mapRow(rs, i), lineAt(rs, 7), Rule.valueOf(rs.getString(14))));
    }

    public void insertBreaks(long runId, List<BreakDecision> breaks) {
        jdbc.batchUpdate("""
                INSERT INTO breaks (run_id, type, account_id, ledger_record_id, statement_line_id, exposure_cents,
                                    item_date, rule_trace)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """, breaks, 500, (ps, b) -> {
            ps.setLong(1, runId);
            ps.setString(2, b.type().name());
            ps.setString(3, b.accountId());
            ps.setObject(4, b.ledger() == null ? null : b.ledger().id());
            ps.setObject(5, b.line() == null ? null : b.line().id());
            ps.setLong(6, b.exposureCents());
            ps.setDate(7, Date.valueOf(b.itemDate()));
            ps.setString(8, jsonMapper.writeValueAsString(new StoredTrace(b.verdict(), b.trace())));
        });
    }

    /** The JSON kept in breaks.rule_trace. */
    public record StoredTrace(String verdict, List<RuleCheck> checks) {
    }

    private static final RowMapper<LedgerItem> LEDGER = (rs, i) -> new LedgerItem(rs.getLong(1), rs.getString(2),
            rs.getDate(3).toLocalDate(), rs.getLong(4), rs.getString(5), rs.getString(6));

    private static final RowMapper<StatementItem> LINE = (rs, i) -> lineAt(rs, 1);

    private static StatementItem lineAt(ResultSet rs, int first) throws SQLException {
        return new StatementItem(rs.getLong(first), rs.getInt(first + 1), rs.getString(first + 2),
                rs.getDate(first + 3).toLocalDate(), rs.getLong(first + 4), rs.getString(first + 5),
                rs.getString(first + 6));
    }

    static Array array(Connection connection, String type, Object[] values) throws SQLException {
        return connection.createArrayOf(type, values);
    }
}
