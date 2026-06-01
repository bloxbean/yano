# ADR-029: Dynamic Transaction Validation Context

## Status

Proposed

## Date

2026-06-01

## Context

Yano wires transaction validation and script evaluation in `YanoProducer` by
creating `TransactionValidator` and `TransactionEvaluator` instances once during
node construction.

ADR-022 made `EpochParamTracker` the source of full epoch-effective protocol
parameter snapshots. ADR-024 then changed transaction validation and evaluation
to resolve protocol parameters from those snapshots at runtime when
epoch-parameter tracking is enabled.

That fixes stale protocol parameters for epoch transitions, but one part of the
transaction context is still fixed at construction time: `SlotConfig`.

This matters for devnet past-time-travel mode. `/devnet/epochs/shift` changes
the effective Shelley `systemStart`, persists it into `shelley-genesis.json`,
updates `YanoConfig.genesisTimestamp`, replaces `genesisConfig`, and rebuilds
`SlotTimeCalculator`. Any validator or evaluator that already captured the old
`SlotConfig` can continue using a stale zero time even though the node runtime
has moved to the shifted genesis time.

The transaction context used by validation and evaluation therefore has two
time-varying inputs:

- protocol parameters, because they can change at epoch boundaries through
  protocol updates and Conway governance enactment
- slot timing, because devnet past-time-travel can change the effective Shelley
  start time after evaluator/validator construction

## Problem

The current architecture has inconsistent refresh behavior:

```text
ProtocolParams
  dynamic when epoch-param tracking is enabled
  static when using protocol-param.json fallback

SlotConfig
  always captured once in YanoProducer
```

This can cause two classes of bugs.

First, if epoch-effective protocol parameters change, every validation and
evaluation path must use the parameters for the epoch containing the current
runtime slot. A stale parameter set can incorrectly accept or reject
transactions after an epoch boundary.

Second, if devnet past-time-travel changes Shelley `systemStart`, every
validation and evaluation path must use the shifted `SlotConfig`. A stale
`zeroTime` can make validity interval and script-context time calculations
disagree with the blocks that Yano is producing.

Recreating validators after every context change would be fragile. It would
require every mutation path to know which validator/evaluator objects exist and
would risk missing REST, mempool, or alternative evaluator paths.

## Decision

Transaction validation and script evaluation will resolve their complete
transaction validation context at call time.

Protocol parameters remain supplied by `EpochProtocolParamsSupplier`:

```java
ProtocolParams getProtocolParams(long slot);
```

Slot timing will be supplied by a new dynamic slot-config contract:

```java
SlotConfig getSlotConfig();
```

The node app will provide a runtime implementation that builds a fresh CCL
`SlotConfig` from the current Yano runtime state. The supplier must resolve the
genesis time in this order:

1. `YanoConfig.getGenesisTimestamp()` when it is positive
2. `Yano.getResolvedGenesisTimestamp()` when it is positive
3. parsed Shelley `systemStart` from loaded genesis data
4. fail with a clear error if no valid genesis time is available

The supplier's returned `SlotConfig.zeroTime` is always epoch milliseconds.
`YanoConfig.getGenesisTimestamp()`, `Yano.getResolvedGenesisTimestamp()`, and
parsed Shelley `systemStart` values must be normalized to epoch milliseconds
before constructing the CCL `SlotConfig`. If a source API returns epoch seconds,
convert it before building the slot config. Raw configurable values that are
expected to be milliseconds must not be silently interpreted as seconds; fail
or log a clear configuration error when the units are ambiguous.

The supplier must also preserve Yano's slot-length derivation semantics. Use
`YanoConfig.getSlotLengthMillis()` when it is positive. When it is zero or
negative, derive the length from loaded Shelley genesis `slotLength * 1000`.
If Shelley slot length is unavailable or invalid, use the existing runtime
fallback of `1000ms` and log the fallback.

For public networks, the value should normally settle on the Shelley
`systemStart` loaded from genesis. For devnet past-time-travel, the value should
follow the updated `YanoConfig.genesisTimestamp` after
`shiftGenesisAndStartProducer`.

The current runtime slot must continue to come from the node tip or equivalent
runtime slot supplier. Transaction validity-start must not be used as a
replacement for the ledger current slot in the node validation path.

Callers with static protocol parameters should wrap them as an
`EpochProtocolParamsSupplier`, for example `slot -> staticProtocolParams`. This
keeps Scalus validator/evaluator construction on one protocol-parameter contract
while still supporting `protocol-param.json` bootstrap/devnet fallback mode.
Fixed `SlotConfig` overloads may remain for isolated tests and standalone
consumers, but node production wiring should use dynamic protocol-parameter and
slot-config suppliers.

## Design

Add a small `SlotConfigSupplier` interface in `ledger-rules` or another shared
module already visible to evaluator implementations.

