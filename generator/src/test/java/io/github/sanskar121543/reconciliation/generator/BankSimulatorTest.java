package io.github.sanskar121543.reconciliation.generator;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanskar121543.reconciliation.generator.Dataset.LedgerRow;
import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class BankSimulatorTest {

    private final List<LedgerRow> ledger = Generator.generate(GeneratorConfig.defaults(5, 2_000)).ledger();

    @Test
    void readsTheLedgerCsvFormatBack() throws IOException {
        String csv = "external_id,account_id,booking_date,amount,currency,reference,description\n"
                + "PLS-E1,PLS-1,2026-10-16,-10000.00,USD,TRF-1,transfer\n";

        assertThat(BankSimulator.readLedger(new StringReader(csv))).containsExactly(new LedgerRow("PLS-E1", "PLS-1",
                LocalDate.of(2026, 10, 16), -1_000_000, "TRF-1", "transfer"));
    }

    @Test
    void accountsForEveryLedgerRecordAndEveryLineExactlyOnce() {
        Dataset bank = BankSimulator.simulate(ledger, 9, 100, 3);

        List<String> ledgerIds = new ArrayList<>();
        List<Integer> rows = new ArrayList<>();
        bank.truth().matches().forEach(m -> {
            ledgerIds.add(m.ledgerExternalId());
            rows.add(m.statementRow());
        });
        bank.truth().breaks().forEach(b -> {
            ledgerIds.add(b.ledgerExternalId());
            rows.add(b.statementRow());
        });
        assertThat(ledgerIds.stream().filter(Objects::nonNull))
                .containsExactlyInAnyOrderElementsOf(ledger.stream().map(LedgerRow::externalId).toList());
        assertThat(rows.stream().filter(Objects::nonNull)).hasSize(bank.statement().size()).doesNotHaveDuplicates();
    }

    @Test
    void isDeterministic() {
        assertThat(BankSimulator.simulate(ledger, 9, 100, 3).statementCsv())
                .isEqualTo(BankSimulator.simulate(ledger, 9, 100, 3).statementCsv());
    }

    @Test
    void neverWritesAZeroAmount() {
        assertThat(BankSimulator.simulate(ledger, 9, 100, 3).statement())
                .allSatisfy(r -> assertThat(r.amountCents()).isNotZero());
    }
}
