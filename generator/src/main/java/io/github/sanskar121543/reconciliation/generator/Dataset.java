package io.github.sanskar121543.reconciliation.generator;

import java.time.LocalDate;
import java.util.List;

/** A generated ledger file, statement file and the ground truth that says how they should reconcile. */
public record Dataset(GeneratorConfig config, List<LedgerRow> ledger, List<StatementRow> statement, Truth truth) {

    public record LedgerRow(String externalId, String accountId, LocalDate bookingDate, long amountCents,
            String reference, String description) {
    }

    /** {@code row} is the 1-based position in the statement file, which is how the service identifies a line. */
    public record StatementRow(int row, String accountId, LocalDate valueDate, long amountCents, String reference,
            String description, String bankTransactionId) {
    }

    public String ledgerCsv() {
        StringBuilder csv = new StringBuilder("external_id,account_id,booking_date,amount,currency,reference,description\n");
        for (LedgerRow r : ledger) {
            csv.append(r.externalId()).append(',').append(r.accountId()).append(',').append(r.bookingDate())
                    .append(',').append(Amounts.format(r.amountCents())).append(",USD,").append(r.reference())
                    .append(',').append(r.description()).append('\n');
        }
        return csv.toString();
    }

    public String statementCsv() {
        StringBuilder csv = new StringBuilder(
                "account_id,value_date,amount,currency,reference,description,bank_transaction_id\n");
        for (StatementRow r : statement) {
            csv.append(r.accountId()).append(',').append(r.valueDate()).append(',')
                    .append(Amounts.format(r.amountCents())).append(",USD,").append(r.reference()).append(',')
                    .append(r.description()).append(',').append(r.bankTransactionId()).append('\n');
        }
        return csv.toString();
    }
}
