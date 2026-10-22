# Design

How the service works, which invariants hold and what enforces them, and every decision with the alternatives
that were considered. Decision numbers (D1…D27) match [PLAN.md](PLAN.md).

## Constraints

- Zero budget: everything runs locally with Docker Compose. No paid services, no cloud, no AI API keys.
- A 16 GB laptop: every container has a memory limit and the JVMs are sized to fit it.
- No invented numbers: every figure in the README comes from a committed file in `results/` or from a test.
- The same stack as the companion Payments Ledger Service: Java 21, Spring Boot 4.1, PostgreSQL 18.

## Components

| Module | Role |
|---|---|
| `service` | The matching service: API, ingestion, Kafka producer and consumers, matching, breaks, workflow, dashboard, metrics |
| `mcp-server` | A separate read-only MCP server that calls the service's API (stdio and Streamable HTTP) |
| `generator` | Synthetic ledger and statement files with known breaks, a scorer, and the accuracy and throughput runners |

The service is organised by feature: `run`, `ingest`, `matching`, `breaks`, `dashboard`, `web` (keys, roles,
problem details, headers), `common`.

## Data model

| Table | Holds | Changes allowed |
|---|---|---|
| `runs` | Label, status (`RECEIVING` → `MATCHING` → `COMPLETED`), tolerances copied at creation, the line counter, `statementQueuedAt`, `matchingProblem` | Status moves forward only |
| `imports` | One per side per run: SHA-256, row counts, rejection report | None (append-only) |
| `ledger_records`, `statement_lines` | The accepted rows, with a normalised reference | None (append-only) |
| `matches` | Ledger record ↔ statement line, the rule that matched them, the instance that did | None (append-only) |
| `breaks` | Type, both sides (either may be absent), exposure, item date, the rule trace; workflow status, assignee, resolution, version | Only `status`, `assignee`, `resolution` |
| `break_notes`, `break_events` | Notes, and the audit trail written by triggers | None (append-only) |

The database enforces the invariants itself, so no bug in the service (and no hand-written SQL) can break them:

- **Append-only facts.** `imports`, `ledger_records`, `statement_lines`, `matches`, `break_notes` and `break_events`
  refuse UPDATE, DELETE and TRUNCATE (trigger `forbid_mutation`); `breaks` refuses DELETE and TRUNCATE.
- **One-to-one matches.** `matches.ledger_record_id` and `statement_line_id` are each UNIQUE.
- **No pairing across runs or accounts.** Matches and breaks reference records by `(id, run_id, account_id)`, so a
  composite foreign key rejects a pair from different runs or accounts.
- **Matched or broken, never both.** Triggers refuse a break for a matched record and a match for a broken one.
- **The workflow and its trail.** A trigger allows only OPEN → INVESTIGATING → RESOLVED and RESOLVED →
  INVESTIGATING, refuses changes to a break's recorded facts, raises its version, and requires
  `matching.actor` to be set; another writes the change to `break_events`. CHECK constraints require an owner past
  OPEN and a resolution exactly when RESOLVED.

`SourceImmutabilityIT` and `AuditTrailIT` try each of these in plain SQL.

## The life of a run

1. **Create** (`POST /v1/runs`): tolerances default to ±$1.00 and ±3 days and are copied into the run (D8).
2. **Ledger upload**: parsed and validated row by row (CSV or JSON, size and row limits); accepted rows are
   bulk-inserted with one `INSERT … SELECT FROM unnest(arrays)` per batch (D11); the import row stores the
   rejection report. Uploading the same bytes again returns the original report; different bytes get 409 (D6).
3. **Statement upload**: validated the same way. The import is recorded and the run moves to MATCHING in one
   transaction; then every accepted line is published to `statement.lines`, keyed by account, and the upload answers
   201 only after Kafka acknowledged all of them. If that fails, the answer is 503 and uploading the same file again
   resumes, skipping lines already stored (D13).
4. **Workers** (3 consumers per instance, 6 partitions): a batch takes a transaction-scoped advisory lock per
   (run, account), stores the lines whose predecessor in the file is already stored (D14), runs the exact rule on
   them, and adds the number stored to the run's counter, all in one transaction.
5. **End of run**: the transaction that brings the counter to the accepted line count runs rules (b) and (c) over
   everything still unmatched, classifies the rest as breaks, and completes the run (D15). Exactly one transaction
   can see that transition, so a run cannot complete twice or early.
6. **Workflow**: people investigate, assign, resolve and reopen breaks; the database keeps the trail (D19–D21).

### Matching

- **(a) Exact**: same account, the same reference character for character, the same amount to the cent. Runs on
  each line as it is stored, in file order.
