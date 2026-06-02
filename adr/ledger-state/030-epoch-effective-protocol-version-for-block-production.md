# ADR-030: Epoch-Effective Protocol Version for Block Production

## Status

Proposed

## Date

2026-06-01

## Context

Yano already has two protocol-parameter modes for transaction validation and
script evaluation.

When epoch-parameter tracking is enabled, `YanoProducer` does not load
`protocol-param.json` for transaction validation/evaluation. Instead it resolves
epoch-effective protocol parameters from `LedgerStateProvider`, backed by
`EpochParamTracker`.

When epoch-parameter tracking is disabled, `protocol-param.json` is used as a
static protocol-parameter source.

Block production has a separate protocol-version path. Produced Conway block
headers include a `protocolVersion` field. The current signed block-production
paths resolve that version from `GenesisConfig.getProtocolVersionMajor()` and
`getProtocolVersionMinor()`. Those methods parse nested
`protocolVersion.{major,minor}` or flat
`protocol_major_ver` / `protocol_minor_ver` from the loaded protocol-parameters
JSON. They do not read `ShelleyGenesisData` or an epoch-effective snapshot. The
fallback `DevnetBlockBuilder` constructor used when no operator keys are
configured has a hardcoded default of `10.2`.

That makes the role of `protocol-param.json` unclear. In devnet profiles where
epoch-parameter tracking is enabled, the file is not the transaction validation
source, but two fields from the file can still drive produced block headers.

