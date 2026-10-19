package io.github.sanskar121543.reconciliation.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.json.JsonMapper;

class ImportParserTest {

    private static final String LEDGER_HEADER = "external_id,account_id,booking_date,amount,currency,reference,description\n";
    private static final String STATEMENT_HEADER = "account_id,value_date,amount,reference,description\n";

    private final ImportParser parser = parser(10, DataSize.ofKilobytes(64));

    @Test
    void readsAValidLedgerFile() {
        ParsedFile<LedgerRecordInput> file = ledger(LEDGER_HEADER
                + "L-1,ACC-1,2026-10-01,1234.56,USD,INV-1,\"Acme, Inc.\"\n"
                + "L-2,ACC-1,2026-10-02,-0.05,,,\n");

        assertThat(file.rowsTotal()).isEqualTo(2);
        assertThat(file.rowsRejected()).isZero();
        assertThat(file.accepted()).containsExactly(
                new LedgerRecordInput(1, "L-1", "ACC-1", LocalDate.of(2026, 10, 1), 123456, "INV-1", "Acme, Inc."),
                new LedgerRecordInput(2, "L-2", "ACC-1", LocalDate.of(2026, 10, 2), -5, "", ""));
        assertThat(file.sha256()).hasSize(64);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "L-1,ACC-1,2026-10-01,12.345,USD,R,|amount",
            "L-1,ACC-1,2026-10-01,\"1,234.00\",USD,R,|amount",
            "L-1,ACC-1,2026-10-01,0.00,USD,R,|amount",
            "L-1,ACC-1,2026-10-01,1000000000.01,USD,R,|amount",
            "L-1,ACC-1,2026-10-01,12,EUR,R,|currency",
            "L-1,ACC-1,01/10/2026,12,USD,R,|booking_date",
            "L-1,ACC-1,1999-12-31,12,USD,R,|booking_date",
            "L-1,,2026-10-01,12,USD,R,|account_id",
            "L 1,ACC-1,2026-10-01,12,USD,R,|external_id",
            ",ACC-1,2026-10-01,12,USD,R,|external_id",
    })
    void rejectsABadField(String row, String field) {
        ParsedFile<LedgerRecordInput> file = ledger(LEDGER_HEADER + row + "\n");

        assertThat(file.accepted()).isEmpty();
        assertThat(file.rowsRejected()).isEqualTo(1);
        assertThat(file.rejections()).extracting(Rejection::field).containsExactly(field);
        assertThat(file.rejections()).extracting(Rejection::row).containsExactly(1);
    }

    @Test
    void rejectsAReferenceThatIsTooLongOrHasControlCharacters() {
        ParsedFile<LedgerRecordInput> file = ledger(LEDGER_HEADER
                + "L-1,ACC-1,2026-10-01,1.00,USD," + "R".repeat(65) + ",\n"
                + "L-2,ACC-1,2026-10-01,1.00,USD,\"A\u0007B\",\n");

        assertThat(file.rejections()).extracting(Rejection::reason)
                .containsExactly("must be at most 64 characters", "must not contain control characters");
    }

    @Test
    void acceptsTheGoodRowsAndReportsEveryBadOne() {
        ParsedFile<LedgerRecordInput> file = ledger(LEDGER_HEADER
                + "L-1,ACC-1,2026-10-01,1.00,USD,R1,\n"
                + "L-2,ACC-1,not-a-date,abc,USD,R2,\n"
                + "L-1,ACC-1,2026-10-03,3.00,USD,R3,\n"
                + "L-4,ACC-1,2026-10-04,4.00,USD,R4\n");

        assertThat(file.accepted()).extracting(LedgerRecordInput::externalId).containsExactly("L-1");
        assertThat(file.rowsRejected()).isEqualTo(3);
        assertThat(file.rejections()).extracting(r -> r.row() + ":" + r.field()).containsExactly(
                "2:booking_date", "2:amount", "3:external_id", "4:null");
    }

    @Test
    void capsTheReportedRejectionsButCountsThemAll() {
        ImportParser smallReport = new ImportParser(new IngestProperties(10, DataSize.ofKilobytes(64), 2),
                JsonMapper.builder().build());
        ParsedFile<StatementLineInput> file = smallReport.statement(stream(STATEMENT_HEADER
                + "ACC-1,x,1.00,R,\nACC-1,x,1.00,R,\nACC-1,x,1.00,R,\n"), Format.CSV);

        assertThat(file.rowsRejected()).isEqualTo(3);
        assertThat(file.rejections()).hasSize(2);
        assertThat(file.rejectionsTruncated()).isTrue();
    }

    @Test
    void headersAreCaseInsensitiveInAnyOrderAndUnknownColumnsAreIgnored() {
        ParsedFile<StatementLineInput> file = statement("﻿Reference,AMOUNT,Value_Date,account_id,Balance\n"
                + "INV-1,10.00,2026-10-01,ACC-1,999.00\n");

        assertThat(file.accepted()).containsExactly(
                new StatementLineInput(1, "ACC-1", LocalDate.of(2026, 10, 1), 1000, "INV-1", "", null));
        assertThat(file.ignoredColumns()).containsExactly("balance");
    }

    @Test
    void aMissingRequiredColumnRejectsTheWholeFile() {
        assertThatThrownBy(() -> statement("account_id,value_date,amount\nACC-1,2026-10-01,1.00\n"))
                .isInstanceOf(MalformedFileException.class).hasMessageContaining("reference");
    }

    @Test
    void brokenQuotingRejectsTheWholeFile() {
        assertThatThrownBy(() -> statement(STATEMENT_HEADER + "ACC-1,2026-10-01,1.00,\"unclosed,\n"))
                .isInstanceOf(MalformedFileException.class);
    }

    @Test
    void anEmptyFileIsMalformed() {
        assertThatThrownBy(() -> statement("")).isInstanceOf(MalformedFileException.class);
    }

    @Test
    void enforcesTheRowAndByteLimits() {
        String elevenRows = STATEMENT_HEADER + "ACC-1,2026-10-01,1.00,R,\n".repeat(11);
        assertThatThrownBy(() -> statement(elevenRows)).isInstanceOf(PayloadTooLargeException.class);

        ImportParser tiny = parser(1000, DataSize.ofBytes(100));
        assertThatThrownBy(() -> tiny.statement(stream(STATEMENT_HEADER + "ACC-1,2026-10-01,1.00,R,\n".repeat(5)),
                Format.CSV)).isInstanceOf(PayloadTooLargeException.class);
    }

    @Test
    void readsJsonRowsWithTheSameRules() {
        ParsedFile<StatementLineInput> file = parser.statement(stream("""
                [{"accountId": "ACC-1", "valueDate": "2026-10-01", "amount": "10.00", "reference": "INV-1"},
                 {"accountId": "ACC-1", "valueDate": "2026-10-01", "amount": 10.5, "reference": "INV-2"},
                 "not an object",
                 {"accountId": "ACC-1", "valueDate": "2026-10-01", "amount": "1", "reference": "", "extra": "x"}]
                """), Format.JSON);

        assertThat(file.accepted()).extracting(StatementLineInput::row).containsExactly(1, 4);
        assertThat(file.rejections()).extracting(r -> r.row() + ":" + r.reason())
                .containsExactly("2:must be a JSON string", "3:must be a JSON object");
        assertThat(file.ignoredColumns()).containsExactly("extra");
    }

    @Test
    void jsonThatIsNotAnArrayIsMalformed() {
        assertThatThrownBy(() -> parser.statement(stream("{\"rows\": []}"), Format.JSON))
                .isInstanceOf(MalformedFileException.class);
        assertThatThrownBy(() -> parser.statement(stream("[{\"accountId\": \"A\"}"), Format.JSON))
                .isInstanceOf(MalformedFileException.class);
    }

    @Test
    void theSameBytesGiveTheSameHash() {
        String body = STATEMENT_HEADER + "ACC-1,2026-10-01,1.00,R,\n";
        assertThat(statement(body).sha256()).isEqualTo(statement(body).sha256())
                .isNotEqualTo(statement(body + "ACC-1,2026-10-01,2.00,R,\n").sha256());
    }

    private ParsedFile<LedgerRecordInput> ledger(String csv) {
        return parser.ledger(stream(csv), Format.CSV);
    }

    private ParsedFile<StatementLineInput> statement(String csv) {
        return parser.statement(stream(csv), Format.CSV);
    }

    private static ImportParser parser(int maxRows, DataSize maxBytes) {
        return new ImportParser(new IngestProperties(maxRows, maxBytes, 1000), JsonMapper.builder().build());
    }

    private static ByteArrayInputStream stream(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }
}
