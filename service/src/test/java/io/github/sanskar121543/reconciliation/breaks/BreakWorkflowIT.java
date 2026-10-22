package io.github.sanskar121543.reconciliation.breaks;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanskar121543.reconciliation.run.ReconciliationIT;
import io.github.sanskar121543.reconciliation.support.ApiClient;
import io.github.sanskar121543.reconciliation.support.ApiResponse;
import io.github.sanskar121543.reconciliation.support.Concurrently;
import io.github.sanskar121543.reconciliation.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;

/** OPEN → INVESTIGATING → RESOLVED through the API, and the audit trail each step leaves. */
@IntegrationTest
class BreakWorkflowIT {

    @Value("${local.server.port}")
    int port;

    ApiClient api;
    long runId;
    long breakId;

    @BeforeEach
    void completedRun() {
        api = ApiClient.forPort(port);
        runId = api.createRun("workflow");
        api.uploadLedger(runId, ReconciliationIT.LEDGER);
        api.uploadStatement(runId, ReconciliationIT.STATEMENT);
        api.awaitCompleted(runId);
        breakId = breakOfType("MISSING_IN_BANK").get("id").asLong();
    }

    @Test
    void aBreakGoesFromOpenThroughInvestigatingToResolvedAndTheTrailSaysWhoDidWhat() {
        JsonNode open = api.get("/v1/breaks/" + breakId).body().get("break");
        assertThat(open.get("status").asString()).isEqualTo("OPEN");
        assertThat(open.get("assignee").isNull()).isTrue();
        assertThat(open.get("version").asLong()).isZero();
        assertThat(open.get("updatedAt").isNull()).isTrue();

        ApiResponse investigating = act("investigate", Map.of("version", 0, "note", "asked the bank"));
        assertThat(investigating.status()).isEqualTo(200);
        assertThat(investigating.textField("status")).isEqualTo("INVESTIGATING");
        assertThat(investigating.textField("assignee")).as("defaults to the caller").isEqualTo("test");
        assertThat(investigating.longField("version")).isEqualTo(1);
        assertThat(investigating.textField("updatedAt")).isNotNull();

        ApiResponse resolved = act("resolve", Map.of("version", 1, "resolution", "TIMING_DIFFERENCE",
                "note", "  cleared on 2026-10-09  "));
        assertThat(resolved.status()).isEqualTo(200);
        assertThat(resolved.textField("status")).isEqualTo("RESOLVED");
        assertThat(resolved.textField("resolution")).isEqualTo("TIMING_DIFFERENCE");
        assertThat(resolved.longField("version")).isEqualTo(2);

        List<JsonNode> trail = history();
        assertThat(trail).extracting(e -> e.get("action").asString() + " " + text(e, "fromStatus") + "->"
                        + text(e, "toStatus") + " " + text(e, "toAssignee") + " " + text(e, "resolution") + " "
                        + text(e, "note"))
                .containsExactly(
                        "STATUS_CHANGED OPEN->INVESTIGATING test - -",
                        "NOTE_ADDED -->- - - asked the bank",
                        "STATUS_CHANGED INVESTIGATING->RESOLVED test TIMING_DIFFERENCE -",
                        "NOTE_ADDED -->- - - cleared on 2026-10-09");
        assertThat(trail).allSatisfy(e -> {
            assertThat(e.get("actor").asString()).isEqualTo("test");
            assertThat(e.get("breakId").asLong()).isEqualTo(breakId);
        });
        assertThat(trail.get(0).get("correlationId").asString())
                .isEqualTo(investigating.header("X-Correlation-Id"));
        assertThat(trail.get(3).get("correlationId").asString()).isEqualTo(resolved.header("X-Correlation-Id"));
    }

    @Test
    void aResolvedBreakCanBeReopenedWithAReasonAndKeepsItsAssignee() {
        act("investigate", Map.of("version", 0, "assignee", "alice"));
        act("resolve", Map.of("version", 1, "resolution", "BANK_FEE", "note", "fee"));

        ApiResponse reopened = act("reopen", Map.of("version", 2, "note", "the fee was refunded"));

        assertThat(reopened.status()).isEqualTo(200);
        assertThat(reopened.textField("status")).isEqualTo("INVESTIGATING");
        assertThat(reopened.textField("resolution")).isNull();
        assertThat(reopened.textField("assignee")).isEqualTo("alice");
        assertThat(history()).extracting(e -> e.get("action").asString() + " " + text(e, "toStatus"))
                .endsWith("STATUS_CHANGED INVESTIGATING", "NOTE_ADDED -");
    }

    @Test
    void assigningIsRecordedAndTheListCanBeFilteredByAssignee() {
        ApiResponse assigned = act("assign", Map.of("version", 0, "assignee", "bob.smith@ops"));

        assertThat(assigned.status()).isEqualTo(200);
        assertThat(assigned.textField("status")).as("assigning does not start the investigation").isEqualTo("OPEN");
        assertThat(act("investigate", Map.of("version", 1)).textField("assignee"))
                .as("the existing owner, not the caller").isEqualTo("bob.smith@ops");
        assertThat(history().getFirst().get("action").asString()).isEqualTo("ASSIGNED");
        assertThat(history().getFirst().get("fromAssignee").isNull()).isTrue();

        JsonNode mine = api.get("/v1/breaks?runId=" + runId + "&assignee=bob.smith@ops").body();
        assertThat(mine).extracting(b -> b.get("id").asLong()).containsExactly(breakId);
    }

