# ADR-CONSENSUS-003: Rollback-Safe Operational Certificate Counter State

## Status

Implemented.

## Date

2026-07-01

## Context

Shelley and post-Shelley Praos validation needs operational certificate counter
state. The header carries an operational certificate issue number. A validating
node must remember the current issue number for each block-issuer cold key so a
pool cannot replay an old certificate or jump the counter unexpectedly.

Yano already validates header-local Shelley+ evidence in layers:

- `kes-signature` verifies the header KES signature and KES period bounds;
- `opcert-signature` verifies the cold-key signature over the operational
  certificate body;
- `vrf-proof` verifies the Praos VRF proof against the epoch nonce;
- `leader-threshold` verifies the VRF leader value against active stake;
- `protocol-view` verifies protocol-version and header-size evidence.

The remaining ledger-view gap is the operational certificate counter. The
current account-state store does not persist this counter, so live
`praos-ledger` validation cannot yet require it for every pool without breaking
backward-compatible sync from existing chain-state databases.

## Source Review

The Haskell Cardano consensus implementation keeps op-cert counters in chain
dependency state:

- `PraosState` contains `praosStateOCertCounters :: Map ... Word64`;
- validation calls `validateKESSignature` with that counter map before VRF
  validation;
- when a header is accepted, `reupdateChainDepState` inserts the header's
  op-cert issue number into the counter map;
- validation rejects `NoCounterForKeyHashOCERT`,
  `CounterTooSmallOCERT`, and `CounterOverIncrementedOCERT`.

The effective Praos counter rule is:

- if the issuer is known in the ledger but has no counter yet, the current
  issue number is treated as `0`;
- otherwise the stored issue number is read from the counter map;
- a header is valid only when `stored <= headerCounter <= stored + 1`;
- accepted headers update the stored issue number to `headerCounter`.

TPraos exposes the same kind of counter state through its protocol state.

A source review of Dingo found header-time VRF/KES validation, local block
producer op-cert loading and validation, and comments/tests stating that
stake-pool/op-cert validation is intentionally skipped in the header-only path
and handled in a ledger validation path. No standalone rollback-safe remote
op-cert counter store equivalent to Haskell's protocol-state map was identified
in the reviewed paths.

## Decision

Yano will add a rollback-safe operational certificate counter store before
making `opcert-state` fail-closed for all `praos-ledger` validation.

Until that store exists and is enabled, `praos-ledger` uses a transitional soft
op-cert counter policy:

- if no op-cert counter state provider exists, accept the `opcert-state` stage;
- if the provider exists but has no counter for the pool, accept the
  `opcert-state` stage;
- if a counter exists for the pool, enforce
  `stored <= headerCounter <= stored + 1`;
- if a counter exists and the header counter is below the stored value or more
  than one above it, reject the header.

This is a backward-compatible policy for current Yano deployments. It avoids
falsely rejecting valid public-network headers while old chain-state databases
lack the new counter map, but it still rejects a header when Yano has explicit
counter evidence and the header conflicts with it.

Yano does not backfill op-cert counters for databases that were already synced
before this state existed. Operators that want strict counter validation should
sync from the validation start point with a Yano version that tracks op-cert
counters. Existing or checkpointed databases should use `none` or `compat`.

## State Model

Introduce an op-cert counter state keyed by block issuer cold-key hash.

Each value should contain:

- `issuerKeyHash`: canonical block issuer cold-key hash;
- `counter`: latest accepted operational certificate issue number;
- `lastUpdatedSlot`;
- `lastUpdatedBlockHash`;
- optional diagnostic fields such as `poolHash` when the pool key role is known.

The stored value represents the current chain-dependent state at the canonical
tip. It is not a historical index.

## Rollback Model

The counter store must follow the same rollback discipline as other Yano
ledger-state stores:

- every canonical apply records a per-block delta before mutating state;
- a delta records the previous counter value or an absent marker;
- rollback restores the previous value exactly;
- fork switch uses the existing generation-fenced apply boundary and does not
  mutate counter state from observer or candidate headers;
- restart recovery must not require in-memory counter history.

This state belongs to canonical block apply, not header fan-in. Observer headers
may be validated against available evidence, but they must not update the
counter map.

## Apply Rule

For a canonical Shelley+ header after header-local crypto has passed:

1. Resolve the issuer cold-key hash from the header.
2. Resolve whether the issuer is registered/eligible in the ledger view.
3. Read the current op-cert counter for that issuer.
4. If no persisted counter exists for a registered issuer, use `0` as the
   current counter during strict validation.
5. Reject if `headerCounter < currentCounter`.
6. Reject if `headerCounter > currentCounter + 1`.
7. Record a delta with the previous counter value.
8. Store `headerCounter` as the new counter for the issuer.

