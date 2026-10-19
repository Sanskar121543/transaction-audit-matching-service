# Results

Recorded evidence. The README quotes only numbers from these files.

| File | What it records |
|---|---|
| [`accuracy/latest.md`](accuracy/latest.md), [`accuracy/latest.json`](accuracy/latest.json) | The most recent precision/recall run (copied into the README) |
| `accuracy/<timestamp>.json` | Every recorded accuracy run, with the generator config and the git commit it ran on |

Reproduce an accuracy run with `scripts/accuracy.sh` (seed 42, 10,000 payments by default). The generator and the
matcher are both deterministic, so the same commit always gives the same counts.
