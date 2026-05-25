# ADR-NET-004: DNS-Aware Peer Session Recovery

## Status

Proposed

## Date

2026-05-19

## Context

Yano now has a `PeerSession` and `PeerSessionSupervisor` that can replace the
active upstream session after disconnects, stale progress, startup failure, and
body-fetch stalls.

An overnight mainnet sync against the Cardano Foundation public relay exposed a
remaining failure mode. After several hours of successful syncing, the upstream
connection closed. Recovery began, but the process repeatedly timed out against
the same resolved address:

```text
connection timed out after 30000 ms:
backbone.mainnet.cardanofoundation.org/147.93.84.155:3001
```

At the same time, the host environment resolved the DNS name to a current set of
relay IPs including another address, such as `187.127.162.198`. Restarting the
process recovered.

The active Yano log showed:

- disconnect at approximately `2026-05-19 08:05:46`,
- `PeerSessionSupervisor` requested recovery at `08:06:41`,
- the replacement startup entered Yaci `PeerClient.connect(...)`,
- Yaci `Session.start()` repeatedly retried
  `backbone.mainnet.cardanofoundation.org/147.93.84.155:3001`,
- Yano did not recover before the process was manually stopped at `08:55:45`.

The previous Yano fix correctly discarded the old `PeerSession`, but the new
session startup was trapped inside Yaci's internal blocking reconnect loop.

## Root Cause

The root cause is shared between Yaci and Yano.

In Yaci:

- `TCPNodeClient.createSocketAddress()` creates a resolved
  `InetSocketAddress`.
- `Session` stores that `SocketAddress`.
- `Session.start()` retries `clientBootstrap.connect(socketAddress).sync()` in
  a loop.
- The loop uses the same already-resolved IP address for every retry.
- `NodeClient.start()` logs startup exceptions instead of surfacing them cleanly
  to the caller.
- `NodeClientConfig.maxRetryAttempts` exists but is not currently enforced by
  `Session.start()`.

In Yano:

- `PeerSessionSupervisor` calls recovery on the scheduler thread.
- `recoverPeerSession(...)` creates a replacement session and starts it
  synchronously.
- `PeerSession.startPipelined(...)` calls `PeerClient.connect(...)`.
- If Yaci startup blocks indefinitely, the Yano supervisor cannot make another
  recovery decision, cannot mark startup failed, and cannot try another
  endpoint.

JVM DNS cache tuning alone is not sufficient. Even if the JVM cache expires,
the stuck `Session` already holds a resolved `SocketAddress` and does not ask
DNS again.

## Why Yaci Store Usually Recovers

Yaci Store does not appear to rely on Spring Boot DNS behavior. The relevant
difference is application lifecycle.

Yaci Store has:

- `AutoRecoveryStartService`, which checks health and publishes a restart event,
- `RequiredRestartProcessor`, which calls `startService.stop()` and
  `startService.start()`,
- `BlockSync.startSync(...)`, which creates a fresh
  `N2NChainSyncFetcher` for each start.

That full restart discards the old Yaci `Session` and naturally gets a fresh
DNS resolution opportunity. Yano should preserve its lighter-weight
`PeerSession` replacement model, but it must prevent Yaci's internal reconnect
loop from owning recovery indefinitely.

## Decision

Keep Yano's `PeerSessionSupervisor` as the owner of application-level recovery.
Do not rely on Yaci's current unbounded internal reconnect loop for Yano's
long-running sync.

Yano should consume new Yaci APIs, described in Yaci ADR 0002, that provide:

- fresh DNS resolution per connect attempt,
- bounded retry attempts,
- startup failure propagation,
- helper-level `NodeClientConfig` injection.

Once available, Yano will configure Yaci for bounded startup attempts and keep
peer replacement, backoff, failover, and status in Yano's supervisor.

JVM DNS TTL flags are not part of the normal fix. They can remain an operator
workaround, but Yano should recover correctly without requiring users to set
`networkaddress.cache.ttl` or `sun.net.inetaddr.ttl`.

## Yano Changes

### 1. Add a Peer Client Factory Boundary

`PeerSession` currently constructs `PeerClient` directly:

```java
peerClient = new PeerClient(host, port, protocolMagic, startPoint);
```