    @Test
    void aRequestBasedOnAnOlderReadIsRefusedAndChangesNothing() {
        act("investigate", Map.of("version", 0));

        ApiResponse stale = act("assign", Map.of("version", 0, "assignee", "carol"));

        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.problemType()).isEqualTo("/problems/break-changed");
        assertThat(api.get("/v1/breaks/" + breakId).body().get("break").get("assignee").asString())
                .isEqualTo("test");
        assertThat(history()).hasSize(1);
    }

    @Test
    void onlyTheNextStepInTheWorkflowIsAllowed() {
        assertThat(act("resolve", Map.of("version", 0, "resolution", "OTHER", "note", "x")).problemType())
                .isEqualTo("/problems/break-transition");
        assertThat(act("reopen", Map.of("version", 0, "note", "x")).problemType())
                .isEqualTo("/problems/break-transition");

        act("investigate", Map.of("version", 0));
        assertThat(act("investigate", Map.of("version", 1)).problemType()).isEqualTo("/problems/break-transition");

        act("resolve", Map.of("version", 1, "resolution", "OTHER", "note", "done"));
        assertThat(act("assign", Map.of("version", 2, "assignee", "dave")).problemType())
                .isEqualTo("/problems/break-resolved");
        assertThat(act("resolve", Map.of("version", 2, "resolution", "OTHER", "note", "again")).status())
                .isEqualTo(409);
        assertThat(history()).extracting(e -> e.get("action").asString())
                .containsExactly("STATUS_CHANGED", "STATUS_CHANGED", "NOTE_ADDED");
    }

    @Test
    void ofManySimultaneousResolvesExactlyOneWins() {
        act("investigate", Map.of("version", 0));

        List<Integer> statuses = Concurrently.run(8, i -> act("resolve", Map.of("version", 1,
                "resolution", "BANK_ERROR", "note", "attempt " + i)).status());

        assertThat(statuses).containsOnly(200, 409);
        assertThat(statuses).filteredOn(s -> s == 200).hasSize(1);
        assertThat(history()).filteredOn(e -> "RESOLVED".equals(text(e, "toStatus"))).hasSize(1);
        assertThat(history()).filteredOn(e -> "NOTE_ADDED".equals(e.get("action").asString())).hasSize(1);
    }

    @Test
    void notesAreAddedInAnyStateAndNeverConflict() {
        act("investigate", Map.of("version", 0));
        act("resolve", Map.of("version", 1, "resolution", "WRITTEN_OFF", "note", "approved by finance"));

        ApiResponse note = api.post("/v1/breaks/" + breakId + "/notes", Map.of("body", "audit sampled this one"));

        assertThat(note.status()).isEqualTo(201);
        assertThat(note.textField("action")).isEqualTo("NOTE_ADDED");
        assertThat(note.textField("note")).isEqualTo("audit sampled this one");
        assertThat(api.get(note.header("Location")).body()).singleElement()
                .satisfies(e -> assertThat(e.get("id").asLong()).isEqualTo(note.longField("id")));
        assertThat(api.get("/v1/breaks/" + breakId).body().get("break").get("version").asLong())
                .as("a note does not change the break").isEqualTo(2);
    }

    @Test
    void badRequestsAreRefusedBeforeAnythingChanges() {
        assertThat(act("resolve", Map.of("version", 0, "resolution", "OTHER")).status())
                .as("resolving needs a note").isEqualTo(400);
        assertThat(act("resolve", Map.of("version", 0, "resolution", "OTHER", "note", "   ")).status())
                .isEqualTo(400);
        assertThat(act("resolve", Map.of("version", 0, "resolution", "MAGIC", "note", "x")).status())
                .isEqualTo(400);
        assertThat(act("investigate", Map.of("version", 0, "assignee", "two words")).status()).isEqualTo(400);
        assertThat(act("investigate", Map.of("version", 0, "note", " ")).status()).isEqualTo(400);
        assertThat(act("investigate", Map.of()).status()).as("the version is required").isEqualTo(400);
        assertThat(act("investigate", Map.of("version", 0, "status", "RESOLVED")).status())
                .as("unknown fields").isEqualTo(400);
        assertThat(api.post("/v1/breaks/" + breakId + "/notes", Map.of("body", "x".repeat(2001))).status())
                .isEqualTo(400);
        assertThat(history()).isEmpty();
    }

    @Test
    void unknownBreaksAre404() {
        assertThat(api.post("/v1/breaks/999999999/investigate", Map.of("version", 0)).status()).isEqualTo(404);
        assertThat(api.post("/v1/breaks/999999999/notes", Map.of("body", "x")).status()).isEqualTo(404);
        assertThat(api.get("/v1/breaks/999999999/history").status()).isEqualTo(404);
    }

    @Test
    void aReadOnlyClientCanReadTheTrailButChangeNothing() {
        ApiClient readOnly = api.withKey(ApiClient.READ_ONLY_KEY);

        assertThat(readOnly.post("/v1/breaks/" + breakId + "/investigate", Map.of("version", 0)).status())
                .isEqualTo(403);
        assertThat(readOnly.post("/v1/breaks/" + breakId + "/notes", Map.of("body", "x")).status()).isEqualTo(403);
        assertThat(readOnly.get("/v1/breaks/" + breakId + "/history").status()).isEqualTo(200);
        assertThat(history()).isEmpty();
    }

    private ApiResponse act(String action, Map<String, ?> body) {
        return api.post("/v1/breaks/" + breakId + "/" + action, body);
    }

    private List<JsonNode> history() {
        List<JsonNode> events = new ArrayList<>();
        api.get("/v1/breaks/" + breakId + "/history").body().forEach(events::add);
        return events;
    }

    private JsonNode breakOfType(String type) {
        for (JsonNode b : api.breaks(runId)) {
            if (type.equals(b.get("type").asString())) {
                return b;
            }
        }
        throw new AssertionError("no " + type + " break in run " + runId);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "-" : value.asString();
    }
}
