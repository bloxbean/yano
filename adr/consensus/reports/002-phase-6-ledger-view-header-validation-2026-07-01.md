# ADR-CONSENSUS-002 Phase 6 Report: Ledger-View Header Validation

## Scope

Implemented the ledger-view pieces that can be made correct with the current
state model:

- Persist pool VRF key hash in pool registration state/history.
- Expose VRF key hash through `LedgerStateProvider.PoolParams`.
- Add `HeaderValidationLedgerViewProvider` as the validation SPI for ledger
  evidence.
- Add runtime provider backed by `LedgerStateProvider`, `AccountStateReadStore`,
  and `EpochParamProvider`.
- Add `praos-ledger` profile with `leader-threshold`, `opcert-state`, and
  `protocol-view` stages.

## Behavior

`leader-threshold` now validates:

- the header issuer cold key maps to the pool hash;
- the header VRF key hash matches the registered pool VRF key hash;
- active pool stake and total active stake are available;
- the VRF leader value is below the Cardano leader threshold for sigma and
  active slot coefficient.

`protocol-view` validates:

- header protocol major/minor against effective epoch protocol params;
- header CBOR size against `maxBlockHeaderSize` when available.

`opcert-state` is present as a transitional soft check. Missing operational
certificate counter state is accepted for backward compatibility with existing
chain-state databases. If counter evidence is present for a pool, the stage
enforces the Praos counter rule `stored <= headerCounter <= stored + 1`.

## Verification

Passed:

```bash
./gradlew :core-api:compileJava :ledger-state:compileJava :runtime:compileJava :runtime:compileTestJava :ledger-state:compileTestJava
./gradlew :runtime:test --tests com.bloxbean.cardano.yano.runtime.sync.validation.ShelleyHeaderValidatorTest --tests com.bloxbean.cardano.yano.runtime.config.UpstreamConfigTest :ledger-state:test --tests com.bloxbean.cardano.yano.ledgerstate.DefaultAccountStateStoreGenesisBootstrapTest --tests com.bloxbean.cardano.yano.ledgerstate.DefaultAccountStateStorePoolHistoryTest
```

## Remaining Work

- Persist op-cert counter/state rollback-safely as described in
  ADR-CONSENSUS-003.
- Move `opcert-state` from soft compatibility behavior to strict Haskell-style
  behavior after counter state can be initialized and rolled back safely.
- Run live sync with `praos-ledger` after ADR-CONSENSUS-003 strict counter state
  is available; until then `praos-lite` or soft `praos-ledger` remains the
  practical public-sync profile.
