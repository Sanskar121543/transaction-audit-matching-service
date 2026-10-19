package io.github.sanskar121543.reconciliation.ingest;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/** A field of an imported row: snake_case as a CSV header, camelCase as a JSON key. */
public enum Column {
    EXTERNAL_ID("external_id", "externalId"),
    ACCOUNT_ID("account_id", "accountId"),
    BOOKING_DATE("booking_date", "bookingDate"),
    VALUE_DATE("value_date", "valueDate"),
    AMOUNT("amount", "amount"),
    CURRENCY("currency", "currency"),
    REFERENCE("reference", "reference"),
    DESCRIPTION("description", "description"),
    BANK_TRANSACTION_ID("bank_transaction_id", "bankTransactionId");

    private final String csvName;
    private final String jsonName;

    Column(String csvName, String jsonName) {
        this.csvName = csvName;
        this.jsonName = jsonName;
    }

    public String csvName() {
        return csvName;
    }

    public String jsonName() {
        return jsonName;
    }

    static Set<Column> required(Side side) {
        return side == Side.LEDGER
                ? EnumSet.of(EXTERNAL_ID, ACCOUNT_ID, BOOKING_DATE, AMOUNT, REFERENCE)
                : EnumSet.of(ACCOUNT_ID, VALUE_DATE, AMOUNT, REFERENCE);
    }

    static Set<Column> allowed(Side side) {
        Set<Column> allowed = EnumSet.copyOf(required(side));
        allowed.addAll(EnumSet.of(CURRENCY, DESCRIPTION));
        if (side == Side.STATEMENT) {
            allowed.add(BANK_TRANSACTION_ID);
        }
        return allowed;
    }

    static Optional<Column> byCsvName(String name) {
        return Arrays.stream(values()).filter(c -> c.csvName.equals(name)).findFirst();
    }

    static Optional<Column> byJsonName(String name) {
        return Arrays.stream(values()).filter(c -> c.jsonName.equals(name)).findFirst();
    }
}
