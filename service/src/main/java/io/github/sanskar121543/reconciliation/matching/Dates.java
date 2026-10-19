package io.github.sanskar121543.reconciliation.matching;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

final class Dates {

    private Dates() {
    }

    static long daysApart(LocalDate a, LocalDate b) {
        return Math.abs(ChronoUnit.DAYS.between(a, b));
    }
}
