# ADR-NET-003: Peer Session Recovery Implementation Plan

## Status

In Progress

## Date

2026-05-18

## Goal

Implement Yano-side upstream recovery so long-running sync can automatically
recover from broken, stale, or half-closed public relay connections without a
process restart.

The first implementation must stay simple and maintainable:

- one active upstream peer,
- no chain selection,
- no failover peer list yet,
- no new Gradle module yet,
- recovery by replacing the active runtime peer session,
- tests and review after every phase.

## Working Rules

- Keep each phase small enough to review and commit independently.
- Do not mix unrelated cleanup with recovery work.
- Preserve existing one-upstream behavior until recovery is explicitly enabled.
- After each implementation phase:
  - run focused tests,
  - run at least two reviewer agents with different review lenses,
  - address blocking findings,
  - commit only files related to that phase.
- Update this document after each phase with status, decisions, verification,
  and commit hash.

## Phase Tracker

| Phase | Status | Commit | Scope |
|---|---|---|---|
| 0 | Completed | `3283023` | ADRs and implementation tracker |
| 1 | Completed | `3283023` | Add `runtime.peer` skeleton and focused unit tests |
| 2 | Completed | `5255e84` | Move current single-peer startup/stop lifecycle behind `PeerSession` with no recovery decision yet |
| 3 | Completed | `446eee5` | Add health tracking from header/body/disconnect/keepalive signals |
| 4 | Completed | `b36f09f` | Add supervisor stale-session detection and single-flight rebuild |
| 5 | Completed | `1a0f40c` | Add rollback guard and body-fetch stuck detection |
| 6 | Completed | `f8cbe6a` | Add terminal failure/status/observability |
| 7 | In Review | Current commit | Add focused recovery tests and TCP-proxy/manual validation plan |

## Phase 0: ADR Alignment and Tracker

### Scope

- Keep ADRs under `adr/network`.
- Make ADR-NET-001 explicitly resilience-first.
- Make ADR-NET-002 package-first, module-later.
- Add this tracker.

### Verification

- Documentation review only.
- ADR-NET-001 updated to make automatic recovery the primary goal.
- ADR-NET-002 updated to defer the Gradle module and use `runtime.peer` first.

### Review Notes

- Claude's review in `adr/network/review1.md` was accepted in part.
- Key decision: do not create a Gradle module in Phase 1.
- Key decision: implement a runtime peer-session boundary first.

### Commit

- Included in the Phase 1 commit.

## Phase 1: `runtime.peer` Skeleton

### Scope

Add a small package with passive types only. No Yano wiring and no behavior
change.

Planned classes:

- `PeerRecoveryReason`
- `PeerSessionState`
- `PeerHealth`
- `PeerSessionStatus`
- `SyncStartPointProvider`

Expected properties:

- no direct `Yano` dependency,
- no direct RocksDB dependency,
- no networking side effects,
- unit tests cover state transitions and timestamp/counter updates.

### Non-Goals

- Do not create `PeerSession` lifecycle yet.
- Do not change `Yano.startClientSync()`.
- Do not add supervisor scheduling yet.

### Verification

- `./gradlew :runtime:test --tests "com.bloxbean.cardano.yano.runtime.peer.*"`
- `./gradlew :runtime:compileJava`

### Review Notes

- Two reviewer agents completed review.
- No blocking issues found.
- Fixed reviewer findings before commit:
  - terminal failure can no longer be partially reset through
    `resetRecoveryAttempts()`;
  - duplicate body-fetch start signals keep the original start time so stuck
    fetch age cannot be refreshed indefinitely;
  - body received progress and body applied progress are tracked separately.

### Commit

- Pending at the time this document update was written; this section is included
  in the Phase 1 commit.

## Phase 2: Single-Peer Lifecycle Extraction

### Scope

Introduce `PeerSession` and move the existing active peer startup lifecycle into
it without changing recovery behavior.

`PeerSession` should own:

- `PeerClient`,
- `PipelineDataListener`,
- `HeaderSyncManager`,
- `BodyFetchManager`,
- TxSubmission initialization,
- session stop.

