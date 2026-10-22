#!/usr/bin/env bash
# The 100,000-line benchmark: one large run through nginx, timed end to end and scored, against whatever
# compose profile is up (full = app1 + app2). 101,000 synthetic payments give 100,941 ledger records and
# 100,053 statement lines (seed 42), so both files have at least 100,000 rows.
#
# Writes results/benchmark/latest.json, latest.md and a timestamped copy, plus, next to it, a
# <timestamp>-details.txt with what the JSON cannot know: each container's peak memory, how many lines each
# Kafka partition carried and which instance owned it, which instance stored how many lines, and the
# server's own timestamps for each phase.
#
# Usage: HARDWARE_NOTE="..." scripts/benchmark.sh   (PAYMENTS and SEED override 101000 and 42)
# The stack must be running: docker compose --profile full up -d --build --wait
# Start from an empty database (docker compose down -v first) so earlier runs do not change the timings.
set -euo pipefail

cd "$(dirname "$0")/.."
[ -f .env ] || { echo "Copy .env.example to .env first" >&2; exit 1; }
set -a; source .env; set +a

export BASE_URL="${BASE_URL:-http://localhost:${HTTP_PORT:-8089}}"
# -dirty if anything outside results/ differs from HEAD: the numbers then belong to uncommitted code.
export GIT_SHA="$(git rev-parse --short HEAD)$(git diff --quiet HEAD -- . ':!results' || echo '-dirty')"
apps=$(docker compose ps --services --status running | grep -c '^app' || true)
export TOPOLOGY="${TOPOLOGY:-$apps app instance(s) behind nginx; Kafka topic with 6 partitions, 3 consumers per instance}"
export HARDWARE_NOTE="${HARDWARE_NOTE:-not recorded}"
cli=generator/target/generator-0.0.1-SNAPSHOT-cli.jar
psql() { docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"; }
offsets() { docker compose exec -T kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 \
    --topic statement.lines | sort; }

./mvnw -B -q -pl generator package -DskipTests

# A small run first, not recorded, so the JIT has compiled the hot paths before the measured run.
warmup=$(mktemp -d)
java -jar "$cli" benchmark --seed 7 --payments 10000 --results "$warmup" > /dev/null
rm -rf "$warmup"

# Sample every container's memory once a second while the benchmark runs.
samples=$(mktemp)
(while true; do docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' >> "$samples"; sleep 1; done) &
sampler=$!
trap 'kill $sampler 2>/dev/null || true; rm -f "$samples"' EXIT

before=$(offsets)
java -jar "$cli" benchmark --seed "${SEED:-42}" --payments "${PAYMENTS:-101000}" --results results/benchmark
after=$(offsets)
kill $sampler 2>/dev/null || true

stamp=$(sed -n 's/^  "recordedAt" : "\(.*\)",$/\1/p' results/benchmark/latest.json | tr ':' '-')
run_id=$(sed -n 's/^  "runId" : \([0-9]*\),$/\1/p' results/benchmark/latest.json)
details="results/benchmark/$stamp-details.txt"
{
    echo "Run $run_id, commit $GIT_SHA"
    echo
    echo "## Peak memory per container during the run (docker stats, sampled every second; [limit])"
    # "app1 312.5MiB / 768MiB" -> the highest MiB seen per container.
    awk '{ v = $2; u = v; sub(/[A-Za-z]+$/, "", v); sub(/^[0-9.]+/, "", u);
           m = (u == "GiB") ? v * 1024 : (u == "KiB" ? v / 1024 : v);
           if (m > peak[$1]) peak[$1] = m; limit[$1] = $4 }
         END { for (c in peak) printf "%-45s %8.1f MiB  [%s]\n", c, peak[c], limit[c] }' "$samples" | sort
    echo
    echo "## Lines published per partition during the run (end offsets after minus before)"
    join -t: -j1 <(echo "$before" | awk -F: '{print $2":"$3}') <(echo "$after" | awk -F: '{print $2":"$3}') \
        | awk -F: '{printf "partition %s: %d records\n", $1, $3 - $2}'
    echo
    echo "## Which consumer owned each partition (at the end of the run)"
    docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
        --describe --group statement-matcher 2>/dev/null | awk 'NR == 1 || /statement.lines/ {print $3, $4, $6, $NF}'
    echo
    echo "## Lines stored per instance"
    psql -c "SELECT ingested_by AS instance, count(*) AS lines_stored FROM statement_lines
             WHERE run_id = $run_id GROUP BY 1 ORDER BY 1"
    echo "## Server timestamps: statement recorded, last batch of lines begun, run completed"
    psql -c "SELECT i.created_at AS statement_recorded, max(s.created_at) AS last_batch_begun,
                    r.completed_at, r.completed_at - max(s.created_at) AS last_batch_and_end_of_run_step
             FROM runs r JOIN imports i ON i.run_id = r.id AND i.side = 'STATEMENT'
             JOIN statement_lines s ON s.run_id = r.id WHERE r.id = $run_id GROUP BY i.created_at, r.completed_at"
    echo "## The end-of-run step, as the worker that completed the run logged it"
    docker compose logs --no-log-prefix app1 app2 2>/dev/null | grep -o "Run $run_id completed[^\"]*" || true
} > "$details"
cat results/benchmark/latest.md "$details"
