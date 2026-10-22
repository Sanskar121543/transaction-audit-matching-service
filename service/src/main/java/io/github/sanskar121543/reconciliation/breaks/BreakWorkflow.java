package io.github.sanskar121543.reconciliation.breaks;

import io.github.sanskar121543.reconciliation.web.ApiCaller;
import io.github.sanskar121543.reconciliation.web.ConflictException;
import io.github.sanskar121543.reconciliation.web.CorrelationIdFilter;
import io.github.sanskar121543.reconciliation.web.NotFoundException;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OPEN → INVESTIGATING → RESOLVED, and RESOLVED → INVESTIGATING to reopen. The database writes the audit trail
 * itself (V5 triggers); each transaction here first names its caller, without which the database refuses the
 * change.
 */
@Service
public class BreakWorkflow {

    private final BreakRepository breaks;
    private final BreakNoteRepository notes;
    private final BreakQueries queries;
    private final JdbcTemplate jdbc;

    public BreakWorkflow(BreakRepository breaks, BreakNoteRepository notes, BreakQueries queries, JdbcTemplate jdbc) {
        this.breaks = breaks;
        this.notes = notes;
        this.queries = queries;
        this.jdbc = jdbc;
    }

    @Transactional
    public BreakView investigate(long id, BreakRequests.Investigate request, ApiCaller caller) {
        Break b = load(id, request.version(), caller);
        require(b, BreakStatus.OPEN, "start investigating");
        b.startInvestigating(Objects.requireNonNullElse(request.assignee(),
                Objects.requireNonNullElse(b.getAssignee(), caller.clientId())));
        return saved(b, request.note(), caller);
    }

    @Transactional
    public BreakView resolve(long id, BreakRequests.Resolve request, ApiCaller caller) {
        Break b = load(id, request.version(), caller);
        require(b, BreakStatus.INVESTIGATING, "resolve");
        b.resolve(request.resolution());
        return saved(b, request.note(), caller);
    }

    @Transactional
    public BreakView reopen(long id, BreakRequests.Reopen request, ApiCaller caller) {
        Break b = load(id, request.version(), caller);
        require(b, BreakStatus.RESOLVED, "reopen");
        b.reopen();
        return saved(b, request.note(), caller);
    }

    @Transactional
    public BreakView assign(long id, BreakRequests.Assign request, ApiCaller caller) {
        Break b = load(id, request.version(), caller);
        if (b.getStatus() == BreakStatus.RESOLVED) {
            throw new ConflictException("break-resolved", "Break " + id + " is RESOLVED; reopen it before reassigning it");
        }
        b.assign(request.assignee());
        return saved(b, null, caller);
    }

    /** A note changes nothing about the break, so it needs no version: notes never conflict. */
    @Transactional
    public BreakEvent addNote(long id, BreakRequests.Note request, ApiCaller caller) {
        nameCaller(caller);
        if (!breaks.existsById(id)) {
            throw notFound(id);
        }
        BreakNote note = notes.saveAndFlush(new BreakNote(id, caller.clientId(), request.body().strip()));
        return queries.noteEvent(note.getId()).orElseThrow();
    }

    private Break load(long id, long version, ApiCaller caller) {
        nameCaller(caller);
        Break b = breaks.findById(id).orElseThrow(() -> notFound(id));
        // A concurrent change that commits after this check still loses: the UPDATE is conditional on the
        // version, and Hibernate turns no row updated into a 409 (ApiExceptionHandler).
        if (b.getVersion() != version) {
            throw new ConflictException("break-changed", "Break " + id + " is at version " + b.getVersion()
                    + ", not " + version + ": it changed since you read it. Read it again.");
        }
        return b;
    }

    /**
     * The triggers record this caller and request as the change's author. set_config(..., true) is SET LOCAL
     * with parameters: both end with the transaction, so a pooled connection never carries them into the next.
     */
    private void nameCaller(ApiCaller caller) {
        jdbc.queryForList("SELECT set_config('matching.actor', ?, true), set_config('matching.correlation_id', ?, true)",
                caller.clientId(), Objects.requireNonNullElse(CorrelationIdFilter.current(), ""));
    }

    private BreakView saved(Break b, String note, ApiCaller caller) {
        // The status change first, then its note, so the trail reads in that order.
        breaks.flush();
        if (note != null) {
            notes.saveAndFlush(new BreakNote(b.getId(), caller.clientId(), note.strip()));
        }
        return queries.view(b.getId()).orElseThrow();
    }

    private static void require(Break b, BreakStatus expected, String action) {
        if (b.getStatus() != expected) {
            throw new ConflictException("break-transition", "Cannot " + action + " break " + b.getId() + ": it is "
                    + b.getStatus() + ", not " + expected);
        }
    }

    private static NotFoundException notFound(long id) {
        return new NotFoundException("break-not-found", "No break with id " + id);
    }
}
