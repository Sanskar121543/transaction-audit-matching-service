# Results

Recorded evidence. The README quotes only numbers from these files.

| File | What it records |
|---|---|
| [`accuracy/latest.md`](accuracy/latest.md), [`accuracy/latest.json`](accuracy/latest.json) | The most recent precision/recall run (copied into the README) |
| `accuracy/<timestamp>.json` | Every recorded accuracy run, with the generator config and the git commit it ran on |
| [`benchmark/latest.md`](benchmark/latest.md), [`benchmark/latest.json`](benchmark/latest.json) | The most recent 100,000-line throughput run: upload and matching times, and the run's precision/recall |
| `benchmark/<timestamp>.json`, `benchmark/<timestamp>-details.txt` | Every recorded throughput run. The details file adds peak memory per container, lines per Kafka partition and which instance owned it, lines stored per instance, and the server's own phase timestamps |

Reproduce an accuracy run with `scripts/accuracy.sh` (seed 42, 10,000 payments by default). The generator and the
matcher are both deterministic, so the same commit always gives the same counts.

Reproduce a throughput run with `scripts/benchmark.sh` against `docker compose --profile full up -d --build --wait`,
starting from an empty database (`docker compose down -v`). Its timings depend on the machine, so each file names it.
