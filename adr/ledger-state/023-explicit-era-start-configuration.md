# ADR-023: Explicit Era Start Configuration

## Status

Proposed - future implementation

## Date

2026-04-29

## Context

Yano currently learns era boundaries from observed block eras and persists the
first observed slot for each era in chainstate metadata. This is the safest
default for normal public-network sync because the chain itself remains the
source of truth.

There are cases where explicit era-start metadata is still useful:

- devnets that intentionally start directly in Alonzo, Babbage, Conway, or a
  later era;
- public or custom test networks whose available history begins after Shelley;
- deterministic tests that need era rules before the first block is processed;
- startup materialization of full protocol-parameter snapshots before an
  observed `BlockAppliedEvent` can populate era metadata.

Cardano node supports this style through configuration values such as
`AlonzoHardForkAtEpoch`, `BabbageHardForkAtEpoch`, and
`ConwayHardForkAtEpoch`. Yano should have an equivalent mechanism, but it
should remain optional and should not replace observed chain metadata for
ordinary sync.

This is especially relevant to protocol-parameter handling because
`EpochParamTracker` applies Shelley, Alonzo, Babbage, and Conway parameter
overlays based on persisted era metadata. If a chain starts directly in Babbage
or Conway, Yano must know that earlier era overlays also apply from epoch 0.

## Decision

Add optional explicit era-start configuration in a future implementation.

The default behavior remains unchanged:

- if no explicit era-start config is provided, Yano infers era starts from
  observed block eras and persists them idempotently;
- public mainnet, preprod, and preview sync should continue to rely on observed
  chain metadata unless an operator deliberately overrides it.

The explicit configuration should be applied during startup before account
state, governance, epoch parameter tracking, block production, bootstrap, or
client sync can process blocks.

The preferred external shape is epoch-based, matching Cardano node terminology:

```yaml
yano:
  era-starts:
    shelley-epoch: 0
    allegra-epoch: 0
    mary-epoch: 0
    alonzo-epoch: 0
    babbage-epoch: 0
    conway-epoch: 0
```

Internally, Yano should continue to persist era starts as slots in chainstate
metadata. Startup config should therefore be converted from epoch to the first
slot of that epoch using `EpochSlotCalc`. A later extension can add exact
`*-slot` values for custom networks that need slot-level precision.

## Rules

1. Config is optional. Missing values mean "infer from observed blocks".
2. If a network starts directly in Babbage, setting `babbage-epoch: 0` is enough
   for `EraProviderImpl` to treat Shelley, Alonzo, and Babbage rules as active
   from epoch 0 because the earliest known era is Babbage.
3. If a network starts directly in Conway, setting `conway-epoch: 0` is enough
   for Shelley, Alonzo, Babbage, and Conway overlays to apply from epoch 0.
4. Configured era starts must be monotonic by era order when more than one era
   is specified.
5. If chainstate already contains an era start for the same era:
   - matching values are accepted;
   - mismatches should fail startup by default, or at minimum require an
     explicit unsafe override flag.
6. Explicit config must not silently rewrite existing chainstate metadata.
7. Era-start metadata is chain identity metadata, not normal rollback data. It
   should not be rolled back by block rollback unless Yano later adds support
   for changing this metadata as part of a fork-aware bootstrap process.

## Implementation Plan

1. Add a small config model, for example `yano.era-starts.*-epoch`, in
   node runtime configuration.
2. Add a startup initializer that runs immediately after genesis and
   `EpochSlotCalc` are available, but before account state, governance,
   protocol-parameter tracking, block production, bootstrap, or sync starts.
3. Convert configured epochs to slots and persist them through
   `DirectRocksDBChainState.setEraStartSlot`.
4. Add validation for monotonic order and existing-chainstate conflicts.
5. Keep observed block-era persistence as the fallback and as the normal public
   network path.
6. Add tests for:
   - no config keeps current inference behavior;
   - Babbage direct start resolves Alonzo and Babbage as epoch 0;
   - Conway direct start resolves Alonzo, Babbage, and Conway as epoch 0;
   - existing matching persisted metadata is accepted;
   - existing conflicting persisted metadata fails startup;
   - explicit metadata is available before `EpochParamTracker.finalizeEpoch(0)`.

## Consequences

Positive:

- Devnets and custom networks can start directly in modern eras without relying
  on the first processed block to seed era metadata.
- Protocol-parameter snapshot materialization becomes deterministic for direct
  Babbage/Conway starts.
- The behavior aligns conceptually with Cardano node hard-fork-at-epoch config.

Tradeoffs:

- Adds another operator-facing configuration surface.
- Misconfiguration can corrupt ledger interpretation, so conflict validation
  must be strict.
- Slot-level precision may still be needed later for non-standard networks.

## Open Questions

- Should the first implementation expose only `*-epoch`, or both `*-epoch` and
  `*-slot`?
- Should mismatches always fail startup, or should there be an explicit
  `allow-era-start-overwrite` repair mode for development only?
- Should future eras use fixed named fields, a map keyed by era number, or both?