- **(b) Tolerance**: same account and sign, amount within the run's tolerance, dates within its window. Two passes:
  first only pairs whose references also agree, then the rest.
- **(c) Normalised reference**: same account and sign, the same reference after normalisation (case, separators,
  leading zeros: `INV-000003` = `inv 3`), amount within tolerance, any date.

Whatever is still unmatched becomes a break, classified in this order: `DUPLICATE` (a second copy of a matched
record), `AMOUNT_MISMATCH` (a ledger record and a bank line with the same normalised reference),
`MISSING_IN_LEDGER` / `MISSING_IN_BANK`. Every choice has an explicit tie-break and records are visited in a fixed
order, so the same files always give the same result; `AccuracyResultsIT` holds the recorded counts to that.

### Failures

| Failure | What happens |
|---|---|
| An instance dies mid-upload | nginx re-sends the buffered upload to the other instance (safe: uploads are idempotent) (`UploadFailoverIT`) |
| An instance dies mid-transaction | Postgres notices the vanished client within about a second and rolls back, releasing its locks; Kafka moves its partitions after the 10 s session timeout; the batch is redelivered and stored once (`InstanceFailoverIT`, D18) |
| A line arrives before its predecessor | It is dropped and arrives again with the next upload of the file; the exact rule always sees file order (D14) |
| Database down, lock timeout, deadlock | Retried with exponential back-off until it works (D17) |
| A record that is not a statement line | Sent to `statement.lines.DLT` (D17) |
| Any other error | Tried twice more, then recorded on the run as `matchingProblem`; that run waits in MATCHING with the reason shown, and other runs go on (D17) |

## Security

- API keys (D7): the service stores only SHA-256 hashes, compares them in constant time, and gives each key a role.
  Read-only keys may only GET or HEAD. The role check runs on the path Spring routes on, so tricks like
  `/v1;x/runs` or `/vendor/../v1/runs` cannot reach a write as a read (`AuthIT`); nginx also refuses `;` in paths.
