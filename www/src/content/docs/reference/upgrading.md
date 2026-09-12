---
title: "Release migration details"
description: "Storage and behavior changes across preview versions."
sidebar:
  order: 10
---

## Pool lifecycle correctness

This version adds ordered same-block pool lifecycle handling and complete live-state
POOLREAP. It also adds the `pool-lifecycle-state-v1` readiness marker. Every
chainstate created before this marker is intentionally incompatible.

Keep or archive an old chainstate if it is still useful for comparison, then
sync the selected network into a clean directory. Startup rejects a populated pre-marker store without changing
it and reports that a resync is required. No boundary-v2, rollback-v2 or
automatic promotion path is provided.

See [Account state and rollback](/reference/account-state/) for the boundary
semantics, compatibility contract and one-shot manual rollback guidance.

## History cleanup

The legacy replay-worker history implementation and its public Java write-session API have
been removed. Projection history is now the only archive writer; `ArchiveBackend` is a
generation-pinned read facade.

Before upgrading, remove every explicit `yano.history.enabled` property, including
`yano.history.enabled=false`. To collect history, configure
`yano.history.projection.enabled=true` and select projection sections or epoch artifacts as
needed. Also remove `yano.account-history.enabled` and legacy `yano.history.worker.*`,
`yano.history.hot-store.*`, `yano.history.datasets.*`, `yano.history.start-mode`,
`yano.history.maintenance.*`, and `yano.history.archive.sqlite.*` properties. Startup rejects
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
