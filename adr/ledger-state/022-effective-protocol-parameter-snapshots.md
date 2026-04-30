# ADR-022: Effective Protocol Parameter Snapshots in EpochParamTracker

## Status

Accepted - implementation in progress

## Date

2026-04-29

## Implementation Progress

Updated on 2026-04-29.

- [x] Extended `EpochParamProvider` with exact rational accessors for fields
  that are rational on-chain, while keeping `BigDecimal` conversion at the API
  projection boundary.
- [x] Added separate Alonzo and Conway cost-model provider methods so the
  tracker can apply era overlays without treating genesis cost models as one
  always-active merged set.
- [x] Completed parser support for Alonzo execution-price rational values and
  Conway `plutusV3CostModel` / `minFeeRefScriptCostPerByte` rational values.
- [x] Wired generic era-start lookup into `EraProvider` / `EraProviderImpl` so
  `EpochParamTracker` can materialize Alonzo, Babbage, and Conway overlays from
  persisted era metadata.
- [x] Changed `EpochParamTracker.finalizeEpoch` to persist a full effective
  snapshot for Shelley and later epochs, including epochs with no pending
  protocol update.
- [x] Changed Conway enactment handling to merge enacted
  `ParameterChangeAction` deltas into the epoch's full effective snapshot rather
  than persisting partial updates.
- [x] Changed cost-model merging to overlay by Plutus language key so `PlutusV3`
  updates do not drop `PlutusV1` or `PlutusV2`.
- [x] Kept rollback on the existing tracker surface: pending update keys and
  finalized snapshot keys are deleted through the existing rollback batch, then
  in-memory state is reloaded from RocksDB.
- [x] Added focused tests for full snapshot materialization, language-wise cost
  model merging, pending update persistence/restart, and rollback of pending and
  finalized protocol parameter rows.
- [x] Added parser/provider/API endpoint tests covering rational preservation,
  Conway cost-model parsing, and Blockfrost-shaped protocol-parameter response
  mapping.
- [x] Fixed era-cleared field read semantics: once a full snapshot exists,
  `minUtxo`, `decentralisationParam`, and `extraEntropy` no longer fall back to
  Shelley genesis values when the snapshot explicitly has them as `null`.
- [x] Preserved `extraEntropy` during normal pre-Babbage merges and cleared it
  through Babbage era rules, matching Yaci Store's `PPEraChangeRules`.
- [x] Added direct Conway-start materialization coverage so an epoch-0 Conway
  chain applies Shelley + Alonzo + Babbage + Conway overlays in one snapshot.
- [x] Moved fresh devnet Conway-era marker setup before block production starts,
  so fast-forwarded devnets do not materialize an epoch snapshot before era
  metadata exists.
- [x] Changed Conway parameter-change and hard-fork enactment persistence to use
  the governance boundary `WriteBatch`, keeping protocol-parameter persistence
  atomic with governance phase commit.
- [x] Fixed cost-model read semantics so finalized pre-Alonzo snapshots do not
  fall back to Alonzo/Conway genesis cost models.
- [x] Changed `cost_models_raw` projection to return Plutus language keys mapped
  to canonical ordered cost lists, while `cost_models` remains the decoded model.
- [x] Canonicalized genesis cost models that arrive as operation-name maps by
  sorting operation names lexicographically before storing/projecting the ordered
  list. API `cost_models` now exposes indexed keys (`000`, `001`, ...) derived
  from the same canonical order, and `cost_models_raw` exposes the canonical
  ordered arrays.
- [x] Kept Babbage+ `decentralisationParam` as `null` in effective snapshots
  and API projection, but normalized it to `0` only when adapting parameters for
  the reward-calculation library.
- [x] Made empty reward-calculation results fatal when
  `yaci.node.exit-on-epoch-calc-error=true`; this prevents a reward exception
  from silently skipping AdaPot storage and therefore skipping later AdaPot
  mismatch verification.
- [x] Fixed all snapshot-backed nullable protocol-parameter getters so a
  finalized epoch snapshot is authoritative. If a field is absent for that era,
  API reads now preserve `null` instead of falling back to future-era genesis or
  provider defaults.
- [x] Changed protocol-parameter API reads to return 404 for requested epochs
  that do not yet have a finalized tracker snapshot when epoch-param tracking is
  enabled.
- [ ] Broader full-suite ledger/governance/reward regression and external
  epoch-by-epoch comparison against Yaci Store or Blockfrost remain as the next
  validation step before treating this as release-ready.

