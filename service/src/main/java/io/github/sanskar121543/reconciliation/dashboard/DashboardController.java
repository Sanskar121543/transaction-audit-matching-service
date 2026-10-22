package io.github.sanskar121543.reconciliation.dashboard;

import io.github.sanskar121543.reconciliation.run.RunRepository;
import io.github.sanskar121543.reconciliation.web.NotFoundException;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** The dashboard's data. Both are reads, so the read-only key may call them too. */
@RestController
class DashboardController {

    private final DashboardStream stream;
    private final Breakdowns breakdowns;
    private final RunRepository runs;

    DashboardController(DashboardStream stream, Breakdowns breakdowns, RunRepository runs) {
        this.stream = stream;
        this.breakdowns = breakdowns;
        this.runs = runs;
    }

    /** {@code event: runs}: the latest runs with their summaries, sent at once and then whenever it changes. */
    @GetMapping(path = "/v1/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> stream() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                // nginx would otherwise buffer the stream and deliver events in bursts.
                .header("X-Accel-Buffering", "no")
                .body(stream.subscribe());
    }

    @GetMapping("/v1/runs/{id}/breakdown")
    RunBreakdown breakdown(@PathVariable long id) {
        if (!runs.existsById(id)) {
            throw new NotFoundException("run-not-found", "No run with id " + id);
        }
        return breakdowns.forRun(id);
    }
}
