# Yano Upstream User Guide

This guide explains how to configure Yano's upstream peer modes. The upstream
layer controls how Yano connects to Cardano nodes for ChainSync, BlockFetch,
header observation, failover, discovery, and transaction forwarding.

## Quick Choice

| Use case | Recommended mode |
|---|---|
| Existing indexer setup with one trusted Cardano node | `trusted-single` |
| High availability across a few trusted nodes | `trusted-failover` |
| Controlled multi-peer testing with static peers | `static-multi` |
| Relay-style setup seeded by static root peers | `rooted-relay` |
| Public-network relay-style testing with snapshots or peer sharing | `p2p-relay` |

`trusted-single` remains the safest default when the upstream is trusted and
high availability is not required.

## Configuration Shape

Yano still supports the legacy single-peer properties:

```yaml
yano:
  remote:
    host: preprod-node.play.dev.cardano.org
    port: 3001
    protocol-magic: 1
```

If `yano.upstream.mode` is absent and `yano.remote.host` is present, Yano maps
that configuration to `trusted-single`.

The newer upstream block is:

```yaml
yano:
  upstream:
    mode: trusted-single
    peers:
      - id: relay-1
        host: preprod-node.play.dev.cardano.org
        port: 3001
        source: local-root
        priority: 0
        trust: trusted

    sync:
      bulk-source: single-trusted
      fan-in-start: near-tip

    failover:
      cooldown-ms: 30000
      max-failures-before-cooldown: 3

    selection:
      policy: trusted-or-quorum-within-rollback-window
      rollback-window-slots: 0
      require-body-before-adoption: true
      trust-policy: trusted-only
      quorum: 2
      tie-break: deterministic

    validation:
      level: none
      body-level: none

    tx:
      forwarding: active-selected

    governor:
      enabled: false
      targets:
        cold: 100
        warm: 8
        hot: 2
      max-concurrent-dials: 4

    discovery:
      enabled: false
      peer-sharing: false
      peer-snapshot-urls: []
      peer-snapshot-files: []
      peer-snapshot-limit: 128
      allow-private-addresses: false
      allowlist: []
      denylist: []
```

Most deployments only need `mode`, `peers`, and a small number of mode-specific
settings.

## Supported Modes

### `trusted-single`

One upstream peer is authoritative. Yano syncs headers and bodies from that peer
and does not start multi-peer observers.

Example:

```yaml
yano:
  upstream:
    mode: trusted-single
    peers:
      - id: upstream
        host: preprod-node.play.dev.cardano.org
        port: 3001
        trust: trusted
```

Advantages:

- Lowest connection, memory, and operational cost.
- Best fit for indexers using a trusted upstream.
- No candidate header store, peer store, discovery, or chain-selection
  complexity.

Disadvantages:

- Single point of failure.
- No peer comparison or quorum.
- Correctness depends on the trusted upstream.

### `trusted-failover`

Multiple configured peers are available, but only one peer is canonical-active
at a time. If startup or recovery fails, Yano rotates to another configured
peer.

Example:

```yaml
yano:
  upstream:
    mode: trusted-failover
    peers:
      - id: primary
        host: relay-a.example.com
        port: 3001
        priority: 0
        trust: trusted
      - id: secondary
        host: relay-b.example.com
        port: 3001
        priority: 10
        trust: trusted
    failover:
      cooldown-ms: 30000
```

Advantages:

- High availability without multi-peer chain-selection risk.
- Simple mental model: one selected peer at a time.
- Good fit for multiple trusted upstreams controlled by the same operator.

Disadvantages:

- Still trusts whichever peer is active.
- No simultaneous header fan-in.
- No quorum or peer discovery.

### `static-multi`

Multiple configured static peers can be connected. One peer is selected as the
canonical source for body sync; observer peers can provide header fan-in for
chain-selection decisions.

Example:

```yaml
yano:
  upstream:
    mode: static-multi
    peers:
      - id: relay-a
        host: relay-a.example.com
        port: 3001
        priority: 0
        trust: trusted
      - id: relay-b
        host: relay-b.example.com
        port: 3001
        priority: 10
        trust: trusted
      - id: relay-c
        host: relay-c.example.com
        port: 3001
        priority: 20
        trust: trusted
    governor:
      targets:
        hot: 3
    sync:
      fan-in-start: near-tip
    selection:
      quorum: 2
```

