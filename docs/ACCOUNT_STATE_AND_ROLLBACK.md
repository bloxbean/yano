# Account state and rollback

Yano's RocksDB account store is the authoritative implementation for stake
accounts, stake-pool and DRep delegations, pool lifecycle state, rewards and
epoch snapshots. The in-memory implementation exists only for deterministic
unit tests; startup never falls back to it when a persistent store is
incompatible or cannot be opened.

## Pool retirement at an epoch boundary

The boundary order is rewards, SNAP, POOLREAP and governance. SNAP therefore
captures the end-of-previous-epoch delegation state before an effective pool
retirement changes live state.

POOLREAP uses the exact deposit stored when the pool's current lifecycle was
created. It credits a registered reward account once, or accounts for an
unclaimed refund in the monetary result, and then removes the live pool,
retirement, lifecycle-slot and stake-pool-delegation rows. Stake accounts,
DRep delegations, pool-parameter history and certificate history remain.

Large delegation scans and writes are split into bounded rollback-v1 chunks. A
durable progress marker prevents a partial transition from being reported as a
complete boundary or a ready chain tip. Startup finishes a valid interrupted
transition before UTXO/account reconciliation and sync.

## Chainstate compatibility

An empty account store atomically records the current epoch-boundary indexes
and the `pool-lifecycle-state-v1` readiness marker. A populated store without
that marker, or with an unknown marker value, is rejected without modification
using `IncompatibleChainStateException`.

There is intentionally no automatic repair or v2 promotion. Incorrect
same-block certificate ordering in an older preview chainstate cannot be
reconstructed exactly from its live rows. Keep the old directory as a backup
if needed and sync into a new chainstate directory.

## Marker inventory

These are the format/readiness guards and transient recovery cursors an
operator may encounter for account-state lifecycle and its required UTXO
index. A permanent marker says that stored data has a known semantic contract;
a transient cursor says that a journaled operation must be resumed.

| Key | Kind | Written when | If absent, malformed or incompatible |
| --- | --- | --- | --- |
| `meta.epoch_boundary_state_version` (with `meta.snapshot_dereg_index_version` and `meta.reward_event_index_version`) | Format version and permanent index-readiness guards | Atomically when an empty account store is initialized | A populated pre-marker, missing subordinate marker or unknown version is rejected without writes; retain a backup and resync. |
| `meta.pool_lifecycle_state_version` (`pool-lifecycle-state-v1`) | Permanent semantic-readiness guard | In the same empty-store initialization batch as the epoch-boundary markers | Missing on a populated store or any value other than v1 is rejected without writes; retain a backup and resync. |
| `meta.utxo_pointer.ready.v1` | Permanent, coordinate-pinned UTXO pointer-index completeness marker | Updated atomically with pointer-index changes after the full index is available | When the pointer index is applicable, a missing, malformed, stale or wrong-coordinate marker makes it not ready and startup fails closed with resync guidance. |
| `meta.genesis_staking_bootstrap` | Permanent idempotence and genesis-identity guard | Atomically with Shelley genesis pools, delegations, deposits and initial derived facts | Absence permits the one-time bootstrap. A marker for a different genesis identity fails closed; verify genesis configuration rather than overwriting it. |
| `meta.rollback.v1.target-slot` | Transient crash-recovery cursor | Before the first bounded account rollback chunk; removed after all phases are reversed | Absence is normal. Presence makes startup resume rollback-v1. A malformed value fails startup and requires restoring a sound checkpoint or resyncing. |
| `meta.pool.reap.progress.v1` | Transient epoch-boundary crash cursor | Before the first POOLREAP chunk, advanced with each chunk and deleted by the final pool/refund commit | Absence is normal. Presence keeps the boundary and application unready; startup resumes a matching boundary or fails closed if its epoch, slot or step is inconsistent. |

## Manual debugging rollback

A one-shot startup rollback can be requested with exactly one command-line
system property:

```text
-Dyano.debug.rollback-to-slot=<slot>
-Dyano.debug.rollback-to-epoch=<epoch>
```

Do not put these properties in `application.yml`; remove them from the next
start so the rollback is not requested again.

The runtime resolves a canonical stored block at or before the target and
rolls back account state, UTXO state and chain state in dependency-safe order.
For a rollback across an epoch boundary, account rollback includes every
POOLREAP chunk: live pool lifecycle rows and pool delegations are restored, and
the exact deposit refund and accumulated reward state are reversed. Replay of
the boundary then applies the same retirement and refund once.

The target must be at or above the common retained rollback floor. If required
reward inputs or journals have been pruned, Yano fails closed; restore a suitable
checkpoint or resync with a larger retention setting instead of forcing the
rollback.

POOLREAP uses the existing rollback-v1 boundary journal and progress marker.
There is no rollback-v2 format.
