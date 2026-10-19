package io.github.sanskar121543.reconciliation.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanskar121543.reconciliation.generator.Dataset;
import io.github.sanskar121543.reconciliation.generator.Generator;
import io.github.sanskar121543.reconciliation.generator.GeneratorConfig;
import io.github.sanskar121543.reconciliation.generator.Scenario;
import io.github.sanskar121543.reconciliation.generator.Truth;
import io.github.sanskar121543.reconciliation.matching.ReferenceNormalizer;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.json.JsonMapper;

/** The generator lives in its own module; this checks its files against the service's real rules. */
class GeneratedFilesTest {

    private final Dataset data = Generator.generate(GeneratorConfig.defaults(7, 20_000));
    private final ImportParser parser = new ImportParser(
            new IngestProperties(200_000, DataSize.ofMegabytes(32), 1000), JsonMapper.builder().build());

    @Test
    void everyGeneratedRowPassesValidation() {
        ParsedFile<LedgerRecordInput> ledger = parser.ledger(stream(data.ledgerCsv()), Format.CSV);
        ParsedFile<StatementLineInput> statement = parser.statement(stream(data.statementCsv()), Format.CSV);

        assertThat(ledger.rejections()).isEmpty();
        assertThat(statement.rejections()).isEmpty();
        assertThat(ledger.accepted()).hasSize(data.ledger().size());
        assertThat(statement.accepted()).hasSize(data.statement().size());
    }

    @Test
    void reformattedReferencesNormaliseToTheLedgersReference() {
        Map<String, String> ledgerReference = data.ledger().stream()
                .collect(Collectors.toMap(Dataset.LedgerRow::externalId, Dataset.LedgerRow::reference));
        Map<Integer, String> lineReference = data.statement().stream()
                .collect(Collectors.toMap(Dataset.StatementRow::row, Dataset.StatementRow::reference));
        Set<Scenario> reformatted = EnumSet.of(Scenario.REFORMATTED_ON_TIME, Scenario.REFORMATTED_LATE);
        Function<Truth.Match, Boolean> agrees = m -> ReferenceNormalizer.normalize(ledgerReference.get(m.ledgerExternalId()))
                .equals(ReferenceNormalizer.normalize(lineReference.get(m.statementRow())));

        assertThat(data.truth().matches().stream().filter(m -> reformatted.contains(m.scenario())))
                .isNotEmpty()
                .allSatisfy(m -> {
                    assertThat(agrees.apply(m)).as(m.toString()).isTrue();
                    assertThat(ledgerReference.get(m.ledgerExternalId())).isNotEqualTo(lineReference.get(m.statementRow()));
                });
    }

    private static ByteArrayInputStream stream(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }
}
