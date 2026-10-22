package io.github.sanskar121543.reconciliation.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanskar121543.reconciliation.support.TestcontainersConfiguration;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Runs completed before statement lines went through Kafka stored their lines in the upload and kept no
 * count of them. Upgrading must count them, or those runs would report 0 lines and a wrong match rate.
 */
class KafkaMigrationIT {

    @Test
    void upgradingCountsTheLinesOfRunsCompletedBefore() {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(
                DockerImageName.parse(TestcontainersConfiguration.POSTGRES_IMAGE))) {
            postgres.start();
            SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new org.postgresql.Driver(),
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            Flyway.configure().dataSource(dataSource).target("3").load().migrate();
            long runId = jdbc.queryForObject("""
                    INSERT INTO runs (label, status, amount_tolerance_cents, date_window_days, created_by, completed_at)
                    VALUES ('before kafka', 'COMPLETED', 100, 3, 'ops', now()) RETURNING id
                    """, Long.class);
            jdbc.update("""
                    INSERT INTO imports (run_id, side, sha256, rows_total, rows_accepted, rows_rejected, rejections,
                                         rejections_truncated, ignored_columns, imported_by)
                    VALUES (?, 'STATEMENT', ?, 2, 2, 0, '[]', false, '[]', 'ops')
                    """, runId, "a".repeat(64));
            for (int row = 1; row <= 2; row++) {
                jdbc.update("""
                        INSERT INTO statement_lines (run_id, row_number, account_id, value_date, amount_cents, currency,
                                                     reference, normalized_reference, description, ingested_by)
                        VALUES (?, ?, 'ACC-1', '2026-10-01', 100, 'USD', 'R', 'R', '', 'local')
                        """, runId, row);
            }

            Flyway.configure().dataSource(dataSource).load().migrate();

            Map<String, Object> run = jdbc.queryForMap(
                    "SELECT statement_lines_received, statement_queued_at FROM runs WHERE id = ?", runId);
            assertThat(run.get("statement_lines_received")).isEqualTo(2);
            assertThat(run.get("statement_queued_at")).isNotNull();
        }
    }
}