Advantages:

- Controlled multi-peer behavior without open discovery.
- Header observations from multiple peers can support quorum-based switching.
- Useful for relay behavior testing with known peers.

Disadvantages:

- More connections and memory than single-active modes.
- Static peer list must be maintained manually.
- Body fetch currently remains selected-peer based, not parallel across all
  peers.
- Without full consensus validation, untrusted multi-peer setups should require
  quorum. A single untrusted longer chain must not be treated as authoritative.

### `rooted-relay`

Root peers seed the peer store and governor. Discovery can remain disabled.
This is a bridge between static multi-peer operation and fuller relay behavior.

Example:

```yaml
yano:
  upstream:
    mode: rooted-relay
    peers:
      - id: root-a
        host: relay-a.example.com
        port: 3001
        source: local-root
        priority: 0
        trust: trusted
      - id: root-b
        host: relay-b.example.com
        port: 3001
        source: local-root
        priority: 10
        trust: trusted
    governor:
      enabled: true
      targets:
        hot: 3
        warm: 8
        cold: 100
    discovery:
      enabled: false
```

Advantages:

- Relay-shaped peer management from configured roots.
- Can preserve known peer metadata in the peer store.
- Does not require open peer discovery.

Disadvantages:

- Governor behavior is intentionally simple compared with Haskell
  `cardano-node`.
- Still selected-peer body fetch.
- Root peer quality matters because they seed the peer set.

### `p2p-relay`

Multi-peer mode with discovery support. Yano can load official peer snapshots,
peer snapshot files, and peer-sharing results, then select eligible peers for
sync and header fan-in.

Example using the official preprod peer snapshot:

```yaml
yano:
  upstream:
    mode: p2p-relay
    discovery:
      enabled: true
      peer-sharing: false
      peer-snapshot-urls:
        - https://book.play.dev.cardano.org/environments/preprod/peer-snapshot.json
      peer-snapshot-limit: 32
      allow-private-addresses: false
    governor:
      enabled: true
      targets:
        hot: 3
        warm: 8
        cold: 100
    sync:
      fan-in-start: near-tip
    selection:
      quorum: 2
      rollback-window-slots: 0
    tx:
      forwarding: active-selected
```

If `discovery.enabled=true`, `p2p-relay` can start without explicit
`yano.upstream.peers` as long as snapshots or peer sharing provide usable peers.
For `mainnet`, `preprod`, and `preview`, Yano can derive the official Cardano
Operations Book peer snapshot URL when discovery is enabled and no snapshot URL
is configured.

Advantages:

- Closest current mode to public relay behavior.
- Can recover from bad configured peers by selecting discovered peers.
- Supports official peer snapshots out of the box.
- Supports header fan-in and quorum-based selected peer switching.

Disadvantages:

- Most operationally complex mode.
- More connections and candidate-header memory.
- Not full Haskell-node behavior yet: no full mempool diffusion, no parallel
  block body fetch across all hot peers, and no full consensus validation.

## Shared Settings

### Sync

```yaml
yano:
  upstream:
    sync:
      bulk-source: single-trusted
      fan-in-start: near-tip
```

Supported `bulk-source` values:

- `single-trusted`: bulk sync from the selected trusted/canonical peer.
- `selected-candidate`: reserved config shape for selected-candidate body
  sourcing.

Supported `fan-in-start` values:

- `disabled`: do not start observer header fan-in.
- `near-tip`: start fan-in once initial sync reaches steady state or near tip.
- `always`: start observer header fan-in immediately, including during initial
  sync.

### Chain Selection

```yaml
yano:
  upstream:
    selection:
      policy: trusted-or-quorum-within-rollback-window
      rollback-window-slots: 0
      trust-policy: trusted-only
      quorum: 2
      tie-break: deterministic
```

Supported policy:

- `trusted-or-quorum-within-rollback-window`

