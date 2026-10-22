package io.github.sanskar121543.reconciliation.run;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

@Repository
public class RunSummaries {

    private final JdbcTemplate jdbc;

    public RunSummaries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public RunSummary forRun(long runId) {
        return forRuns(List.of(runId)).get(runId);
    }

    public Map<Long, RunSummary> forRuns(Collection<Long> runIds) {
        Map<Long, long[]> sizes = new HashMap<>();
        Map<Long, Long> expectedLines = new HashMap<>();
        Map<Long, Map<String, Long>> byRule = new HashMap<>();
        Map<Long, Map<String, Long>> byType = new HashMap<>();
        for (long runId : runIds) {
            sizes.put(runId, new long[2]);
            byRule.put(runId, new TreeMap<>());
            byType.put(runId, new TreeMap<>());
        }
        Long[] ids = runIds.toArray(Long[]::new);
        query("""
                SELECT run_id, 'ledger', count(*) FROM ledger_records WHERE run_id = ANY(?) GROUP BY run_id
                UNION ALL
                SELECT id, 'statement', statement_lines_received FROM runs WHERE id = ANY(?)
                """, ids, 2, (runId, key, count) -> sizes.get(runId)["ledger".equals(key) ? 0 : 1] = count);
        query("SELECT run_id, side, rows_accepted FROM imports WHERE run_id = ANY(?) AND side = 'STATEMENT'", ids, 1,
                (runId, side, accepted) -> expectedLines.put(runId, accepted));
        query("SELECT run_id, rule, count(*) FROM matches WHERE run_id = ANY(?) GROUP BY run_id, rule", ids, 1,
                (runId, rule, count) -> byRule.get(runId).put(rule, count));
        query("SELECT run_id, type, count(*) FROM breaks WHERE run_id = ANY(?) GROUP BY run_id, type", ids, 1,
                (runId, type, count) -> byType.get(runId).put(type, count));

        Map<Long, RunSummary> result = new HashMap<>();
        for (long runId : runIds) {
            long ledger = sizes.get(runId)[0];
            long lines = sizes.get(runId)[1];
            long matches = byRule.get(runId).values().stream().mapToLong(Long::longValue).sum();
            long breaks = byType.get(runId).values().stream().mapToLong(Long::longValue).sum();
            result.put(runId, new RunSummary(ledger, lines, expectedLines.get(runId), matches, byRule.get(runId),
                    breaks, byType.get(runId), matchRate(matches, ledger + lines)));
        }
        return result;
    }

    static Double matchRate(long matches, long records) {
        if (records == 0) {
            return null;
        }
        return BigDecimal.valueOf(2 * matches).divide(BigDecimal.valueOf(records), 4, RoundingMode.HALF_EVEN)
                .doubleValue();
    }

    private interface Row {
        void accept(long runId, String key, long count);
    }

    private void query(String sql, Long[] runIds, int parameters, Row row) {
        jdbc.query(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql);
            for (int i = 1; i <= parameters; i++) {
                ps.setArray(i, connection.createArrayOf("int8", runIds));
            }
            return ps;
        }, (RowCallbackHandler) rs -> row.accept(rs.getLong(1), rs.getString(2), rs.getLong(3)));
    }
}
