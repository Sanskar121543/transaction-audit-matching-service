package io.github.sanskar121543.reconciliation.breaks;

import io.github.sanskar121543.reconciliation.web.ApiCaller;
import io.github.sanskar121543.reconciliation.web.ApiKeyFilter;
import io.github.sanskar121543.reconciliation.web.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/breaks")
class BreakController {

    private final BreakQueries queries;
    private final BreakWorkflow workflow;

    BreakController(BreakQueries queries, BreakWorkflow workflow) {
        this.queries = queries;
        this.workflow = workflow;
    }

    @GetMapping
    List<BreakView> list(@RequestParam(required = false) Long runId,
            @RequestParam(required = false) BreakType type,
            @RequestParam(required = false) BreakStatus status,
            @RequestParam(required = false) @Pattern(regexp = BreakRequests.ASSIGNEE) String assignee,
            @RequestParam(required = false) @Pattern(regexp = "[A-Za-z0-9._-]{1,34}") String accountId,
            @RequestParam(required = false) @PositiveOrZero Long minExposureCents,
            @RequestParam(required = false) @Min(0) @Max(36_500) Integer minAgeDays,
            @RequestParam(required = false) @PositiveOrZero Long afterId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return queries.list(new BreakFilter(runId, type, status, assignee, accountId, minExposureCents, minAgeDays,
                afterId, limit));
    }

    @GetMapping("/{id}")
    BreakDetail get(@PathVariable long id) {
        return queries.get(id).orElseThrow(() -> notFound(id));
    }

    /** Who changed what and when, oldest first, including every note. */
    @GetMapping("/{id}/history")
    List<BreakEvent> history(@PathVariable long id,
            @RequestParam(defaultValue = "0") @PositiveOrZero long afterId,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        if (!queries.exists(id)) {
            throw notFound(id);
        }
        return queries.history(id, afterId, limit);
    }

    @PostMapping("/{id}/investigate")
    BreakView investigate(@PathVariable long id, @Valid @RequestBody BreakRequests.Investigate request,
            @RequestAttribute(ApiKeyFilter.CALLER_ATTRIBUTE) ApiCaller caller) {
        return workflow.investigate(id, request, caller);
    }

    @PostMapping("/{id}/resolve")
    BreakView resolve(@PathVariable long id, @Valid @RequestBody BreakRequests.Resolve request,
            @RequestAttribute(ApiKeyFilter.CALLER_ATTRIBUTE) ApiCaller caller) {
        return workflow.resolve(id, request, caller);
    }

    @PostMapping("/{id}/reopen")
    BreakView reopen(@PathVariable long id, @Valid @RequestBody BreakRequests.Reopen request,
            @RequestAttribute(ApiKeyFilter.CALLER_ATTRIBUTE) ApiCaller caller) {
        return workflow.reopen(id, request, caller);
    }

    @PostMapping("/{id}/assign")
    BreakView assign(@PathVariable long id, @Valid @RequestBody BreakRequests.Assign request,
            @RequestAttribute(ApiKeyFilter.CALLER_ATTRIBUTE) ApiCaller caller) {
        return workflow.assign(id, request, caller);
    }

    @PostMapping("/{id}/notes")
    ResponseEntity<BreakEvent> addNote(@PathVariable long id, @Valid @RequestBody BreakRequests.Note request,
            @RequestAttribute(ApiKeyFilter.CALLER_ATTRIBUTE) ApiCaller caller) {
        BreakEvent event = workflow.addNote(id, request, caller);
        return ResponseEntity.created(URI.create("/v1/breaks/" + id + "/history?afterId=" + (event.id() - 1) + "&limit=1"))
                .body(event);
    }

    private static NotFoundException notFound(long id) {
        return new NotFoundException("break-not-found", "No break with id " + id);
    }
}
