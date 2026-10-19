| Metric | Value |
|---|---|
| Data | 10000 synthetic payments (seed 42) over 12 accounts: 9982 ledger records, 9871 statement lines |
| Run settings | amount tolerance 1.00, date window 3 days |
| Match precision | 98.88% (9186 of 9290 matches made pair the right two records) |
| Match recall | 97.86% (9186 of 9387 true matches found) |
| Break precision | 71.57% (793 of 1108 reported breaks have the right type and records) |
| Break recall | 88.41% (793 of 897 injected breaks found exactly) |
| AMOUNT_MISMATCH precision / recall | 100.00% / 90.66% (165 right; 165 reported, 182 injected) |
| DUPLICATE precision / recall | 100.00% / 97.22% (175 right; 175 reported, 180 injected) |
| MISSING_IN_BANK precision / recall | 60.22% / 81.46% (268 right; 445 reported, 329 injected) |
| MISSING_IN_LEDGER precision / recall | 57.28% / 89.81% (185 right; 323 reported, 206 injected) |
| Correct matches made by the expected rule | 9186 of 9186 |
| Recorded | 2026-10-19T07:13:27Z, commit 2204d66 |

| Scenario | Truth entries | Exactly right |
|---|---|---|
| CLEAN | 8000 | 8000 |
| FEE_DEDUCTED | 386 | 376 |
| REFORMATTED_ON_TIME | 307 | 297 |
| REFORMATTED_LATE | 301 | 284 |
| MISSING_IN_BANK | 225 | 221 |
| MISSING_IN_LEDGER | 206 | 185 |
| AMOUNT_MISMATCH | 182 | 165 |
| DUPLICATE_IN_BANK | 192 | 189 |
| DUPLICATE_IN_LEDGER | 168 | 166 |
| REFERENCE_LOST_LATE | 109 | 0 |
| LOOKALIKE | 208 | 96 |
