# ADR-NET-002: Runtime Peer Management Package and Later Network Module

## Status

Draft

## Date

2026-05-18

## Context

Yano currently wires upstream Cardano node connectivity directly inside
`Yano`. It creates a single Yaci `PeerClient`, then passes that instance to
`HeaderSyncManager` and `BodyFetchManager`. Those managers hold the `PeerClient`
as a constructor dependency, so a stale or broken upstream client cannot be
replaced cleanly without rebuilding the surrounding runtime objects.

ADR-NET-001 describes the immediate reconnect recovery problem. The final
direction is broader:

- support one configured upstream exactly as today,
- support multiple configured upstreams as failover peers,
- eventually support multiple active peers with chain selection and better peer
  connection management.

The first instinct was to create a separate Gradle `network` module immediately.
That is premature. The first useful abstraction must own a runtime-specific
lifecycle: `PeerClient`, `PipelineDataListener`, `HeaderSyncManager`,
`BodyFetchManager`, TxSubmission setup, rollback coordination, and progress
signals. Those classes currently live in `runtime` and are tightly coupled to
runtime chain state, event publishing, and rollback handling.

The immediate product goal is not module purity. Yano should self-recover from
upstream connection problems, like Yaci Store does operationally, and remain
useful during long-running mainnet sync. The package boundary exists to make
that recovery implementation clean and evolvable.

Creating a separate Gradle module now would either create an invalid dependency
direction or expose the raw `PeerClient` back to `Yano`, leaving the lifecycle
problem unsolved.

## Decision

Do not create a new Gradle module in Phase 1.

Create a focused package inside the existing `runtime` module:

- `com.bloxbean.cardano.yano.runtime.peer`

This package will own the active upstream peer session and the recovery
supervisor. It will keep the implementation single-active-peer first, while
using per-peer state so that failover and later multi-peer work do not require a
full redesign.

The ADR remains under `adr/network` because it describes network lifecycle and
peer-management architecture, even though the first code lands inside
`runtime`.

Promotion to a Gradle module is deferred until the boundary is proven, most
likely after failover support or when a second consumer needs the code.

## Goals

- Extract active peer lifecycle out of the `Yano` god object.
- Make stale-peer rebuild explicit and testable.
- Keep connection and progress health state per peer from the beginning.
- Preserve today's single-upstream behavior when only one host is configured.
- Avoid exposing raw `PeerClient` as the main runtime integration point.
- Leave a clear path to failover peers and later chain selection.

## Non-Goals

- Create a Gradle `network` module in Phase 1.
- Implement chain selection now.
- Run multiple active header-sync peers now.
- Replace Yaci's `PeerClient` or mini-protocol implementations.
- Move generic networking code out of Yaci.
- Fix Yaci library reconnect semantics in this package. Yaci hardening remains
  a later library task described in ADR-NET-001.

## Phase 1 Boundary

### `runtime.peer` Owns

- Active `PeerClient` creation, start, stop, and replacement.
- `PipelineDataListener` creation for the active peer.
- `HeaderSyncManager` and `BodyFetchManager` lifecycle for the active peer.
- TxSubmission protocol initialization for the active peer.
- Peer health timestamps and counters.
- Recovery cooldown, jitter, max-attempt policy, and terminal failure state.
- Disconnect, failed-write, stale-progress, and body-fetch timeout signals.
- Peer status snapshots for runtime status endpoints.

### `Yano` / Runtime Owns

- Chain state and persisted cursor selection.
- Genesis, era, and protocol configuration.
- Event bus ownership.
- Ledger/account/nonce state.
- Rollback execution and data-store consistency.
- REST API and transaction validation.
- Future chain-selection decisions.

`runtime.peer` may call runtime services and managers because it lives in the
same Gradle module. It should still keep a narrow API so the code can be moved
later if the boundary becomes clean.

## Phase 1 Abstractions

Only the abstractions needed for the immediate recovery fix should land first:

- `PeerSession`
  - Owns one active `PeerClient`, `PipelineDataListener`,
    `HeaderSyncManager`, and `BodyFetchManager`.
  - Provides `start(...)`, `stop(...)`, `restart(...)`, `submitTx(...)`, and
    status methods.
  - Recreates the whole active-peer pipeline atomically during recovery.
- `PeerSessionSupervisor`
  - Scheduled watchdog for one `PeerSession`.
  - Uses single-flight recovery, cooldown with jitter, and max-attempt
    terminal behavior.
- `PeerHealth`
  - Mutable state object updated by header, body, keepalive, disconnect, and
    failed-write signals.
  - Tracks app progress separately from connection liveness.