Yano should still decide when to start sync and should still use the same
remote host/port/protocol magic. The user-visible behavior should remain the
same.

### Verification

- Existing runtime tests covering header/body managers.
- Compile runtime.
- Manual code review of startup/stop path.
- `./gradlew :runtime:compileJava`
- `./gradlew :runtime:test --tests "com.bloxbean.cardano.yano.runtime.peer.*" --tests "com.bloxbean.cardano.yano.runtime.HeaderSyncManagerSimpleTest" --tests "com.bloxbean.cardano.yano.runtime.BodyFetchManagerSimpleTest"`

### Review Notes

- Two reviewer agents completed review.
- No blocking issues found.
- Accepted maintainability feedback before commit:
  - `PeerSession` now depends on a narrow `PeerSessionCallbacks` interface
    instead of concrete `Yano`;
  - raw manager accessors remain for Phase 2 compatibility and should be
    narrowed in later phases as status/phase helpers move into `PeerSession`.

### Commit

- Pending at the time this document update was written; this section is included
  in the Phase 2 commit.

## Phase 3: Health Tracking

### Scope

Wire `PeerHealth` updates from:

- header rollforward,
- body received/applied,
- disconnect,
- keepalive response timestamp,
- body-fetch in-progress state.

Expose health snapshot in a simple status object.

### Verification

- Unit tests around `PeerHealth`.
- Focused tests for listener callbacks updating health.
- `./gradlew :runtime:compileJava`
- `./gradlew :runtime:test --tests "com.bloxbean.cardano.yano.runtime.peer.*" --tests "com.bloxbean.cardano.yano.runtime.PipelineDataListenerHealthTest" --tests "com.bloxbean.cardano.yano.runtime.HeaderSyncManagerSimpleTest" --tests "com.bloxbean.cardano.yano.runtime.BodyFetchManagerSimpleTest"`

### Review Notes

- Two reviewer agents completed an initial review.
- Fixed blocking findings before commit:
  - `noBlockFound` now clears the health `bodyFetchInProgress` flag so empty
    fetch ranges do not look stuck;
  - body-applied health is recorded from `BodyFetchManager` only after the
    block has been stored and `BlockAppliedEvent` has been published;
  - body-received and body-applied progress remain separate so later
    supervision can distinguish peer delivery from local application.
- A final reviewer pass was run after the fixes and focused tests.
- Fixed final lifecycle findings before commit:
  - `PeerSession.stop()` clears active body-fetch health state;
  - startup exceptions mark the session as `TERMINAL_FAILURE` with
    `STARTUP_FAILED` instead of leaving it in `STARTING`;
  - stopping a terminal session preserves the terminal state and reason;
  - keepalive refresh and failed body-application health semantics are covered
    by focused tests.
- Failed-write health remains a known upstream observability gap because Yaci's
  current `PeerClient` path logs write failures without exposing a callback.
  Phase 4/7 will rely on stale progress detection first; a later Yaci-side hook
  can make this signal immediate.

### Commit

- Included in the Phase 3 commit.

## Phase 4: Supervisor Recovery

### Scope

Add `PeerSessionSupervisor`:

- scheduled health checks,
- no-progress detection,
- keepalive/app-progress evaluation,
- single-flight recovery guard,
- cooldown with jitter,
- recovery-attempt accounting,
- restart by replacing the active `PeerSession`.

### Verification

- Supervisor does not recover while progress advances.
- Supervisor recovers when stale.
- Concurrent health checks trigger only one recovery.
- Disconnect alone does not recover immediately.
- Fresh keepalive does not mask stale application progress.
- Cooldown and configured jitter prevent repeated recoveries.
- `./gradlew :runtime:compileJava`
- `./gradlew :runtime:test --tests "com.bloxbean.cardano.yano.runtime.peer.PeerSessionSupervisorTest" --tests "com.bloxbean.cardano.yano.runtime.peer.*" --tests "com.bloxbean.cardano.yano.runtime.PipelineDataListenerHealthTest" --tests "com.bloxbean.cardano.yano.runtime.HeaderSyncManagerSimpleTest" --tests "com.bloxbean.cardano.yano.runtime.BodyFetchManagerSimpleTest"`

