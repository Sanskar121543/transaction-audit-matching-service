package io.github.sanskar121543.reconciliation.generator;

import io.github.sanskar121543.reconciliation.generator.Dataset.LedgerRow;
import io.github.sanskar121543.reconciliation.generator.Dataset.StatementRow;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Generates a ledger file and a bank statement file from one list of synthetic payments, injecting known
 * breaks, and records the ground truth. Deterministic: java.util.Random's algorithm is fixed by its
 * specification, so the same config gives the same files on any JVM.
 */
public final class Generator {

    private static final String[] PREFIXES = {"INV", "PO", "PAY", "SUP"};
    private static final long MIN_AMOUNT_CENTS = 500;
    private static final long MAX_AMOUNT_CENTS = 2_500_000;

    private final GeneratorConfig config;
    private final Random random;
    private final List<DraftLedger> ledger = new ArrayList<>();
    private final List<DraftLine> lines = new ArrayList<>();
    private final List<DraftMatch> matches = new ArrayList<>();
    private final List<DraftBreak> breaks = new ArrayList<>();

    private Generator(GeneratorConfig config) {
        this.config = config;
        this.random = new Random(config.seed());
    }

    public static Dataset generate(GeneratorConfig config) {
        Generator generator = new Generator(config);
        for (int seq = 1; seq <= config.payments(); seq++) {
            generator.payment(seq, generator.pickScenario());
        }
        return generator.dataset();
    }

    // Sort keys: date, then the payment's sequence number, then 0 for an original and 1 for its copy, so a
    // copy always comes right after the record it copies.
    private record DraftLedger(int seq, int copy, String account, LocalDate date, long cents, String reference,
            String description) {
    }

    private record DraftLine(int seq, int copy, String account, LocalDate date, long cents, String reference,
            String description) {
    }

    private record DraftMatch(DraftLedger ledger, DraftLine line, Scenario scenario, String expectedRule) {
    }

    private record DraftBreak(String type, DraftLedger ledger, DraftLine line, Scenario scenario) {
    }

    private void payment(int seq, Scenario scenario) {
        String account = String.format("ACC-%03d", 1 + random.nextInt(config.accounts()));
        LocalDate date = config.firstDay().plusDays(random.nextInt(config.days()));
        long cents = amount();
        String reference = reference(seq);
        int window = config.dateWindowDays();
        switch (scenario) {
            case CLEAN -> pair(scenario, "EXACT", ledger(seq, account, date, cents, reference),
                    line(seq, account, date.plusDays(random.nextInt(3)), cents, reference));
            case FEE_DEDUCTED -> {
                long fee = 1 + random.nextInt((int) config.toleranceCents());
                pair(scenario, "TOLERANCE", ledger(seq, account, date, cents, reference),
                        line(seq, account, date.plusDays(random.nextInt(window + 1)), cents - Long.signum(cents) * fee,
                                reference));
            }
            case REFORMATTED_ON_TIME -> pair(scenario, "TOLERANCE", ledger(seq, account, date, cents, reference),
                    line(seq, account, date.plusDays(random.nextInt(window + 1)), cents, reformat(reference)));
            case REFORMATTED_LATE -> pair(scenario, "NORMALIZED_REFERENCE", ledger(seq, account, date, cents, reference),
                    line(seq, account, date.plusDays(window + 1 + random.nextInt(12)), cents, reformat(reference)));
            case MISSING_IN_BANK -> breaks.add(new DraftBreak("MISSING_IN_BANK",
                    ledger(seq, account, date, cents, reference), null, scenario));
            case MISSING_IN_LEDGER -> {
                boolean fee = random.nextBoolean();
                long bankOnly = fee ? -(50 + random.nextInt(3_500)) : 1 + random.nextInt(2_000);
                DraftLine line = new DraftLine(seq, 0, account, date, bankOnly, "", fee ? "BANK FEE" : "INTEREST");
                lines.add(line);
                breaks.add(new DraftBreak("MISSING_IN_LEDGER", null, line, scenario));
            }
            case AMOUNT_MISMATCH -> {
                long difference = Math.max(config.toleranceCents() + 1, Math.abs(cents) * (2 + random.nextInt(14)) / 100);
                if (difference >= Math.abs(cents)) {
                    difference = Math.abs(cents) / 2;
                }
                String bankReference = random.nextBoolean() ? reference : reformat(reference);
                breaks.add(new DraftBreak("AMOUNT_MISMATCH", ledger(seq, account, date, cents, reference),
                        line(seq, account, date.plusDays(random.nextInt(3)), cents - Long.signum(cents) * difference,
                                bankReference), scenario));
            }
            case DUPLICATE_IN_BANK -> {
                DraftLine original = line(seq, account, date.plusDays(random.nextInt(3)), cents, reference);
                pair(scenario, "EXACT", ledger(seq, account, date, cents, reference), original);
                DraftLine copy = new DraftLine(seq, 1, account, original.date().plusDays(random.nextInt(2)), cents,
                        reference, original.description());
                lines.add(copy);
                breaks.add(new DraftBreak("DUPLICATE", null, copy, scenario));
            }
            case DUPLICATE_IN_LEDGER -> {
                DraftLedger original = ledger(seq, account, date, cents, reference);
                pair(scenario, "EXACT", original, line(seq, account, date.plusDays(random.nextInt(3)), cents, reference));
                DraftLedger copy = new DraftLedger(seq, 1, account, date, cents, reference, original.description());
                ledger.add(copy);
                breaks.add(new DraftBreak("DUPLICATE", copy, null, scenario));
            }
            case REFERENCE_LOST_LATE -> pair(scenario, null, ledger(seq, account, date, cents, reference),
                    line(seq, account, date.plusDays(window + 1 + random.nextInt(10)), cents, ""));
            case LOOKALIKE -> {
                // The second payment gets a reference from beyond the normal range, so it is still unique.
                DraftLedger first = ledger(seq, account, date, cents, reference);
                DraftLedger second = new DraftLedger(seq, 1, account, date.plusDays(1), cents,
                        reference(config.payments() + seq), description(cents));
                ledger.add(second);
                pair(scenario, "TOLERANCE", second, line(seq, account, date.plusDays(random.nextInt(2)), cents, ""));
                breaks.add(new DraftBreak("MISSING_IN_BANK", first, null, scenario));
            }
            default -> throw new IllegalStateException("Unhandled scenario " + scenario);
        }
    }

