# ADR-026: Effective Protocol Parameters and Cost Model Projection

## Status

Proposed

## Date

2026-06-09

## Context

Yano currently exposes protocol parameters through two different paths:

- Runtime/static JSON via `NodeAPI.getProtocolParameters()`, backed by
  `GenesisConfig.getProtocolParameters()`.
- Epoch-effective ledger state via
  `LedgerStateProvider.getProtocolParameters(int epoch)`.

REST endpoint `/api/v1/epochs/{epoch}/parameters` currently knows about both
paths. It asks the ledger state provider first and, when that provider is not
available in bootstrap mode, falls back to parsing static `protocol-param.json`
directly in the resource.

That works for bootstrap mode, but it puts protocol-parameter source selection
in the REST layer. Direct Java callers of `NodeAPI` still cannot ask the node for
effective protocol parameters for a specific epoch. A resource should shape HTTP
responses; the node implementation should decide whether effective parameters
come from tracked ledger state or static fallback state.

`LedgerStateProvider.ProtocolParamsSnapshot` is also now used outside
`LedgerStateProvider` ownership boundaries. It is consumed by REST DTO mapping,
runtime transaction validation/evaluation, protocol-version selection, and
ledger-state tests. Keeping it as a nested record makes unrelated consumers look
like they depend on `LedgerStateProvider`.

The cost model fields in that snapshot are currently loose
`Map<String, Object>` values. This hides two distinct shapes:

- `costModelsRaw`: the ledger/native ordered list of costs per Plutus language.
- `costModels`: the user-facing/API projection.

Historical monitoring already found a Blockfrost compatibility mismatch:
Yano can expose `cost_models` with sequential numeric keys such as `"000"`,
`"001"`, while Blockfrost exposes operation-name keys such as
`"addInteger-cpu-arguments-intercept"`. The ordered values match in many cases,
but the JSON shape differs.

There is also a hidden ordering bug in the current projection code:
`CostModelUtil` sorts operation-name maps lexicographically before converting
them to ordered lists. Lexicographic order is not the Plutus protocol cost model
order. For any language where builtin operation names are not already in
protocol order when sorted alphabetically, this can produce incorrect
`cost_models_raw` ordering. The move to an explicit operation-name registry is
therefore both a refactor and a correctness fix.

The static network `protocol-param.json` files already contain operation-name
maps for known Plutus languages. The preview file has complete lists for the
current languages:

- `app/config/network/preview/protocol-param.json`
- `cost_models.PlutusV1`: 332 operation names
- `cost_models.PlutusV2`: 332 operation names
- `cost_models.PlutusV3`: 350 operation names

Those names should be reused to make ledger-derived API projection as useful as
the static file projection.

## Decision

Promote protocol parameters to a top-level domain model and move effective
source selection into `NodeAPI` / `Yano`.

Create `com.bloxbean.cardano.yano.api.model.ProtocolParamsSnapshot` as a
top-level record. It replaces `LedgerStateProvider.ProtocolParamsSnapshot` and
keeps the existing scalar protocol-parameter fields.

Use explicit cost model types in the snapshot:

```java
Map<String, LinkedHashMap<String, Long>> costModels
Map<String, List<Long>> costModelsRaw
```

`costModelsRaw` is the ledger/native ordered list form. `costModels` is the
user-facing projection. The nested map for `costModels` must be a
`LinkedHashMap` so the serialized field order remains the protocol operation
order.

Add an epoch-aware effective protocol-parameter API to `NodeAPI`:

```java
Optional<ProtocolParamsSnapshot> getProtocolParameters(int epoch)
```

`Yano` implements this method by selecting:

1. Ledger-state epoch parameters when a `LedgerStateProvider` is available and
   has parameters for the requested epoch.
2. A cached static `protocol-param.json` snapshot when ledger-state parameters
   are unavailable.
3. `Optional.empty()` when neither source is available.

Keep the existing raw JSON method temporarily:

```java
String getProtocolParameters()
```

This method continues to mean "raw configured static protocol-parameter JSON"
and remains available for `/node/protocol-params`. It should be deprecated or
removed in a later compatibility cleanup, not changed silently to mean
"effective latest protocol parameters".

`EpochResource` should call only the epoch-aware `NodeAPI` method and map the
returned `ProtocolParamsSnapshot` to `ProtocolParamsDto`. REST resources must not
parse static `protocol-param.json` directly.

The REST endpoint will not be versioned for the `cost_models` shape change.
The endpoint is intended to be Blockfrost-compatible, and numeric keys in
`cost_models` are treated as an existing compatibility bug. Consumers that need
stable ordered values should use `cost_models_raw`, which remains the ordered
list representation.

## Cost Model Operation Names

Introduce a `core-api` operation-name registry, for example
`CostModelOpNames`.

The registry must maintain one ordered `List<String>` per known Plutus language:

- `PlutusV1`
- `PlutusV2`
- `PlutusV3`

Store these lists as resource files, not large Java constants, for example:

```text
core-api/src/main/resources/cost-model-opnames/PlutusV1.txt
core-api/src/main/resources/cost-model-opnames/PlutusV2.txt
core-api/src/main/resources/cost-model-opnames/PlutusV3.txt
```

Each file contains one operation name per line. Resource files are easier to
diff against protocol specs and easier to regenerate from known-good network
configuration.

The initial lists must be copied from
`app/config/network/preview/protocol-param.json` using insertion order from the
`cost_models` object:

