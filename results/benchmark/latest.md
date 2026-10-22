| Metric | Value |
|---|---|
| Data | 101000 synthetic payments (seed 42) over 12 accounts: 100941 ledger records (6.7 MB CSV), 100053 statement lines (6.4 MB CSV) |
| Topology | 2 app instance(s) behind nginx; Kafka topic with 6 partitions, 3 consumers per instance |
| Ledger upload (validate + store) | 2.7 s |
| Statement upload (validate + record + queue every line on Kafka) | 1.8 s |
| Then until the run is COMPLETED (workers store and match every line, then the end-of-run step) | 41.0 s |
| Total, first byte of the ledger to COMPLETED | 45.6 s |
| Statement throughput (upload + matching) | 2,336 lines/s |
| Server-side, statement recorded to run completed | 42.3 s |
| Match precision / recall | 97.46% / 97.54% |
| Break precision / recall | 66.53% / 69.32% |
| Machine | Laptop: AMD Ryzen 9 7845HX (12 cores/24 threads), 16 GB RAM, Windows 11, Docker Desktop 29.8.0 (WSL2, 7.4 GiB for Docker) |
| Recorded | 2026-10-22T03:15:41Z, commit ee94f21 |
