package io.github.sanskar121543.reconciliation.matching;

import io.github.sanskar121543.reconciliation.run.RunRepository;
import io.github.sanskar121543.reconciliation.web.NotFoundException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MatchController {

    private final JdbcTemplate jdbc;
    private final RunRepository runs;

    MatchController(JdbcTemplate jdbc, RunRepository runs) {
        this.jdbc = jdbc;
        this.runs = runs;
    }

    /** Every match of a run, in id order; page with {@code afterId}. */
    @GetMapping("/v1/runs/{runId}/matches")
    List<MatchView> list(@PathVariable long runId, @RequestParam(defaultValue = "0") @PositiveOrZero long afterId,
            @RequestParam(defaultValue = "500") @Min(1) @Max(1000) int limit) {
        if (!runs.existsById(runId)) {
            throw new NotFoundException("run-not-found", "No run with id " + runId);
        }
        return jdbc.query("""
                SELECT m.id, m.account_id, m.rule, l.id, l.external_id, s.id, s.row_number, m.matched_by, m.created_at
                  FROM matches m
                  JOIN ledger_records l ON l.id = m.ledger_record_id
                  JOIN statement_lines s ON s.id = m.statement_line_id
                 WHERE m.run_id = ? AND m.id > ?
                 ORDER BY m.id
                 LIMIT ?
                """, (rs, i) -> new MatchView(rs.getLong(1), rs.getString(2), Rule.valueOf(rs.getString(3)),
                rs.getLong(4), rs.getString(5), rs.getLong(6), rs.getInt(7), rs.getString(8),
                rs.getTimestamp(9).toInstant()), runId, afterId, limit);
    }
}
