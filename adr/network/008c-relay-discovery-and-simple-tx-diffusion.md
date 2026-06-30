# ADR-NET-008C: Relay Discovery MVP and Simple Transaction Diffusion

## Status

Accepted / MVP implementation complete

## Date

2026-06-30

## Implementation Status

- 2026-06-30: Implemented Yaci server-side peer-sharing support and handshake
  negotiation.
- 2026-06-30: Implemented Yano relay config, server-side peer-sharing wiring,
  peer-sharing provider, simplified tx diffusion config, and relay status
  fields.
- 2026-06-30: Verified with focused unit tests, app producer tests,
  `:app:quarkusBuild`, and a live preprod Haskell downstream node.
- 2026-06-30: Live test confirmed Haskell connected inbound to Yano,
  negotiated peer sharing, diffused a submitted transaction to Yano, and a Yaci
  peer-sharing client received a bounded peer list from Yano.

## Context

ADR-NET-008 introduced pluggable upstream selection. ADR-NET-008B introduced
transaction diffusion and mempool relay primitives. The 008B design intentionally
kept several policy modes:

- `disabled`
- `local-submit-only`
- `trusted-hot`
- `all-hot`

Those modes are useful internally, but they are more policy surface than the
first relay-style operator experience needs.

For the MVP, Yano should behave more like a simple relay:

1. Any connected Cardano node can participate in inbound tx diffusion when tx
   diffusion is enabled.
2. Yano can ask upstream peers for peer-sharing information.
3. Yano can answer peer-sharing requests from inbound peers with its advertised
   endpoint and known upstream peers.
4. Operators should only need a small amount of configuration.

This does not mean that a node becomes globally discoverable by setting one
flag. Public discoverability still requires a routable advertised endpoint and
at least one peer that learns and advertises Yano through the Cardano peer
sharing graph. A local Haskell node with `advertise: false` in its topology can
connect to Yano, but it will not advertise Yano to the wider network.

## Decision

Use a simple tx diffusion switch for the MVP:

```yaml
yano:
  tx:
    diffusion:
      enabled: true
```

`enabled: true` maps to the existing `all-hot` diffusion behavior. Any connected
N2N peer whose TxSubmission protocol announces unknown tx ids can be asked for
transaction bodies, and accepted txs enter the existing single
`TransactionAdmission` path.

Keep `yano.tx.diffusion.mode` as a compatibility setting for tests, old
profiles, and advanced experiments. If both are present, `mode` remains
authoritative because it is the more explicit legacy/advanced setting.

Add a separate relay discovery switch:

```yaml
yano:
  relay:
    auto-discovery: false
    advertised-host: auto
    advertised-port: 0
    allow-private-addresses: false
```

When `yano.relay.auto-discovery=true`:

- Yano enables upstream discovery even if `yano.upstream.discovery.enabled` is
  omitted.
- Yano enables upstream peer-sharing requests even if
  `yano.upstream.discovery.peer-sharing` is omitted.
- Yano advertises peer-sharing support in the N2N server handshake.
- Yano installs a peer-sharing server agent for each inbound N2N session.
- Yano answers peer-sharing requests with a bounded list built from:
  - the configured advertised endpoint, if present and allowed;
  - an auto-resolved local interface endpoint when `advertised-host` is blank
    or `auto`;
  - the current upstream peer store.

`allow-private-addresses` exists for local and lab tests. Public relay
configuration should leave it disabled and advertise a routable IP or DNS name.
Auto-resolution does not provide NAT traversal. It only chooses an address to
publish through peer sharing.

## Configuration

Minimal public preprod relay-style profile:

```yaml
yano:
  network: preprod
  server:
    enabled: true
    port: 13338

  upstream:
    mode: p2p-relay
    discovery:
      enabled: true
      peer-snapshot-urls:
        - https://book.play.dev.cardano.org/environments/preprod/peer-snapshot.json

  relay:
    auto-discovery: true
    advertised-host: "<public-host-or-ip>" # or "auto" for local/lab use
    advertised-port: 13338

  tx:
    diffusion:
      enabled: true
```

Minimal local Haskell-node test profile:

```yaml
yano:
  server:
    enabled: true
    port: 13338

  relay:
    auto-discovery: true
    advertised-host: auto
    advertised-port: 13338
    allow-private-addresses: true

  tx:
    diffusion:
      enabled: true
```

