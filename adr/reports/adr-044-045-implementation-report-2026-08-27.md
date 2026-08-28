# ADR-044 / ADR-045 Implementation Report

Date: 2026-08-27

Branch: `feat/selective-epoch-artifacts`

## Result

ADR-044 selective epoch-artifact enrollment and ADR-045 durable gap, pause,
resume and coverage semantics are implemented. The final automated regression
suite, a fresh mainnet smoke through block 1,000,000, and the preprod
selection/join/recovery scenario all passed.

## Delivered behavior

- `yano.history.projection.epoch-artifacts` accepts strict versioned selectors,
  `all`, or `none`.
- A fresh archive defaults to all shipped artifacts. An omitted property on a
  populated archive preserves its stored selection.
- Mid-archive additions are prospective and persist `projectedFromEpoch`;
  earlier epochs are `NOT_PROJECTED`, not gaps.
- Removal and contract changes fail closed.
- Unselected artifact readers, writers, staging and physical sink tables are
  not installed.
- Per-dataset `PENDING`, `COMPLETE` and `GAP` outcomes, pause state, compact
  missed-boundary intervals, exact-point rollback and explicit resume are
  durable across restart.
- Zero-row artifacts carry positive completion evidence.
- Range reads and the consistency point fail closed for incomplete selected
  epoch history. Coverage endpoints and bounded-cardinality metrics distinguish
  selection, enrollment, gaps and capture state.
- Legacy unstructured staging failures remain visible and require an audited
  acknowledgement before staged enrollment can proceed.

## Review iteration

The first preprod restart found an ADR-042 recovery defect: DuckLake
`coordinate()` returned a placeholder slot/hash for a non-empty projection
sink. Exact pre-drain verification therefore rejected a healthy archive as
non-canonical.

The fix persists the real terminal slot and block hash in every projection
receipt in the same transaction as rows and coverage. `coordinate()` now
returns that exact point. Preview receipts that predate the endpoint columns
fail with an explicit rebuild diagnostic instead of weakening canonicality.
`DuckLakeProjectionSinkTest` now asserts the complete coordinate, so a
placeholder cannot regress unnoticed.

## Automated verification

The following final command completed successfully in 6m52s:

```text
./gradlew :archive-modules:archive-api:test \
  :archive-modules:archive-core:test \
  :archive-modules:archive-store-ducklake:test \
  :ledger-state:test :runtime:test :app:test
```

Focused restart-reconciliation and DuckLake exact-coordinate tests also passed.
The final distribution was built with:

```text
./gradlew :app:yanoDistZip
```

`git diff --check` reports no whitespace errors.

## Mainnet smoke

Folder:

```text
/Users/satya/Downloads/yano-try/adr04445-final-mainnet.Hk71JW/yano-0.1.0-pre14
```

The fresh final ZIP reached block 1,002,337 / slot 1,002,447, entirely within
Byron. At the observation point:

- all five epoch artifacts were selected;
- `EPOCH_STAKE` was enrolled from epoch 208 and the other four from epoch 209;
- `projection.drainFailures = 0`;
- `apply.byron.unresolvedInputs = 0`;
- `apply.byron.filteredStoreUnresolvedInputs = 0`; and
- `apply.continuityWarnings = 0`.

The archive then restarted successfully and continued beyond block 1,036,687.
The exact sink point was accepted; contribution remained healthy with no drain
failure or continuity warning. Evidence is retained in `mainnet-final.log`,
`mainnet-final-1m-status.json`, `mainnet-final-restart.log` and
`mainnet-final-restart-status.json` in that folder.

## Preprod selection and recovery

Folder:

```text
/Users/satya/Downloads/yano-try/adr04445-final-preprod.JkcgKw/yano-0.1.0-pre14
```

The same final ZIP was exercised as follows:

1. Fresh start with `epoch-artifacts=none` produced an empty artifact identity,
   reported all five datasets as `NOT_SELECTED`, retained genesis and block
   projection, and created none of the five artifact data tables.
2. Graceful restart with the property omitted preserved `none` and passed exact
   pre-drain sink-point reconciliation.
3. Explicit join of `reward:v1,ada-pot:v1` enrolled both from epoch 11 and
   logged the prospective prefix. Only `rewards` and `ada_pots` were created.
4. Both datasets reached continuous `COMPLETE` coverage from epoch 11 through
   epoch 31 without a gap; the other three stayed `NOT_SELECTED`.
5. A graceful joined-state restart preserved enrollment and progressed through
   epoch 32 with no drain failure.
6. The exact HTTP listener PID was killed with `SIGKILL`. Restart reconciled one
   state-transition block, retained the same enrollment, progressed through
   epoch 33, and reported zero gaps and zero drain failures.

All isolated listeners on 17080/17081/17337/17338 were stopped after validation.
The pre-existing user mainnet node on port 7070 was not modified and remained
running.

## Remaining scope

ADR-046 remains the separate decision for the complete public read API surface.
ADR-044/045 provide the selection, enrollment, coverage and failure-preflight
foundation it consumes.
