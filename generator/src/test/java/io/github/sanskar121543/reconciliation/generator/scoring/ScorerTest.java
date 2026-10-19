package io.github.sanskar121543.reconciliation.generator.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanskar121543.reconciliation.generator.Scenario;
import io.github.sanskar121543.reconciliation.generator.Truth;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScorerTest {

    private final Truth truth = new Truth(
            List.of(new Truth.Match("L-1", 1, Scenario.CLEAN, "EXACT"),
                    new Truth.Match("L-2", 2, Scenario.LOOKALIKE, "TOLERANCE")),
            List.of(new Truth.Break("MISSING_IN_BANK", "L-3", null, Scenario.LOOKALIKE),
                    new Truth.Break("MISSING_IN_LEDGER", null, 3, Scenario.MISSING_IN_LEDGER)));

    @Test
    void aPerfectPredictionScoresOneEverywhere() {
        Score score = Scorer.score(truth, new Predictions(
                List.of(new Predictions.Match("L-1", 1, "EXACT"), new Predictions.Match("L-2", 2, "TOLERANCE")),
                List.of(new Predictions.Break("MISSING_IN_BANK", "L-3", null),
                        new Predictions.Break("MISSING_IN_LEDGER", null, 3))));

        assertThat(score.matches()).isEqualTo(new Counts(2, 2, 2));
        assertThat(score.matches().precision()).isEqualTo(1.0);
        assertThat(score.breaks().recall()).isEqualTo(1.0);
        assertThat(score.expectedRuleUsed()).isEqualTo(new Score.RuleAgreement(2, 2));
        assertThat(score.scenarios().get("LOOKALIKE")).isEqualTo(new Score.ScenarioResult(2, 2));
    }

    @Test
    void pairingTheWrongRecordIsAFalseMatchAndAMissedOne() {
        // The lookalike mistake: the bank line went to L-3, so L-2 is reported missing instead.
        Score score = Scorer.score(truth, new Predictions(
                List.of(new Predictions.Match("L-1", 1, "EXACT"), new Predictions.Match("L-3", 2, "TOLERANCE")),
                List.of(new Predictions.Break("MISSING_IN_BANK", "L-2", null),
                        new Predictions.Break("MISSING_IN_LEDGER", null, 3))));

        assertThat(score.matches()).isEqualTo(new Counts(2, 2, 1));
        assertThat(score.matches().precision()).isEqualTo(0.5);
        assertThat(score.breaksByType().get("MISSING_IN_BANK")).isEqualTo(new Counts(1, 1, 0));
        assertThat(score.scenarios().get("LOOKALIKE")).isEqualTo(new Score.ScenarioResult(2, 0));
    }

    @Test
    void aBreakWithTheWrongTypeIsWrong() {
        Score score = Scorer.score(truth, new Predictions(List.of(),
                List.of(new Predictions.Break("DUPLICATE", null, 3))));

        assertThat(score.breaks()).isEqualTo(new Counts(1, 2, 0));
        assertThat(score.matches().precision()).isNull();
        assertThat(score.matches().recall()).isEqualTo(0.0);
    }
}