`rollback-window-slots`:

- `0` or omitted: derive from Shelley genesis as
  `ceil(securityParam / activeSlotsCoeff)`.
- Positive value: use that explicit slot window.

Typical derived values:

| Network shape | Derived window |
|---|---:|
| mainnet/preprod, `k=2160`, `f=0.05` | `43,200` slots |
| preview, `k=432`, `f=0.05` | `8,640` slots |
| devnet, `k=100`, `f=1.0` | `100` slots |

The candidate header store is in memory and non-canonical. It is capped at
`16,384` observations. With `targetHot=3` on mainnet/preprod, expected retained
observations are roughly `2 * 2160 = 4,320`, or about `2-4 MiB` on HotSpot.
The hard cap is roughly `8-15 MiB`.

### Validation

```yaml
yano:
  upstream:
    validation:
      level: none
      body-level: none
```

Supported header validation levels:

- `none`: no upstream header validation.
- `structural`: structural header checks.
- `header-signature`: Shelley+ header signature checks in addition to
  structural checks.

Supported body validation levels:

- `none`: body validation framework is present, but no body validator is
  enabled by default.

`validated` trust policy exists in the config shape but currently fails fast
because consensus-lite or full validation is not implemented yet.

### Transaction Forwarding

```yaml
yano:
  upstream:
    tx:
      forwarding: active-selected
```

Supported values:

- `active-selected`: forward submitted transactions to the selected active
  upstream peer.
- `all-hot-trusted`: forward to the active peer and trusted observer peers.
- `disabled`: do not forward submitted transactions upstream.

This is upstream forwarding only. Yano does not yet implement full relay-style
mempool diffusion across all upstream and downstream peers.

### Discovery

```yaml
yano:
  upstream:
    discovery:
      enabled: true
      peer-sharing: false
      peer-snapshot-urls:
        - https://book.play.dev.cardano.org/environments/preprod/peer-snapshot.json
      peer-snapshot-limit: 32
      allow-private-addresses: false
      allowlist: []
      denylist: []
```

Discovery is used by multi-peer modes. It is especially useful with
`p2p-relay`.

Discovery sources:

- configured peer snapshot URLs;
- configured peer snapshot files;
- peer-sharing from seed/root peers when `peer-sharing=true`.

Address filters:

- `allow-private-addresses=false` rejects private/local addresses from
  discovery.
- `allowlist` and `denylist` can constrain accepted peers.

## Status Endpoint Fields

The node status endpoint exposes upstream fields such as:

- `upstreamMode`
- `upstreamConfiguredPeerCount`
- `upstreamHotPeerCount`
- `upstreamObserverPeerCount`
- `upstreamKnownPeerCount`
- `upstreamCandidateHeaderCount`
- `upstreamActivePeer`
- `upstreamTxForwarding`
- `upstreamDiscoveryRunning`
- `upstreamValidationLevel`
- `upstreamValidationAcceptedHeaders`
- `upstreamValidationRejectedHeaders`

`upstreamCandidateHeaderCount` is the number of candidate observations retained
in memory, not the number of unique blocks. Candidate observations are keyed by
`(peerId, blockHash)`.

## Practical Recommendations

- Use `trusted-single` for production indexers unless you need HA or relay-style
  behavior.
- Use `trusted-failover` before `static-multi` if the goal is only high
  availability.
- Use `static-multi` with known trusted peers for controlled multi-peer tests.
- Use `p2p-relay` with official peer snapshots for public-network relay testing.
- Keep `validation.level=none` unless you explicitly want header validation
  overhead.
- Keep `selection.quorum=2` or higher when any untrusted peers are involved.
- Leave `rollback-window-slots=0` unless there is a specific operational reason
  to override the genesis-derived value.

## Current Limitations

- Full relay-style mempool diffusion is not implemented.
- Parallel block body fetch across hot peers is not implemented.
- Full Cardano consensus validation is not implemented.
- Body validation framework exists, but the only built-in body level is `none`.
- Candidate headers are intentionally non-canonical and in memory only; restart
  clears them, and observers refill them after sync resumes from RocksDB.
