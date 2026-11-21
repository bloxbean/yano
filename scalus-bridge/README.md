# Yano Scalus Bridge

Adapter between Yano and Scalus (`org.scalus`, 1.1.1): full Cardano ledger-rule transaction
validation and Plutus script evaluation (ExUnits) through Scalus's Conway rules and CEK machine, plus the
`scalus` engine behind the `ledger-rules` validation-engine SPI. Written in Scala 3 and Java; the Java entry
points keep the Scala types out of callers' signatures.

## Artifact

`org.yanoproject:yano-scalus-bridge`

## Key contents

Package `org.yanoproject.scalusbridge`:

| Type | Purpose |
|---|---|
| `ScalusTransactionFactory` | Pure Java factory that hides the Scala types and returns a `TransactionValidator` or `TransactionEvaluator`. |
| `ScalusBasedTransactionValidator`, `ScalusBasedTransactionEvaluator` | Validation (via `LedgerBridge`, which runs Scalus's `CardanoMutator`) and ExUnits evaluation. |
| `ScalusEngineFactory`, `ScalusLedgerValidationEngine` | The `scalus` engine (`yano.validation.engine=scalus`, or as a shadow engine) running Scalus's Conway rules against the request's `LedgerView`. Registered in `META-INF/services/org.yanoproject.ledger.rules.LedgerValidationEngineFactory`. |
| `ScalusScriptPhaseEvaluator` | Phase-2 evaluator for the Java ledger-rules engine (`java-scalus`). |
| `LedgerViewStateProvider` | Overlay-aware ledger state over a `LedgerView`. |

The Scala sources (`src/main/scala`) hold the bridges (`LedgerBridge`, `ProtocolParamsBridge`,
`UtxoBridge`, `CertStateBridge`, ...) and Yano's validator overrides. Native-image configuration ships under
`META-INF/native-image/org.yanoproject/yano-scalus-bridge`.

## Dependencies and boundaries

- `api` dependencies on [`ledger-rules`](../ledger-rules/README.md), [`cardano-api`](../cardano-api/README.md),
  the Scala 3 library, Scalus (`scalus-bloxbean-cardano-client-lib`, `scalus-cardano-ledger`) and
  `blst-java`.
- Used by [`tx-services`](../tx-services/README.md) and, in tests, by `ledger-conformance`. `runtime` does
  not depend on it, so the Scalus stack stays optional.

Design: [ADR-056](../adr/056-conway-java-ledger-rules-with-state-overlays.md),
[ADR-051 Scalus upgrade](../adr/051-upgrade-scalus-to-1.1.1.md).

## Build and test

```bash
./gradlew :scalus-bridge:build -PskipSigning=true
./gradlew :scalus-bridge:test
# Amaru scenario tests run only with the corpus:
./gradlew :scalus-bridge:test -PamaruScenariosDir=<amaru clone or its crates/amaru-ledger/tests/data/transaction>
```

## Notes

- Java and Scala sources are compiled jointly by the Scala compiler (`src/main/java` is added to the Scala
  source set); `compileJava` itself compiles nothing.