### Review Notes

- Two reviewer agents completed an initial review.
- Fixed blocking lifecycle findings before commit:
  - recovery startup failures now leave a terminal placeholder session so the
    supervisor can retry after cooldown instead of going quiet;
  - recovery and stop now share `peerSessionLock`, and recovery re-checks
    `isRunning` before starting the replacement session;
  - initial peer startup now checks `isRunning` under `peerSessionLock` before
    creating and starting the session, so a concurrent stop cannot leave a new
    peer running after shutdown;
  - volatile `peerSession` reads in tx forwarding and manager accessors now use
    local snapshots to avoid transient NPEs while recovery swaps sessions.
- Fixed policy findings before commit:
  - disconnect recovery is evaluated before the no-progress gate, while later
    header/body/keepalive activity suppresses stale disconnect signals;
  - stale application progress triggers recovery even when keepalive remains
    fresh, matching the real failure mode where the socket is alive but sync
    has stopped;
  - cooldown jitter is covered by a deterministic unit test.
- Residual coverage gap: Yano's private recovery method is not directly unit
  tested yet. Phase 7 real-network fault validation must cover replacement
  startup, repeated recovery failure, and shutdown during recovery.
- Final reviewer pass found no unresolved medium/high Phase 4 findings.

### Commit

- Included in the Phase 4 commit.

## Phase 5: Rollback and Body-Fetch Stuck Guards

### Scope

- Add rollback-in-progress guard around recovery.
- Add no-body-received timeout when body fetch is in progress.
- Clear stale body-fetch state and signal supervisor.
- Ensure recovery waits for rollback completion.

### Verification

- Rollback in progress blocks recovery.
- Stuck body fetch triggers recovery after policy.
- Recovery does not leave old batch state blocking new session.
- Rollback and disconnect clear body-fetch health state.
- `./gradlew :runtime:compileJava`
- `./gradlew :runtime:test --tests "com.bloxbean.cardano.yano.runtime.peer.PeerSessionSupervisorTest" --tests "com.bloxbean.cardano.yano.runtime.peer.*" --tests "com.bloxbean.cardano.yano.runtime.PipelineDataListenerHealthTest" --tests "com.bloxbean.cardano.yano.runtime.BodyFetchManagerSimpleTest" --tests "com.bloxbean.cardano.yano.runtime.HeaderSyncManagerSimpleTest"`

### Review Notes

- Two reviewer agents completed review.
- Fixed blocking findings before commit:
  - rollback handling and peer recovery now share `peerSessionLock`, so they
    cannot stop/recreate the active peer while rollback is mutating local state;
  - rollback clears both `PeerHealth` body-fetch state and the real
    `BodyFetchManager` batch state;
  - body-fetch stuck detection now uses last body activity while a batch is in
    progress, so it catches both no-body and partial-progress stalls;
  - `Policy` keeps an 8-argument compatibility constructor while adding the new
    body-fetch-stuck timeout to the canonical policy.
- Final reviewer pass found no unresolved Phase 5 findings.

### Commit

- Included in the Phase 5 commit.

## Phase 6: Terminal Failure and Observability

### Scope

- Add terminal failure state after max recovery attempts.
- Publish or expose a sync-stuck status signal.
- Add status fields for recovery attempts, reason, progress ages, and current
  peer.
- Add clear logs around recovery start/success/failure.

### Verification

- Max attempts enter terminal state.
- Status exposes recovery state.
- No log spam in normal operation.
- `./gradlew :core-api:compileJava`
- `./gradlew :core-api:test --tests "com.bloxbean.cardano.yano.api.model.NodeStatusTest"`
- `./gradlew :runtime:compileJava`
- `./gradlew :runtime:test --tests "com.bloxbean.cardano.yano.runtime.peer.*" --tests "com.bloxbean.cardano.yano.runtime.PipelineIntegrationTest" --tests "com.bloxbean.cardano.yano.runtime.PipelineDataListenerHealthTest" --tests "com.bloxbean.cardano.yano.runtime.BodyFetchManagerSimpleTest" --tests "com.bloxbean.cardano.yano.runtime.HeaderSyncManagerSimpleTest"`

