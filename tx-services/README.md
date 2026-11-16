# Yano Transaction Services

Optional transaction validation and script evaluation for a Yano node. It creates the concrete
Scalus-, Aiken- and julc-backed services that `runtime` installs, so the core runtime does not carry the
Scalus bridge and evaluator dependencies itself.

## Artifact

`org.yanoproject:yano-tx-services`

The test fixtures (`src/testFixtures`) are not part of the publication.

## Key contents

Package `org.yanoproject.tx`:

| Type | Purpose |
|---|---|
| `DefaultTransactionServicesFactory` | `create(TransactionBootstrapContext, TransactionBootstrapOptions)` returns the `TransactionServices`: the Scalus transaction validator, the ExUnits evaluator chosen by `yano.block-producer.script-evaluator` (`scalus`, `julc`, otherwise Aiken) and the ADR-056 validation engines. `app-core` passes it to the runtime as its `TransactionServicesFactory`. |
| `ValidationEngineBootstrap` | Discovers `LedgerValidationEngineFactory` implementations with `ServiceLoader` (`scalus` from `scalus-bridge`, `amaru*` from the optional `amaru-validator`) and builds the configured engines. |
| `ShadowBundleReplay` | Replays shadow-validation bundles through an engine; also a command line (`java -cp app/build/yano.jar org.yanoproject.tx.ShadowBundleReplay [--scalus] <bundle.json or dir>...`). |
| `TransactionProtocolParamsResolver`, `RuntimeSlotConfigSupplier` | Effective protocol parameters and slot timing for validation and evaluation. |

Test fixtures (`org.yanoproject.tx.gate`): the devnet ledger-rules gate (`DevnetGateNode`,
`DevnetFollowerNode`, `BlockRevalidator`, `RemoteYanoNode`, ...), shared with `amaru-validator`.

## Dependencies and boundaries

- `api` dependency on [`runtime`](../runtime/README.md); implementation dependencies on
  [`scalus-bridge`](../scalus-bridge/README.md) and [`script-evaluators`](../script-evaluators/README.md).
- Used by `app-core`; `runtime` must not depend on it.

Design: [ADR-056](../adr/056-conway-java-ledger-rules-with-state-overlays.md),
[ADR-057](../adr/057-optional-amaru-wasm-transaction-validator.md).

## Build and test

```bash
./gradlew :tx-services:build -PskipSigning=true
./gradlew :tx-services:test
# devnet ledger-rules gate (about a minute; disabled by default):
./gradlew :tx-services:test --tests '*JavaEngineDevnetGateTest' -PledgerRulesGate=true
```

The remote-node workloads (`yano.gate.*`, `yano.parity.*`) and `yano.shadow.bundles` replay run only when
those system properties are passed; see `build.gradle`.