```bash
jq -r '.cost_models.PlutusV1 | keys_unsorted[]' app/config/network/preview/protocol-param.json
jq -r '.cost_models.PlutusV2 | keys_unsorted[]' app/config/network/preview/protocol-param.json
jq -r '.cost_models.PlutusV3 | keys_unsorted[]' app/config/network/preview/protocol-param.json
```

Do not derive the constants with sorted keys. The list order is the protocol cost
model order and must be preserved as a `List<String>`.

`CostModelUtil` should keep permissive input handling because source data can
arrive as:

- named operation maps from static `protocol-param.json`
- indexed maps with `"000"`, `"001"` keys from existing Yano projection
- ordered lists from ledger-native cost models
- arrays or numeric strings from JSON parsing

The utility should publish typed canonical outputs:

- raw output: `Map<String, List<Long>>`
- named/user output: `Map<String, LinkedHashMap<String, Long>>`

Projection rules:

1. If input is a complete named operation map for a known Plutus language,
   preserve operation names but emit them in registry/protocol order.
2. If input is a partial named operation map or an unknown language, preserve
   operation names and insertion order.
3. If input is a list for a known Plutus language and its length matches the
   registry, map list values to the language operation-name list.
4. If input is indexed numeric-key map, preserve numeric order when converting
   to raw list, then apply rule 2 when possible.
5. For unknown languages or length mismatches, fall back to padded numeric keys
   (`"000"`, `"001"`, ...).

When Cardano introduces a new Plutus language or changes a cost model schema,
the registry and tests must be updated.

## Consequences

Positive:

- Effective protocol-parameter source selection is available to all `NodeAPI`
  callers, not only REST.
- REST becomes a thin HTTP projection layer.
- `ProtocolParamsSnapshot` becomes a reusable domain value instead of a nested
  ledger-state type.
- Snapshot cost model fields become type-safe and self-describing.
- Ledger-derived `cost_models` can become Blockfrost-compatible named maps where
  operation names are known.

Tradeoffs:

- Static fallback will convert
  `protocol-param.json -> ProtocolParamsSnapshot -> ProtocolParamsDto`, even
  though the static JSON already resembles the REST DTO. This is intentional to
  keep runtime/domain and REST wire models separate.
- The operation-name registry is another maintained protocol artifact. New
  Plutus versions require a code update.
- `EpochParamProvider` and related genesis/ledger internals may still accept
  loose cost model input shapes until a broader cleanup is done.
- Static protocol-parameter fallback is cached for the running node process.
  Hot reload of `protocol-param.json` is out of scope; operators must restart
  the node after editing the file.

## Implementation Plan

1. Add the top-level `ProtocolParamsSnapshot` model in `core-api`.
2. Replace references to `LedgerStateProvider.ProtocolParamsSnapshot` with the
   new top-level model.
3. Update `LedgerStateProvider.getProtocolParameters(int epoch)` to return
   `Optional<ProtocolParamsSnapshot>`.
4. Add `NodeAPI.getProtocolParameters(int epoch)` as the effective typed API.
5. Keep `NodeAPI.getProtocolParameters()` as raw static JSON for compatibility.
6. Add operation-name resource files and a registry loader with ordered
   `List<String>` values derived from preview `protocol-param.json`.
7. Tighten `CostModelUtil` typed outputs while keeping permissive input helpers.
8. Add a mapper from static `protocol-param.json` to
   `ProtocolParamsSnapshot`; cache the parsed static template in `Yano`.
9. Update `Yano.getProtocolParameters(int epoch)` to select ledger-state first,
   static fallback second.
10. Update `ProtocolParamsDto.from(...)` to consume top-level
    `ProtocolParamsSnapshot`.
11. Update `EpochResource` to call the epoch-aware `NodeAPI` method and remove
    REST-local static JSON parsing and cache.
12. Keep `StaticProtocolVersionSupplier` and transaction validation/evaluation
    source selection behavior unchanged unless the implementation can reuse the
    new mapper without changing semantics.

## Tests

Add focused tests for:

1. Operation-name registry counts and ordering against
   `app/config/network/preview/protocol-param.json`.
2. Static JSON named `cost_models` projection preserves names and order.
3. Ledger/raw list cost models project to named ordered maps for V1, V2, and V3
   when lengths match the registry.
4. Unknown language or mismatched length falls back to numeric keys.
5. `costModelsRaw` remains ordered list form.
6. `NodeAPI.getProtocolParameters(int epoch)` selects ledger-state params when
   available.
7. `NodeAPI.getProtocolParameters(int epoch)` selects cached static params when
   ledger state is unavailable.
8. `/api/v1/epochs/{epoch}/parameters` uses the typed `NodeAPI` path and keeps
   Blockfrost-compatible output.
9. Existing raw `/api/v1/node/protocol-params` behavior is unchanged.

Run focused suites:

```bash
./gradlew :core-api:test :runtime:test :ledger-state:test :app:test \
  --tests '*ProtocolParams*' --tests '*ProtocolVersion*' --tests '*CostModel*'
./gradlew :app:quarkusBuild
```

## References

- `adr/012-bootstrap-state-mode-lightweight-relay.md`
- `adr/018-rest-api-prefix-and-blockfrost-compatible-ledger-apis.md`
- `adr/025-bootstrap-partial-state-ledger-and-nonce-boundaries.md`
- `app/docs/preprod-protocol-params-monitor.md`
- `app/config/network/preview/protocol-param.json`