- `SyncStartPointProvider`
  - Runtime callback that returns candidate intersection points, not a single
    point.
  - The candidate list should include `header_tip` when safe, `tip`, and older
    fallback points spanning the security window.

Do not add `PeerPool`, `PeerId`, `ActivePeerHandle`, or
`PeerSessionFactory` in Phase 1 unless implementation pressure proves they are
needed. A pool of one and a factory with one implementation do not yet carry
their weight.

## Runtime Flow

1. `Yano` constructs a `PeerSession` with runtime dependencies and callbacks.
2. `PeerSession` asks `SyncStartPointProvider` for candidate intersection
   points.
3. `PeerSession` creates a fresh `PeerClient`.
4. `PeerSession` creates fresh `HeaderSyncManager`, `BodyFetchManager`, and
   `PipelineDataListener`.
5. `PeerSession` connects the peer, enables TxSubmission, starts header sync,
   and starts body fetch monitoring.
6. Header/body callbacks update `PeerHealth`.
7. The supervisor periodically evaluates `PeerHealth`.
8. If recovery is needed, the supervisor waits for rollback quiescence, stops
   the current session, creates a fresh session from the latest candidate
   points, and resumes.
9. `Yano.submitTransaction(...)` forwards through `PeerSession.submitTx(...)`,
   not directly through a raw `PeerClient`.

With one configured upstream, this is equivalent to rebuilding the same peer.
With later failover peers, the same lifecycle can be applied to a different
configured peer.

## Operational Rules

- Recovery must not tear down managers while rollback is in progress.
- Active peer replacement must recreate `PipelineDataListener`,
  `HeaderSyncManager`, `BodyFetchManager`, TxSubmission initialization, and
  header sync start as one lifecycle.
- `PeerClient.isRunning()` must not be trusted as connection health.
- Keepalive response time is an input to health evaluation, but application
  progress remains the primary signal.
- `header_tip` must not be used blindly if it is far ahead of `tip`.
- If intersection fails at `header_tip`, retry with fallback points before
  declaring peer failure.
- Body-fetch progress should be measured by last block received, not only by
  batch start time.
- After max recovery attempts, the node must enter an operator-visible terminal
  state; logging alone is insufficient.

## Phase 2: Failover Peers

After the single-active recovery path is stable, add failover support.

Potential Phase 2 abstractions:

- `UpstreamPeerConfig`
  - host, port, protocol magic, optional name, priority, enabled flag.
- `PeerId`
  - stable identifier used in logs, status, and health maps.
- `PeerPool`
  - owns configured peers and chooses the active peer in failover mode.
- `PeerSelectionPolicy`
  - chooses the next peer by priority, failure count, and cooldown.

Rules:

- Only one peer is active for sync.
- Standby peers are cold by default; they are configured but not connected.
- When the active peer fails, the pool chooses the next eligible peer by
  priority and cooldown state.
- If all peers are cooling down, the pool retries the earliest eligible peer
  after backoff.
- With a single configured peer, failover degenerates to rebuilding the same
  peer.

Cold standby is preferred initially because it avoids unnecessary relay load and
does not require health semantics for idle open mini-protocol sessions. Warm
standby can be added later if failover latency becomes a real issue.

## Phase 3: Gradle Module Extraction

Re-evaluate a separate Gradle module only after the runtime boundary is proven.

Module extraction becomes reasonable when:

- the peer lifecycle API no longer directly depends on runtime-specific managers,
- failover or peer-pool logic has a second consumer,
- chain selection consumes peer status through a stable interface,
- generic code can be separated from runtime chain/event/rollback logic.

Possible future module name:

- `network`

Possible future base package:

- `com.bloxbean.cardano.yano.network`

Until then, use the package boundary inside `runtime`.

## Future Multi-Peer Evolution

Later chain selection will need a different shape from the Phase 1 single-active
callbacks.

Expected later additions:

- multiple connected peers,
- per-peer ChainSync header streams,
- peer tip comparison,
- peer scoring,
- selected-chain tracking,
- body fetch scheduling across healthy peers,
- peer promotion and demotion,
- DNS/IP rotation,
- inbound peer support if Yano moves toward a fuller relay role.

The Phase 1 single-active APIs are not frozen as multi-active APIs. In
particular, `SyncStartPointProvider`, active-peer replacement callbacks, and
status models may be redesigned when chain selection lands.

## Configuration

Keep current single-peer configuration backward compatible:

- `remoteCardanoHost`
- `remoteCardanoPort`
- protocol magic / network settings

Phase 1 recovery configuration:

- `clientSyncRecoveryEnabled`
  - default `true`.
- `clientSyncHealthCheckIntervalSeconds`
  - default `30`.
- `clientSyncNoProgressTimeoutSeconds`
  - default `180`.
