package io.github.sanskar121543.reconciliation.ingest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Field rules shared by CSV and JSON input. A row with any error is rejected as a whole. */
final class RowValidator {

    static final long MAX_ABS_AMOUNT_CENTS = 100_000_000_000L;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,63}");
    private static final Pattern ACCOUNT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,33}");
    private static final Pattern AMOUNT = Pattern.compile("-?\\d{1,10}(\\.\\d{1,2})?");
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("\\p{Cntrl}");
    private static final LocalDate EARLIEST = LocalDate.of(2000, 1, 1);
    private static final LocalDate LATEST = LocalDate.of(2099, 12, 31);
    private static final int MAX_REFERENCE = 64;
    private static final int MAX_DESCRIPTION = 200;

    private RowValidator() {
    }

    static Optional<LedgerRecordInput> ledger(RawRow raw, List<Rejection> rejections) {
        if (unreadable(raw, rejections)) {
            return Optional.empty();
        }
        Checker check = new Checker(raw);
        String externalId = check.id(Column.EXTERNAL_ID, true);
        String accountId = check.account();
        LocalDate date = check.date(Column.BOOKING_DATE);
        long amount = check.amount();
        check.currency();
        String reference = check.text(Column.REFERENCE, MAX_REFERENCE);
        String description = check.text(Column.DESCRIPTION, MAX_DESCRIPTION);
        return check.result(rejections,
                () -> new LedgerRecordInput(raw.row(), externalId, accountId, date, amount, reference, description));
    }

    static Optional<StatementLineInput> statement(RawRow raw, List<Rejection> rejections) {
        if (unreadable(raw, rejections)) {
            return Optional.empty();
        }
        Checker check = new Checker(raw);
        String accountId = check.account();
        LocalDate date = check.date(Column.VALUE_DATE);
        long amount = check.amount();
        check.currency();
        String reference = check.text(Column.REFERENCE, MAX_REFERENCE);
        String description = check.text(Column.DESCRIPTION, MAX_DESCRIPTION);
        String bankTransactionId = check.id(Column.BANK_TRANSACTION_ID, false);
        return check.result(rejections, () -> new StatementLineInput(raw.row(), accountId, date, amount, reference,
                description, bankTransactionId.isEmpty() ? null : bankTransactionId));
    }

    // A row that is not an object, or has the wrong number of CSV fields, gets that one error, not one per field.
    private static boolean unreadable(RawRow raw, List<Rejection> rejections) {
        if (raw.shapeErrors().stream().noneMatch(e -> e.field() == null)) {
            return false;
        }
        rejections.addAll(raw.shapeErrors());
        return true;
    }

    /** "12.34" to 1234. Exact: no floating point is involved. */
    static long parseCents(String amount) {
        return new BigDecimal(amount).movePointRight(2).longValueExact();
    }

    private static final class Checker {

        private final RawRow raw;
        private final List<Rejection> errors = new ArrayList<>();

        Checker(RawRow raw) {
            this.raw = raw;
            errors.addAll(raw.shapeErrors());
        }

        String id(Column column, boolean required) {
            String value = raw.get(column);
            if (value.isEmpty()) {
                if (required) {
                    fail(column, "is required");
                }
            } else if (!ID.matcher(value).matches()) {
                fail(column, "must be 1-64 letters, digits or . _ : / - (starting with a letter or digit)");
            }
            return value;
        }

        String account() {
            String value = raw.get(Column.ACCOUNT_ID);
            if (value.isEmpty()) {
                fail(Column.ACCOUNT_ID, "is required");
            } else if (!ACCOUNT.matcher(value).matches()) {
                fail(Column.ACCOUNT_ID, "must be 1-34 letters, digits or . _ - (starting with a letter or digit)");
            }
            return value;
        }

        LocalDate date(Column column) {
            String value = raw.get(column);
            if (value.isEmpty()) {
                fail(column, "is required");
                return null;
            }
            try {
                LocalDate date = LocalDate.parse(value);
                if (date.isBefore(EARLIEST) || date.isAfter(LATEST)) {
                    fail(column, "must be between " + EARLIEST + " and " + LATEST);
                }
                return date;
            } catch (DateTimeParseException e) {
                fail(column, "must be an ISO date like 2026-10-18");
                return null;
            }
        }

        long amount() {
            String value = raw.get(Column.AMOUNT);
            if (value.isEmpty()) {
                fail(Column.AMOUNT, "is required");
                return 0;
            }
            if (!AMOUNT.matcher(value).matches()) {
                fail(Column.AMOUNT, "must look like 1234.56 or -1234.56: at most 2 decimals, no thousands separators");
                return 0;
            }
            long cents = parseCents(value);
            if (cents == 0) {
                fail(Column.AMOUNT, "must not be zero");
            } else if (Math.abs(cents) > MAX_ABS_AMOUNT_CENTS) {
                fail(Column.AMOUNT, "must be at most 1000000000.00 either way");
            }
            return cents;
        }

        void currency() {
            String value = raw.get(Column.CURRENCY);
            if (!value.isEmpty() && !"USD".equals(value)) {
                fail(Column.CURRENCY, "must be USD (multi-currency is out of scope) or left empty");
            }
        }

        String text(Column column, int maxLength) {
            String value = raw.get(column);
            if (value.length() > maxLength) {
                fail(column, "must be at most " + maxLength + " characters");
            } else if (CONTROL_CHARACTERS.matcher(value).find()) {
                fail(column, "must not contain control characters");
            }
            return value;
        }

        <T> Optional<T> result(List<Rejection> rejections, Supplier<T> build) {
            if (!errors.isEmpty()) {
                rejections.addAll(errors);
                return Optional.empty();
            }
            return Optional.of(build.get());
        }

        // A field that already has a shape error (say, a JSON number) is not reported a second time.
        private void fail(Column column, String reason) {
            if (errors.stream().noneMatch(e -> column.csvName().equals(e.field()))) {
                errors.add(new Rejection(raw.row(), column.csvName(), reason));
            }
        }
    }
}
