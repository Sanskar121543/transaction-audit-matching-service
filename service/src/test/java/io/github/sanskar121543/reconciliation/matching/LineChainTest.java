package io.github.sanskar121543.reconciliation.matching;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanskar121543.reconciliation.ingest.StatementLineMessage;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LineChainTest {

    // Account A has rows 1, 3, 4; account B has rows 2, 5.
    static final StatementLineMessage A1 = line(1, 0, "A");
    static final StatementLineMessage B2 = line(2, 0, "B");
    static final StatementLineMessage A3 = line(3, 1, "A");
    static final StatementLineMessage A4 = line(4, 3, "A");
    static final StatementLineMessage B5 = line(5, 2, "B");

    @Test
    void aWholeFileInOrderIsReadyInRowOrder() {
        LineChain.Selection selection = LineChain.select(List.of(A1, B2, A3, A4, B5), Set.of());

        assertThat(rows(selection.ready())).containsExactly(1, 2, 3, 4, 5);
        assertThat(selection.alreadyStored()).isZero();
        assertThat(selection.predecessorMissing()).isZero();
    }

    @Test
    void aLineWhosePredecessorIsStoredIsReady() {
        LineChain.Selection selection = LineChain.select(List.of(A4, B5), Set.of(1, 2, 3));

        assertThat(rows(selection.ready())).containsExactly(4, 5);
    }

    @Test
    void storedLinesAndLinesDeliveredTwiceAreSkipped() {
        // A redelivered batch, then a second upload of the same file interleaved with the first.
        LineChain.Selection selection = LineChain.select(List.of(A1, A1, B2, A3, B2, A3, A4), Set.of(1, 2));

        assertThat(rows(selection.ready())).containsExactly(3, 4);
        assertThat(selection.alreadyStored()).isEqualTo(5);
    }

    @Test
    void aLineThatArrivesBeforeItsPredecessorIsDropped() {
        // Row 3 was lost in a failed send; rows 4 and 5 got through. B is unaffected.
        LineChain.Selection selection = LineChain.select(List.of(A1, B2, A4, B5), Set.of());

        assertThat(rows(selection.ready())).containsExactly(1, 2, 5);
        assertThat(selection.predecessorMissing()).isEqualTo(1);
    }

    @Test
    void theMissingLineArrivingLaterUnblocksTheChain() {
        // Row 4 was dropped earlier; the re-sent file brings 3 and 4 again.
        LineChain.Selection selection = LineChain.select(List.of(A1, B2, A3, A4, B5), Set.of(1, 2, 5));

        assertThat(rows(selection.ready())).containsExactly(3, 4);
        assertThat(selection.alreadyStored()).isEqualTo(3);
    }

    @Test
    void rowsToCheckAreTheDeliveredRowsAndTheirPredecessors() {
        assertThat(LineChain.rowsToCheck(List.of(A4, B5, A1))).containsExactlyInAnyOrder(4, 3, 5, 2, 1);
    }

    private static List<Integer> rows(List<StatementLineMessage> lines) {
        return lines.stream().map(StatementLineMessage::row).toList();
    }

    private static StatementLineMessage line(int row, int previousRow, String account) {
        return new StatementLineMessage(1, "sha", row, previousRow, account, LocalDate.of(2026, 10, 1), 100, "R" + row,
                "", null);
    }
}
