# ADR-055 implementation plan

Baseline: preview release, fresh sync expected. No legacy migration layer or
automatic commit. Archival projection remains outside this registry.

Latest corrections, review decisions and validation are recorded in
[the implementation review follow-up](055-ctr-review-follow-up.md). Its results
supplement the original milestone below; the earlier live smoke did not explicitly
assert wallet query availability at the tip.

## Work sequence

- [x] Define neutral ChainPoint, read-only effective UTxO changes, contributor
  lifecycle and scoped committed-read/write contracts.
- [x] Implement ordered startup registry, namespace storage, atomic failure
  markers/undo, lifecycle fences and maintenance guards.
- [x] Extract wallet contribution and query coordination from DefaultUtxoStore;
  keep good-address indexing and explicit partial-scan results.
- [x] Wire manifest-required plugin discovery, configuration, API metadata,
  lifecycle wrappers and an external example.
- [x] Test transaction isolation, failure/rollback/pruning, lifecycle/restart,
  effective changes, wallet behavior and plugin policy/discovery.
- [x] Review the full change, fix findings, repeat focused regressions and
  document remaining validation limits.

## Design checks

New features must not add feature-specific branches to DefaultUtxoStore. The host
owns one batch and the read lock. Index implementations own extraction and undo.
No generic cache, event bus, asynchronous callbacks, or schema migration framework.
Prefer a focused change view to sharing mutable transaction objects. Disabled
indexes must not impose capture work. Fresh sync permits consolidating duplicate
chain-point types directly.

## Validation

Use real RocksDB fixtures for savepoints, aborted batches, rollback/replay,
same-block effects, generation invalidation, retention and rebind. Run wallet/API,
UTxO, plugin-catalog and archival regression suites. Any live sync runs must use a
dedicated test directory/port, never the user's mainnet process or database.

## Review fixes

- Freeze registration on direct genesis, rollback and prune entry points, not
  only the normal runtime startup path.
- Preserve canonical failure markers across successful later blocks and fail
  closed when host undo is unavailable; reject non-forward apply transitions.
- Capture same-block addresses before optional storage filtering and expose
  Allegra's protocol-level removals separately from transaction inputs.
- Validate deferred genesis materialization against the initial genesis funds;
  reject maintenance mutations that would bypass registered indexes.
- Fence reads before native storage replacement, rebind host handles before
  contributor callbacks, and expire contexts on product stop/restart.
- Close apply-owned contributor products before awaiting their cleanup futures
  during full runtime shutdown. A live preprod smoke test exposed this ordering
  issue; a real-runtime external-JAR regression now covers it.
- Attempt all product cleanup even when one close throws, preserving fatal
  failures without self-suppression or leaving cleanup futures pending.

The external example and plugin catalog tests exercise directory-JAR discovery,
explicit selection, activation, same-batch writes, rollback, restart and shutdown.
No archival contributor migration, hot loading, parallel callbacks, migration
layer or automatic backfill was added. All changes remain uncommitted for review.

## Completed validation (2026-09-07)

- Selected runtime wallet/UTxO/plugin/rollback/snapshot suites: 344 passed,
  one opt-in fixture test skipped.
- Runtime plugin-catalog semantics: 93 passed, including external JAR and
  full-runtime shutdown; plugin-catalog module: 82 passed.
- Archival projection regressions: 251 passed. Selected wallet/address API and
  generated-native-metadata checks: 12 passed. Total: **782 passed, 1 skipped**.
- `:app:quarkusBuild` and `git diff --check` passed.
- Isolated JVM preprod smoke with both wallet indexes and the example plugin:
  progressed through Byron/Shelley to block 91,333 without contributor errors or
  degraded status. After forced termination of the original shutdown hang,
  the corrected build restored nonce state at block 47,789; epoch-6 nonce matched.
  Corrected graceful shutdown completed in 40 ms; the subsequent restart restored
  at block 79,906 and continued applying. The final test process was stopped.

Live artifacts, launch command, logs, nonce samples, and the original shutdown
thread dump are retained at `/tmp/yano-contributors-preprod-zFTsir`. HTTP port
17079, preprod default peer, dedicated chainstate; the user's mainnet was untouched.
This is a short sync/restart smoke, not a full mainnet/preprod sync, a native-image
execution, or exhaustive crash/epoch-boundary validation. Those remain release
qualification work, not evidence claimed by this implementation milestone.

Regression command:

```sh
./gradlew :runtime:test \
  --tests 'org.yanoproject.runtime.wallet.*' \
  --tests 'org.yanoproject.runtime.utxo.*' \
  --tests 'org.yanoproject.runtime.plugins.*' \
  --tests 'org.yanoproject.runtime.internal.RuntimeNodePluginRollbackTest' \
  --tests 'org.yanoproject.runtime.devnet.DevnetSnapshotRestoreServiceTest' \
  :runtime:pluginCatalogSemanticsTest :plugin-catalog:test \
  :archive-modules:archive-core:test \
  --tests 'org.yanoproject.archive.core.projection.*' \
  :app:test \
  --tests 'org.yanoproject.app.api.wallet.WalletScanResourceTest' \
  --tests 'org.yanoproject.app.api.addresses.AddressResourceTest' \
  --tests 'org.yanoproject.app.PluginCatalogGeneratedNativeMetadataTest' \
  :app:quarkusBuild --console=plain
```
