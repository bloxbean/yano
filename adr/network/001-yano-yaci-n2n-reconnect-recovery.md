# ADR-NET-001: Peer Session Recovery for Resilient Upstream Sync

## Status

Draft

## Date

2026-05-18

## Primary Goal

Yano must automatically recover from upstream connection failures during
long-running sync, without requiring a process restart.

The immediate objective is production resilience comparable to Yaci Store:

- broken TCP connection,
- half-closed channel,
- failed write,
- inactive Netty channel,
- stuck body fetch,
- failed reconnect,
- stale `PeerClient` that still appears "running",

should all eventually result in Yano discarding the bad upstream session,
creating a fresh one, and resuming from persisted chain state.

Multi-peer failover, chain selection, and a future reusable network module are
important, but they are secondary to this first recovery goal.

## Context

During long-running mainnet sync from public Cardano relays, Yano can stop
making progress after several hours. Restarting the process recovers sync.

Observed failure patterns include:

- `ConnectTimeoutException` while reconnecting to a public relay address.
- `Broken pipe` and `StacklessClosedChannelException` while sending ChainSync
  protocol messages after a block-fetch response.
- Repeated `Cannot write message: channel is null or inactive` warnings after
  the connection is already unusable.

The broken-pipe stack is especially important. It shows block-fetch processing
on one Netty event-loop thread while ChainSync is already trying to find an
intersection on another connection thread. `N2NPeerFetcher.handleBlockFound`
confirms the fetched block and immediately asks `ChainsyncAgent` to send the
next message, but the underlying channel is already closed or half-closed.

Yaci Store recovers from similar production conditions because it has
application-level restart supervision around its sync flow. Yano needs the same
kind of outer supervision, adapted to its pipelined header/body architecture.

## Current Findings

### Yano

Yano creates one `PeerClient` for upstream sync and keeps using it:

- `Yano.startClientSync()` creates `peerClient` only if it is `null`.
- `HeaderSyncManager` and `BodyFetchManager` keep that `PeerClient` as a final
  constructor dependency.
- `PipelineDataListener.onDisconnect()` only notifies the managers.
- `HeaderSyncManager.onDisconnect()` assumes ChainSync auto-reconnect will
  resume from the last confirmed point.
- `BodyFetchManager.onDisconnect()` resets the in-progress batch flag, but does
  not pause or trigger a client rebuild.
- REST transaction submission forwards through the current `peerClient`, so
  TxSubmission lifecycle must also follow peer replacement.

This means Yano has no outer supervisor that can discard a stale `PeerClient`,
recreate the managers, re-enable TxSubmission, and resume from persisted chain
state.

### Yaci

The Yaci branch currently used by Yano still has reconnect semantics that are
not strong enough for this failure mode:

- `NodeClient.isRunning()` returns `session != null`, not whether the channel is
  active.
- `Session.start()` performs blocking `connect(...).sync()` and sleeps inside
  the reconnect loop.
- `NodeClient.SessionListenerAdapter.disconnected()` calls `start()` from the
  disconnect callback path.
- `Agent.writeMessage()` logs inactive-channel and failed-write cases, but does
  not notify `NodeClient` or force the channel/session into reconnect.
- `Agent.writeSingleSegment()` and segmented writes can observe `Broken pipe` or
  `ClosedChannelException` asynchronously through the Netty write future; today
  those failures are only logged.

The net effect is that callers can see `isRunning() == true` while the channel
is not usable. Yano then continues scheduling header/body work against a stale
client.

### Yaci Store

Yaci Store uses `BlockSync` / `BlockRangeSync` and has a restart layer:

- `BlockFetchService` owns sync start/stop and tracks last received block time.
- `RequiredRestartProcessor` handles restart events with debounce, backoff, and
  max-attempt guardrails.
- `AutoRecoveryStartService` can publish restart events when health checks fail.
- Restart creates fresh fetcher/client instances and resumes from persisted
  cursor state.

Yano should follow the same operational pattern: application-level progress
supervision and full sync-session replacement.

## Decision

Implement Yano-side peer-session recovery first.

Phase 1 will add a focused package inside the existing `runtime` module:

- `com.bloxbean.cardano.yano.runtime.peer`

The first implementation remains single-active-peer. With one configured
upstream, recovery means rebuilding the same peer. Later, the same lifecycle can
be extended to fail over to another configured peer.