[Issue #12](https://github.com/bloxbean/yano/issues/12) requires updating
devnet configuration to protocol version 11 and the latest cost models. That
should not make `protocol-param.json` the source of truth when
epoch-parameter tracking is enabled.

## Problem

Yano currently has inconsistent protocol-version sources:

```text
Transaction validation/evaluation
  tracking enabled  -> effective epoch protocol params
  tracking disabled -> static protocol-param.json

Produced block header protocolVersion
  currently -> static protocol-parameters JSON or builder default
```

This can produce incorrect block headers in devnet and custom networks. If the
effective protocol version for an epoch is `11.0`, but the block builder keeps a
static `10.2` value from `protocol-param.json` or from its default constructor,
Yano will produce blocks that do not match the ledger epoch parameters used by
validation.

Reading produced block protocol version directly from Shelley genesis is also
not correct as a general rule. On public networks, Shelley genesis contains the
network's starting protocol version, not necessarily the current version. The
current version is derived by carrying historical parameter updates and Conway
governance enactments through epoch boundaries.

## Decision

Produced block protocol version must be resolved from the same epoch-effective
protocol parameters used by validation when epoch-parameter tracking is enabled.

For block production with epoch-parameter tracking enabled:

1. Resolve the block slot to an epoch using the runtime `EpochSlotCalc`.
2. Resolve the epoch-effective protocol parameters for that epoch from
   `EpochParamTracker` / `LedgerStateProvider`.
3. Stamp `protocolMajorVer` and `protocolMinorVer` from that effective epoch
   snapshot into the block header.

Devnet profiles already enable epoch-parameter tracking by default
(`yano.epoch-params.tracking-enabled=true`). This decision preserves that
default. Produced devnet block headers must come from the same effective
protocol-parameter source as validation. `shelley-genesis.json` provides the
genesis/bootstrap protocol version that seeds `DefaultEpochParamProvider` and
`EpochParamTracker`. For a PV11 devnet, the devnet Shelley genesis must declare
`protocolParams.protocolVersion` as `11.0`.

Alonzo and Conway genesis files provide the genesis-era cost models and related
era parameters that seed effective snapshots. A PV11 devnet must update those
genesis cost-model inputs as needed.

For block production with epoch-parameter tracking disabled:

1. `protocol-param.json` is the static protocol-parameter source.
2. The produced block header protocol version is read from that static file.
3. Transaction validation/evaluation, if enabled, also use the same static file.

Role of `protocol-param.json`:

| epoch-param tracking | transaction validation/evaluation | produced block `protocolVersion` |
|----------------------|-----------------------------------|----------------------------------|
| enabled              | not used                          | not used                         |
| disabled             | static source                     | static source                    |

Role of `shelley-genesis.json` `protocolParams.protocolVersion`:

- tracking enabled: bootstrap input to
  `DefaultEpochParamProvider` / `EpochParamTracker` for the first non-Byron
  effective snapshot. For devnet this is epoch 0. It is not read directly by
  validation or block production at steady state.
- tracking disabled: not used for produced block `protocolVersion`. Block
  production uses `protocol-param.json`.

Public network Shelley genesis files must not be edited to the current protocol
version. Their Shelley genesis protocol version remains the historical genesis
value. When public-network tracking is enabled, current protocol version comes
from the effective epoch snapshot. When tracking is disabled, the configured
`protocol-param.json` must represent the desired static snapshot.

## Design

Add a small runtime protocol-version contract for block builders:

```java
record ProtocolVersion(long major, long minor) {}

interface ProtocolVersionSupplier {
    ProtocolVersion getProtocolVersion(long slot);
}
```

The tracking-enabled implementation should:

1. Convert `slot` to `epoch`.
2. Read the epoch's effective protocol-parameter snapshot from the ledger state
   provider or directly from the enabled epoch-param tracker.
3. Return the snapshot's protocol major/minor version.
4. Cache only the last resolved `epoch -> protocolVersion` value. Protocol
   parameters are epoch-scoped and cannot change inside an epoch.
5. If the snapshot is missing and an enabled `EpochParamTracker` is available,
   call `bootstrapEpochIfNeeded(epoch)` once and re-read the snapshot. This
   handles first-block devnet production without changing the existing block
   builder construction order.
6. Fail clearly before producing a block if tracking is enabled but effective
   protocol parameters for the block's epoch are still unavailable.

The static implementation should:

1. Parse `protocol-param.json` through the existing explicit
   `ProtocolParamsMapper`.
2. Return the static protocol major/minor for every slot.

`DevnetBlockBuilder` and `SignedBlockBuilder` should resolve the protocol
version during `buildBlock(...)`, using the block slot being produced. They
must not capture a production protocol version once at construction time.

Yano wiring sites that currently read or imply a static produced-block protocol
version and must switch to the supplier when tracking is enabled:

1. Slot-leader block producer startup currently reads fixed values from
   `genesisConfig.getProtocolVersion{Major,Minor}()` and passes them to
   `SignedBlockBuilder`.
2. Past-time-travel slot-leader producer startup follows the same fixed-value
   pattern.
3. `createBlockBuilder(...)` signed-devnet branch follows the same fixed-value
   pattern.
4. `createBlockBuilder(...)` fallback branch currently uses
   `new DevnetBlockBuilder()` with no version override, relying on the
   hardcoded `10.2` default.

All four sites must consult the same mode decision used for transaction
validation wiring, so produced blocks cannot diverge from validated
transactions.

Existing constructors that accept fixed protocol versions may remain as wrappers
for tests and standalone consumers:

```java
new DevnetBlockBuilder(10, 2)
```

should internally wrap the fixed value as a constant supplier. Production Yano
wiring must pass the runtime supplier.

The no-arg `DevnetBlockBuilder` default should not be used by production Yano
block-production wiring. It may remain for isolated tests if needed, but tests
that care about protocol version should use an explicit fixed version or a
supplier.

`Yano` should build the protocol-version supplier from the same mode decision
used for transaction validation:

- if epoch-parameter tracking is enabled, use effective epoch params
- otherwise, require/load `protocol-param.json` for static fallback

`GenesisConfig.load(...)` calls in tracking-enabled runtime paths should not
load `protocol-param.json` just to discover protocol version.

## Consequences

Positive:

- Produced devnet blocks use the same epoch-effective protocol version as
  transaction validation.
- PV11 devnet support can be driven from genesis/bootstrap epoch params instead
  of a partially live `protocol-param.json`.
- Public-network block production does not confuse Shelley genesis starting PV
  with current effective PV.
- `protocol-param.json` has one clear purpose: static fallback when tracking is
  disabled.
- The one-epoch cache is simple and matches ledger semantics.

Tradeoffs:

- Block production now depends on the effective epoch-param snapshot when
  tracking is enabled.
- The supplier is responsible for first-epoch availability. Today
  `EpochParamTracker.bootstrapEpochIfNeeded(...)` runs from the genesis-block
  event, while block builders are constructed before genesis is produced. Lazy
  bootstrap on first lookup keeps the current wiring order unchanged.
- Tests must cover both tracking-enabled and static-fallback modes.

## Implementation Notes

For devnet PV11 configuration:

- update `app/config/network/devnet/shelley-genesis.json` to
  `protocolParams.protocolVersion = 11.0`
- update devnet Alonzo/Conway genesis cost-model inputs to the latest required
  PV11 values. Shelley genesis carries the protocol version, not Plutus cost
  models.
- update `app/config/network/devnet/protocol-param.json` to `11.0` and matching
  cost models only for the tracking-disabled static fallback mode

Use these implementation sources for the devnet PV11 update:

- PV11 Plutus cost models:
  `/Users/satya/work/bloxbean/yaci-devkit/applications/cli/config/plutus-costmodels-v11.json`
- latest preview PV11 protocol parameters for the static fallback
  `protocol-param.json` baseline:

```bash
CARDANO_NODE_SOCKET_PATH=/Users/satya/work/cardano-node/preview/db/node.socket \
  /Users/satya/work/cardano-node/preview/bin/cardano-cli \
  query protocol-parameters \
  --testnet-magic 2 \
  --out-file /tmp/yano-preview-protocol-params-pv11.json
```

The preview node command is only a source for the devnet static-fallback file.
Do not update public-network Shelley genesis protocol versions from it. When
copying preview protocol parameters into devnet fallback config, review any
network- or devnet-specific values intentionally rather than treating the live
preview output as a blanket replacement for every devnet setting.

For public network configs, `protocol-param.json` should remain a static
fallback snapshot. Updating those files to PV11 is only correct when the bundled
static fallback is intended to represent PV11-era network parameters.

The comment in `DevnetBlockBuilder` that says the default protocol version must
be greater than or equal to the current protocol version from
`shelley-genesis.json` should be removed or replaced when the supplier is wired
in. It reinforces the source-of-truth confusion this ADR resolves.

## Tests

Add or update tests for these scenarios:

1. Tracking-enabled protocol-version supplier resolves slot to epoch and reads
   the epoch-effective protocol version from the tracker/snapshot.
2. The supplier caches the last epoch and refreshes when a later block slot maps
   to a new epoch.
3. Tracking-enabled first-block production bootstraps or lazily materializes the
   effective snapshot before resolving produced-block protocol version.
4. Tracking-enabled block production fails clearly if the effective snapshot for
   the block epoch is still unavailable.
5. `DevnetBlockBuilder` stamps the supplier-resolved protocol version into the
   produced block header.
6. `SignedBlockBuilder` stamps the supplier-resolved protocol version into the
   produced block header.
7. Fallback `DevnetBlockBuilder` path with no operator keys and tracking enabled
   stamps the epoch-effective protocol version, not the hardcoded `10.2`
   default.
8. Devnet tracking-enabled startup can produce a PV11 block without consulting
   `protocol-param.json` for protocol version, even if the file is missing or
   pinned to a different version.
9. Tracking-disabled startup uses `protocol-param.json` as the static source for
   both transaction validation/evaluation and produced block protocol version.
10. Public-network Shelley genesis protocol version is not treated as the current
   produced-block protocol version when tracking is enabled.
11. Devnet Shelley genesis PV11 and Alonzo/Conway cost-model inputs seed the
   initial effective epoch-param snapshot.
