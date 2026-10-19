#!/usr/bin/env bash
# Exports the Payments Ledger Service's completed ledger entries as a ledger CSV in this project's format,
# with one read-only \copy. Nothing in that project is changed.
#
#   Each ledger entry  ->  one ledger record
#   external_id  PLS-E<entry id>       account_id   PLS-<account id>
#   booking_date the entry's UTC date  amount       signed, in dollars (debits negative)
#   reference    TRF-<transfer id>     description  transfer or reversal
#
# Usage: scripts/export-payments-ledger.sh [output.csv]   (default data/payments-ledger-export.csv)
# Needs only that project's postgres container: (cd "$LEDGER_DIR" && docker compose up -d --wait postgres)
# Do not start its full stack while this project's stack is running: together they do not fit in memory.
set -euo pipefail

cd "$(dirname "$0")/.."
LEDGER_DIR="${LEDGER_DIR:-../payments-ledger-service}"
out="${1:-data/payments-ledger-export.csv}"
[ -f "$LEDGER_DIR/.env" ] || { echo "No $LEDGER_DIR/.env: set LEDGER_DIR to the Payments Ledger Service checkout" >&2; exit 1; }
db_user="$(sed -n 's/^POSTGRES_USER=//p' "$LEDGER_DIR/.env")"
db_name="$(sed -n 's/^POSTGRES_DB=//p' "$LEDGER_DIR/.env")"

mkdir -p "$(dirname "$out")"
(cd "$LEDGER_DIR" && docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U "$db_user" -d "$db_name" -c "\copy (
    SELECT 'PLS-E' || e.id                                           AS external_id,
           'PLS-' || e.account_id                                    AS account_id,
           to_char(e.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD')    AS booking_date,
           (e.amount_cents::numeric / 100)::numeric(14, 2)           AS amount,
           'USD'                                                     AS currency,
           'TRF-' || e.transfer_id                                   AS reference,
           lower(t.kind)                                             AS description
      FROM ledger_entries e
      JOIN transfers t ON t.id = e.transfer_id
     WHERE t.status = 'COMPLETED'
     ORDER BY e.id
) TO STDOUT WITH (FORMAT csv, HEADER)") > "$out"

echo "Wrote $(($(wc -l < "$out") - 1)) ledger records to $out"
