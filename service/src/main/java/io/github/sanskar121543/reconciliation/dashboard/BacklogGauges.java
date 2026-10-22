package io.github.sanskar121543.reconciliation.dashboard;

import io.github.sanskar121543.reconciliation.breaks.BreakStatus;
import io.github.sanskar121543.reconciliation.run.RunStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * How much work is waiting, as gauges: breaks and their exposure by status, and runs by status. They describe
 * the database, not this instance, so every instance reports the same values: chart them with max(), not
 * sum(). Refreshed every 15 seconds, the Prometheus scrape interval's order, instead of querying per scrape.
 */
@Component
class BacklogGauges {

    private static final Logger log = LoggerFactory.getLogger(BacklogGauges.class);

    private final JdbcTemplate jdbc;
    private final Map<BreakStatus, AtomicLong> breaks = new EnumMap<>(BreakStatus.class);
    private final Map<BreakStatus, AtomicLong> exposure = new EnumMap<>(BreakStatus.class);
    private final Map<RunStatus, AtomicLong> runs = new EnumMap<>(RunStatus.class);

    BacklogGauges(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        for (BreakStatus status : BreakStatus.values()) {
            breaks.put(status, register(registry, "matching.breaks.backlog", "Breaks in each workflow status",
                    status.name()));
            exposure.put(status, register(registry, "matching.breaks.backlog.exposure.cents",
                    "Total exposure of the breaks in each workflow status, in cents", status.name()));
        }
        for (RunStatus status : RunStatus.values()) {
            runs.put(status, register(registry, "matching.runs", "Runs in each status", status.name()));
        }
    }

    @Scheduled(fixedDelay = 15_000)
    void refresh() {
        try {
            Map<BreakStatus, long[]> breakRows = new EnumMap<>(BreakStatus.class);
            jdbc.query("SELECT status, count(*), coalesce(sum(exposure_cents), 0) FROM breaks GROUP BY status",
                    (RowCallbackHandler) rs -> breakRows.put(BreakStatus.valueOf(rs.getString(1)),
                            new long[] {rs.getLong(2), rs.getLong(3)}));
            Map<RunStatus, Long> runRows = new EnumMap<>(RunStatus.class);
            jdbc.query("SELECT status, count(*) FROM runs GROUP BY status",
                    (RowCallbackHandler) rs -> runRows.put(RunStatus.valueOf(rs.getString(1)), rs.getLong(2)));
            breaks.forEach((status, gauge) -> gauge.set(breakRows.getOrDefault(status, new long[2])[0]));
            exposure.forEach((status, gauge) -> gauge.set(breakRows.getOrDefault(status, new long[2])[1]));
            runs.forEach((status, gauge) -> gauge.set(runRows.getOrDefault(status, 0L)));
        } catch (DataAccessException e) {
            // Keep the last values; the database being down shows up in the health check and the logs elsewhere.
            log.debug("Backlog gauges not refreshed: {}", e.getMessage());
        }
    }

    private static AtomicLong register(MeterRegistry registry, String name, String description, String status) {
        AtomicLong value = new AtomicLong();
        Gauge.builder(name, value, AtomicLong::get).description(description).tag("status", status)
                .strongReference(true).register(registry);
        return value;
    }
}
