| Metric | Value |
|---|---|
| Data | 10000 synthetic payments (seed 42) over 12 accounts: 9982 ledger records, 9871 statement lines |
| Run settings | amount tolerance 1.00, date window 3 days |
| Match precision | 99.10% (9208 of 9292 matches made pair the right two records) |
| Match recall | 98.09% (9208 of 9387 true matches found) |
| Break precision | 73.14% (806 of 1102 reported breaks have the right type and records) |
| Break recall | 89.86% (806 of 897 injected breaks found exactly) |
| AMOUNT_MISMATCH precision / recall | 100.00% / 91.76% (167 right; 167 reported, 182 injected) |
| DUPLICATE precision / recall | 100.00% / 98.33% (177 right; 177 reported, 180 injected) |
| MISSING_IN_BANK precision / recall | 60.54% / 81.16% (267 right; 441 reported, 329 injected) |
| MISSING_IN_LEDGER precision / recall | 61.51% / 94.66% (195 right; 317 reported, 206 injected) |
| Correct matches made by the expected rule | 9208 of 9208 |
| Recorded | 2026-10-19T07:17:12Z, commit 1d671a3 |

| Scenario | Truth entries | Exactly right |
|---|---|---|
| CLEAN | 8000 | 8000 |
| FEE_DEDUCTED | 386 | 386 |
| REFORMATTED_ON_TIME | 307 | 307 |
| REFORMATTED_LATE | 301 | 286 |
| MISSING_IN_BANK | 225 | 220 |
| MISSING_IN_LEDGER | 206 | 195 |
| AMOUNT_MISMATCH | 182 | 167 |
| DUPLICATE_IN_BANK | 192 | 191 |
| DUPLICATE_IN_LEDGER | 168 | 166 |
| REFERENCE_LOST_LATE | 109 | 0 |
| LOOKALIKE | 208 | 96 |
