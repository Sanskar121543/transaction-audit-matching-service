#!/usr/bin/env bash
# End-to-end check against a running stack, through nginx: a run, a ledger, a statement that goes through
# Kafka, the run completing with the expected matches and breaks, a replayed upload, the dashboard and its
# event stream, and (full profile) Prometheus scraping every instance and Grafana's provisioned dashboard.
# Usage: scripts/smoke.sh [base-url]   (reads OPERATOR_API_KEY from .env)
# EXPECT_INSTANCES (default 2) is how many different app instances must answer: 1 for the lite profile.
set -euo pipefail

cd "$(dirname "$0")/.."
# shellcheck disable=SC1091
set -a; source .env; set +a
BASE="${1:-http://localhost:${HTTP_PORT:-8089}}"
KEY_HEADER="X-API-Key: ${OPERATOR_API_KEY}"

post() { curl -sS -X POST "$BASE$1" -H "$KEY_HEADER" -H "Content-Type: $2" --data-binary "$3" -w '\n%{http_code}'; }
get() { curl -sS "$BASE$1" -H "$KEY_HEADER"; }
field() { grep -o "\"$1\":[^,}]*" | head -1 | cut -d: -f2- | tr -d '"'; }

ledger='external_id,account_id,booking_date,amount,reference
L-1,ACC-1,2026-10-01,100.00,INV-1
L-2,ACC-1,2026-10-02,250.00,INV-2
L-3,ACC-2,2026-10-03,-40.00,PAY-3'
statement='account_id,value_date,amount,reference
ACC-1,2026-10-01,100.00,INV-1
ACC-1,2026-10-03,249.50,WIRE 9
ACC-2,2026-10-03,-40.00,PAY-3
ACC-2,2026-10-04,-2.50,'

run=$(post /v1/runs application/json '{"label":"smoke"}' | head -1 | field id)
post "/v1/runs/$run/ledger" text/csv "$ledger" | tail -1 | grep -qx 201 || { echo "FAIL: ledger upload"; exit 1; }
post "/v1/runs/$run/statement" text/csv "$statement" | tail -1 | grep -qx 201 || { echo "FAIL: statement upload"; exit 1; }

status=""
for _ in $(seq 1 100); do
    status=$(get "/v1/runs/$run" | field status)
    [ "$status" = "COMPLETED" ] && break
    sleep 0.2
done
[ "$status" = "COMPLETED" ] || { echo "FAIL: run $run is $status after 20 s"; exit 1; }

summary=$(get "/v1/runs/$run")
matches=$(echo "$summary" | field matches)
breaks=$(echo "$summary" | field breaks)
replay=$(post "/v1/runs/$run/statement" text/csv "$statement" | tail -1)

instances=$(for _ in 1 2 3 4 5 6 7 8; do curl -sS -o /dev/null -D - "$BASE/actuator/health" | tr -d '\r' | sed -n 's/^X-Served-By: //Ip'; done | sort -u | tr '\n' ' ')
seen=$(echo "$instances" | wc -w)

echo "run=$run matches=$matches breaks=$breaks replay_status=$replay instances_seen=[$instances]"
[ "$matches" = "3" ] && [ "$breaks" = "1" ] && [ "$replay" = "200" ] || { echo "FAIL"; exit 1; }
[ "$seen" -ge "${EXPECT_INSTANCES:-2}" ] || { echo "FAIL: expected ${EXPECT_INSTANCES:-2} instances, saw $seen"; exit 1; }

# The dashboard page needs no key; its event stream does, and must come through nginx unbuffered.
curl -fsS "$BASE/" | grep -q "<title>Reconciliation Dashboard</title>" || { echo "FAIL: dashboard page"; exit 1; }
event=$(curl -sS -N --max-time 5 -H "$KEY_HEADER" "$BASE/v1/stream" 2>/dev/null | grep -m1 '^event:' || true)
[ "$event" = "event:runs" ] || { echo "FAIL: event stream sent '$event'"; exit 1; }
curl -fsS -H "$KEY_HEADER" "$BASE/v1/runs/$run/breakdown" | grep -q '"byType"' || { echo "FAIL: breakdown"; exit 1; }

# With the full profile: Prometheus scrapes every instance, and Grafana has the provisioned dashboard.
if docker compose ps --services --status running 2>/dev/null | grep -qx prometheus; then
    prometheus="http://localhost:${PROMETHEUS_PORT:-9091}"
    grafana="http://localhost:${GRAFANA_PORT:-3001}"
    up=0
    for _ in $(seq 1 30); do
        up=$(curl -fsS "$prometheus/api/v1/query?query=sum(up%7Bjob%3D%22matching%22%7D)" \
            | sed -n 's/.*"value":\[[^,]*,"\([0-9]*\)"\].*/\1/p')
        [ "${up:-0}" -ge "${EXPECT_INSTANCES:-2}" ] && break
        sleep 2
    done
    [ "${up:-0}" -ge "${EXPECT_INSTANCES:-2}" ] || { echo "FAIL: Prometheus scrapes ${up:-0} instances"; exit 1; }
    provisioned=""
    for _ in $(seq 1 30); do
        provisioned=$(curl -fsS "$grafana/api/dashboards/uid/reconciliation" 2>/dev/null | grep -o '"uid":"reconciliation"' || true)
        [ -n "$provisioned" ] && break
        sleep 2
    done
    [ -n "$provisioned" ] || { echo "FAIL: Grafana dashboard not provisioned"; exit 1; }
    echo "prometheus_targets_up=$up grafana_dashboard=ok"
fi
echo "SMOKE OK"
