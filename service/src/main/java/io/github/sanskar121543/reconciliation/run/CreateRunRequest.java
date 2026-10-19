package io.github.sanskar121543.reconciliation.run;

import io.github.sanskar121543.reconciliation.matching.Tolerance;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CreateRunRequest(
        @NotBlank @Size(max = 100) String label,
        @PositiveOrZero @Max(Tolerance.MAX_AMOUNT_CENTS) Long amountToleranceCents,
        @PositiveOrZero @Max(Tolerance.MAX_DATE_WINDOW_DAYS) Integer dateWindowDays) {
}