    private Scenario pickScenario() {
        int roll = random.nextInt(1000);
        int cumulative = 0;
        for (Scenario scenario : Scenario.values()) {
            cumulative += scenario.weightPerThousand();
            if (roll < cumulative) {
                return scenario;
            }
        }
        throw new IllegalStateException("Scenario weights must add up to 1000");
    }

    /** Log-uniform between $5 and $25,000, so small amounts are as common as large ones per order of magnitude. */
    private long amount() {
        double logMin = Math.log(MIN_AMOUNT_CENTS);
        double logMax = Math.log(MAX_AMOUNT_CENTS);
        long cents = Math.round(Math.exp(logMin + random.nextDouble() * (logMax - logMin)));
        return random.nextInt(10) < 6 ? -cents : cents;
    }

    /** Unique per payment: "INV-00100042". The single run of digits keeps every reformat below equivalent. */
    private String reference(int seq) {
        return PREFIXES[random.nextInt(PREFIXES.length)] + "-" + String.format("%08d", 100_000 + seq);
    }

    /** How banks rewrite references; each version normalises to the same letters and digits. */
    private String reformat(String reference) {
        String withoutZeros = reference.replaceFirst("-0+", "-");
        return switch (random.nextInt(5)) {
            case 0 -> reference.toLowerCase(Locale.ROOT);
            case 1 -> reference.replace('-', ' ');
            case 2 -> reference.replace("-", "");
            case 3 -> withoutZeros;
            default -> withoutZeros.replace('-', ' ').toLowerCase(Locale.ROOT);
        };
    }

    private DraftLedger ledger(int seq, String account, LocalDate date, long cents, String reference) {
        DraftLedger draft = new DraftLedger(seq, 0, account, date, cents, reference, description(cents));
        ledger.add(draft);
        return draft;
    }

    private DraftLine line(int seq, String account, LocalDate date, long cents, String reference) {
        DraftLine draft = new DraftLine(seq, 0, account, date, cents, reference, cents < 0 ? "ACH DEBIT" : "ACH CREDIT");
        lines.add(draft);
        return draft;
    }

    private static String description(long cents) {
        return cents < 0 ? "Supplier payment" : "Customer receipt";
    }

    private void pair(Scenario scenario, String expectedRule, DraftLedger ledger, DraftLine line) {
        matches.add(new DraftMatch(ledger, line, scenario, expectedRule));
    }

    private Dataset dataset() {
        ledger.sort(Comparator.comparing(DraftLedger::date).thenComparingInt(DraftLedger::seq)
                .thenComparingInt(DraftLedger::copy));
        lines.sort(Comparator.comparing(DraftLine::date).thenComparingInt(DraftLine::seq)
                .thenComparingInt(DraftLine::copy));

        Map<DraftLedger, String> externalIds = new IdentityHashMap<>();
        List<LedgerRow> ledgerRows = new ArrayList<>();
        for (DraftLedger d : ledger) {
            String externalId = String.format("L-%06d", ledgerRows.size() + 1);
            externalIds.put(d, externalId);
            ledgerRows.add(new LedgerRow(externalId, d.account(), d.date(), d.cents(), d.reference(), d.description()));
        }
        Map<DraftLine, Integer> rows = new IdentityHashMap<>();
        List<StatementRow> statementRows = new ArrayList<>();
        for (DraftLine d : lines) {
            int row = statementRows.size() + 1;
            rows.put(d, row);
            statementRows.add(new StatementRow(row, d.account(), d.date(), d.cents(), d.reference(), d.description(),
                    String.format("BT-%08d-%d", d.seq(), d.copy())));
        }

        List<Truth.Match> truthMatches = matches.stream()
                .map(m -> new Truth.Match(externalIds.get(m.ledger()), rows.get(m.line()), m.scenario(), m.expectedRule()))
                .toList();
        List<Truth.Break> truthBreaks = breaks.stream()
                .map(b -> new Truth.Break(b.type(), b.ledger() == null ? null : externalIds.get(b.ledger()),
                        b.line() == null ? null : rows.get(b.line()), b.scenario()))
                .toList();
        return new Dataset(config, ledgerRows, statementRows, new Truth(truthMatches, truthBreaks));
    }
}