- Client ids must match `[A-Za-z0-9._@-]{1,64}`, because they become the actor in the audit trail.
- Every response gets an `X-Correlation-Id` (a client's own value if it is safe, otherwise a new UUID), which is
  logged and stored with each audit entry.
- The dashboard writes every imported value as text, never HTML, and a Content Security Policy allows only the
  page's own scripts and connections.
- Every port is published on 127.0.0.1 only. `/actuator` is not reachable through nginx (except health).
- CI scans the full git history with gitleaks; only the documented demo keys in `.env.example` are allowlisted.

## Decisions

Each one: the choice, the alternatives considered, and why. D1–D3 were approved by the owner before any code
depended on them; the rest are defaults chosen during the build and are easy to change.

**D1 MCP library.** *Choice:* the official MCP Java SDK 2.1.0 in a plain Boot module. *Alternatives:* Spring AI
2.0.1's MCP server starter. *Why:* five extra jars and explicit wiring, versus about fifteen jars of model layer and
auto-configuration this project does not use. The SDK also has the client the tests need.

**D2 Cloud.** *Choice:* nothing is deployed. *Alternatives:* a free-tier VM on Oracle, AWS or Azure. *Why:* the
owner's call; the README says everything runs locally.

**D3 AI clients in the MCP guide.** *Choice:* MCP Inspector is the tested path; other clients are documented as
untested. *Why:* it needs no account and has a scriptable CLI. Gemini CLI's free Google-login tier ended on
2026-06-18.

**D4 When each rule runs.** *Choice:* exact on each line as it arrives; tolerance and normalised reference once
per run, after the last line. *Alternatives:* every rule per line. *Why:* a fuzzy match made early could take a
ledger record that a later line matches exactly.

**D5 Match rate.** *Choice:* matched records ÷ all records, both sides. *Alternatives:* matched lines ÷ lines.
*Why:* one number that drops for a missing record on either side.

**D6 Uploads.** *Choice:* one ledger file, then one statement file per run; same bytes = no-op, other bytes = 409.
*Alternatives:* appending files. *Why:* matching needs the whole ledger, and retrying an upload is always safe.

**D7 Who is "who".** *Choice:* the API client id. *Alternatives:* user accounts and logins. *Why:* out of scope;
each person can get their own key, and the trail then names them.

**D8 Default tolerances.** *Choice:* ±$1.00 and ±3 days, copied into each run. *Why:* typical bank-fee and
settlement-delay sizes; copying them means a run can always be explained by its own settings.

**D9 Aging buckets.** *Choice:* 0–2, 3–7, 8–30, 31+ days from the item date. *Alternatives:* 30/60/90.
*Why:* daily reconciliation cares about days, not quarters.

**D10 Importing the Payments Ledger.** *Choice:* a script that `\copy`s that project's entries into this
project's ledger CSV. *Alternatives:* a connector reading its database. *Why:* one CSV format to validate and test,
and the other project stays untouched.

**D11 Persistence.** *Choice:* JPA for entities with behaviour (run, break workflow, notes); plain JDBC with
`unnest` arrays for bulk inserts and matching queries. *Alternatives:* JPA everywhere. *Why:* Hibernate cannot
batch inserts with identity ids, and 100,000 lines need batching.

**D12 Ports.** *Choice:* 8089, 5433, 9095… on 127.0.0.1. *Why:* different from the ledger project, so a stray
stack never answers on the wrong port.

**D13 Getting lines onto Kafka.** *Choice:* record the statement, commit, publish every line, answer 201 only
when Kafka has acknowledged them all; on failure answer 503 and let a re-upload resume. *Alternatives:* a
transactional outbox. *Why:* the outbox stores a second copy of 100,000 lines and adds a relay to close a gap that
retrying the upload already closes. *Cost:* if nobody retries, the run waits in MATCHING with `statementQueuedAt`
empty, which says what to do.

**D14 Order inside an account.** *Choice:* each message names the account's previous row; a worker stores a line
only once that row is stored. *Alternatives:* trusting Kafka's per-partition order. *Why:* redelivery, interleaved
uploads or a failed send can still reorder lines, and the exact rule must see file order for results to be
reproducible (`LineChainPropertyTest`).

**D15 Completing a run.** *Choice:* a counter on the run row, raised in the transaction that stores lines.
*Alternatives:* polling for "all lines in". *Why:* exactly one transaction sees the counter reach the total.

**D16 Partitioning.** *Choice:* 6 partitions keyed by account, 3 consumers per instance. *Why:* 6 splits evenly
over 1, 2 or 3 instances. Lines need not split evenly: the benchmark records each partition's share.

**D17 Failed records.** *Choice:* invalid → dead-letter topic; transient → retry with back-off; anything else →
two more tries, then recorded on the run. *Why:* a run can never complete with a line missing, and one bad run
does not hold up others.

**D18 Noticing a dead instance.** *Choice:* Kafka session timeout 10 s; Postgres ends a session idle in a
transaction after 15 s and checks for vanished clients every second. *Why:* a killed instance's partitions and
locks pass on within seconds.

**D19 Workflow steps.** *Choice:* OPEN → INVESTIGATING → RESOLVED, and reopen; an owner past OPEN; a resolution
code and a note to resolve. *Alternatives:* free-text statuses. *Why:* the codes make "how were breaks settled"
answerable without parsing text.

**D20 Who writes the audit trail.** *Choice:* Postgres triggers, with the actor named per transaction.
*Alternatives:* audit rows written by the service. *Why:* the service cannot see changes made in `psql`, and every
future code path would have to remember. *Cost:* a manual fix must `SET LOCAL matching.actor` first.

**D21 Concurrent edits.** *Choice:* optimistic, with a version the database raises on every change.
*Alternatives:* last write wins; locking a break while someone works on it. *Why:* the first loses changes
silently; the second blocks others for a person's think time.

**D22 Live dashboard.** *Choice:* Server-Sent Events, each instance reading the latest runs from Postgres once a
second while anyone is connected. *Alternatives:* WebSockets; Postgres LISTEN/NOTIFY. *Why:* SSE is one-way, which
is all the page needs. Reading the database is correct with two instances by construction (a dashboard on app1
sees app2's work); LISTEN/NOTIFY would need a dedicated connection per instance and triggers on hot tables.
*Cost:* up to a second of delay.

**D23 Counting for Prometheus.** *Choice:* counters added after the transaction commits; backlog gauges read from
Postgres every 15 s. *Why:* rolled-back batches are never counted, so the totals agree with the database.

**D24 Prometheus and Grafana.** *Choice:* in the `full` profile only, provisioned, anonymous view-only Grafana.
*Why:* the `lite` profile stays small for daily work.

**D25 What the MCP server can reach.** *Choice:* seven GET-only tools over the public API with the read-only key,
plus a start-up check that the key really cannot write. *Alternatives:* querying Postgres directly; tools that
change breaks. *Why:* two independent locks (no code that writes, a key the service refuses), and it sees exactly
what the dashboard sees without database credentials.

**D26 Text an AI reads.** *Choice:* every result says where it came from and that its text is data; arguments are
checked before any request. *Why:* uploaded files and notes are attacker-controllable text that ends up in a model's
context.

**D27 MCP transports.** *Choice:* stdio and Streamable HTTP; HTTP needs a bearer token, an allowed Host and Origin.
*Alternatives:* HTTP without authentication on loopback. *Why:* any web page can make a browser send requests to
localhost, and DNS rebinding can make them look same-origin.
