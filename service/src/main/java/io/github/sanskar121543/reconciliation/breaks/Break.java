package io.github.sanskar121543.reconciliation.breaks;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A break's workflow: who owns it and how far it has got. Everything else about a break is a fact the end-of-run
 * step recorded; it is not mapped here, and the database refuses to change it. Breaks are only ever inserted by
 * that step, never through this entity.
 */
@Entity
@Table(name = "breaks")
public class Break {

    @Id
    private Long id;

    @Enumerated(EnumType.STRING)
    private BreakStatus status;

    private String assignee;

    @Enumerated(EnumType.STRING)
    private BreakResolution resolution;

    // The database raises it on every change as well, so a change made outside this service also makes a
    // request based on an older read fail instead of overwriting it.
    @Version
    private long version;

    protected Break() {
    }

    /** OPEN → INVESTIGATING, owned by {@code assignee}. */
    public void startInvestigating(String assignee) {
        require(BreakStatus.OPEN);
        this.status = BreakStatus.INVESTIGATING;
        this.assignee = assignee;
    }

    /** INVESTIGATING → RESOLVED. */
    public void resolve(BreakResolution resolution) {
        require(BreakStatus.INVESTIGATING);
        this.status = BreakStatus.RESOLVED;
        this.resolution = resolution;
    }

    /** RESOLVED → INVESTIGATING, still owned by whoever resolved it unless reassigned. */
    public void reopen() {
        require(BreakStatus.RESOLVED);
        this.status = BreakStatus.INVESTIGATING;
        this.resolution = null;
    }

    public void assign(String assignee) {
        if (status == BreakStatus.RESOLVED) {
            throw new IllegalStateException("Break " + id + " is RESOLVED");
        }
        this.assignee = assignee;
    }

    private void require(BreakStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Break " + id + " is " + status + ", not " + expected);
        }
    }

    public Long getId() {
        return id;
    }

    public BreakStatus getStatus() {
        return status;
    }

    public String getAssignee() {
        return assignee;
    }

    public long getVersion() {
        return version;
    }
}
