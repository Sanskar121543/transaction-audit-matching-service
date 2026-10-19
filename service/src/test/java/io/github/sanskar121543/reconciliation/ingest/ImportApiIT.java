package io.github.sanskar121543.reconciliation.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanskar121543.reconciliation.support.ApiClient;
import io.github.sanskar121543.reconciliation.support.ApiResponse;
import io.github.sanskar121543.reconciliation.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

@IntegrationTest
@TestPropertySource(properties = {"matching.ingest.max-rows=50", "matching.ingest.max-bytes=8KB"})
class ImportApiIT {

    @Value("${local.server.port}")
    int port;

    ApiClient api;
    long runId;

    @BeforeEach
    void newRun() {
        api = ApiClient.forPort(port);
        runId = api.createRun("imports");
    }

    @Test
    void returnsARejectionReportAndImportsTheValidRows() {
        ApiResponse response = api.uploadLedger(runId, """
                external_id,account_id,booking_date,amount,reference,notes
                L-1,ACC-1,2026-10-01,10.00,R-1,a
                L-2,ACC-1,2026-13-01,10.00,R-2,b
                L-3,ACC 1,2026-10-01,10.001,R-3,c
                """);

        assertThat(response.status()).isEqualTo(201);
        JsonNode report = response.body();
        assertThat(report.get("rowsTotal").asInt()).isEqualTo(3);
        assertThat(report.get("rowsAccepted").asInt()).isEqualTo(1);
        assertThat(report.get("rowsRejected").asInt()).isEqualTo(2);
        assertThat(report.get("ignoredColumns").get(0).asString()).isEqualTo("notes");
        assertThat(report.get("rejections")).hasSize(3);
        assertThat(report.at("/rejections/0/row").asInt()).isEqualTo(2);
        assertThat(report.at("/rejections/0/field").asString()).isEqualTo("booking_date");

        JsonNode stored = api.get("/v1/runs/" + runId + "/imports").body();
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).get("rejections")).hasSize(3);
    }

    @Test
    void acceptsRowsAsJson() {
        ApiResponse response = api.send("/v1/runs/" + runId + "/ledger", "application/json", """
                [{"externalId": "L-1", "accountId": "ACC-1", "bookingDate": "2026-10-01", "amount": "10.00",
                  "reference": "R-1"},
                 {"externalId": "L-2", "accountId": "ACC-1", "bookingDate": "2026-10-01", "amount": 10,
                  "reference": "R-2"}]
                """);

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body().get("rowsAccepted").asInt()).isEqualTo(1);
        assertThat(response.body().at("/rejections/0/reason").asString()).isEqualTo("must be a JSON string");
    }

    @Test
    void aFileThatCannotBeReadIsRefusedWhole() {
        ApiResponse noReference = api.uploadLedger(runId, "external_id,account_id,booking_date,amount\n");
        ApiResponse brokenQuotes = api.uploadLedger(runId,
                "external_id,account_id,booking_date,amount,reference\nL-1,ACC-1,2026-10-01,1.00,\"open\n");

        assertThat(noReference.status()).isEqualTo(400);
        assertThat(noReference.problemType()).isEqualTo("/problems/malformed-file");
        assertThat(brokenQuotes.status()).isEqualTo(400);
        assertThat(api.get("/v1/runs/" + runId + "/imports").body()).isEmpty();
    }

    @Test
    void refusesUploadsOverTheLimits() {
        String header = "external_id,account_id,booking_date,amount,reference\n";
        StringBuilder rows = new StringBuilder(header);
        for (int i = 1; i <= 51; i++) {
            rows.append("L-").append(i).append(",ACC-1,2026-10-01,1.00,R\n");
        }

        assertThat(api.uploadLedger(runId, rows.toString()).status()).isEqualTo(413);
        assertThat(api.uploadLedger(runId, header + "L-1,ACC-1,2026-10-01,1.00," + "R".repeat(9_000) + "\n")
                .status()).isEqualTo(413);
    }

    @Test
    void otherContentTypesAreRefused() {
        assertThat(api.send("/v1/runs/" + runId + "/ledger", "text/plain", "x").status()).isEqualTo(415);
    }
}
