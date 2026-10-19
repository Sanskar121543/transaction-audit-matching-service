package io.github.sanskar121543.reconciliation.generator;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanskar121543.reconciliation.generator.Dataset.LedgerRow;
import io.github.sanskar121543.reconciliation.generator.Dataset.StatementRow;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class GeneratorTest {

    private final Dataset data = Generator.generate(GeneratorConfig.defaults(42, 5_000));

    @Test
    void theSameSeedGivesTheSameFilesAndAnotherSeedDoesNot() {
        Dataset again = Generator.generate(GeneratorConfig.defaults(42, 5_000));
        Dataset other = Generator.generate(GeneratorConfig.defaults(43, 5_000));

        assertThat(again.ledgerCsv()).isEqualTo(data.ledgerCsv());
        assertThat(again.statementCsv()).isEqualTo(data.statementCsv());
        assertThat(again.truth()).isEqualTo(data.truth());
        assertThat(other.statementCsv()).isNotEqualTo(data.statementCsv());
    }

    @Test
    void everyRecordAndEveryLineIsAccountedForExactlyOnceInTheTruth() {
        List<String> ledgerIds = new ArrayList<>();
        List<Integer> rows = new ArrayList<>();
        data.truth().matches().forEach(m -> {
            ledgerIds.add(m.ledgerExternalId());
            rows.add(m.statementRow());
        });
        data.truth().breaks().forEach(b -> {
            ledgerIds.add(b.ledgerExternalId());
            rows.add(b.statementRow());
        });

        assertThat(ledgerIds.stream().filter(Objects::nonNull))
                .containsExactlyInAnyOrderElementsOf(data.ledger().stream().map(LedgerRow::externalId).toList());
        assertThat(rows.stream().filter(Objects::nonNull))
                .containsExactlyInAnyOrderElementsOf(data.statement().stream().map(StatementRow::row).toList());
    }

    @Test
    void injectsEveryScenarioRoughlyInProportion() {
        Map<Scenario, Integer> seen = new EnumMap<>(Scenario.class);
        data.truth().matches().forEach(m -> seen.merge(m.scenario(), 1, Integer::sum));
        data.truth().breaks().stream().filter(b -> b.scenario() != Scenario.DUPLICATE_IN_BANK
                        && b.scenario() != Scenario.DUPLICATE_IN_LEDGER && b.scenario() != Scenario.LOOKALIKE)
                .forEach(b -> seen.merge(b.scenario(), 1, Integer::sum));

        assertThat(seen.keySet()).containsExactlyInAnyOrder(Scenario.values());
        assertThat(Arrays.stream(Scenario.values()).mapToInt(Scenario::weightPerThousand).sum()).isEqualTo(1000);
        assertThat(seen.get(Scenario.CLEAN)).isBetween(3_800, 4_200);
    }

    @Test
    void producesValuesTheServiceAccepts() {
        assertThat(data.ledger()).allSatisfy(r -> {
            assertThat(r.amountCents()).isNotZero();
            assertThat(Math.abs(r.amountCents())).isLessThanOrEqualTo(2_500_000);
            assertThat(r.reference()).hasSizeLessThanOrEqualTo(64);
        });
        assertThat(data.statement()).allSatisfy(r -> assertThat(r.amountCents()).isNotZero());
        assertThat(data.ledger().stream().map(LedgerRow::externalId).distinct()).hasSize(data.ledger().size());
    }

    @Test
    void rowsAreNumberedInFileOrderAndSortedByDate() {
        for (int i = 0; i < data.statement().size(); i++) {
            assertThat(data.statement().get(i).row()).isEqualTo(i + 1);
        }
        assertThat(data.statement()).isSortedAccordingTo(
                (a, b) -> a.valueDate().compareTo(b.valueDate()));
        assertThat(data.statementCsv().lines().count()).isEqualTo(data.statement().size() + 1);
    }
}
