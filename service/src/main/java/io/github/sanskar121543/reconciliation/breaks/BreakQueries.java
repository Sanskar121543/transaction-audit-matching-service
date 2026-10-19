package io.github.sanskar121543.reconciliation.breaks;

import io.github.sanskar121543.reconciliation.breaks.BreakView.LedgerSide;
import io.github.sanskar121543.reconciliation.breaks.BreakView.StatementSide;
import io.github.sanskar121543.reconciliation.matching.MatchStore.StoredTrace;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class BreakQueries {

    private static final String SELECT = """
            SELECT b.id, b.run_id, b.type, b.status, b.account_id, b.exposure_cents, b.item_date, b.created_at,
                   b.rule_trace,
                   l.id AS l_id, l.external_id, l.booking_date, l.amount_cents AS l_amount, l.reference AS l_reference,
                   l.description AS l_description,
                   s.id AS s_id, s.row_number, s.value_date, s.amount_cents AS s_amount, s.reference AS s_reference,
                   s.description AS s_description, s.bank_transaction_id
              FROM breaks b
              LEFT JOIN ledger_records l ON l.id = b.ledger_record_id
              LEFT JOIN statement_lines s ON s.id = b.statement_line_id
            """;

    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public BreakQueries(JdbcTemplate jdbc, JsonMapper jsonMapper, Clock clock) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    public List<BreakView> list(BreakFilter filter) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE true");
        List<Object> args = new ArrayList<>();
        if (filter.runId() != null) {
            sql.append(" AND b.run_id = ?");
            args.add(filter.runId());
        }
        if (filter.type() != null) {
            sql.append(" AND b.type = ?");
            args.add(filter.type().name());
        }
        if (filter.status() != null) {
            sql.append(" AND b.status = ?");
            args.add(filter.status().name());
        }
        if (filter.accountId() != null) {
            sql.append(" AND b.account_id = ?");
            args.add(filter.accountId());
        }
        if (filter.minExposureCents() != null) {
            sql.append(" AND b.exposure_cents >= ?");
            args.add(filter.minExposureCents());
        }
        if (filter.minAgeDays() != null) {
            sql.append(" AND b.item_date <= ?");
            args.add(Date.valueOf(today().minusDays(filter.minAgeDays())));
        }
        if (filter.afterId() != null) {
            sql.append(" AND b.id > ?");
            args.add(filter.afterId());
        }
        sql.append(" ORDER BY b.id LIMIT ?");
        args.add(filter.limit());
        LocalDate today = today();
        return jdbc.query(sql.toString(), (rs, i) -> view(rs, today), args.toArray());
    }

    public Optional<BreakDetail> get(long id) {
        LocalDate today = today();
        return jdbc.query(SELECT + " WHERE b.id = ?", (rs, i) -> new BreakDetail(view(rs, today),
                jsonMapper.readValue(rs.getString("rule_trace"), StoredTrace.class)), id).stream().findFirst();
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static BreakView view(ResultSet rs, LocalDate today) throws SQLException {
        LocalDate itemDate = rs.getDate("item_date").toLocalDate();
        long ageDays = ChronoUnit.DAYS.between(itemDate, today);
        LedgerSide ledger = rs.getObject("l_id") == null ? null : new LedgerSide(rs.getLong("l_id"),
                rs.getString("external_id"), rs.getDate("booking_date").toLocalDate(), rs.getLong("l_amount"),
                rs.getString("l_reference"), rs.getString("l_description"));
        StatementSide statement = rs.getObject("s_id") == null ? null : new StatementSide(rs.getLong("s_id"),
                rs.getInt("row_number"), rs.getDate("value_date").toLocalDate(), rs.getLong("s_amount"),
                rs.getString("s_reference"), rs.getString("s_description"), rs.getString("bank_transaction_id"));
        return new BreakView(rs.getLong("id"), rs.getLong("run_id"), BreakType.valueOf(rs.getString("type")),
                BreakStatus.valueOf(rs.getString("status")), rs.getString("account_id"), rs.getLong("exposure_cents"),
                itemDate, ageDays, AgingBucket.of(ageDays), ledger, statement,
                rs.getTimestamp("created_at").toInstant());
    }
}