Do not create a separate Gradle `network` module in Phase 1. The first useful
recovery object must own runtime-specific classes such as
`HeaderSyncManager`, `BodyFetchManager`, `PipelineDataListener`, event
publishing, TxSubmission setup, and rollback coordination. ADR-NET-002 records
the package-first approach and later module extraction path.

Defer Yaci library changes to a later hardening task. Yaci should still be fixed
so downstream applications get better connection semantics, but Yano should not
depend on perfect library reconnect behavior to be useful in production.

## Options Considered

### Option 1: Wait for Yaci reconnect fixes only

Rejected as the immediate fix.

This is the right long-term direction, but Yano would remain vulnerable until it
updates and validates a new Yaci version. It also does not give Yano
application-level health checks, progress detection, or restart policy.

### Option 2: Replace Yano's pipeline with Yaci Store style `BlockSync`

Rejected.

Yano's current design separates header sync from body fetch and uses RocksDB
`header_tip` and `tip` for pipelined catch-up. Replacing that path would be a
large regression in architecture and performance. Yaci Store's reliability comes
mostly from restart supervision, not from the older fetcher shape itself.

### Option 3: Add single-peer recovery directly inside `Yano`

Rejected.

This would be the smallest patch, but `Yano` is already too large and owns too
many lifecycle fields. Adding more peer health, timers, and recovery state there
would make the later failover path harder.

### Option 4: Add `runtime.peer` session recovery now, Yaci hardening later

Accepted.

This gives Yano immediate operational recovery while keeping the first
implementation close to the runtime objects it must rebuild.

## Yano Fix Plan

### 1. Add `PeerSession`

`PeerSession` owns the whole active upstream sync lifecycle:

- `PeerClient`,
- `PipelineDataListener`,
- `HeaderSyncManager`,
- `BodyFetchManager`,
- TxSubmission initialization,
- start/stop/restart lifecycle,
- current peer status,
- `submitTx(...)` forwarding.

Recovery must recreate all of these together. Recreating only `PeerClient` is
not sufficient because the managers hold the old `PeerClient` as final
constructor state.

`Yano.submitTransaction(...)` should forward through `PeerSession.submitTx(...)`
instead of directly accessing a raw `PeerClient`.

### 2. Add `PeerHealth`

Track per-session progress timestamps and counters:

- last header received time,
- last body/block received time,
- last body/block applied time,
- last successful keepalive response time,
- last header tip slot/block number,
- last body tip slot/block number,
- last disconnect time,
- last failed write time if detectable,
- current body-fetch in-progress state,
- consecutive recovery attempts,
- terminal failure state.

The preferred source of progress is actual header/body movement, not
`PeerClient.isRunning()`.

Keepalive must be an input to health evaluation. However, it should not be the
only gate. A connection can still respond to keepalive while the application
pipeline is stuck. Recovery should consider both connection liveness and
application progress.

### 3. Add `PeerSessionSupervisor`

Start a scheduled supervisor when client sync starts and stop it when Yano
stops.

The supervisor should periodically evaluate:

- Yano is running and sync is expected to be active.
- The node is not intentionally stopped.
- Rollback is not in progress.
- Header/body progress has not advanced for the configured timeout.
- Keepalive has not advanced for the configured timeout, or app progress is
  stale while there is a body/header gap that should be closing.
- Body fetch has not received blocks within the configured body-progress
  timeout.
- A disconnect or failed-write signal occurred and progress did not resume.

Suggested initial defaults:

- check interval: 30 seconds,
- no-progress timeout during bulk sync: 180 seconds,
- no-progress timeout near tip: 600 seconds,
- near-tip slot threshold: 60 slots,
- keepalive timeout: 90 seconds,
- no-body-received timeout: 60 seconds,
- minimum time between recoveries: 60 seconds plus 0-50% jitter,
- maximum consecutive recoveries before terminal failure: configurable, default
  10.

Near-tip must be a live predicate, recomputed from current remote tip and body
tip. Do not rely only on `isInitialSyncComplete`, which is a one-way phase flag.

### 4. Rebuild the Stale Session

When recovery is needed:

1. Use a single-flight guard so only one recovery runs.
2. Wait for rollback quiescence.
3. Stop `BodyFetchManager` if running.
4. Best-effort stop the old `PeerClient` even if `isRunning()` is misleading.
5. Drop the old `PipelineDataListener`, `HeaderSyncManager`, and
   `BodyFetchManager`.
