#!/usr/bin/env bash
# One command to reproduce the matcher's precision and recall: generates synthetic data with known breaks,
# reconciles it through the running service's API, scores the result against the ground truth, and writes
# results/accuracy/latest.json, latest.md and a timestamped copy.
# Usage: scripts/accuracy.sh            (SEED and PAYMENTS override the defaults 42 and 10000)
# The service must be running: docker compose --profile lite up -d --build --wait
set -euo pipefail

cd "$(dirname "$0")/.."
[ -f .env ] || { echo "Copy .env.example to .env first" >&2; exit 1; }
set -a; source .env; set +a

export BASE_URL="${BASE_URL:-http://localhost:${HTTP_PORT:-8089}}"
# -dirty if anything outside results/ differs from HEAD: the numbers then belong to uncommitted code.
export GIT_SHA="$(git rev-parse --short HEAD)$(git diff --quiet HEAD -- . ':!results' || echo '-dirty')"

./mvnw -B -q -pl generator package -DskipTests
java -jar generator/target/generator-0.0.1-SNAPSHOT-cli.jar accuracy \
    --seed "${SEED:-42}" --payments "${PAYMENTS:-10000}" --results results/accuracy