Implementation note: the current implementation stores full snapshots using the
existing `ProtocolParamUpdate` JSON shape, populated with full effective values.
That keeps the storage surface small and compatible with the existing
`EpochParamTracker` column family. A separate versioned snapshot DTO is still a
valid future hardening step if we need stronger legacy-chainstate detection.

## Context

Yano now exposes Blockfrost-compatible epoch protocol-parameter endpoints:

```text
GET /epochs/latest/parameters
GET /epochs/{number}/parameters
```

The API should return the full effective protocol parameters for the requested
epoch. Those values are not just genesis values and are not just on-chain update
deltas. They are the epoch-effective ledger parameters after:

1. Shelley genesis bootstrap,
2. era transition parameter additions and removals,
3. pre-Conway protocol parameter update proposals,
4. Conway governance parameter-change enactments,
5. hard fork initiation protocol-version updates.

Today `EpochParamTracker` tracks pending updates and finalized epoch values, but
the finalized values are still shaped like merged `ProtocolParamUpdate` values.
That makes them behave partly like deltas with fallback to
`DefaultEpochParamProvider`, not like a full persisted epoch snapshot. This is
not ideal for read APIs, and it can hide completeness bugs such as cost model
replacement instead of language-by-language merge.

Yaci Store has the design we want conceptually:

- `protocol_params_proposal` stores update/proposal deltas.
- `epoch_param` stores the resolved full effective parameters for each epoch.
- each epoch resolves from previous epoch params, era genesis params, and
  applicable updates.

Yano should follow the same model, but keep the resolved values inside
`EpochParamTracker` and its existing RocksDB column family. We do not want a
separate protocol-parameter read index because that would add duplicate storage,
additional rollback handling, and another source of truth.

## Decision

`EpochParamTracker` will become the single source of truth for effective
protocol parameters after Shelley starts.

The tracker will keep two distinct concepts:

- pending update deltas, keyed by effective epoch and source slot/tx index;
- finalized full effective protocol parameter snapshots, keyed by epoch.

The persisted finalized value must represent the complete effective protocol
parameters for that epoch. API reads and ledger calculations can then both read
from the same resolved provider surface.

This does not mean protocol parameters become an API-only cache. The tracker is
ledger state. Any read DTO conversion must happen outside this persistent model.

## Persistence Model

Persist protocol parameters in a ledger/internal representation, not in the REST
DTO shape.

Rational values must be stored in rational form when the source value is
rational:

- `UnitInterval`
- `NonNegativeInterval`
- or an internal equivalent with numerator and denominator

Do not persist these values as `BigDecimal`. `BigDecimal` is only for API DTO
projection and display compatibility. This matters for exact ledger semantics
and for avoiding precision drift across restart, rollback, and reserialization.

Examples of rational fields:

- `rho`
- `tau`
- `a0`
- `decentralisationParam`
- `priceMem`
- `priceStep`
- pool voting thresholds
- DRep voting thresholds
- `minFeeRefScriptCostPerByte`

The persisted model should be versioned or otherwise self-describing enough to
distinguish new full snapshots from any legacy serialized `ProtocolParamUpdate`
rows in existing local chainstates.

## Genesis Parser Coverage

Byron does not need protocol-parameter support. Protocol parameters start at
Shelley.

### Shelley Genesis

Bundled Shelley genesis files for mainnet, preprod, preview, and sanchonet have
the same `protocolParams` key set:

- `a0`
- `decentralisationParam`
- `eMax`
- `extraEntropy`
- `keyDeposit`
- `maxBlockBodySize`
- `maxBlockHeaderSize`
- `maxTxSize`
- `minFeeA`
- `minFeeB`
- `minPoolCost`
- `minUTxOValue`
- `nOpt`
- `poolDeposit`
- `protocolVersion`
- `rho`
- `tau`

The existing Shelley parser already reads these fields. As part of the
implementation, add tests that assert this completeness explicitly for all
bundled networks.

### Alonzo Genesis

Bundled Alonzo genesis files have:

- `collateralPercentage`
- `costModels`
- `executionPrices`
- `lovelacePerUTxOWord`
- `maxBlockExUnits`
- `maxCollateralInputs`
- `maxTxExUnits`
- `maxValueSize`

The parser must read all of these. `executionPrices.prMem` and
`executionPrices.prSteps` should be retained internally as rational values, not
as `BigDecimal`.

