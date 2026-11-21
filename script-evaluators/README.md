# Yano Script Evaluators

Plutus script evaluators for Yano: Aiken- and julc-backed `TransactionEvaluator`s that compute ExUnits
for transaction evaluation, and the julc phase-2 `ScriptPhaseEvaluator` used by the Java ledger-rules
engine (`java-julc`).

## Artifact

`org.yanoproject:yano-script-evaluators`

## Key contents

| Package | Types |
|---|---|
| `org.yanoproject.ledger.scripteval` | `AikenTxEvaluator` and `JulcTxEvaluator` (cardano-client-lib `TransactionEvaluator`s), `YaciScriptSupplier` (reference scripts from Yano's UTXO state), `JulcPlutusDataInit` (julc class-initialization workaround, below). |
| `org.yanoproject.ledger.scripteval.phase2` | `JulcScriptPhaseEvaluator`, the phase-2 contract of `ledger-rules`; internally `ConwayTxInfoTranslator` builds the Plutus V1-V3 script contexts as cardano-ledger does, and `JulcMachine` runs them on julc's CEK machine. |

## Dependencies and boundaries

- `api` dependency on [`ledger-rules`](../ledger-rules/README.md); implementation dependencies on the Aiken
  Java binding, julc `cardano-client-lib` integration and `julc-vm-java`.
- Used by [`tx-services`](../tx-services/README.md) (evaluator selection) and, in tests, by `runtime`,
  `scalus-bridge` and `ledger-conformance`. `runtime` does not depend on it at compile time.

Design: [ADR-056](../adr/056-conway-java-ledger-rules-with-state-overlays.md) (Phase 7c).

## Build and test

```bash
./gradlew :script-evaluators:build -PskipSigning=true
./gradlew :script-evaluators:test
```

## Notes

- `JulcPlutusDataInit` works around [julc #266](https://github.com/bloxbean/julc/issues/266) (a
  `PlutusData` class-initialization deadlock when two threads first use its types at once).
