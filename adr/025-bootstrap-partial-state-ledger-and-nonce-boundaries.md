# ADR-025: Bootstrap Partial-State Ledger and Nonce Boundaries

## Status

Proposed

## Date

2026-06-08

## Context

ADR-012 introduced bootstrap state mode as a lightweight relay mode. In this mode
Yano starts from a recent canonical block, writes enough synthetic chain state to
intersect with the upstream peer, injects selected UTXOs from Blockfrost or Koios,
and then continues forward sync.

This is intentionally not a full ledger replay from genesis. The local database
does not contain historical block bodies before the bootstrap point, nor does it
contain historical ledger-derived state such as epoch parameter snapshots,
governance state, rewards, AdaPot values, stake snapshots, or account-state
history.

This boundary also applies to epoch nonce tracking. Cardano epoch nonce evolution
is cumulative from genesis: replay needs every nonce-relevant block body, plus
era-effective protocol parameters such as historical `extraEntropy`. When Yano
starts from a bootstrap point, the first stored block may be millions of blocks
after genesis. Attempting startup nonce repair from genesis fails correctly
because the earlier block bodies are missing.

Observed preprod bootstrap failure:

```text
Cannot repair epoch nonce state from genesis: first stored block is 4799801,
so earlier block bodies required for nonce replay are missing
```

The failure is not caused by missing Blockfrost credentials once bootstrap data
fetch succeeds. It is caused by runtime nonce tracking starting in a partial-state
database.

Yano already treats several ledger-derived subsystems as ineffective in bootstrap
mode at the app wiring layer. The runtime must apply the same boundary to nonce
tracking and API behavior.

## Decision

Bootstrap mode is a partial-state mode. When `yano.bootstrap.enabled=true`, Yano
must automatically disable derived ledger-state behavior that cannot be computed
from the bootstrap database.

The following subsystems must be disabled or treated as unavailable in bootstrap
mode:

- account-state tracking
- epoch parameter tracking from blocks
- account history
- stake-balance index
- epoch snapshot amount tracking
- AdaPot tracking
- reward calculation
- governance tracking
- snapshot export
- epoch nonce tracking

Runtime epoch nonce tracking must not initialize in bootstrap mode. The runtime
must leave `epochNonceState` unset, must not run `NonceReplayService`, and must
not register `NonceEvolutionListener`.

Bootstrap mode must not be combined with block-producer mode. Producing blocks
requires valid nonce evolution and ledger state; a partial bootstrap database
cannot provide those inputs. Yano must reject this configuration at validation
time rather than allowing a node to start and fail later.

Bootstrap mode must continue to expose static protocol parameters from the
configured `protocol-param.json` file. This static source is valid for lightweight
transaction submission and transaction evaluation, but it is not an
epoch-effective ledger snapshot.

API behavior:

- `/api/v1/node/protocol-params` returns configured static protocol params.
- `/api/v1/node/epoch-nonce` returns unavailable because nonce state is not tracked.
- Epoch-specific ledger-derived endpoints return unavailable unless they
  explicitly document a bootstrap static fallback.
- If `/api/v1/epochs/{epoch}/parameters` is given a bootstrap fallback, the
  response must be based on static `protocol-param.json` and must not include a
  tracked epoch nonce.

Operators should not need to manually disable every derived ledger-state property
in `config/application.yml`. Enabling bootstrap mode is the single switch that
selects partial-state behavior.

## Consequences

Positive:

- Bootstrap startup no longer fails during nonce repair when the first stored
  block is after genesis.
- Bootstrap mode does not present partial state as full ledger truth.
- Transaction submission, transaction validation, and script evaluation can still
  use static protocol parameters.
- Operator configuration stays simple.

Tradeoffs:

- Bootstrap nodes cannot answer epoch nonce queries.
- Bootstrap nodes cannot provide reliable historical ledger-state APIs.
- Static protocol params may become stale after future protocol parameter
  updates, so bootstrap mode remains a lightweight relay mode, not a full ledger
  node.

## Implementation Plan

1. In runtime startup, gate relay/client nonce initialization on bootstrap mode:
   skip `initNonceTracking()` when `config.isEnableBootstrap()` is true.
2. Log a clear startup message when nonce tracking is skipped because bootstrap
   mode is enabled.
3. Reject `yano.bootstrap.enabled=true` together with block-producer mode during
   configuration validation.
4. Keep existing app-layer effective config behavior that disables derived
   ledger-state subsystems under bootstrap mode.
5. Ensure transaction validation/evaluation selects static `protocol-param.json`
   when epoch-param tracking is ineffective in bootstrap mode.
6. Keep `/node/protocol-params` backed by `GenesisConfig.getProtocolParameters()`.
7. Keep `/node/epoch-nonce` unavailable when `epochNonceState` is null.
8. Decide whether `/epochs/{epoch}/parameters` should return static protocol
   params in bootstrap mode or remain unavailable. Default implementation should
   prefer a clear static fallback only if the response can avoid implying tracked
   ledger state.

## Tests

Add focused tests for:

1. Bootstrap runtime startup does not call nonce repair and leaves nonce state
   null.
2. Bootstrap startup with a partial chain whose first stored block is greater
   than zero does not fail with nonce replay errors.
3. Configuration validation rejects bootstrap mode combined with block-producer
   mode.
4. App effective config disables derived ledger-state subsystems when bootstrap
   is enabled.
5. `/node/protocol-params` returns static JSON in bootstrap mode.
6. `/node/epoch-nonce` returns unavailable in bootstrap mode.
7. Transaction validation/evaluation resolves static protocol params when
   bootstrap mode makes epoch-param tracking ineffective.

Run focused suites:

```bash
./gradlew :app:test :runtime:test --tests '*Bootstrap*' --tests '*Nonce*' --tests '*ProtocolParams*'
```

## References

- `adr/012-bootstrap-state-mode-lightweight-relay.md`
- `adr/ledger-state/024-effective-protocol-params-for-tx-validation.md`
- `adr/ledger-state/027-durable-epoch-nonce-state-repair.md`