Bundled Alonzo genesis files currently contain `PlutusV1` cost models. Later
Plutus cost models arrive through protocol updates or Conway genesis and must be
merged by language.

### Babbage Transition

Babbage has no separate genesis file in the bundled configs. The tracker must
apply era transition rules when the Babbage era starts.

Required rules include:

- preserve previously effective parameters;
- remove deprecated fields such as decentralisation parameter and extra entropy
  from the effective Babbage-era API view;
- translate Alonzo `lovelacePerUTxOWord` into the Babbage/Conway
  `coinsPerUtxoSize` / `coinsPerUtxoByte` semantics where needed;
- preserve existing cost models unless an update replaces a specific language.

These rules should be implemented in one small era-transition materializer and
tested independently.

### Conway Genesis

Bundled Conway genesis files have:

- `committee`
- `committeeMaxTermLength`
- `committeeMinSize`
- `constitution`
- `dRepActivity`
- `dRepDeposit`
- `dRepVotingThresholds`
- `govActionDeposit`
- `govActionLifetime`
- `minFeeRefScriptCostPerByte`
- `plutusV3CostModel`
- `poolVotingThresholds`

Only protocol-parameter fields should be part of the protocol-parameter
snapshot. `committee` and `constitution` remain governance state bootstrap
inputs.

Current parser coverage must be completed before the endpoint is considered
correct:

- parse `plutusV3CostModel`;
- merge `PlutusV3` into the existing cost model map without dropping
  `PlutusV1` or `PlutusV2`;
- keep thresholds and `minFeeRefScriptCostPerByte` as rational values
  internally.

## EpochParamTracker Design

### Internal Types

Introduce an internal full snapshot model if `ProtocolParamUpdate` cannot
represent full effective params clearly and losslessly. The snapshot type should
belong to `ledger-state` or `node-api` only if it is needed by more than the
tracker.

The internal model should:

- contain every supported Shelley-through-Conway protocol parameter;
- use rational types for rational fields;
- store cost models by Plutus language;
- avoid Blockfrost field names and REST-specific null handling;
- include a schema/version marker for persisted JSON.

`ProtocolParamUpdate` should remain the representation for deltas from
transactions or governance actions.

### Materialization

Add a small materializer owned by the tracker:

```text
previous full snapshot
  + era transition/genesis overlay
  + pending protocol update delta
  + enacted governance delta
  = full effective snapshot for epoch
```

This materializer must be deterministic and independently tested.

### Finalization

At epoch boundary:

1. Determine whether the epoch is pre-Shelley. If yes, do not create a protocol
   parameter snapshot.
2. Find the previous full snapshot, if any.
3. If this is the first Shelley/non-Byron epoch, bootstrap from Shelley genesis.
4. If an era transition starts at this epoch, apply that era's parameter
   overlay/rules:
   - Alonzo genesis fields at Alonzo start,
   - Babbage transition rules at Babbage start,
   - Conway genesis fields at Conway start.
5. Merge pending pre-Conway protocol update deltas for the epoch.
6. Persist the full effective snapshot for the epoch.

If no update exists for an epoch, persist a carried-forward full snapshot. This
keeps historical API reads as simple point lookups and prevents fallback logic
from becoming the real source of truth.

### Conway Enactment

Governance enactment currently calls `applyEnactedParamChange(epoch, update)`.
That method should merge the enacted update onto the full snapshot for that
epoch and persist the updated full snapshot.

If the snapshot for that epoch does not exist yet, the method must materialize
the epoch from the previous snapshot and era rules before applying the enacted
delta. It must not persist a partial update.

Hard fork initiation actions should update only protocol major/minor in the full
snapshot. Era metadata remains owned by the existing era tracking path.

### Cost Models

Cost models must merge by language key. A newer update containing only
`PlutusV3` must not drop existing `PlutusV1` or `PlutusV2`.

Rules:

- start with previous effective cost models;
- overlay language entries present in the new update/genesis overlay;
- preserve missing languages;
- keep raw data sufficient to produce Blockfrost-compatible `cost_models` and
  `cost_models_raw` API fields.

This fixes the known class of bugs where the whole cost model map is replaced by
a partial update.

## Rollback

Do not add a new rollback surface.

The existing tracker rollback model remains correct if finalized keys contain
full snapshots:

- delete pending update keys whose source slot is greater than the rollback
  target slot;
- delete finalized snapshot keys whose epoch is greater than the rollback target
  epoch;
