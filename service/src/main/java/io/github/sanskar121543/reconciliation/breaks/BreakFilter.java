package io.github.sanskar121543.reconciliation.breaks;

/** Every field is optional; {@code afterId} pages through results in id order. */
public record BreakFilter(Long runId, BreakType type, BreakStatus status, String accountId, Long minExposureCents,
        Integer minAgeDays, Long afterId, int limit) {
}
