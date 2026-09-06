# Issue 119 acceptance audit

Implementation handoff is complete under the owner's 2026-09-06 decision to
accept devnet validation and perform preprod sync manually. The larger replay
was stopped at the owner's request; incomplete results are not acceptance evidence.
The table retains the distinction between verified behavior and deferred measurements.

| Requirement | Evidence / current result |
|---|---|
| Independent flags, default off, no disabled extraction/writes | Runtime guards in `DefaultUtxoStore`; four-mode storage spike; unavailable API tests. |
| Wallet profile enables both, complete UTxOs and retained bodies, removes archival settings | `app/config/application-wallet.yml`; actual wallet/node JVM and native tests run with archival projection disabled. |
| Canonical, atomic coverage with explicit origin and identity | `WalletIndexStore` staging shares the UTxO write batch; uncommitted-batch, gaps, late enablement, incompatible metadata, canonical-point and checkpoint tests. |
| Definitive null only with complete first-seen history; zero and spent addresses | Store/API tests and live genesis/funded/fully spent tests. |
| Permanent first-seen survives restart/pruning; repeated outputs deduplicate | Conditional first insert in `stageBlock`; exact-address and repeated-output tests; real pruner and restart tests. |
| Invalid collateral and same-block create/spend semantics | `WalletIndexRuntimeTest` exercises effective effects; invalid ordinary outputs remain absent. |
| Exact addresses sharing payment credentials remain distinct | `WalletIndexStoreTest.completeGenesisSlotZeroAndExactAddressIdentity`. |
| Supported extraction and filter membership | Independent raw-CBOR expectations for ten serialized blocks across six eras; literal GCS golden vector; supported event matrix tests. Historical input/scan reference gate remains open below. |
| Full/resumed wallet scans, assets, outgoing-only transactions | Real wallet client/port live test on JVM and native, distinct stake credentials, durable outpoint reload; scanner full/resumed equivalence tests. |
| Coverage/body gaps rejected; no backfill fallback | Missing-filter, body-pruner, late-enable and checkpoint-without-index tests; origin range rejection. |
| Apply/restart/crash/rollback/replay and stale cursors | Live JVM/native forced termination and snapshot reorg; exact/legacy rollback, same-height replacement, active scan, header-only rollback, corrupt/missing undo tests. |
| Incompatible UTxO filters rejected | Both independent flags tested with UTxO disabled, built-in filters, and plugin storage-filter chains. |
| Wallet discovery and persistent canonical history | `YanoNodeClient`, `YanoNodePorts`, `WalletScanHistory`, application wiring and their unit/live tests; receive/change discovery retains independent existing counters. |
| Fresh-sync/configuration/retention/API/resource documentation | `docs/wallet-indexes.md`, ADR 054 and linked measurement reports. |
| Million-filter performance measurements | Synthetic four-mode spike complete. Real historical four-mode replay harness smoke-tested; full run stopped during baseline; deferred to manual validation. |
| Historical brute-force comparison and performance matrix | **Deferred to manual follow-up:** full historical input-resolution/history reference comparison; full-node JVM/native throughput, allocations/memory, candidate vs confirmed reads, true cold-cache and concurrent-sync/tip impact. |

The owner accepted devnet validation for this implementation handoff. Manual
preprod sync and any further historical/performance measurements are follow-up
work, not blockers for this task. Production resource budgets have not been
established by the synthetic spike or short devnet runs.