- reload the in-memory tracker state from RocksDB after the rollback batch is
  committed.

This handles:

- rollback of a transaction containing a pending protocol update;
- rollback across an epoch boundary;
- rollback across a Conway enactment boundary.

No independent read-index rollback is needed because there is no independent
read index.

## API Projection

`/epochs/{number}/parameters` and `/epochs/latest/parameters` should read the
effective snapshot through `EpochParamTracker` / `EpochParamProvider`.

Only the endpoint DTO mapper converts exact rational values into `BigDecimal`
for Blockfrost-compatible JSON fields.

The API layer may return `null` for era-inapplicable fields, but the persistent
model should preserve the actual ledger state values and era applicability
separately.

## Migration and Existing Chainstates

This feature has not been released, but local development chainstates may exist
with old serialized finalized values.

Implementation should be tolerant:

- on load, detect new full snapshots by version/shape;
- if a legacy `ProtocolParamUpdate` finalized row is found, treat it as a delta
  and materialize a full snapshot in memory using previous epoch/genesis state;
- log a clear warning that a legacy protocol-parameter row was upgraded in
  memory;
- avoid silently claiming exact recovery if old persisted data is insufficient.

Because this is pre-release, it is acceptable to recommend a resync for exact
historical protocol-parameter API validation if a legacy chainstate cannot be
fully recovered. New synced chainstates must persist full snapshots from the
start.

## Safety Plan

This can be implemented safely if it is phased and tested before switching any
ledger calculations to depend on new snapshot behavior.

### Phase 1: Tests and Read-Only Model

- Add parser coverage tests for all bundled Shelley, Alonzo, and Conway genesis
  files.
- Add tests proving rational fields remain rational in the internal model.
- Add the internal full snapshot model and materializer without wiring it into
  epoch processing.

### Phase 2: Parser Completeness

- Change Alonzo parsed execution prices from API-style decimal values to exact
  rational/internal values.
- Parse Conway `plutusV3CostModel`.
- Keep API conversion to `BigDecimal` outside the parser/persistent model.

### Phase 3: Materializer Tests

Add focused tests for:

- first Shelley epoch bootstrap;
- network starting directly in Alonzo, Babbage, or Conway for devnet/custom
  networks;
- Alonzo overlay preserving Shelley params;
- Babbage transition rules;
- Conway overlay preserving previous Plutus languages and adding `PlutusV3`;
- partial protocol update preserving unchanged fields;
- multiple updates in the same effective epoch;
- governance enactment after epoch finalization.

### Phase 4: Tracker Persistence

- Change finalized `epochParams` values to full snapshots.
- Keep pending update storage as deltas.
- Make `finalizeEpoch` always persist full effective snapshots for Shelley and
  later epochs.
- Make `applyEnactedParamChange` persist full effective snapshots.
- Add tolerant legacy loading.

### Phase 5: Provider and API Mapping

- Update provider getters to read from full snapshots first.
- Keep `DefaultEpochParamProvider` as genesis/bootstrap fallback only.
- Keep API DTO conversion as the only `BigDecimal` conversion layer.
- Verify `/epochs/{number}/parameters` returns complete Blockfrost-shaped
  responses including all available Plutus cost models.

### Phase 6: Regression Verification

Before merging implementation:

- run existing AdaPot and reward calculation tests;
- run governance tests that use protocol parameters for threshold decisions;
- run protocol parameter endpoint tests;
- compare selected mainnet/preprod/preview epochs against Yaci Store or
  Blockfrost where available;
- verify rollback tests for pending updates and finalized snapshots.

## Risk Assessment

Risk is medium because protocol parameters are used by ledger-state calculation.
The safe path is not a broad refactor; it is a semantic tightening of
`EpochParamTracker` so it stores the full values it already tries to resolve.

The main risks are:

- era transition rules implemented incorrectly;
- partial update merging dropping fields;
- cost model map replacement dropping older Plutus languages;
- decimal persistence losing rational exactness;
- legacy chainstate rows being misread as full snapshots.

The mitigations are:

- keep a single source of truth in `EpochParamTracker`;
- keep pending deltas separate from finalized full snapshots;
- preserve rational values internally;
- use versioned/tolerant persistence;
- add parser and materializer tests before wiring;
- keep existing ledger calculation regression tests as mandatory gates.

With those constraints, this is a safe implementation path and avoids the extra
storage and rollback complexity of a separate protocol-parameter read index.
