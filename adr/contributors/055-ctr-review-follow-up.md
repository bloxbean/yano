# ADR-055 implementation review follow-up

2026-09-07. Changes remain uncommitted for manual review.

## Build failure

The conformance fixture still required plugin API level 8 after core-api moved to
level 9. Its manifest now declares 9, matching its existing current-level contract.
All eight fixture tests pass.

The subsequent full `clean build` ran 1,617 runtime tests and stopped on one
`AppChainL1RefTest.fabricatedL1Ref_rejectedByFollower` connection timeout. That exact
test passed on an isolated retry in 16 seconds. This is not a claim that the full
clean build passed. Logs: `/tmp/yano-contributors-clean-build.log` and
`/tmp/yano-contributors-connection-retry.log`.

## Review decisions and changes

1. **Shifted devnet genesis:** replaced the applied-height restriction with an
   atomic, one-shot Shelley materialization marker. Funds still must match the
   initial digest. Matching repeated calls do not resurrect spent funds. A real
   RocksDB regression materializes after fast-forward and retries after spending.
2. **Shutdown:** drain the non-client async UTxO queue while products/callbacks are
   live, close index products, then seal callbacks and await cleanup. Restart
   recreates the async handler. A full-runtime external-JAR test holds a queued
   writer across stop and checks availability after stop and restart.
3. **Faucet:** run the maintenance preflight outside the mutation/degradation
   handler. Unsupported requests leave the node healthy and do not mutate state.
4. **Publishing:** the example is a Java plugin-JAR module, not a published/signed
   Yano library. Its task inventory has no publishing or signing tasks.
5. **Upgrades:** the wallet guide now explicitly requires a new sync database.
   Existing unavailable wallet history produces a startup warning; no migration
   or silent backfill is inferred.
6. **Archival capture:** retained the useful pre-filter same-block address capture.
   Added Shelley and Byron owner-hook regression assertions with storage filters.
   The archival collector/lifecycle itself remains outside the new registry.
7. **Scan cost:** ChainPoint uses a static Pattern. Error-free filter pages now
   perform two bounded prefix probes (one per feature), not two gets per record.
   Pages containing errors retain per-record lookup for canonical validation.
8. **Host corruption:** deliberately fail startup/mutation rather than overwrite
   corrupt safety/undo metadata. Queries report unavailable, with a corruption
   reason. Tests verify staged writes are not committed. ADR documents this policy.
9. **Registration:** resolve and preflight every selected provider, including
   namespace, persisted schema/network, and full-storage requirements, before
   registering any. Missing/duplicate/filtered-storage tests verify retry does not
   encounter participants left behind by failed preflight.
10. **Diagnostics:** readable wallet range and identity survive host-gate rejection;
    stored failure reasons are surfaced. Closed/replaced native handles are never
    read just to enrich a diagnostic.
11. **Contracts:** added bootstrap-plus-wallet rejection, actual YAML-list forwarding,
    disabled-capture and same-height/hash-mismatch regressions. Restore preparation
    is mandatory in the actions interface and asserted before storage replacement.
    The reserved-wallet parity test also exposed and fixed the offline inspector's
    missing reservation check.
12. **Small cleanups:** removed redundant freeze calls, renamed the CCL credential
    test, and retain the first representative decoding error per feature/block.
    Defensive byte-array copies remain intentional. The example README already
    described effective transaction outputs, not genesis indexing; no genesis
    support is claimed or added.

## Validation

The final focused command passed **674 tests, zero failures/skips**: runtime
UTxO/wallet/faucet/restore 208; runtime plugin catalog 94; plugin catalog 82;
archival projection 251; app producer/wallet/address/native-metadata 31;
conformance fixture 8. `:app:quarkusBuild` and `git diff --check` passed.
Command output: `/tmp/yano-contributors-review-final.log`.

The existing `:runtime:benchmarkWalletIndexes` completed with 10,000 synthetic
blocks on Java 25.0.2, macOS aarch64, 1 GiB heap. With both wallet indexes enabled,
reading all 10,000 filter records took 14.35/14.08 ms for one query credential,
16.64/16.14 ms for ten, and 64.92/64.10 ms for 200 (reopened/repeat). This excludes
body reads and credential extraction; OS caches were not flushed. These are
post-change observations, not a measured before/after speedup or mainnet sizing.
Report: `/tmp/yano-contributor-review-bench-doBudx/run/report.json`.

The earlier preprod smoke checked progress, nonce recovery and shutdown, not
explicit wallet query availability at the tip. Absence of error log lines is not
proof of availability. A premature restart during the original shutdown hang did
briefly contend the DB lock; the second process failed to open RocksDB and was
stopped. No claim of successful concurrent database use is made. Mainnet was not
touched. Full historical CCL verification, native-image execution and exhaustive
live crash/epoch-boundary qualification remain outside this follow-up. Snapshot
tests cover service fencing/order and real external-contributor handle rebind
separately, not a full external-plugin service-level restore scenario.