Introduce a small factory abstraction inside `runtime.peer`, for example:

```java
interface PeerClientFactory {
    PeerClient create(PeerEndpoint endpoint, Point startPoint);
}
```

The default factory will create a Yaci `PeerClient` with a Yaci
`NodeClientConfig` once Yaci exposes that constructor path.

Benefits:

- tests can inject fast failing clients without opening sockets,
- Yano can pass bounded Yaci retry config in one place,
- later multi-peer and per-IP selection do not require changing
  `PeerSession`.

### 2. Add PeerEndpoint and DNS-Aware Selection

Represent the active connection target as a `PeerEndpoint`:

```text
configuredHost: backbone.mainnet.cardanofoundation.org
resolvedHost: 187.127.162.198 or null
port: 3001
protocolMagic: 764824073
displayName: backbone.mainnet.cardanofoundation.org:3001
```

For the first implementation, one configured upstream host remains the default.
However, a DNS hostname can produce multiple candidate IP addresses. Yano should
track failures per resolved address so one bad public relay IP does not poison
the whole configured host.

Rules:

- On startup and recovery, resolve all A/AAAA addresses for the configured
  host.
- Prefer addresses not in cooldown.
- When a connection attempt times out, mark that resolved address failed.
- Rotate to another resolved address on the next recovery attempt.
- Preserve the configured hostname in logs and status, but also log the
  selected resolved address.
- If resolution fails, keep the existing behavior of trying the configured host
  and let Yaci report the failure.

This is also the right infrastructure for later multi-upstream failover. A list
of configured hosts is just a larger candidate set.

### 3. Keep Recovery Non-Blocking

The supervisor scheduler must not be blocked for minutes inside one startup
attempt.

Move connection startup into a dedicated recovery/startup executor or make the
supervisor dispatch recovery asynchronously with a single-flight guard.

Required behavior:

- the scheduled health-check thread remains free,
- only one recovery attempt runs at a time,
- a startup timeout marks the attempt failed,
- failed startup updates `PeerHealth` and selected endpoint failure state,
- the next supervisor pass can choose the next address or peer.

If Yaci exposes bounded startup, the timeout should normally not fire. It is
still a defensive guard for unexpected library or network behavior.

### 4. Configure Yaci for Supervised Mode

Once Yaci exposes `NodeClientConfig` through `PeerClient` / `N2NPeerFetcher`,
Yano should use bounded or fail-fast settings rather than unbounded internal
reconnect.

Suggested initial policy:

```text
connectionTimeoutMs: 30000
autoReconnect: false
maxRetryAttempts: 0
```

or, if we want one short local retry before returning control to Yano:

```text
connectionTimeoutMs: 10000
initialRetryDelayMs: 1000
autoReconnect: true
maxRetryAttempts: 1 or 2
```

The key invariant is that `PeerClient.connect(...)` must return or throw within
a bounded time so Yano can continue supervising.

### 5. Preserve Single-Upstream Behavior

The user-facing default remains one configured upstream host and port.

With one upstream host:

- initial sync uses that host,
- recovery rebuilds that host,
- if the host resolves to multiple addresses, Yano can rotate through those
  addresses,
- if all addresses fail, Yano backs off and retries later.

No chain selection is introduced by this ADR.

### 6. Prepare for Multi-Upstream Failover

The same endpoint model should support later configuration such as:

```yaml
yano:
  upstreams:
    - host: backbone.mainnet.cardanofoundation.org
      port: 3001
      priority: 1
    - host: relays-new.cardano-mainnet.iohk.io
      port: 3001
      priority: 2
```

Initial failover rules should stay simple:

- only one active peer,
- cold standby peers,
- no chain selection,
- pick the first eligible endpoint by priority and cooldown,
- resume from persisted chain state.

This keeps the design aligned with ADR-NET-002 without expanding scope now.

## Interim Workaround if Yaci Is Not Yet Fixed

If Yano must ship before the Yaci ADR is implemented, Yano can add a temporary
defensive layer:

1. Resolve all IPs for the configured host in Yano.
2. Pass one selected IP address to `PeerClient` instead of the hostname.
3. Run `PeerClient.connect(...)` in a bounded startup worker.
4. On timeout, call `PeerClient.stop()`, mark that IP failed, and try another
   IP on the next recovery attempt.

