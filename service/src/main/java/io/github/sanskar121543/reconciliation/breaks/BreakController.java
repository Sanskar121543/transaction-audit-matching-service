package io.github.sanskar121543.reconciliation.breaks;

import io.github.sanskar121543.reconciliation.web.NotFoundException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/breaks")
class BreakController {

    private final BreakQueries queries;

    BreakController(BreakQueries queries) {
        this.queries = queries;
    }

    @GetMapping
    List<BreakView> list(@RequestParam(required = false) Long runId,
            @RequestParam(required = false) BreakType type,
            @RequestParam(required = false) BreakStatus status,
            @RequestParam(required = false) @Pattern(regexp = "[A-Za-z0-9._-]{1,34}") String accountId,
            @RequestParam(required = false) @PositiveOrZero Long minExposureCents,
            @RequestParam(required = false) @Min(0) @Max(36_500) Integer minAgeDays,
            @RequestParam(required = false) @PositiveOrZero Long afterId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return queries.list(new BreakFilter(runId, type, status, accountId, minExposureCents, minAgeDays, afterId,
                limit));
    }

    @GetMapping("/{id}")
    BreakDetail get(@PathVariable long id) {
        return queries.get(id).orElseThrow(() -> new NotFoundException("break-not-found", "No break with id " + id));
    }
}
