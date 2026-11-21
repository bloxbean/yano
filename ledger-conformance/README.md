# Yano Ledger Conformance

The ledger-rules conformance harness ([ADR-056](../adr/056-conway-java-ledger-rules-with-state-overlays.md)
§8). It runs the transaction-validation engines (the Java rules and Scalus) side by side over the Amaru
scenarios, the cardano-blueprint vectors and a mutation matrix, gates the Java engine, and generates the
conformance reports, including the Cardano node-diversity reports
([ADR-060](../adr/060-node-diversity-conformance-reports.md)). It is a separate module because it needs
every engine, and `scalus-bridge` itself depends on `ledger-rules`.

## Artifact

Not published: test sources only. The build removes its publication, and it is excluded from the Central
deployment scope.

## Key contents

Test packages under `org.yanoproject.ledger.conformance`:

| Package | What it holds |
|---|---|
| `runner` | `ConformanceRunner`, `ConformanceEngine`, `ScenarioCases`: loads cases and runs engines over them. |
| `engines` | Engine adapters (`JavaViewEngine`, `ScalusViewEngine`, legacy variants, `EngineLatencyBenchmarkTest`). |
| `gate` | Java-engine gates (`JavaEnginePhase3/4/5GateTest`, `BlueprintVectorGateTest`, ...). |
| `coverage`, `mutation`, `blueprint` | Coverage matrix of `@Covers` tests, mutation matrix, cardano-blueprint vector runner. |
| `report`, `diversity` | Baseline report and the node-diversity report generators. |

Data and scripts:

- `vectors/cardano-blueprint/` - vendored, pinned blueprint vectors ([README](vectors/cardano-blueprint/README.md)).
- `node-diversity/` - `corpora.env` (pinned corpora), `fetch-corpora.sh`, `score-reports.sh`.
- `docs/` - generated reports (`baseline-2026-09.md`, `blueprint-vectors-2026-09.md`); `conformanceReport`
  also regenerates [`ledger-rules/docs/conway-rule-coverage.md`](../ledger-rules/docs/conway-rule-coverage.md).

## Dependencies and boundaries

- Test dependencies on [`ledger-rules`](../ledger-rules/README.md) (and its test fixtures),
  [`scalus-bridge`](../scalus-bridge/README.md), [`script-evaluators`](../script-evaluators/README.md),
  [`runtime`](../runtime/README.md) and `julc-vm-java`.
- No module depends on it.

## Build and test

```bash
# runner, coverage, mutation and blueprint gates; the scenario parts skip without the Amaru corpus
./gradlew :ledger-conformance:test
./gradlew :ledger-conformance:test -PamaruScenariosDir=<amaru clone or its crates/amaru-ledger/tests/data/transaction>
./gradlew :ledger-conformance:conformanceReport -PamaruScenariosDir=<dir>
ledger-conformance/node-diversity/fetch-corpora.sh <dir>
./gradlew :ledger-conformance:nodeDiversityReports -PnodeDiversityCorpora=<dir>
```

Other switches (`-Pconformance.strict=false`, `-PblueprintVectors=<dir>`, `-PengineBenchmark=true`) are
described at the top of [`build.gradle`](build.gradle).

## Notes

- Strict by default: an in-scope Conway constructor without a `@Covers` test fails the build.
- Test tasks always rerun (no caching), because results depend on external corpora and other modules'
  engines.