The local test profile is intentionally not a public relay profile. Other public
nodes cannot dial private or loopback addresses on the operator's machine.

## Implementation

Yaci protocol support:

- Server-side handshake negotiation now preserves peer-sharing support when both
  sides request/support it instead of always responding with `peerSharing=0`.
- `PeerSharingServerAgent` implements the server side of the peer-sharing
  mini-protocol. It is policy-free: it clamps request size, asks a provider for
  peers, and emits `MsgSharePeers`.

Yano runtime support:

- `YanoPropertyKeys.Relay` defines the relay discovery config keys.
- `YanoPropertyKeys.Tx.DIFFUSION_ENABLED` defines the simplified tx diffusion
  switch.
- `YanoProducer` maps `relay.auto-discovery=true` to upstream discovery and
  upstream peer-sharing requests.
- `TxSubsystem` defaults tx diffusion to `all-hot` when
  `yano.tx.diffusion.enabled` is not explicitly disabled and no legacy mode is
  configured.
- `ServeSubsystem` advertises peer sharing and installs
  `PeerSharingServerAgent` when relay auto-discovery is enabled.
- `RelayPeerSharingProvider` builds bounded peer-sharing responses from the
  advertised or auto-resolved endpoint and current peer store, applying the
  same private/public address policy used by upstream discovery.
- `SyncSubsystem` exposes a read-only snapshot of its peer store to the server
  side provider.

## Invariants

- Tx diffusion never writes directly to the mempool.
- Every tx accepted from local submit, N2C, N2N, or future relay paths goes
  through `TransactionAdmission`.
- The existing `TransactionValidateEvent` chain remains the tx validation and
  plugin extension point.
- Peer-sharing responses are bounded by the peer-sharing request amount.
- Private or loopback addresses are not advertised unless explicitly enabled.

## Non-Goals

- No public NAT traversal.
- No guarantee that enabling `relay.auto-discovery` alone makes a local node
  reachable or known by the wider Cardano network.
- No ledger registration or stake-pool relay metadata integration.
- No new chain-selection or block-validation behavior.
- No replacement of the existing upstream peer governor.
- No class/trust-specific tx diffusion policy in the MVP operator config.

## Operational Notes

For public relay testing:

- Set `relay.advertised-host` to a routable DNS name or public IP. `auto` can
  help local tests but does not prove public reachability.
- Open and forward the advertised N2N port.
- Use at least one upstream or local-root peer that can learn and advertise
  Yano through peer sharing.
- Keep `relay.allow-private-addresses=false`.

For local testing with `/Users/satya/work/cardano-node/preprod`:

- Yano can auto-advertise a local interface endpoint when
  `relay.advertised-host` is blank or `auto`.
- The Haskell node topology can include Yano as a local root.
- To test whether the Haskell node advertises Yano further, its local root entry
  must use `advertise: true`; with `advertise: false`, the Haskell node connects
  to Yano but should not diffuse Yano as a root.

## Verification Plan

Unit/integration checks:

- Yaci handshake negotiates `peerSharing=1` only when both sides support it.
- Yaci peer-sharing server clamps request sizes and returns at most the
  requested number of peers.
- Yano relay peer-sharing provider filters private addresses by default,
  includes loopback only when explicitly allowed, suppresses duplicates, and
  respects request bounds.
- Existing tx diffusion tests continue to pass with `enabled=true` default and
  legacy `mode` compatibility.
- App producer tests confirm legacy `mode` and upstream forwarding compatibility.

Live preprod checks:

- Build `:app:quarkusBuild`.
- Start Yano from `app/` with `config/application.yml`.
- Start/restart the Haskell preprod node in
  `/Users/satya/work/cardano-node/preprod`.
- Confirm the Haskell node connects inbound to Yano.
- Confirm Yano server handshake negotiates peer sharing when the Haskell node
  requests it.
- Submit a simple preprod transaction to the local Haskell node.
- Confirm Yano accepts the diffused tx through N2N TxSubmission and status
  increments `txDiffusionInboundAccepted`.

## Consequences

The operator-facing relay MVP becomes smaller and easier to explain:

- `tx.diffusion.enabled=true` means "accept tx diffusion from connected peers."
- `relay.auto-discovery=true` means "participate in peer-sharing as a relay."

The advanced 008B modes remain in code as compatibility and test seams, but they
are not the primary MVP configuration surface.