This avoids relying on DNS inside Yaci for the selected attempt, but it is still
less clean than fixing Yaci because the Yaci startup loop can remain blocked
until its current timeout/sleep cycle exits. It should be treated as a temporary
bridge only.

## Alternatives Considered

### Follow Yaci Store by fully restarting Yano sync

Rejected for the main design.

A full stop/start would probably recover more often because it discards every
runtime object. It is also heavier and less aligned with Yano's active
`PeerSession` architecture. Replacing the active peer session is enough if the
lower-level startup path is bounded.

### Keep using Yaci internal auto-reconnect

Rejected for Yano's supervised mode.

Internal reconnect is useful for simple consumers, but Yano has application
state, persisted chain state, status endpoints, rollback guards, body-fetch
state, and future failover policy. Yano needs to observe failures and choose
the next action.

### Set JVM DNS TTL flags

Rejected as the primary fix.

This can help future DNS lookups but cannot change one already-resolved
`InetSocketAddress` stored inside Yaci `Session`.

### Only use a different public relay hostname

Rejected.

Changing relays may avoid one bad IP temporarily, but the same class of failure
can happen with any DNS-backed public relay.

## Phased Plan

### Phase A: Wait for Yaci Fresh DNS and Bounded Startup APIs

Implement Yaci ADR 0002:

- fresh address resolution per connect attempt,
- bounded retry attempts,
- startup failure propagation,
- helper-level config injection.

Yano should update its dependency to that Yaci version when available.

### Phase B: Yano Supervised Yaci Startup

Update `PeerSession` to create `PeerClient` through a factory using bounded
Yaci config.

Add tests:

- startup failure marks session terminal without blocking scheduler,
- supervisor can request another recovery after startup failure,
- status endpoint reports current failure reason.

### Phase C: DNS-Aware Endpoint Rotation

Add `PeerEndpoint`, `PeerEndpointResolver`, and minimal cooldown state.

Add tests:

- hostname with two resolved addresses chooses the first eligible address,
- timed-out address enters cooldown,
- next recovery chooses the second address,
- cooldown expiry makes the first address eligible again.

### Phase D: Real Fault Validation

Validate against a local fault proxy and public test network:

- drop active TCP connection,
- blackhole one resolved address if feasible,
- confirm recovery chooses another address or retries after cooldown,
- confirm sync progresses without process restart.

For mainnet, a short validation can start from an existing chainstate near the
observed failure height and force connection drops. A full overnight mainnet
run remains the final confidence test.

## Configuration

Add explicit runtime properties only if needed. Suggested names:

```text
yano.peer.connect-timeout-ms=30000
yano.peer.startup-timeout-ms=45000
yano.peer.address-cooldown-ms=300000
yano.peer.max-address-failures=3
```

Defaults should preserve current behavior for a single configured upstream,
except that recovery becomes bounded and observable.

## Observability

Status and logs should show:

- configured host,
- selected resolved address,
- peer state,
- recovery reason,
- startup failure message,
- failed address count,
- cooldown deadline when relevant.

Example log shape:

```text
Starting peer session: peer=backbone.mainnet.cardanofoundation.org:3001,
resolved=187.127.162.198:3001, attempt=3
```

## Acceptance Criteria

- A failed connect attempt cannot block the supervisor indefinitely.
- A stale resolved IP cannot be reused forever.
- Yano can recover from public relay DNS rotation without process restart.
- Yano still works with one configured upstream host.
- No chain selection or multi-active-peer behavior is introduced.
- Tests cover bounded startup failure and endpoint rotation.
- Real network fault validation demonstrates resumed sync after forced
  disconnects.

## Consequences

This keeps Yano's recovery model simple: one active session, supervised by
Yano, rebuilt from persisted chain state.

It also creates the minimum infrastructure needed for later failover peers:
endpoint identity, failure state, cooldown, and selection. Chain selection
remains out of scope.

The tradeoff is that Yano will need a small peer-client factory and endpoint
selection layer. That is justified because public relay DNS and multi-address
failure behavior are operational realities for long-running mainnet sync.
