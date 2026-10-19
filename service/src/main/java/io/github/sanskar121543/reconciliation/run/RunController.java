package io.github.sanskar121543.reconciliation.run;

import io.github.sanskar121543.reconciliation.matching.Tolerance;
import io.github.sanskar121543.reconciliation.web.ApiCaller;
import io.github.sanskar121543.reconciliation.web.ApiKeyFilter;
import io.github.sanskar121543.reconciliation.web.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Limit;
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
@RequestMapping("/v1/runs")
class RunController {

    private final RunRepository runs;
    private final RunSummaries summaries;
    private final RulesProperties defaults;

    RunController(RunRepository runs, RunSummaries summaries, RulesProperties defaults) {
        this.runs = runs;
        this.summaries = summaries;
        this.defaults = defaults;
    }

    @PostMapping
    ResponseEntity<RunResponse> create(@Valid @RequestBody CreateRunRequest request,
            @RequestAttribute(ApiKeyFilter.CALLER_ATTRIBUTE) ApiCaller caller) {
        Tolerance tolerance = new Tolerance(
                request.amountToleranceCents() == null ? defaults.amountToleranceCents() : request.amountToleranceCents(),
                request.dateWindowDays() == null ? defaults.dateWindowDays() : request.dateWindowDays());
        Run run = runs.save(new Run(request.label().strip(), tolerance, caller.clientId()));
        return ResponseEntity.created(URI.create("/v1/runs/" + run.getId()))
                .body(RunResponse.of(run, summaries.forRun(run.getId())));
    }

    @GetMapping
    List<RunResponse> list(@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        List<Run> recent = runs.findAllByOrderByIdDesc(Limit.of(limit));
        Map<Long, RunSummary> byId = summaries.forRuns(recent.stream().map(Run::getId).toList());
        return recent.stream().map(run -> RunResponse.of(run, byId.get(run.getId()))).toList();
    }

    @GetMapping("/{id}")
    RunResponse get(@PathVariable long id) {
        Run run = runs.findById(id).orElseThrow(() -> new NotFoundException("run-not-found", "No run with id " + id));
        return RunResponse.of(run, summaries.forRun(id));
    }
}
