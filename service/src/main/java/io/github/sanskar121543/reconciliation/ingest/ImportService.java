package io.github.sanskar121543.reconciliation.ingest;

import io.github.sanskar121543.reconciliation.common.ReconciliationMetrics;
import io.github.sanskar121543.reconciliation.matching.RunCompletion;
import io.github.sanskar121543.reconciliation.run.Run;
import io.github.sanskar121543.reconciliation.run.RunRepository;
import io.github.sanskar121543.reconciliation.run.RunStatus;
import io.github.sanskar121543.reconciliation.web.ApiCaller;
import io.github.sanskar121543.reconciliation.web.ConflictException;
import io.github.sanskar121543.reconciliation.web.NotFoundException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A run takes exactly one ledger file, then exactly one statement file. Uploading the same file again
 * returns the original report and changes nothing, so a client can always retry an upload.
 * <p>
 * The ledger is stored in the request. The statement is recorded in the request, then its lines go to
 * Kafka for the workers to match; the run completes when the last one is stored. While the run is still
 * matching, uploading the same statement again queues its lines again: that is how an upload interrupted
 * after the statement was recorded is resumed, and the workers skip every line they already have.
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final RunRepository runs;
    private final ImportStore imports;
    private final StatementPublisher publisher;
    private final RunCompletion completion;
    private final TransactionTemplate transactions;
    private final ReconciliationMetrics metrics;

    public ImportService(RunRepository runs, ImportStore imports, StatementPublisher publisher,
            RunCompletion completion, TransactionTemplate transactions, ReconciliationMetrics metrics) {
        this.runs = runs;
        this.imports = imports;
        this.publisher = publisher;
        this.completion = completion;
        this.transactions = transactions;
        this.metrics = metrics;
    }

    public void requireRun(long runId) {
        if (!runs.existsById(runId)) {
            throw notFound(runId);
        }
    }

    // A replay takes no lock: an import row never changes once written. Only recording a new file locks the
    // run, so a retried upload never waits behind the end-of-run step, which holds that lock while it runs.
    @Transactional
    public ImportReport importLedger(long runId, ParsedFile<LedgerRecordInput> file, ApiCaller caller) {
        Optional<ImportReport> existing = imports.find(runId, Side.LEDGER);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), file);
        }
        Run run = runs.lockById(runId).orElseThrow(() -> notFound(runId));
        existing = imports.find(runId, Side.LEDGER);
        if (existing.isPresent()) {
            // Recorded by another upload while this one waited for the lock.
            return replayOrConflict(existing.get(), file);
        }
        if (run.getStatus() != RunStatus.RECEIVING) {
            throw new ConflictException("run-not-receiving", "Run " + runId + " is " + run.getStatus());
        }
        imports.insertLedger(runId, file.accepted());
        metrics.imported("ledger", file.accepted().size(), file.rowsRejected());
        return imports.insert(runId, Side.LEDGER, file, caller.clientId());
    }

    public ImportReport importStatement(long runId, ParsedFile<StatementLineInput> file, ApiCaller caller) {
        RecordedStatement recorded = transactions.execute(status -> recordStatement(runId, file, caller));
        if (recorded.queueLines()) {
            log.info("Run {}: queueing {} statement lines on Kafka{}", runId, file.accepted().size(),
                    recorded.report().replayed() ? " again, except those already stored" : "");
            publisher.publish(runId, file.sha256(), file.accepted(), recorded.storedThrough());
            imports.markStatementQueued(runId);
        }
        return recorded.report();
    }

    /** {@code storedThrough}: see {@link StatementPublisher#publish}. */
    private record RecordedStatement(ImportReport report, boolean queueLines, Map<String, Integer> storedThrough) {
    }

    private RecordedStatement recordStatement(long runId, ParsedFile<StatementLineInput> file, ApiCaller caller) {
        Optional<ImportReport> existing = imports.find(runId, Side.STATEMENT);
        if (existing.isEmpty()) {
            Run run = runs.lockById(runId).orElseThrow(() -> notFound(runId));
            existing = imports.find(runId, Side.STATEMENT);
            if (existing.isEmpty()) {
                return recordFirstStatement(run, file, caller);
            }
        }
        ImportReport replay = replayOrConflict(existing.get(), file);
        if (runs.findById(runId).orElseThrow(() -> notFound(runId)).getStatus() != RunStatus.MATCHING) {
            return new RecordedStatement(replay, false, Map.of());
        }
        requireSameValidation(existing.get(), file);
        return new RecordedStatement(replay, true, imports.lastStoredRowByAccount(runId));
    }

    private RecordedStatement recordFirstStatement(Run run, ParsedFile<StatementLineInput> file, ApiCaller caller) {
        long runId = run.getId();
        if (imports.find(runId, Side.LEDGER).isEmpty()) {
            throw new ConflictException("ledger-required-first",
                    "Upload the ledger file before the statement: matching needs the whole ledger.");
        }
        ImportReport report = imports.insert(runId, Side.STATEMENT, file, caller.clientId());
        metrics.imported("statement", file.accepted().size(), file.rowsRejected());
        run.startMatching();
        if (file.accepted().isEmpty()) {
            // No line will ever reach a worker, so nothing else would complete the run.
            imports.markStatementQueued(runId);
            completion.complete(run);
            return new RecordedStatement(report, false, Map.of());
        }
        return new RecordedStatement(report, true, Map.of());
    }

    // Re-queueing the lines is safe only if this upload accepted exactly the lines the first one did. The same
    // bytes always parse the same way, unless the validation rules changed in between (a newer version).
    private static void requireSameValidation(ImportReport recorded, ParsedFile<StatementLineInput> file) {
        if (recorded.rowsTotal() != file.rowsTotal() || recorded.rowsAccepted() != file.accepted().size()
                || recorded.rowsRejected() != file.rowsRejected()) {
            throw new ConflictException("validation-changed", "Run " + recorded.runId() + "'s statement was accepted "
                    + "with " + recorded.rowsAccepted() + " lines but now validates to " + file.accepted().size()
                    + "; its lines cannot be queued again. Create a new run.");
        }
    }

    private static ImportReport replayOrConflict(ImportReport existing, ParsedFile<?> file) {
        if (existing.sha256().equals(file.sha256())) {
            return existing.asReplay();
        }
        throw new ConflictException("already-imported", "Run " + existing.runId() + " already has a "
                + existing.side().name().toLowerCase(Locale.ROOT) + " file with a different SHA-256; create a new run.");
    }

    private static NotFoundException notFound(long runId) {
        return new NotFoundException("run-not-found", "No run with id " + runId);
    }
}
