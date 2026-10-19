package io.github.sanskar121543.reconciliation.generator;

import io.github.sanskar121543.reconciliation.generator.Dataset.LedgerRow;
import io.github.sanskar121543.reconciliation.generator.Dataset.StatementRow;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;

/**
 * Plays the bank for a ledger file that already exists, such as the Payments Ledger Service export: it
 * writes the statement that bank would send, with known breaks, and the ground truth. Only scenarios that
 * leave the ledger as it is apply here (no duplicate ledger records, no lookalikes).
 */
public final class BankSimulator {

    private static final Scenario[] SCENARIOS = {Scenario.CLEAN, Scenario.FEE_DEDUCTED,
            Scenario.REFORMATTED_ON_TIME, Scenario.REFORMATTED_LATE, Scenario.MISSING_IN_BANK, Scenario.AMOUNT_MISMATCH,
            Scenario.DUPLICATE_IN_BANK, Scenario.REFERENCE_LOST_LATE};
    private static final int[] WEIGHTS = {850, 30, 30, 20, 30, 20, 10, 10};

    private BankSimulator() {
    }

    public static List<LedgerRow> readLedger(Reader csv) throws IOException {
        List<LedgerRow> rows = new ArrayList<>();
        for (CSVRecord r : CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get().parse(csv)) {
            rows.add(new LedgerRow(r.get("external_id"), r.get("account_id"), LocalDate.parse(r.get("booking_date")),
                    new BigDecimal(r.get("amount")).movePointRight(2).longValueExact(), r.get("reference"),
                    r.isMapped("description") ? r.get("description") : ""));
        }
        return rows;
    }

    public static Dataset simulate(List<LedgerRow> ledger, long seed, long toleranceCents, int dateWindowDays) {
        Random random = new Random(seed);
        record Line(LocalDate date, int seq, int copy, String account, long cents, String reference,
                String description) {
        }
        record Pending(Line line, String ledgerExternalId, Scenario scenario, String expectedRule, String breakType) {
        }
        List<Pending> pending = new ArrayList<>();
        List<Truth.Break> ledgerOnly = new ArrayList<>();
        int seq = 0;
        for (LedgerRow record : ledger) {
            seq++;
            Scenario scenario = pick(random);
            LocalDate date = record.bookingDate();
            long cents = record.amountCents();
            String reference = record.reference();
            switch (scenario) {
                case CLEAN -> pending.add(new Pending(new Line(date.plusDays(random.nextInt(3)), seq, 0,
                        record.accountId(), cents, reference, "BANK"), record.externalId(), scenario, "EXACT", null));
                case FEE_DEDUCTED -> {
                    long fee = 1 + random.nextInt((int) toleranceCents);
                    long bank = Math.abs(cents) > fee ? cents - Long.signum(cents) * fee : cents;
                    pending.add(new Pending(new Line(date.plusDays(random.nextInt(dateWindowDays + 1)), seq, 0,
                            record.accountId(), bank, reference, "BANK"), record.externalId(), scenario,
                            bank == cents ? "EXACT" : "TOLERANCE", null));
                }
                case REFORMATTED_ON_TIME -> pending.add(new Pending(new Line(
                        date.plusDays(random.nextInt(dateWindowDays + 1)), seq, 0, record.accountId(), cents,
                        reformat(reference, random), "BANK"), record.externalId(), scenario, "TOLERANCE", null));
                case REFORMATTED_LATE -> pending.add(new Pending(new Line(
                        date.plusDays(dateWindowDays + 1 + random.nextInt(12)), seq, 0, record.accountId(), cents,
                        reformat(reference, random), "BANK"), record.externalId(), scenario, "NORMALIZED_REFERENCE",
                        null));
                case MISSING_IN_BANK ->
                        ledgerOnly.add(new Truth.Break("MISSING_IN_BANK", record.externalId(), null, scenario));
                case AMOUNT_MISMATCH -> {
                    long bank = cents + Math.max(toleranceCents + 1, Math.abs(cents) / 10);
                    pending.add(new Pending(new Line(date.plusDays(random.nextInt(3)), seq, 0, record.accountId(),
                            bank == 0 ? 1 : bank, reference, "BANK"), record.externalId(), scenario, null,
                            "AMOUNT_MISMATCH"));
                }
                case DUPLICATE_IN_BANK -> {
                    Line original = new Line(date.plusDays(random.nextInt(3)), seq, 0, record.accountId(), cents,
                            reference, "BANK");
                    pending.add(new Pending(original, record.externalId(), scenario, "EXACT", null));
                    pending.add(new Pending(new Line(original.date().plusDays(random.nextInt(2)), seq, 1,
                            record.accountId(), cents, reference, "BANK"), null, scenario, null, "DUPLICATE"));
                }
                case REFERENCE_LOST_LATE -> pending.add(new Pending(new Line(
                        date.plusDays(dateWindowDays + 1 + random.nextInt(10)), seq, 0, record.accountId(), cents, "",
                        "BANK"), record.externalId(), scenario, null, null));
                default -> throw new IllegalStateException("Unexpected scenario " + scenario);
            }
        }
        for (int i = 0; i < ledger.size() / 50; i++) {
            LedgerRow near = ledger.get(random.nextInt(ledger.size()));
            pending.add(new Pending(new Line(near.bookingDate(), ++seq, 0, near.accountId(),
                    -(50 + random.nextInt(3_500)), "", "BANK FEE"), null, Scenario.MISSING_IN_LEDGER, null,
                    "MISSING_IN_LEDGER"));
        }

        pending.sort(Comparator.comparing((Pending p) -> p.line().date()).thenComparingInt(p -> p.line().seq())
                .thenComparingInt(p -> p.line().copy()));
        List<StatementRow> statement = new ArrayList<>();
        List<Truth.Match> matches = new ArrayList<>();
        List<Truth.Break> breaks = new ArrayList<>(ledgerOnly);
        for (Pending p : pending) {
            int row = statement.size() + 1;
            Line l = p.line();
            statement.add(new StatementRow(row, l.account(), l.date(), l.cents(), l.reference(), l.description(),
                    String.format("BT-%08d-%d", l.seq(), l.copy())));
            if (p.breakType() == null) {
                matches.add(new Truth.Match(p.ledgerExternalId(), row, p.scenario(), p.expectedRule()));
            } else {
                breaks.add(new Truth.Break(p.breakType(), p.ledgerExternalId(), row, p.scenario()));
            }
        }
        GeneratorConfig config = new GeneratorConfig(seed, ledger.size(), 1, ledger.getFirst().bookingDate(), 1,
                toleranceCents, dateWindowDays);
        return new Dataset(config, ledger, statement, new Truth(matches, breaks));
    }

    private static Scenario pick(Random random) {
        int roll = random.nextInt(1000);
        int cumulative = 0;
        for (int i = 0; i < SCENARIOS.length; i++) {
            cumulative += WEIGHTS[i];
            if (roll < cumulative) {
                return SCENARIOS[i];
            }
        }
        throw new IllegalStateException("Weights must add up to 1000");
    }

    // Always changes the text (unlike Generator's, these references may have no zeros or separators to drop).
    private static String reformat(String reference, Random random) {
        String lower = reference.toLowerCase(Locale.ROOT);
        return random.nextBoolean() ? lower : lower.replace('-', ' ');
    }
}
