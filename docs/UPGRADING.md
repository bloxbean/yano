# Upgrading preview releases

## ADR-058 AdaPot deposits

The AdaPot `deposits` field (`/api/v1/epochs/{n}/adapot`, `/latest/adapot`, `/adapots`)
now holds the ledger's deposit pot (Haskell `utxosDeposited`): stake-key, pool, DRep and
governance-proposal deposits. Before, it counted only stake-key and DRep deposits. Four
new fields split it by category: `deposits_key`, `deposits_pool`, `deposits_drep` and
`deposits_proposal`. `deposits_key + deposits_pool` equals db-sync's and Koios's
`deposits_stake`. Treasury, reserves, fees and rewards do not change.

No migration runs. On an existing chainstate, AdaPots written before the upgrade keep the
old value and have `null` categories; every epoch boundary after the upgrade writes the new
value. For a consistent history, sync from genesis. The archive's `ada_pots.deposits` column
follows the same rule. `GET /api/v1/api/debug/deposits` audits the deposit state at the tip.
While an epoch boundary is being processed, `/epochs/{n}/adapot` for the new epoch briefly
shows the previous epoch's `deposits` with `null` categories; the categories appear when the
boundary completes.

On a devnet with Shelley-genesis pools, the genesis pool deposits are no longer counted in
the internal stake-key and DRep deposit total. Recreate such devnets.

## ADR-050 pool-lifecycle correctness

ADR-050 adds ordered same-block pool lifecycle handling and complete live-state
POOLREAP. It also adds the `pool-lifecycle-state-v1` readiness marker. Every
chainstate created before this change is intentionally incompatible, including
the retained mainnet tip and the e447/e505/e610/e628 checkpoint ladder.

Keep or archive an old chainstate if it is still useful for comparison, then
sync mainnet from a clean directory and rebuild the checkpoint ladder from that
accepted replay. Startup rejects a populated pre-marker store without changing
it and reports that a resync is required. No boundary-v2, rollback-v2 or
automatic promotion path is provided.

See [Account state and rollback](ACCOUNT_STATE_AND_ROLLBACK.md) for the boundary
semantics, compatibility contract and one-shot manual rollback guidance.

## ADR-047 history cleanup

The legacy replay-worker history implementation and its public Java write-session API have
been removed. Projection history is now the only archive writer; `ArchiveBackend` is a
generation-pinned read facade.

Before upgrading, remove every explicit `yano.history.enabled` property, including
`yano.history.enabled=false`. To collect history, configure
`yano.history.projection.enabled=true` and select projection sections or epoch artifacts as
needed. Also remove `yano.account-history.enabled`, `yano.history.live-enabled`, and
legacy `yano.history.worker.*`, `yano.history.hot-store.*`, `yano.history.datasets.*`,
`yano.history.start-mode`, `yano.history.maintenance.*`, and `yano.history.archive.sqlite.*` properties. Startup rejects
these keys rather than silently ignoring them, and readiness reports `DOWN` while the
configuration error is present.

The removed Java surface includes the replay hot-store/progress/resolver types,
`ArchiveWriteSession`, `ArchiveReceipt`, `ArchiveRetentionCutoff`, and
`DuckLakeWriteSession`. Integrators should use `ProjectionSink` for archive writes and the
read-only repositories exposed by `ArchiveBackend` for queries.

`GET /history/watermark` no longer consults legacy `archive_coverage` metadata. It reports the
projection consistency point or an unavailable response. Existing replay metadata tables are
left inert and are not dropped automatically; current projection archives do not require a
rebuild.