### Review Notes

- Two reviewer agents completed review.
- Fixed reviewer findings before commit:
  - `peerRecoveryReason` is no longer reported as `UNKNOWN` before any
    recovery attempt or failure;
  - terminal peer recovery status now keeps the real last trigger reason
    (`KEEPALIVE_STALE`, `STARTUP_FAILED`, etc.) while
    `peerRecoveryTerminal=true` carries the terminal state.
- Residual future cleanup: `MAX_PEER_RECOVERY_FAILURES` is intentionally a
  simple constant for this phase. Make it configurable only if Phase 7 fault
  validation shows the default is not suitable.
- Final reviewer pass found no unresolved Phase 6 findings.

### Commit

- Included in the Phase 6 commit.

## Phase 7: Focused Recovery Validation

### Scope

- Add focused integration tests where practical.
- Add manual validation checklist for mainnet overnight sync.
- Add TCP proxy scenario if feasible in this repo.
- Validate against a real public test network with induced faults before
  declaring the recovery work done.
- Tune defaults only after evidence.

### Verification

- Mainnet sync through public relay overnight.
- Forced disconnect/broken pipe recovers.
- Real test-network sync recovers after induced faults, for example dropped TCP
  connection, paused proxy, outbound packet rejection, or proxy restart.
- Real test-network validation is required after implementation, not only as a
  manual follow-up. The run must record which network, relay/proxy topology,
  fault type, last synced slot before fault, recovery time, and final synced
  slot after recovery.
- Fault validation is repeated during bulk catch-up and near tip.
- Existing runtime tests pass.

### Real-Network Validation

Preview bulk-catch-up validation was run against a local TCP fault proxy:

```bash
python3 scripts/network/tcp_fault_proxy.py \
  --listen-port 33001 \
  --target-host preview-node.play.dev.cardano.org \
  --target-port 3001 \
  --control-port 33002
```

Yano was started with a disposable chainstate and the proxy as its upstream:

```bash
java -Dquarkus.profile=preview \
  -Dquarkus.http.port=7081 \
  -Dyano.server.port=13381 \
  -Dyano.remote.host=127.0.0.1 \
  -Dyano.remote.port=33001 \
  -Dyano.storage.path=/tmp/yano-peer-recovery-preview-2/chainstate \
  -Dyano.utxo.enabled=false \
  -Dyano.account-state.enabled=false \
  -Dyano.account-history.enabled=false \
  -Dyano.adapot.enabled=false \
  -Dyano.rewards.enabled=false \
  -Dyano.governance.enabled=false \
  -Dyano.epoch-params.tracking-enabled=false \
  -Dyaci.plugins.enabled=false \
  -jar build/yano.jar
```

Observed results:

- Baseline sync through the proxy reached `peerState=RUNNING`,
  `peerRecoveryFailures=0`, and local slot `258105`.
- A forced active-connection drop through `POST /drop` recovered through Yaci's
  built-in reconnect path. The proxy accepted a new connection, Yano stayed
  `RUNNING`, recovery failures stayed `0`, and local slot advanced to `675992`
  within roughly 15 seconds.
- A full proxy outage reproduced the harder stale-peer condition. The
  supervisor replaced the stale session and the new session stayed in
  `STARTING` while Yaci retried connects against the unavailable proxy.
- Restarting the proxy restored sync without restarting Yano. The peer returned
  to `RUNNING`, recovery failures stayed `0`, local slot advanced from `764405`
  at outage to `1384435` roughly 25 seconds after proxy restart, and logs
  continued through slot `1764034` before the validation process was stopped.

Near-tip validation remains pending. This run intentionally covered the
historical bulk-catch-up path where the original stuck-sync failure has been
observed most often.

### Fix From Validation

The first full-outage run found a recovery-path bug: `recoverPeerSession()`
opened a fresh `TipFinder` connection while the upstream was already unhealthy.
Because Yaci starts the network connection synchronously inside the reactive
source, `block(Duration.ofSeconds(5))` did not reliably bound the recovery path.