6. Clear stale remote-tip/session state.
7. Ask `SyncStartPointProvider` for fresh candidate intersection points.
8. Create a fresh `PeerClient`.
9. Recreate managers and listener.
10. Connect, enable TxSubmission, start header sync, and start body fetch
    monitoring.

This mirrors the manual process restart that already works, but does it within
the running process.

### 5. Use Multi-Point Intersection Candidates

Do not restart from a single point only.

`SyncStartPointProvider` should return a candidate list such as:

- safe `header_tip`, when it is not too far ahead of `tip`,
- `tip`,
- older points behind `tip`, for example approximately `tip - 10`,
  `tip - 100`, `tip - 1000`, and `tip - k`.

The exact lookup mechanism can use available chain-state indexes. The important
rule is that a new peer should get fallback points before Yano declares the peer
unusable.

If `header_tip.slot - tip.slot` is too large, default threshold `k = 2160`, do
not use `header_tip` as the primary recovery point. Prefer `tip` or a
multi-point list.

### 6. Treat Disconnect as a Signal

`PipelineDataListener.onDisconnect()` should remain lightweight, but it should
notify `PeerHealth`.

Do not immediately rebuild on every disconnect. Normal Yaci reconnects can work.
Use disconnect as a signal, and rebuild only when progress does not resume
within policy.

### 7. Improve Body Fetch Recovery

`BodyFetchManager.fetchBlockRange()` currently sets `batchInProgress = true`
before sending the request. If Yaci only logs an inactive-channel write and does
not throw, the batch can remain stuck.

Track body progress by last received body/block callback, not only by batch
start time:

- record when a batch starts,
- record each block/body received,
- if no body is received for the configured timeout while a batch is in
  progress, clear the stuck state and signal the supervisor,
- after repeated body-progress timeouts, rebuild the peer session.

### 8. Guard Rollback

Rollback mutates chain state and publishes rollback events to ledger/account
listeners. Recovery must not tear down managers in the middle of rollback.

Add a rollback-in-progress guard around `handleChainSyncRollback(...)` and make
the supervisor wait or skip recovery while rollback is active.

### 9. Define Terminal Failure

After `clientSyncMaxRecoveryAttempts`, logging is not enough.

Terminal failure should:

- publish a `SyncStuckEvent` or equivalent runtime event,
- expose failure in status,
- fail readiness/health if Yano has an active health endpoint,
- increment a `recoveryGivenUp` counter,
- optionally exit the process under an operator-controlled config flag.

The default should be operator-visible and restartable without silently leaving
Yano as a zombie.

### 10. Add Observability

Add logs and status fields for:

- peer-session recovery enabled/disabled,
- current peer host/port,
- last header/body progress age,
- last keepalive response age,
- last disconnect time,
- body-fetch in-progress age,
- recovery attempt count,
- last recovery reason,
- terminal failure reason,
- old and new start candidate points,
- selected recovery point.

The status API should make it obvious when Yano is "running but stale".

### 11. Configuration

Expose runtime options or config fields:

- `clientSyncRecoveryEnabled` default `true`,
- `clientSyncHealthCheckIntervalSeconds` default `30`,
- `clientSyncNoProgressTimeoutSeconds` default `180`,
- `clientSyncNearTipNoProgressTimeoutSeconds` default `600`,
- `clientSyncNearTipSlotThreshold` default `60`,
- `clientSyncKeepAliveTimeoutSeconds` default `90`,
- `clientSyncRecoveryCooldownSeconds` default `60`,
- `clientSyncMaxRecoveryAttempts` default `10`,
- `clientSyncNoBodyReceivedTimeoutSeconds` default `60`,
- `clientSyncExitOnTerminalFailure` default `false`.

Names can be adjusted to match Yano's existing config style.

## Yano Test Plan

Add focused tests before or with implementation:

- Supervisor does not restart while header or body progress advances.
- Supervisor restarts when no progress exceeds timeout.
- Disconnect alone does not restart immediately.
- Disconnect followed by no progress triggers recovery.
- Failed write / inactive channel signal followed by no progress triggers
  recovery.
- Body fetch no-received timeout clears stuck in-progress state and can trigger
  recovery after repeated failures.