- `clientSyncNearTipNoProgressTimeoutSeconds`
  - default `600`.
- `clientSyncNearTipSlotThreshold`
  - default `60`.
- `clientSyncKeepAliveTimeoutSeconds`
  - default `90`.
- `clientSyncRecoveryCooldownSeconds`
  - default `60`, plus 0-50% jitter.
- `clientSyncMaxRecoveryAttempts`
  - default `10`.
- `clientSyncNoBodyReceivedTimeoutSeconds`
  - default `60`.

Phase 2 failover configuration:

- `upstreamPeers`
  - optional list of host/port/name/priority entries.

Names should be aligned with existing Yano config style during implementation.

## Implementation Plan

### Phase 1: Extract Single-Active Peer Lifecycle

- Add `runtime.peer` package.
- Add `PeerSession`, `PeerSessionSupervisor`, `PeerHealth`, and
  `SyncStartPointProvider`.
- Move active `PeerClient` creation and teardown behind `PeerSession`.
- Move `PipelineDataListener`, `HeaderSyncManager`, `BodyFetchManager`, and
  TxSubmission setup into `PeerSession` lifecycle.
- Replace direct REST transaction forwarding through `peerClient` with
  `PeerSession.submitTx(...)`.
- Use multi-point intersection candidates from `SyncStartPointProvider`.
- Add terminal failure state and status reporting.
- Keep one configured upstream.

### Phase 2: Add Failover Peer List

- Add optional `upstreamPeers` config.
- Add a small `PeerPool` only when there is more than one configured peer.
- Select one active peer by priority.
- On failure, move the failed peer to cooldown and try the next peer.
- Preserve single-peer behavior when only one peer exists.

### Phase 3: Revisit Module Extraction

- Evaluate moving generic peer-pool/status/policy code to a `network` module.
- Keep runtime-specific manager creation in `runtime` unless a clean interface
  exists.

### Phase 4: Later Yaci Hardening

- Add real channel health in Yaci.
- Convert write failures into reconnect signals.
- Avoid blocking reconnect work on Netty event-loop threads.
- Feed improved Yaci health events into `PeerSession`.

## Test Plan

Phase 1 tests:

- Single configured peer starts and sync lifecycle is wired.
- Single configured peer failure rebuilds the same peer.
- Recovery is single-flight under concurrent health checks.
- Peer health is tracked per session, not globally.
- `SyncStartPointProvider` is called before each new peer start.
- Active peer replacement recreates `PipelineDataListener`,
  `HeaderSyncManager`, and `BodyFetchManager`.
- TxSubmission is re-enabled after session replacement.
- `submitTransaction(...)` uses the current `PeerSession`.
- Body-fetch in-progress state from the old session does not block the new
  session.
- Disconnect alone does not cause immediate replacement if progress resumes.
- Stale active peer triggers replacement and resumes from persisted chain state.
- Rollback in progress blocks teardown until rollback completes.
- Intersection rejected by the new session triggers fallback candidate points.
- Max attempts enter terminal state and expose health/status failure.

Phase 2 tests:

- Multiple configured peers fail over to the next eligible peer.
- Failed peer enters cooldown and is not immediately selected again.
- If all peers are cooling down, the pool retries after backoff.
- Peer health is tracked per configured peer.

Manual/long-running validation:

- Mainnet sync through one public relay overnight.
- Mainnet sync through two configured public relays with forced first-peer
  failure.
- TCP proxy test that drops the active connection and later restores it.

## Consequences

Positive:

- The immediate reconnect fix is aligned with the future multi-peer direction.
- Yano avoids adding more lifecycle fields directly to `Yano`.
- The first implementation can own runtime-specific objects without bad module
  dependencies.
- Failover peers can be added without changing header/body pipeline semantics.
- Future chain selection gets per-peer state as a foundation.

Tradeoffs:

- Phase 1 is not a reusable network module yet.
- Runtime still owns the first implementation.
- The first implementation has more structure than a minimal single-peer
  watchdog.
- Full chain selection remains separate work.

## Resolved Questions

- Phase 1 package: `com.bloxbean.cardano.yano.runtime.peer`.
- No Gradle module in Phase 1.
- `SyncStartPointProvider` returns candidate points, not one point.
- Active peer replacement is synchronous from Yano's perspective in Phase 1.
- Standby peers remain cold in Phase 2 unless measured failover latency requires
  warm standby.

## Open Questions

- Should terminal failure only fail readiness, or optionally call
  `System.exit(1)` under an operator-controlled flag?
- Should future status APIs expose only the active peer in Phase 1, or include a
  shape that already supports configured failover peers?
- What is the best source for fallback points older than `tip` when only sparse
  point lookup APIs are available?