The fix is to avoid `TipFinder` during recovery. Recovery now reuses the cached
remote tip when available, or uses the local restart point as a temporary
recovery scope when no cached remote tip exists. The replacement peer session
still starts chain sync from the local point, so the cached remote tip is only a
bulk-vs-realtime/status hint during recovery.

The recovered chain-sync intersection callback refreshes the cached remote tip
once the replacement session reaches the peer, so status and initial-sync
completion are not permanently tied to the temporary recovery scope.

### Fast Explicit-Disconnect Follow-up

Mainnet bulk sync testing showed that waiting for the periodic supervisor tick
and 30-second disconnect grace can add avoidable delay when the upstream has
already reported an explicit disconnect. This is different from ambiguous
no-progress or keepalive-stale cases, where conservative timers still make
sense.

The follow-up change keeps Yaci auto-reconnect disabled in Yano, but lets
`PipelineDataListener.onDisconnect()` notify the Yano supervisor immediately.
The supervisor then requests clean session replacement without waiting for the
next scheduled tick.

To avoid a tight reconnect loop against unhealthy public relays:

- the first two explicit-disconnect recoveries in a burst bypass the normal
  cooldown;
- after the second fast recovery, subsequent disconnect recoveries use the
  normal recovery cooldown and jitter;
- the fast quota resets after five minutes without disconnect-driven recovery;
- silent stalls, body-fetch stalls, startup failures, and keepalive/no-progress
  recovery keep the existing conservative timers.

### Review Notes

- Two reviewer passes were run for Phase 7.
- Initial review found no high/medium issues, but called out stale remote-tip
  status as a residual risk.
- A second review found medium findings around stale/synthetic remote-tip usage,
  null/no-point tip handling in the shared pipelined start path, and validation
  wording that overstated near-tip coverage.
- Fixed before commit:
  - `remoteTip` is now volatile and refreshed from recovered chain-sync
    intersection callbacks;
  - startup and recovery normalize null/no-point tips into a temporary local
    sync scope;
  - `startPipelinedClientSync`, `checkSyncProgress`, and `getStatus` guard
    `remoteTip.getPoint()`;
  - the validation notes now explicitly say bulk-catch-up preview validation
    completed and near-tip validation remains pending.
- Final reviewer pass found no remaining high/medium issues.

### Commit

- Pending Phase 7 review.

## Current Design Decisions

- Phase 1 package is `com.bloxbean.cardano.yano.runtime.peer`.
- New Gradle module is deferred.
- Recovery replaces the whole active session, not only `PeerClient`.
- Failover peers are deferred until single-peer recovery is stable.
- Chain selection is out of scope.
- `NodeClient.isRunning()` compatibility should be preserved in Yaci; add a new
  channel-health method later.

## Implementation Log

- 2026-05-18: Created tracker. Phase 0 in progress.
- 2026-05-18: Phase 1 completed and committed as `3283023`.
- 2026-05-18: Phase 7 validation scope updated in `6d1f5d0` to require real
  public test-network fault testing before declaring recovery complete.
- 2026-05-18: Phase 2 completed and committed as `5255e84`.
- 2026-05-18: Phase 3 completed and committed as `446eee5`.
- 2026-05-18: Phase 4 completed and committed as `b36f09f`.
- 2026-05-18: Phase 5 completed and committed as `1a0f40c`.
- 2026-05-18: Phase 6 completed and committed as `f8cbe6a`.
- 2026-05-18: Phase 7 preview bulk-catch-up fault validation completed. A
  forced TCP drop recovered through Yaci reconnect; a full proxy outage
  recovered after proxy restart through the Yano supervisor replacement path.
  The recovery path was simplified to reuse cached remote-tip context instead
  of opening `TipFinder` during recovery. Near-tip fault validation remains
  pending.
- 2026-05-19: Added explicit-disconnect fast recovery. Yano now wakes the
  supervisor directly from the disconnect callback, allows two immediate clean
  session replacements per disconnect burst, then falls back to normal
  cooldown/jitter. Focused supervisor/listener tests, full `:runtime:test`, and
  `:app:compileJava` passed.
