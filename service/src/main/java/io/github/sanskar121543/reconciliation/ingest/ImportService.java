package io.github.sanskar121543.reconciliation.ingest;

import io.github.sanskar121543.reconciliation.matching.RunCompletion;
import io.github.sanskar121543.reconciliation.matching.StatementProcessor;
import io.github.sanskar121543.reconciliation.run.Run;
import io.github.sanskar121543.reconciliation.run.RunRepository;
import io.github.sanskar121543.reconciliation.run.RunStatus;
import io.github.sanskar121543.reconciliation.web.ApiCaller;
import io.github.sanskar121543.reconciliation.web.ConflictException;
import io.github.sanskar121543.reconciliation.web.NotFoundException;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A run takes exactly one ledger file, then exactly one statement file. Uploading the same file again
 * returns the original report and changes nothing, so a client can always retry an upload.
 */
@Service
public class ImportService {

    private final RunRepository runs;
    private final ImportStore imports;
    private final StatementProcessor processor;
    private final RunCompletion completion;

    public ImportService(RunRepository runs, ImportStore imports, StatementProcessor processor,
            RunCompletion completion) {
        this.runs = runs;
        this.imports = imports;
        this.processor = processor;
        this.completion = completion;
    }

    public void requireRun(long runId) {
        if (!runs.existsById(runId)) {
            throw notFound(runId);
        }
    }

    @Transactional
    public ImportReport importLedger(long runId, ParsedFile<LedgerRecordInput> file, ApiCaller caller) {
        Run run = runs.lockById(runId).orElseThrow(() -> notFound(runId));
        Optional<ImportReport> existing = imports.find(runId, Side.LEDGER);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), file);
        }
        if (run.getStatus() != RunStatus.RECEIVING) {
            throw new ConflictException("run-not-receiving", "Run " + runId + " is " + run.getStatus());
        }
        imports.insertLedger(runId, file.accepted());
        return imports.insert(runId, Side.LEDGER, file, caller.clientId());
    }

    @Transactional
    public ImportReport importStatement(long runId, ParsedFile<StatementLineInput> file, ApiCaller caller) {
        Run run = runs.lockById(runId).orElseThrow(() -> notFound(runId));
        Optional<ImportReport> existing = imports.find(runId, Side.STATEMENT);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), file);
        }
        if (imports.find(runId, Side.LEDGER).isEmpty()) {
            throw new ConflictException("ledger-required-first",
                    "Upload the ledger file before the statement: matching needs the whole ledger.");
        }
        processor.process(runId, file.accepted());
        ImportReport report = imports.insert(runId, Side.STATEMENT, file, caller.clientId());
        completion.complete(run);
        return report;
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
