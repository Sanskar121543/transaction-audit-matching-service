package io.github.sanskar121543.reconciliation.ingest;

import io.github.sanskar121543.reconciliation.web.ApiCaller;
import io.github.sanskar121543.reconciliation.web.ApiKeyFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Upload a file as the raw body ({@code Content-Type: text/csv}, e.g. {@code curl --data-binary @ledger.csv})
 * or send the rows as a JSON array ({@code application/json}). Both go through the same validation.
 */
@RestController
@RequestMapping("/v1/runs/{runId}")
class ImportController {

    private final ImportParser parser;
    private final ImportService imports;
    private final ImportStore store;

    ImportController(ImportParser parser, ImportService imports, ImportStore store) {
        this.parser = parser;
        this.imports = imports;
        this.store = store;
    }

    @PostMapping(path = "/ledger", consumes = "text/csv")
    ResponseEntity<ImportReport> ledgerCsv(@PathVariable long runId, HttpServletRequest request,
            @RequestAttribute(ApiKeyFilter.CALLER_ATTRIBUTE) ApiCaller caller) throws IOException {
        return ledger(runId, request, Format.CSV, caller);
    }

    @PostMapping(path = "/ledger", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ImportReport> ledgerJson(@PathVariable long runId, HttpServletRequest request,
            @RequestAttribute(ApiKeyFilter.CALLER_ATTRIBUTE) ApiCaller caller) throws IOException {
        return ledger(runId, request, Format.JSON, caller);
    }

    @PostMapping(path = "/statement", consumes = "text/csv")
    ResponseEntity<ImportReport> statementCsv(@PathVariable long runId, HttpServletRequest request,
            @RequestAttribute(ApiKeyFilter.CALLER_ATTRIBUTE) ApiCaller caller) throws IOException {
        return statement(runId, request, Format.CSV, caller);
    }

    @PostMapping(path = "/statement", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ImportReport> statementJson(@PathVariable long runId, HttpServletRequest request,
            @RequestAttribute(ApiKeyFilter.CALLER_ATTRIBUTE) ApiCaller caller) throws IOException {
        return statement(runId, request, Format.JSON, caller);
    }

    @GetMapping("/imports")
    List<ImportReport> list(@PathVariable long runId) {
        imports.requireRun(runId);
        return store.forRun(runId);
    }

    private ResponseEntity<ImportReport> ledger(long runId, HttpServletRequest request, Format format,
            ApiCaller caller) throws IOException {
        imports.requireRun(runId);
        return respond(imports.importLedger(runId, parser.ledger(request.getInputStream(), format), caller));
    }

    private ResponseEntity<ImportReport> statement(long runId, HttpServletRequest request, Format format,
            ApiCaller caller) throws IOException {
        imports.requireRun(runId);
        return respond(imports.importStatement(runId, parser.statement(request.getInputStream(), format), caller));
    }

    private static ResponseEntity<ImportReport> respond(ImportReport report) {
        return ResponseEntity.status(report.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(report);
    }
}
