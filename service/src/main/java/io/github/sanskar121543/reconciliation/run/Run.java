package io.github.sanskar121543.reconciliation.run;

import io.github.sanskar121543.reconciliation.matching.Tolerance;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "runs")
public class Run {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String label;

    @Enumerated(EnumType.STRING)
    private RunStatus status;

    @Column(name = "amount_tolerance_cents")
    private long amountToleranceCents;

    @Column(name = "date_window_days")
    private int dateWindowDays;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    // Written with plain SQL, by the upload and the workers, while other transactions may hold this entity:
    // read-only here, so flushing a stale copy can never overwrite them.
    @Column(name = "statement_queued_at", insertable = false, updatable = false)
    private Instant statementQueuedAt;

    @Column(name = "matching_problem", insertable = false, updatable = false)
    private String matchingProblem;

    @Column(name = "matching_problem_at", insertable = false, updatable = false)
    private Instant matchingProblemAt;

    protected Run() {
    }

    public Run(String label, Tolerance tolerance, String createdBy) {
        this.label = label;
        this.status = RunStatus.RECEIVING;
        this.amountToleranceCents = tolerance.amountCents();
        this.dateWindowDays = tolerance.dateWindowDays();
        this.createdBy = createdBy;
        // Postgres stores microseconds; truncating keeps the 201 body equal to a later GET.
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** The statement is recorded and its lines are queued for the workers. */
    public void startMatching() {
        if (status != RunStatus.RECEIVING) {
            throw new IllegalStateException("Run " + id + " is " + status + ", not RECEIVING");
        }
        status = RunStatus.MATCHING;
    }

    public void complete(Instant at) {
        if (status == RunStatus.COMPLETED) {
            throw new IllegalStateException("Run " + id + " is already completed");
        }
        status = RunStatus.COMPLETED;
        completedAt = at.truncatedTo(ChronoUnit.MICROS);
    }

    public Tolerance tolerance() {
        return new Tolerance(amountToleranceCents, dateWindowDays);
    }

    public Long getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public RunStatus getStatus() {
        return status;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getStatementQueuedAt() {
        return statementQueuedAt;
    }

    public String getMatchingProblem() {
        return matchingProblem;
    }

    public Instant getMatchingProblemAt() {
        return matchingProblemAt;
    }
}