- Recovery is single-flight when multiple health checks fire concurrently.
- Recovery waits while rollback is in progress.
- Recovery recreates `PeerClient`, `PipelineDataListener`, `HeaderSyncManager`,
  `BodyFetchManager`, and TxSubmission setup.
- `submitTransaction(...)` uses the current `PeerSession` after recovery.
- `header_tip` far ahead of `tip` falls back to `tip`.
- Intersection rejection at the first candidate tries fallback candidates.
- Max attempts publish terminal failure and expose health/status failure.

Add one integration-style test with a TCP proxy:

- start sync through proxy,
- allow progress,
- stop proxy or force broken pipe,
- restore proxy,
- verify Yano recreates the session and resumes after proxy returns.

## Later Yaci Fix Plan

### 1. Expose Real Connection Health

Do not change `NodeClient.isRunning()` semantics in a breaking way.

Add a new method instead:

- `isConnected()` or `isChannelActive()` that checks active session channel,
- expose current remote address and reconnect state for diagnostics.

Yano should use real channel health when available, but it should still keep
application-level progress supervision.

### 2. Convert Write Failures Into Connection Failures

In `Agent.writeMessage()` and write-future listeners:

- if channel is null/inactive, notify a connection-failure callback instead of
  only logging,
- if write future fails with `IOException`, `Broken pipe`, or
  `ClosedChannelException`, close the channel and notify the session/client,
- make this idempotent so repeated failed writes do not create restart storms.

The goal is that broken writes enter the same recovery path as channel close.

### 3. Move Reconnect Work Off Netty Event Loop

Avoid blocking the Netty event-loop thread:

- do not call blocking `connect().sync()` from close-future callbacks,
- do not `Thread.sleep()` in reconnect logic on an event-loop thread,
- schedule reconnect attempts on a dedicated executor or Netty scheduler,
- honor `maxRetryAttempts`,
- use backoff with jitter.

### 4. Clear Old Channel State Safely

On disconnect or session dispose:

- clear all agent channel references,
- mark the old inbound handler inactive,
- ensure late messages from old channels are ignored,
- reset protocol agents only at the correct reconnect boundary.

Some of this already exists in the current Yaci branch, but the write-failure
path still needs to drive it reliably.

### 5. Improve Reconnect Tests

Add enabled tests, not disabled-only tests:

- remote close during ChainSync send,
- broken pipe during BlockFetch callback followed by ChainSync send,
- connection refused for several retries and later success,
- connect timeout and later success,
- inactive channel write triggers reconnect,
- no blocking operation is performed on Netty event-loop threads.

The tests should assert progress after reconnect, not just that logs appear.

## Rollout Plan

1. Implement `runtime.peer` recovery behind default-on config.
2. Run mainnet sync overnight through the same CF/IOG public relay setup.
3. Tune timeouts to avoid false positives during sparse or overloaded periods.
4. Add optional failover peer list after single-peer recovery is stable.
5. Add Yaci library fixes and publish a new pre-release.
6. Update Yano to consume the fixed Yaci version.
7. Keep Yano supervision even after Yaci is fixed, because application-level
   progress supervision is still useful in production.

## Consequences

Positive:

- Yano can self-recover from stale upstream clients without a process restart.
- Recovery reuses persisted chain state, matching the manual restart behavior.
- Public relay instability no longer leaves the node stuck indefinitely.
- The recovery model can evolve into failover peers later.
- Later Yaci fixes improve all downstream users.

Tradeoffs:

- Yano will have more lifecycle complexity around `PeerClient` and pipeline
  managers.
- A too-aggressive no-progress timeout could restart during harmless slow
  periods, so defaults must be conservative.
- Yaci still needs library-level correction so applications are not forced to
  duplicate reconnect logic.

## Resolved Questions

- Phase 1 lives in `runtime.peer`, not a new Gradle module.
- Recovery should recreate the whole peer session, not only `PeerClient`.
- `SyncStartPointProvider` should return fallback candidate points.
- Preserve Yaci `NodeClient.isRunning()` compatibility; add a new health method
  later.

## Open Questions

- Should supervisor settings live in `YanoConfig` directly or under
  `RuntimeOptions.globals()` first?
- Should terminal failure only fail readiness/status, or optionally call
  `System.exit(1)` under a config flag?
- What chain-state APIs are needed to efficiently build fallback intersection
  points behind `tip`?
