# Transaction Audit & Matching Service: Plan

> **Status: executed autonomously from 2026-10-19.** You asked me to build the whole project, so the phases run back to back. Each ends with a git tag (`phase-1` … `phase-7`). Done so far: phases 1–4.
>
> You approved D1–D3 before any code depended on them. Every other "default I picked" is a decision you hadn't made yet. DESIGN.md records each one as choice / alternatives / why, so any of them is easy to change.

**30-second pitch.** It compares our ledger against a bank statement, finds every mismatch, lets a person resolve them with a full audit trail, and exposes a read-only AI interface so I can ask questions about the mismatches in plain English.

---

## 1. Constraints (from your spec)

- **Zero budget.** Everything runs locally with Docker Compose. No paid services and no accounts, except GitHub for the repo and CI.
- **No invented numbers.** Every README number comes from a committed result file or test.
- **No AI API keys.** The MCP server only exposes tools. The AI client (not this project) runs the model.
- **16 GB laptop.** Every container has a memory limit with matching JVM flags. Never run this stack at the same time as the Payments Ledger Service stack.
- **Same stack as the Payments Ledger Service:** Java 21, Spring Boot 4.1.1, PostgreSQL 18.

## 2. Decisions

| # | Decision | Choice | Why / how to change |
|---|---|---|---|
| D1 | MCP library (**you approved**) | The official **MCP Java SDK 2.1.0** (`io.modelcontextprotocol.sdk:mcp`) in a plain Boot 4.1.1 module | 5 extra jars, explicit wiring, stdio + Streamable HTTP servlet transport, and a client for tests. Spring AI 2.0.1 also works on Boot 4.1.1, but it adds about 15 jars of model layer and auto-config. Fallback: SDK 2.0.1. |
| D2 | Cloud phase (**you approved**) | **Skipped.** Nothing is deployed. | Oracle is out (your call), and you chose to skip AWS and Azure too. The README says everything runs locally. |
| D3 | AI clients in MCP_GUIDE.md (**you approved**) | MCP Inspector is the tested path; Antigravity CLI and Gemini CLI are documented and marked "not tested by me" | Gemini CLI's free Google-login tier ended on 2026-06-18 (Google's README: replaced by Antigravity CLI for unpaid users). |
| D4 | When each rule runs | The **exact** rule runs on each statement line as it arrives (Kafka worker). The **tolerance** and **normalized-reference** rules run once per run, after the last line | A fuzzy match can never take a ledger record that a later line would have matched exactly. The rule order stays (a) → (b) → (c). |
| D5 | Match rate | matched records / all records, counting both sides | One number that drops for a missing record on either side. |
| D6 | Upload order | A run takes one ledger file, then one statement file. Re-uploading the same file (same SHA-256) is a no-op; a different file is 409 | Matching needs the whole ledger. Retrying an upload is always safe. |
| D7 | "Who" in the audit trail | The authenticated API client id (`ops`, `mcp`, …) | User login is out of scope. Each person can get their own key, and the trail then names them. |
| D8 | Default tolerances | Amount ±$1.00, dates ±3 days, copied into each run when it is created | Typical bank-fee and settlement-delay sizes. A run can override both. |
| D9 | Aging buckets | 0–2, 3–7, 8–30, 31+ days from the item date | Daily reconciliation cares about days, not quarters. |
| D10 | Payments Ledger import | `scripts/export-payments-ledger.sh` turns that project's ledger entries into this project's plain ledger CSV with one SQL `\copy` | One CSV format to validate and test. The other project stays untouched. |
| D11 | Persistence style | JPA for entities with behaviour (run, break workflow, notes); plain JDBC with `unnest` arrays for bulk inserts and matching queries | Hibernate cannot batch inserts with identity ids. 100,000 lines need batching. |
| D12 | Ports | 8089 (nginx), 5433 (Postgres), and so on, all on 127.0.0.1 | Different from the ledger project, so a stray stack never answers on the wrong port. |
| D13 | Getting statement lines onto Kafka | The upload records the statement (import row, run → MATCHING) and commits. Then it publishes every accepted line, keyed by account, and answers 201 only after Kafka has acknowledged them all. If publishing fails it answers 503, and uploading the same file again resumes, re-sending only lines not yet stored. The run shows `statementQueuedAt` once every line is acknowledged. A re-upload that validates differently from the first (a newer version's rules) is refused with 409 | The alternative is a transactional outbox: keep the file in Postgres and have a relay publish it. That adds a second copy of 100,000 lines and a relay, to close a gap that retrying the upload already closes. nginx re-sends an upload whose instance died (`UploadFailoverIT`), and the project's own clients retry. Cost: if nobody ever retries, the run stays MATCHING with `statementQueuedAt` empty, which says the file must be uploaded again. |
| D14 | Matching order inside an account | Each message names the account's previous row. A worker stores a line only once that row is stored, and drops it otherwise (it comes again with the next upload of the file) | Kafka keeps one producer's records in order on a partition. Redelivery, two uploads interleaving, or a send failing mid-upload can still put a line before its predecessor. The exact rule must see file order, or results stop being reproducible (`AccuracyResultsIT`, `LineChainPropertyTest`). |
| D15 | Completing a run | A counter on the run row, raised in the same transaction that stores lines. The transaction that brings it to the accepted count runs the end-of-run step | Exactly one transaction sees that transition, so nothing polls and a run cannot complete twice or early. |
| D16 | Partitioning | 6 partitions keyed by account id, 3 consumers per instance, consumer client id = instance id | 6 splits evenly over 1, 2 or 3 instances. Lines need not split evenly: with the generator's 12 accounts, one partition carries a third of them (the benchmark records this). |
| D17 | A record that fails | An invalid record goes to `statement.lines.DLT`. A transient failure is retried with back-off until it succeeds: database down, lock timeout, deadlock, or a session Postgres ended, recognised by exception type or SQLState. Any other failure is tried twice more, then logged and recorded on the run as `matchingProblem` (exception type and SQLState, never imported text), and the batch goes on with other runs | A run cannot complete with a line missing: completion counts stored lines, and the counter moves only while the run is MATCHING and never past the accepted count. A run that hits a bug therefore waits in MATCHING with the reason shown, and nobody else's run is held up (`StatementLineListenerIT`). |
| D18 | Noticing a dead instance | Kafka session timeout 10 s. Postgres ends a session idle in a transaction after 15 s (lifted for the end-of-run step), and checks for a vanished client every 1 s | A killed instance's partitions and locks pass to the other instance within seconds (`InstanceFailoverIT`). |
| D19 | Workflow steps | OPEN → INVESTIGATING → RESOLVED, plus RESOLVED → INVESTIGATING to reopen. No skipping straight to RESOLVED. Investigating needs an owner (the one given, else the current one, else the caller). Resolving needs a resolution code (`TIMING_DIFFERENCE`, `BANK_FEE`, `BANK_ERROR`, `LEDGER_CORRECTED`, `WRITTEN_OFF`, `OTHER`) and a note; reopening needs a note. A resolved break cannot be reassigned until reopened. Notes can be added in any state | The codes make "how were breaks settled" answerable later (dashboard, MCP) without parsing free text. The note says why. |
| D20 | Who writes the audit trail | **Postgres triggers**, in the same transaction as the change. Each transaction names its caller with `set_config('matching.actor', …, true)` (SET LOCAL), and the triggers refuse any change to a break, and any note, that names nobody. The trail and the notes are append-only, and a break's recorded facts (type, records, exposure, …) cannot change | The alternative, writing audit rows from the service, misses changes made in psql and depends on every future code path remembering to do it. Cost: a manual fix in psql must `SET LOCAL matching.actor` first, which is the point (`AuditTrailIT`). |
| D21 | Two people changing one break | Optimistic: every change sends the `version` it read, the database raises the version on every change (including ones made outside the service), and a stale request gets 409 and changes nothing. Notes carry no version: they never conflict | Last-write-wins silently loses someone's change. A lock held across a person's think time would block everyone else (`BreakWorkflowIT`: of 8 simultaneous resolves, exactly one wins). |

## 3. Stack (versions checked 2026-10-19)

| Piece | Version |
|---|---|
| Java / Spring Boot | 21 / 4.1.1 (Framework 7.0.9, Jackson 3.1.5) |
| PostgreSQL | `postgres:18.6` |
| Kafka | `apache/kafka:4.3.1` (KRaft) |
| nginx | `nginx:1.30.6-alpine` (stable line) |
| kafbat-ui | `ghcr.io/kafbat/kafka-ui:v1.5.0` |
| Prometheus / Grafana | `prom/prometheus:v3.13.4` (LTS) / `grafana/grafana:13.2.4` |
| MCP | MCP Java SDK 2.1.0; MCP Inspector 2.11.0 for manual testing |
| CSV | Apache Commons CSV 1.14.1 |
| Tests | JUnit (Boot-managed), Testcontainers 2.0.5 |
| Secret scan | gitleaks v8.30.1 |
| Chart.js | 4.5.1, vendored into the repo (MIT licence) |

## 4. Phases (as your spec defines them)

| Phase | Builds | Proof / tests |
|---|---|---|
| 1 | Runs; CSV/JSON ingestion with validation and a rejection report; exact matching; every break type with aging; API keys with operator/read-only roles | `ReconciliationIT`, `ImportApiIT`, `AuthIT` (path tricks), `SourceImmutabilityIT`, rule unit tests |
| 2 | Tolerance and normalized-reference rules; synthetic generator with known breaks; precision/recall | rule tests; `scripts/accuracy.sh` → `results/accuracy/` |
| 3 | Kafka (KRaft) ingestion partitioned by account; nginx + 2 instances; compose lite/full | `SameFileConcurrencyIT` (same file through both instances); `InstanceFailoverIT` (SIGKILL mid-transaction) and `UploadFailoverIT` (SIGKILL mid-upload); `scripts/benchmark.sh` → `results/benchmark/` |
| 4 | Workflow OPEN → INVESTIGATING → RESOLVED, assignee, notes, append-only audit trail with triggers | `BreakWorkflowIT` (every step through the API, stale versions, 8 simultaneous resolves); `AuditTrailIT` (changes made straight in Postgres are recorded or refused; the trail is append-only) |
| 5 | Dashboard (vanilla JS, Chart.js, SSE); Prometheus + Grafana | stream and dashboard tests |
| 6 | MCP server module (stdio + HTTP), read-only by design, with safety controls | every tool through an MCP client; a test that no tool can change data |
| 7 | CI (tests, compose smoke, gitleaks, commit-message check), README, DESIGN, MCP_GUIDE, AI-ASSISTANCE, demo GIF | CI green |
| 8 | Optional cloud VM | **Skipped (D2)** |
