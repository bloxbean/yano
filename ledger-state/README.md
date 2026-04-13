# ledger-state — Cardano Ledger State Implementation

This module implements Cardano ledger state tracking for the Yano, covering:
- Stake registration/deregistration
- Pool and DRep delegation
- Epoch delegation snapshots (mark snapshot)
- Reward calculation (via [cf-java-rewards-calculation](https://github.com/cardano-foundation/cf-java-rewards-calculation))
- AdaPot (treasury/reserves) tracking
- Protocol parameter tracking
- Conway governance (ratification, enactment, DRep distribution, DRep expiry)

## Documentation

- [Design Document](docs/design.md) — Architecture, epoch boundary flow, key/value formats, rollback, TODOs
- [DRep Distribution](docs/drep-distribution.md) — Distribution calculation, PV9 bug-compat, PV10 hardfork rebuild
- [DRep Expiry](docs/drep-expiry.md) — Expiry tracking across Haskell, Amaru, Yano, Yaci Store

## Epoch Boundary Processing (EPOCH Rule)

At each epoch boundary (first block of a new epoch), three events fire in sequence:

```
PreEpochTransitionEvent   →  Params, rewards, SNAP (delegation snapshot), POOLREAP, governance
EpochTransitionEvent      →  Prune old snapshots + stake events
PostEpochTransitionEvent  →  Credit spendable reward_rest to accounts
```

All three events complete before `BlockAppliedEvent` for the first block of the new epoch.

### PreEpochTransition steps

1. **Finalize protocol parameters** for the new epoch
2. **Credit MIR reward_rest** (pre-reward, so MIR amounts enter reward balances before snapshot)
3. **Calculate & distribute rewards** (using snapshot from N-4, fees from N-1)
4. **SNAP**: Create delegation snapshot (UTXO balance + rewards + reward_rest)
5. **POOLREAP**: Pool deposit refunds for retiring pools
6. **PV10 hardfork**: Rebuild DRep delegation reverse index (one-time, marker-gated)
7. **Conway governance**: Ratification, enactment, DRep distribution, deposit refunds, DRep expiry

### Why this ordering?

- Rewards before SNAP: distributed rewards included in snapshot balances
- SNAP before POOLREAP: pool refunds don't inflate active stake
- POOLREAP before governance: refunds credited before DRep distribution
- PV10 rebuild before governance: clean reverse index for DRep deregistration cleanup

## Snapshot Epoch Convention

Snapshot epoch labels match [yaci-store](https://github.com/bloxbean/yaci-store) `epoch_stake`:

| Convention | Meaning of `snapshot[E]` |
|-----------|--------------------------|
| **yaci (this module)** | Delegation/stake state at the **END** of epoch E |
| **yaci-store `epoch_stake`** | Same — state at the **END** of epoch E |

For reward epoch N, the snapshot lookup is:
```
reward epoch N → stakeEpoch = N-2 → snapshotKey = N-4
```

## Stake Deregistration Behavior

Stake deregistration **completely removes** the account entry including pool and DRep delegations — matching Haskell's `Map.extract` on `dsAccounts`.

Re-registration creates a fresh entry with no delegations.

| Action | Account entry | Pool delegation | DRep delegation |
|--------|--------------|-----------------|-----------------|
| **Stake deregistration** | Removed entirely | Discarded | Discarded |
| **Pool retirement (POOLREAP)** | Preserved | Set to Nothing | Preserved |

## Storage

- **RocksDB** (`DefaultAccountStateStore`): Production persistence with column families
- **In-memory** (`InMemoryAccountStateStore`): Testing and development

### Key prefixes (RocksDB `acct_state`)

| Prefix | Byte | Data |
|--------|------|------|
| `PREFIX_ACCT` | `0x01` | Stake account (reward + deposit) |
| `PREFIX_POOL_DELEG` | `0x02` | Pool delegation |
| `PREFIX_DREP_DELEG` | `0x03` | DRep delegation |
| `PREFIX_DREP_DELEG_REVERSE` | `0x04` | DRep reverse index (PV9 stale, rebuilt at PV10) |
| `PREFIX_POOL_DEPOSIT` | `0x10` | Pool registration params |
| `PREFIX_POOL_RETIRE` | `0x11` | Pool retirement |
| `PREFIX_ACCT_REG_SLOT` | `0x14` | Latest registration slot |
| `PREFIX_DREP_REG` | `0x20` | DRep registration deposit |
| `PREFIX_POOL_BLOCK_COUNT` | `0x50` | Pool block count (epoch-scoped) |
| `PREFIX_EPOCH_FEES` | `0x51` | Epoch fees |
| `PREFIX_ADAPOT` | `0x52` | AdaPot (treasury/reserves) |
| `PREFIX_STAKE_EVENT` | `0x55` | Registration/deregistration events |
| `PREFIX_REWARD_REST` | `0x56` | Deferred rewards (MIR, refunds, withdrawals) |
| `0x60-0x6D` | | Governance state (proposals, votes, DRep state, committee, etc.) |

See [design.md](docs/design.md) for full key/value format reference.

## Reward Calculation

Uses [cf-java-rewards-calculation](https://github.com/cardano-foundation/cf-java-rewards-calculation),
the same library as yaci-store. Identical inputs produce identical outputs — any treasury/reserves mismatch implies an input difference.

### Input mapping (reward epoch N)

| Input | Source | Epoch ref |
|-------|--------|-----------|
| Treasury/reserves | `AdaPotTracker.getAdaPot(N-1)` | N-1 |
| Protocol params | `EpochParamProvider.get*(N-2)` | N-2 |
| Block counts | `getPoolBlockCounts(N-2)` | N-2 |
| Epoch fees | `getEpochFees(N-2)` | N-2 |
| Active stake | Sum of `snapshot[N-2]` amounts | N-2 |
| Pool states | `snapshot[N-2]` + pool params | N-2 |
| Retired pools | `PREFIX_POOL_RETIRE` where epoch=N | N |
| Deregistered | Stake events in fee epoch slot range | N-1 |