For the current transitional implementation, steps 4 through 6 apply only when
the provider has explicit counter evidence for the pool.

## Interfaces

Implemented read interface:

```java
public interface OpCertCounterProvider {
    Optional<OpCertCounterState> getOpCertCounterState(String issuerKeyHash);
    OptionalLong getOpCertCounter(String issuerKeyHash);
}
```

`HeaderValidationLedgerViewProvider.opCertStateFor(...)` remains the validation
SPI used by the header pipeline. The runtime ledger provider should source it
from `LedgerStateProvider`, which extends `OpCertCounterProvider`. Writes stay
inside `DefaultAccountStateStore` so rollback safety uses the existing
per-block delta journal instead of a second mutable store API.

## Validation Profiles

During the transitional period:

| Profile | Op-cert counter behavior |
| --- | --- |
| `none` | No header validation. |
| `header-signature` | Header-local KES and op-cert signatures only. No counter state. |
| `praos-lite` | Header-local signatures plus VRF proof. No counter state. |
| `praos-ledger` + `opcert-counter-mode=none` | Ledger-view checks run, but the op-cert counter stage is disabled. This is the packaged default while counter-state rollout remains new. |
| `praos-ledger` + `opcert-counter-mode=compat` | Soft counter check: skip when state is absent, reject when present and inconsistent. |
| `praos-ledger` + `opcert-counter-mode=strict` | Haskell-style counter check: registered issuer without state is initialized to `0`; inconsistent counters reject. |

## Implementation Plan

### Phase 0: Transitional Soft Check

- Change `opcert-state` so missing state accepts the stage.
- When state is present, enforce `stored <= headerCounter <= stored + 1`.
- Add regression tests for missing state, too-small counter, and
  over-incremented counter.

Status: implemented on 2026-07-01.

### Phase 1: Store Skeleton

- Add `OpCertCounterProvider` and `OpCertCounterState`.
- Add RocksDB-backed read support inside account state.
- Add serialization with versioned values and per-block delta records.

Status: implemented on 2026-07-01. The public read provider, versioned CBOR
value, and RocksDB-backed read skeleton are in place. A separate write-side
`OpCertCounterStore` SPI was removed to keep one rollback authority:
`DefaultAccountStateStore`. Canonical apply mutations are handled in Phase 2.

### Phase 2: Canonical Apply Integration

- Update the counter store only from the canonical apply path.
- Keep observer/candidate header validation read-only.
- Add generation/fork-switch tests.

Status: implemented on 2026-07-01. `DefaultAccountStateStore.applyBlock`
updates op-cert counters only from canonical `BlockAppliedEvent`, and runtime
header validation reads explicit counter evidence through
`LedgerStateHeaderValidationLedgerViewProvider`.

### Phase 3: Rollback And Restart Tests

- Test simple rollback, deep rollback within retention, fork switch, restart,
  and replay from existing chain state.
- Verify deltas are pruned with the same retention policy as related
  chain-dependent state.

Status: implemented on 2026-07-01 for account-state rollback, restart, and
reconcile replay. Op-cert counters use normal per-block account-state deltas,
so they follow the same delta pruning policy as the rest of account state.

### Phase 4: Strict Mode

- Wire `LedgerStateHeaderValidationLedgerViewProvider` to the counter store.
- Add a strict mode that treats registered issuer with no explicit state as
  current counter `0`.
- Keep a compatibility mode for old databases and trusted indexing.

Status: implemented on 2026-07-01. `yano.upstream.validation.opcert-counter-mode`
supports `none` (default), `compat`, and `strict`. `none` disables the counter
stage, `compat` checks stored evidence when present and skips absent evidence,
and `strict` synthesizes counter `0` only for issuers registered in the ledger
view.

### Phase 5: Backfill And Migration

- Define how a node starting from a checkpoint initializes counter state.
- Do not provide automatic migration/backfill for existing chain-state databases
  in the preview implementation.
- Keep the default `opcert-counter-mode=none`.

Status: removed/deferred on 2026-07-01. Strict op-cert counter validation is
supported only for databases synced with counter tracking from the validation
start point. Existing and checkpointed databases should use `none` or `compat`.

## Consequences

The soft check keeps current public-network validation usable and avoids a
forced database migration before the state exists.

The tradeoff is that `praos-ledger` remains incomplete when op-cert counter
state is absent. Operators needing full Haskell-style validation must wait for
the rollback-safe counter store or provide a custom validation plugin with its
own counter evidence.

## Acceptance Criteria

- Missing op-cert counter state does not fail current `praos-ledger` sync.
- Present counter evidence rejects too-small and over-incremented counters.
- The future store is rollback-safe and restart-safe.
- Strict mode matches Haskell's `stored <= headerCounter <= stored + 1` rule.
- Only canonical headers update the counter store.
