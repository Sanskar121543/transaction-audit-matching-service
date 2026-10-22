package io.github.sanskar121543.reconciliation.dashboard;

import io.github.sanskar121543.reconciliation.breaks.AgingBucket;
import io.github.sanskar121543.reconciliation.breaks.BreakResolution;
import io.github.sanskar121543.reconciliation.breaks.BreakStatus;
import io.github.sanskar121543.reconciliation.breaks.BreakType;
import io.github.sanskar121543.reconciliation.dashboard.RunBreakdown.AccountRow;
import io.github.sanskar121543.reconciliation.dashboard.RunBreakdown.AgingRow;
import io.github.sanskar121543.reconciliation.dashboard.RunBreakdown.TypeRow;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

@Repository
public class Breakdowns {

    private static final int TOP_ACCOUNTS = 10;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public Breakdowns(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** One grouped query: a run has a few thousand distinct (type, status, resolution, date) groups at most. */
    public RunBreakdown forRun(long runId) {
        LocalDate today = LocalDate.now(clock);
        Map<BreakType, long[]> byType = new EnumMap<>(BreakType.class);
        for (BreakType type : BreakType.values()) {
            byType.put(type, new long[4]);
        }
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (BreakStatus status : BreakStatus.values()) {
            byStatus.put(status.name(), 0L);
        }
        Map<AgingBucket, long[]> byAge = new EnumMap<>(AgingBucket.class);
        for (AgingBucket bucket : AgingBucket.values()) {
            byAge.put(bucket, new long[BreakStatus.values().length]);
        }
        Map<String, Long> resolutions = new LinkedHashMap<>();
        for (BreakResolution resolution : BreakResolution.values()) {
            resolutions.put(resolution.name(), 0L);
        }

        jdbc.query("""
                SELECT type, status, resolution, item_date, count(*) AS breaks, sum(exposure_cents) AS exposure
                  FROM breaks WHERE run_id = ?
                 GROUP BY type, status, resolution, item_date
                """, (RowCallbackHandler) rs -> {
            BreakType type = BreakType.valueOf(rs.getString("type"));
            BreakStatus status = BreakStatus.valueOf(rs.getString("status"));
            long breaks = rs.getLong("breaks");
            long exposure = rs.getLong("exposure");
            long[] typeRow = byType.get(type);
            typeRow[0] += breaks;
            typeRow[1] += exposure;
            if (status != BreakStatus.RESOLVED) {
                typeRow[2] += breaks;
                typeRow[3] += exposure;
            } else {
                resolutions.merge(rs.getString("resolution"), breaks, Long::sum);
            }
            byStatus.merge(status.name(), breaks, Long::sum);
            long age = ChronoUnit.DAYS.between(rs.getDate("item_date").toLocalDate(), today);
            byAge.get(AgingBucket.of(age))[status.ordinal()] += breaks;
        }, runId);

        List<AccountRow> accounts = jdbc.query("""
                SELECT account_id, count(*) AS breaks, sum(exposure_cents) AS exposure
                  FROM breaks WHERE run_id = ? AND status <> 'RESOLVED'
                 GROUP BY account_id ORDER BY exposure DESC, account_id LIMIT ?
                """, (rs, i) -> new AccountRow(rs.getString("account_id"), rs.getLong("breaks"),
                rs.getLong("exposure")), runId, TOP_ACCOUNTS);

        List<TypeRow> types = new ArrayList<>();
        byType.forEach((type, row) -> types.add(new TypeRow(type, row[0], row[1], row[2], row[3])));
        List<AgingRow> ages = new ArrayList<>();
        byAge.forEach((bucket, row) -> ages.add(new AgingRow(bucket, bucket.label(),
                row[BreakStatus.OPEN.ordinal()], row[BreakStatus.INVESTIGATING.ordinal()],
                row[BreakStatus.RESOLVED.ordinal()])));
        return new RunBreakdown(runId, types, byStatus, ages, resolutions, accounts);
    }
}