Update Scalus bridge dynamic factory overloads so the node wiring can pass:

- `EpochProtocolParamsSupplier`
- `SlotConfigSupplier`
- current-slot `LongSupplier`
- optional current-epoch resolver for supplementary CCL rules

`ScalusBasedTransactionValidator` should resolve, per `validate` call:

1. current runtime slot
2. epoch-effective protocol parameters for that slot
3. current slot config

It should then pass the resolved slot config into `LedgerBridge.validate`.

`ScalusBasedTransactionEvaluator` should resolve the same context per
`evaluate` call before constructing `ScalusTransactionEvaluator`.

Each evaluator bridge must convert from the dynamic CCL `SlotConfig` into the
target library's slot-config type through a small adapter/helper. Do not assume
constructor argument order or units are identical across CCL, Scalus, JULC, and
Aiken. The adapter must preserve epoch-millisecond `zeroTime`, `zeroSlot`, and
millisecond `slotLength` according to the target library's expected constructor
shape.

The non-Scalus script evaluator paths must not keep a stale slot config either.
Add supplier-based overloads for `AikenTxEvaluator` and `JulcTxEvaluator`, or
wrap their construction so each evaluation uses a slot config produced from the
same runtime supplier. If those evaluator libraries only accept fixed slot
config at construction time, construct the library evaluator inside each
`evaluate` call using the current supplied slot config.

`YanoProducer.initTransactionEvaluator` should build one runtime slot-config
supplier and pass it to every validator/evaluator path. Static
`protocol-param.json` fallback mode may keep static protocol parameters, but it
must still use the dynamic slot-config supplier in devnet/block-producer paths.

`Yano.shiftGenesisAndStartProducer` should not directly recreate validators or
evaluators. Its existing updates to config/genesis/slot-time state are the
single source consumed by the dynamic supplier.

## Consequences

Positive:

- Validation and evaluation use epoch-effective protocol parameters after epoch
  transitions.
- Devnet past-time-travel updates to Shelley `systemStart` are reflected without
  rebuilding validators.
- REST evaluation, mempool validation, and block production validation can share
  the same runtime context behavior.
- Static constructors remain available for focused tests and non-node usage.

Tradeoffs:

- Validation and evaluation do a small amount of extra context resolution per
  call.
- Runtime wiring must expose genesis time through a clear API rather than
  relying on a construction-time local variable.
- Tests must cover dynamic slot timing as well as dynamic protocol parameters.

## Implementation Notes

The runtime slot-config supplier should create a new `SlotConfig` value rather
than mutating an existing one. This keeps validator and evaluator code simple
and avoids sharing mutable config across concurrent requests.

The supplier should use the current resolved slot length from `YanoConfig` or
derive it from loaded Shelley genesis using the same logic as the block producer
startup path. This ADR does not introduce dynamic slot-length changes during a
running process.

If protocol-parameter tracking is enabled and no effective snapshot exists for
the current epoch, validation should fail clearly rather than silently pinning
to a stale fallback. Static `protocol-param.json` remains a bootstrap/devnet
fallback only when epoch-parameter tracking is disabled.

## Tests

Add or update tests for these scenarios:

1. `EffectiveProtocolParamsSupplier` refreshes when the current slot maps to a
   new epoch.
2. Scalus validator dynamic wiring asks the protocol-parameter supplier on each
   validation call using the current runtime slot.
3. Scalus evaluator dynamic wiring asks the protocol-parameter supplier on each
   evaluation call using the current runtime slot.
4. Dynamic slot-config supplier returns an updated `zeroTime` after
   `YanoConfig.genesisTimestamp` changes.
5. Dynamic slot-config supplier returns `zeroTime` in epoch milliseconds and
   rejects or clearly reports ambiguous raw seconds-looking inputs.
6. Dynamic slot-config supplier preserves `slotLengthMillis == 0`
   auto-derivation from Shelley genesis and the existing `1000ms` fallback.
7. Evaluator-specific slot-config adapters preserve `zeroTime`, `zeroSlot`,
   and `slotLength` despite constructor order differences.
8. Devnet past-time-travel initializes validation/evaluation before epoch
   shift, calls `/devnet/epochs/shift`, and then validates/evaluates with the
   shifted Shelley start time.
9. Aiken and Julc evaluator paths use the latest supplied slot config, or tests
   document and guard any intentionally unsupported dynamic path.
10. Static fallback mode keeps static protocol parameters but picks up dynamic
   slot config when the runtime genesis timestamp changes.

Focused test commands:

```text
./gradlew :scalus-bridge:test
./gradlew :runtime:test --tests '*EffectiveProtocolParamsSupplierTest' --tests '*Slot*'
./gradlew :app:test --tests '*Devnet*' --tests '*Evaluation*'
```
