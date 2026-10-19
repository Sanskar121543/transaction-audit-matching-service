package io.github.sanskar121543.reconciliation.breaks;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.sanskar121543.reconciliation.matching.MatchStore.StoredTrace;

/** A break and the explanation of why its records did not match: the verdict and every rule that was tried. */
public record BreakDetail(@JsonProperty("break") BreakView breakView, StoredTrace explanation) {
}
