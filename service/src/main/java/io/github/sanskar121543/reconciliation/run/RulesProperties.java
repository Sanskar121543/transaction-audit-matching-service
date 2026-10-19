package io.github.sanskar121543.reconciliation.run;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Defaults for new runs; a run can override them when it is created. */
@ConfigurationProperties("matching.rules")
public record RulesProperties(
        @DefaultValue("100") long amountToleranceCents,
        @DefaultValue("3") int dateWindowDays) {
}
