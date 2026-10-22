package io.github.sanskar121543.reconciliation.matching;

import io.github.sanskar121543.reconciliation.ingest.ImportStore;
import io.github.sanskar121543.reconciliation.ingest.StatementLineMessage;
import io.github.sanskar121543.reconciliation.run.Run;
import io.github.sanskar121543.reconciliation.run.RunRepository;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * Stores and exact-matches one run's lines from a Kafka batch, in one transaction. The worker that stores
 * a run's last line also completes the run: the counter on the run row decides which one that is.
 */
@Component
public class StatementLineWorker {

    private static final Logger log = LoggerFactory.getLogger(StatementLineWorker.class);

    private final ImportStore imports;
    private final MatchStore store;
    private final StatementProcessor processor;
    private final RunRepository runs;
    private final RunCompletion completion;

    public StatementLineWorker(ImportStore imports, MatchStore store, StatementProcessor processor, RunRepository runs,
            RunCompletion completion) {
        this.imports = imports;
        this.store = store;
        this.processor = processor;
        this.runs = runs;
        this.completion = completion;
    }

    @Transactional
    public void handle(long runId, List<StatementLineMessage> delivered) {
        Optional<ImportStore.StatementFile> file = imports.statementFile(runId);
        if (file.isEmpty()) {
            // Lines are published only after their import commits, so this is a line nobody uploaded here.
            log.warn("Ignored {} lines for run {}, which has no statement import", delivered.size(), runId);
            return;
        }
        List<StatementLineMessage> ofThisFile = delivered.stream()
                .filter(line -> line.sha256().equals(file.get().sha256()))
                .toList();
        if (ofThisFile.size() < delivered.size()) {
            log.warn("Ignored {} lines for run {} from a file other than the one it imported",
                    delivered.size() - ofThisFile.size(), runId);
        }
        if (ofThisFile.isEmpty()) {
            return;
        }
        if (!file.get().runMatching()) {
            // Completed: every line it accepted is stored, so these are copies (a redelivery or a re-upload).
            return;
        }

        Set<String> accounts = ofThisFile.stream().map(StatementLineMessage::accountId).collect(Collectors.toSet());
        store.lockAccounts(runId, accounts);
        LineChain.Selection selection = LineChain.select(ofThisFile,
                store.storedRows(runId, LineChain.rowsToCheck(ofThisFile)));
        if (selection.predecessorMissing() > 0) {
            log.warn("Run {}: {} lines arrived before the line they follow and were dropped; uploading the same "
                    + "statement again delivers them in order", runId, selection.predecessorMissing());
        }
        List<StatementItem> stored = processor.process(runId,
                selection.ready().stream().map(StatementLineMessage::toInput).toList());
        if (stored.isEmpty()) {
            return;
        }

        OptionalInt received = store.addReceivedLines(runId, stored.size());
        if (received.isEmpty()) {
            // Another worker completed the run while this one stored lines, so they are not among the lines the
            // run accepted (which are all stored). Keep none of it: the run's outcome is final.
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            log.warn("Run {}: {} lines arrived after the run completed and were not kept", runId, stored.size());
            return;
        }
        if (received.getAsInt() > file.get().acceptedLines()) {
            // More lines than the statement accepted: some are not from the accepted set. Roll back this batch
            // rather than let the run complete wrong (the listener records the problem on the run).
            throw new IllegalStateException("Run " + runId + " would hold " + received.getAsInt()
                    + " statement lines but its statement accepted " + file.get().acceptedLines());
        }
        if (received.getAsInt() == file.get().acceptedLines()) {
            Run run = runs.lockById(runId).orElseThrow();
            store.allowLongComputationInThisTransaction();
            long started = System.nanoTime();
            RunCompletion.Summary summary = completion.complete(run);
            log.info("Run {} completed: all {} lines in; the end-of-run step made {} matches and {} breaks in {} ms",
                    runId, received.getAsInt(), summary.matches(), summary.breaks(), (System.nanoTime() - started) / 1_000_000);
        }
    }
}
