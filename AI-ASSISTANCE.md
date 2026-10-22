# AI Assistance

This project was built with an AI coding assistant (Claude Code), working from my specification: the problem, the
constraints (zero budget, local only, a 16 GB laptop, no invented numbers), the stack, and the phases in
[PLAN.md](PLAN.md). I made the decisions that shaped it (D1–D3 in PLAN.md) before any code depended on them; the
others are defaults recorded with their alternatives in [DESIGN.md](DESIGN.md), so any of them can be revisited.

The assistant wrote most of the code, tests and documentation, ran the tests, the benchmarks and the stack, and
recorded the results.

Nothing here is taken on trust:

- Every behaviour the README describes has a test that exercises it against real PostgreSQL, Kafka and nginx
  containers, including killing an instance mid-transaction and making changes straight in the database.
- Every number in the README is copied from a committed file in [`results/`](results/README.md), which names the
  commit and the machine it was measured on, and says how to reproduce it.
- CI re-runs every test and the accuracy check on each push and fails if a recorded count changes.

The MCP server in this repository runs no model and needs no AI key; it only lets an AI client read the data.
