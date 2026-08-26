# ADR-041 implementation and validation report

Date: 2026-08-26

Branch: `feat/byron-utxo-apply`

Target base: `origin/fix/byron-address-projection-resolver` (`91b4cbcd053ddfc86f49155e972fad18202e98e5`)

## Review fixes

- Reconciliation now classifies the persisted Byron envelope before choosing a
  decoder (`0` = EBB, `1` = main). Main-block application is outside every
  classification/deserialization catch, so an apply failure cannot be retried
  or suppressed as an EBB.
- Fresh producer startup establishes the full-history marker and Byron genesis
  state but defers Shelley initial funds to the producer genesis-block path.
  This preserves the real genesis hash and gives stake-balance indexing exactly
  one writer.
- `yano.utxo.rebuild-unmarked-from-genesis=true` exposes the explicit one-shot
  full-history rebuild to operators. It fails closed if canonical bodies needed
  for replay are unavailable and is ignored after the capability marker exists.
- The Shelley-start UTXO-total metadata key is supplied by the chain metadata
  interface instead of duplicated as a string literal, and reconciliation
  attempts boundary capture only at its first non-Byron block.

## Automated verification

The complete verification command passed:

```text
./gradlew test :app:quarkusBuild
```

Result: 2,259 tests, 0 failures, 3 pre-existing runtime skips; the packaged
application build also passed. Focused regression tests cover persisted Byron
envelope classification, reconciliation apply-failure propagation, producer
genesis composition, and the operator rebuild path. `git diff --check` passes.

The Byron apply measurement remains approximately 717 bytes of RocksDB growth
per block; measured throughput in the implementation runs was about 20k-24.5k
blocks/second with the continuity observer enabled.

## Devnet block-producer validation

The current packaged runtime was run against a fresh, plugin-free BP devnet in:

```text
/Users/satya/Downloads/yano-cluster/adr041-devnet-bp-20260826
```

Observed on the initial run:

- stage 64 initialized the capability with `shelley=0` and logged that 25
  Shelley outputs were deferred;
- the producer committed its real genesis block and then stored the 25 Shelley
  outputs once;
- the captured Shelley-boundary total was `9010500000000000`, equal to the
  genesis `initialFunds` sum;
- a sampled stake credential with an expected `10000000000` lovelace reported
  exactly `10000000000`, not twice that amount.

On restart the producer resumed from block 207, emitted no genesis initialization,
deferral, or seeding messages, and continued producing blocks. Logs are retained
as `yano.log.initial` and `yano.log.graceful-restart` in that directory.
After validation, the current runtime was restarted from the preserved state on
HTTP port 17100/N2N port 18100 and left running. It resumed from block 563,
remained non-degraded, and again emitted no genesis-seeding messages.

The checked three-node showcase deploy script could not build the current
branch because it still names the removed `:appchain-client` and
`:appchain-showcase` Gradle projects. The retained cluster also uses a pre11
plugin bundle while this branch packages pre13. Its original pre11 runtime JAR
was restored without modifying its chainstate or identities. This tooling/ABI
limitation does not affect the plugin-free current-runtime BP result above.

## Preprod sync and recovery validation

The current packaged runtime was synced from a fresh preprod chainstate in:

```text
/Users/satya/Downloads/yano-cluster/adr041-preprod-sync-20260826
```

The run crossed Byron to Shelley at slot 86,400/block 46 and continued through
Allegra, Alonzo, and Babbage. No unresolved Byron input, UTXO apply failure,
runtime degradation, corruption, or error was observed.

The same chainstate then passed both recovery scenarios required by the sync
validation procedure:

1. After a graceful stop, startup validated RocksDB, reconciled UTXO and account
   state, restored epoch-5 nonce state, intersected, and resumed applying.
2. After an exact `SIGKILL` of the Java process, startup again reported no
   corruption, reconciled both stores, restored epoch-5 nonce state, and resumed
   sync. It advanced beyond block 183,000 before the validation run was stopped.

The retained evidence is `yano.log.initial`, `yano.log.pre-kill`, and
`yano.log.post-kill-recovery` in the preprod validation directory. The
chainstate was preserved.

## Remaining completion gate

ADR-041 still requires a trusted-reference comparison of final UTXO outpoints
and total lovelace at the Shelley boundary. The preprod replay proves live
cross-era application and recovery but is not that independent comparison.
A mainnet replay was not started because the sync-validation procedure requires
explicit mainnet authorization.
