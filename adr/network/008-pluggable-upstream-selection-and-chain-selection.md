# ADR-NET-008: Pluggable Upstream Selection, Peer Selection, and Chain Selection

## Status

Accepted

## Implementation Status

Updated: 2026-06-29

| Phase | Status | Notes |
|---|---|---|
| 0 | Implemented | Added upstream status shape, canonical-write invariants in tests, and selected component names in code. |
| 1 | Implemented | Added `UpstreamConfig`, peer/config policy types, legacy `yano.remote.*` compatibility, and app config parsing. |
| 2 | Implemented | `SyncSubsystem` now exposes upstream status and uses the configured upstream peer list while preserving the current single-active path. |
| 3 | Implemented | Trusted failover rotates to the next configured peer after startup/recovery failure. |
| 4 | Implemented | Added `HeaderFanIn`, candidate header store, and observer `PeerSession` workers that feed candidate headers without mutating canonical state. |
| 5 | Implemented | Added trust/quorum chain-selection strategy and selected-upstream switching. Canonical adoption remains conservative: single untrusted candidates are observe-only. The rollback window is now derived from Shelley genesis as `ceil(k / activeSlotsCoeff)` slots unless explicitly overridden. |
| 6 | Implemented | Added `BodyFetchScheduler` seam and live upstream tx forwarding policies: `active-selected`, `all-hot-trusted`, and `disabled`. |
| 7 | Implemented | Added peer store, file-backed persistence for multi-peer/discovery modes, peer governor, observer fallback, and retry cooldown. |
| 8 | Implemented | Added live Yaci peer-sharing discovery, address hygiene, and official Cardano Operations Book peer-snapshot URL/file loading. |
| 9 | Implemented | Added optional Shelley+ header validation: structural header checks, header hash verification, KES signature verification, and operational-certificate cold signature verification. Added the body-validation API/config scaffold with `none` as the only default body preset. Byron validation remains future work. |

Implementation reports:

- `adr/reports/008-phase-0.md`
- `adr/reports/008-phase-1.md`
- `adr/reports/008-phase-2.md`
- `adr/reports/008-phase-3.md`
- `adr/reports/008-phase-4.md`
- `adr/reports/008-phase-5.md`
- `adr/reports/008-phase-6.md`
- `adr/reports/008-phase-7.md`
- `adr/reports/008-phase-8.md`
- `adr/reports/008-phase-9.md`
- `adr/reports/008a-discovery-first-bootstrap.md`

Final validation:

- `git diff --check`
- `./gradlew :core-api:test :runtime:test :app:test :testkit:test --console=plain`
- `./gradlew :runtime:test --tests "com.bloxbean.cardano.yano.runtime.sync.validation.BodyValidationPipelineTest" --tests "com.bloxbean.cardano.yano.runtime.BodyFetchManagerSimpleTest.customBodyValidatorCanRejectBeforeStorage" --tests "com.bloxbean.cardano.yano.runtime.assembly.YanoAssemblyTest" --tests "com.bloxbean.cardano.yano.runtime.config.UpstreamConfigTest" :app:test --tests "com.bloxbean.cardano.yano.app.YanoProducerTest" --console=plain`
- `./gradlew :app:test --tests "com.bloxbean.cardano.yano.app.YanoProducerTest" :runtime:test --tests "com.bloxbean.cardano.yano.runtime.sync.validation.ShelleyHeaderValidatorTest" --tests "com.bloxbean.cardano.yano.runtime.config.UpstreamConfigTest" --console=plain`
- `./gradlew :app:quarkusBuild --console=plain`
- `./gradlew :runtime:test --tests "com.bloxbean.cardano.yano.runtime.sync.multipeer.MultiPeerScaffoldingTest" --tests "com.bloxbean.cardano.yano.runtime.sync.SyncSubsystemTest" --tests "com.bloxbean.cardano.yano.runtime.config.UpstreamConfigTest" --console=plain`
- `./gradlew :runtime:test --console=plain`
- `./gradlew :app:haskellSyncTest --console=plain`

The latest Haskell sync run used dynamically allocated Yano n2n ports `62916`
and `62984`, avoiding local port `3001`. Past-time-travel sync ended with both
Yano and cardano-node at slot `4895`. Regular block-producer sync crossed the
epoch boundary and ended with both tips at slot `1200`.

Live preprod validation used `p2p-relay` mode with the official peer snapshot
from `https://book.play.dev.cardano.org/environments/preprod/peer-snapshot.json`.
The sampled status endpoint reported one active canonical peer, one observer
peer, 71 known peers, 217 candidate headers, discovery running, 85,881
processed blocks at local slot 1,803,783, and canonical body apply progress.
The file-backed peer store persisted the 71 discovered/snapshot peers.

Phase 9 live preprod header validation used legacy `yano.remote.*`
trusted-single mode with `-Dyano.upstream.validation.level=header-signature`
on local ports `7118`/`7119`. The sampled status endpoint reported
`upstreamValidationLevel=header-signature`, `upstreamValidationAcceptedHeaders=5455`,
`upstreamValidationRejectedHeaders=0`, and the active peer
`preprod-node.play.dev.cardano.org:3001`. This also verified that explicit
`yano.upstream.*` overrides are honored when peers are supplied through the
legacy remote compatibility path.

Discovery-first failover hardening was revalidated on preprod with the app
`p2p-relay` config. After the invalid configured peer and two failing snapshot
relays, Yano selected `132.226.203.38:6001`, found intersection, and processed
headers/bodies instead of looping back to the failed trusted configured peer.
Startup/dial failover now immediately attempts the next selected peer in the
serialized recovery executor, avoiding the supervisor's normal 30-60 second
cooldown/polling delay for this specific failure class.

The chain-selection rollback window is no longer hardcoded in app config by
default. If `yano.upstream.selection.rollback-window-slots` is omitted or set
to `0`, runtime derives it from Shelley genesis as
`ceil(securityParam / activeSlotsCoeff)`. For mainnet/preprod this is
`ceil(2160 / 0.05) = 43,200` slots; for preview it is
`ceil(432 / 0.05) = 8,640` slots; for a devnet with `k=100` and
`activeSlotsCoeff=1.0` it is `100` slots. Positive config values still override
the derived value.

## Date

2026-06-27

## Context

Yano currently has a new runtime shape based on explicit subsystems and a
`NodeKernel` startup order. In relay mode, the sync path still behaves as a
single trusted upstream client:

- `YanoConfig` exposes `remoteHost` and `remotePort`.
- `RuntimeNode` creates one `SyncSubsystem`.
- `SyncSubsystem` owns one `PeerSession`.
- `PeerSession` owns one Yaci `PeerClient`, one `HeaderSyncManager`, one
  `BodyFetchManager`, and one `LedgerApplyProcessor` generation.
- `HeaderSyncManager` stores received headers directly into canonical
  `ChainState`.
- `BodyFetchManager` fetches and applies block bodies from the same active peer.
- Rollback classification and recovery assume one upstream source.

That is acceptable for the current indexer and lightweight relay use case where
the configured upstream is trusted. It is not enough if Yano is moved toward a
relay category where it should connect to multiple upstream peers, survive peer
failure, compare competing upstream chains, optionally discover peers, and later
perform stronger validation.

The desired product shape is configurable:

- keep today's single static trusted upstream with no behavior change;
- allow multiple static upstream peers with failover or chain selection;
- allow multiple bootstrap/root peers that can seed initial connectivity;
- later allow automatic peer discovery and a fuller relay governor;
- allow block and header validation to be added later as an option;
- select these behaviors through configuration, not through forks of the
  runtime.

Existing ADRs already describe broader P2P relay work:

- `adr/009-yaci-archive-relay-governor-tx-gossip-and-storage-modes.md`
- `adr/017-real-p2p-relay-node-roadmap.md`
- `adr/network/002-network-peer-management-module.md`
- `adr/network/008a-relay-hardening-candidate-store-tx-diffusion-discovery-bootstrap.md`

This ADR narrows the decision to the runtime boundary and implementation plan
for pluggable upstream behavior. It supersedes the upstream-selection and
chain-selection portions of ADR-009 and ADR-017. Those documents remain useful
background for storage, transaction gossip, and broad relay roadmap concerns,
but implementation should use the terminology and phase ordering in this ADR
when the documents overlap.

Canonical component names:

- `PeerStore`
- `PeerPool`
- `PeerGovernor`
- `HeaderFanIn`
- `BodyFetchScheduler`
- `ChainSelectionStrategy`
- `CanonicalApplier`

Avoid introducing alternate names for these concepts in implementation.

## Decision Drivers

- Preserve current single-upstream trusted behavior by default.
- Keep Yaci `PeerClient` and `PeerSession` as single-peer workers.
- Add a strategy boundary above `PeerSession`, not inside Yaci mini-protocol
  code.
- Make upstream behavior swappable by config.
- Keep canonical chain writes single-writer and selected-chain only.
- Do not let unselected peer headers mutate canonical `ChainState`.
- Keep the design compatible with the current subsystem and kernel lifecycle.
- Treat validation and peer trust as chain-selection inputs, not optional
  decoration. Multi-peer selection must not be described as safer than a single
  trusted upstream until its trust or validation preconditions are met.
- Avoid implementing full Cardano Genesis/Praos validation in the first phase.

## Decision

Introduce an upstream-management layer owned by `SyncSubsystem`.

`SyncSubsystem` remains the runtime subsystem started by the kernel, but it will
delegate upstream behavior to an `UpstreamController`. The user-facing
configuration exposes presets, while implementation keeps two controller
families:

- `SingleActiveUpstreamController`
  - covers `trusted-single` and `trusted-failover`;
  - only one active sync peer mutates canonical state;
  - no multi-peer chain-selection claim.

- `MultiPeerUpstreamController`
  - covers `static-multi`, `rooted-relay`, and `p2p-relay`;
  - runs multiple peer sessions;
  - uses `HeaderFanIn`, candidate storage, `ChainSelectionStrategy`,
    `BodyFetchScheduler`, and `CanonicalApplier`;
  - is gated by trust, quorum, or validation policy before it can switch the
    canonical chain.

The initial presets are:

1. `trusted-single`
   - Current behavior.
   - One configured endpoint from `yano.remote.host` and `yano.remote.port`.
   - The upstream is authoritative.
   - Headers and bodies can continue to flow through the existing direct
     canonical path.

2. `trusted-failover`
   - Multiple configured endpoints.
   - Only one active sync peer at a time.
   - No chain selection across simultaneously active peers.
   - On failure, select the next eligible peer by priority, health, and
     cooldown.
   - This is the smallest step from today's model and is useful for indexers.

3. `static-multi`
   - Multiple static upstream peers can be active.
   - Peers submit candidate headers to `HeaderFanIn`.
   - A chain selection strategy chooses the selected candidate only when trust,
     quorum, or validation policy permits it.
   - Only selected headers and selected bodies are written to canonical
     `ChainState`.
   - With only structural validation, canonical rollback/adoption is allowed
     only from peers configured as trusted or from a configured quorum of
     mutually agreeing peers. A single untrusted longer candidate is not enough.
   - Bodies may initially be fetched from the selected peer only; body fan-out
     can follow after correctness is proven.

4. `rooted-relay`
   - Static root/bootstrap peers seed the peer store.
   - A governor maintains cold, warm, and hot peer sets.
   - Chain selection is the same interface as `static-multi`.
   - Discovery can still be disabled.

5. `p2p-relay`
   - Root peers plus peer-sharing or other discovery sources.
   - Persistent peer store, scoring, churn, allow/deny policy, and target peer
     counts.
   - Same chain selection and validation seams.

These presets share lower-level components where possible but make different
tradeoffs around connection count, trust, and chain selection. They are presets
over controller plus policy, not five unrelated implementations.

## Target Architecture

```text
RuntimeKernel
  |
  v
SyncSubsystem
  |
  +-- UpstreamControllerFactory
  |     selects implementation from config
  |
  +-- UpstreamController
        |
        +-- SingleActiveUpstreamController
        |     presets: trusted-single, trusted-failover
        |
        +-- MultiPeerUpstreamController
              presets: static-multi, rooted-relay, p2p-relay

Multi-peer modes:

PeerSource(s)
  static roots, bootstrap roots, discovered peers
        |
        v
PeerStore
  persistent known peers, source, score, backoff, state
        |
        v
PeerSelection / Governor
  cold -> warm -> hot, priority, cooldown, limits
        |
        v
PeerSession[N]
  one Yaci PeerClient per peer
        |
        v
HeaderFanIn
  candidate headers, per-peer tips, no canonical mutation
        |
        v
ChainSelectionStrategy
  selected candidate, rollback/adoption decision
        |
        v
CanonicalApplier
  selected headers, selected bodies, rollbacks, events
        |
        v
ChainState, LedgerApplyProcessor, derived stores, server notification
```

## Subsystem Boundaries

### `SyncSubsystem`

Owns:

- runtime lifecycle integration with `NodeKernel`;
- sync phase and startup/shutdown coordination;
- creation of the configured `UpstreamController`;
- compatibility APIs used by the rest of the runtime:
  - current sync status,
  - current peer status or peer summary,
  - transaction forwarding,
  - current header/body managers where still needed during migration.

It should stop directly owning a single `peerSession` once multiple modes exist.
Instead it should own an `UpstreamController`.

### `UpstreamController`

New runtime-local interface:

```java
public interface UpstreamController extends AutoCloseable {
    String mode();
    void start();
    void stop();
    void submitTxBytes(String txHash, byte[] txCbor, TxBodyType txBodyType);
    UpstreamStatus status();
    SubsystemHealth health();
}
```

Implementations:

- `SingleActiveUpstreamController`
- `MultiPeerUpstreamController`

The first implementation can wrap today's `SyncSubsystem` peer-session behavior.
Later implementations can reuse `PeerSession` but replace the header/body intake
path.

### `PeerSession`

Remains a single-peer worker:

- one endpoint;
- one Yaci `PeerClient`;
- lifecycle, health, recovery state for that peer;
- optional ChainSync and BlockFetch workers depending on mode.

`PeerSession` should not perform chain selection. In multi-peer mode it should
publish peer-scoped signals to an intake component.

### `CanonicalApplier`

New abstraction that serializes selected-chain side effects:

- canonical header adoption;
- canonical block body apply;
- rollback event publishing;
- derived-store rollback coordination;
- server notification;
- sync progress accounting;
- `LedgerApplyProcessor` generation close/reopen during selected-chain rollback
  or peer replacement.

This keeps the invariant that only one component writes the selected chain.
The existing trusted-single path can initially use the current
`HeaderSyncManager` and `BodyFetchManager` directly, but multi-peer modes must
go through `CanonicalApplier`.

### `CandidateHeaderStore`

Multi-peer modes need a non-canonical store for peer headers:

- keyed by peer id and block hash;
- records slot, block number, previous hash, era, original header bytes, source
  peer, and received time;
- maintains per-peer tip;
- prunes candidate data behind the rollback horizon and for banned peers.

Candidate headers must not be written to `ChainState.headers`,
`slot_to_hash`, `number_by_slot`, or `header_tip` until selected.

The candidate store should be separate from canonical chain RocksDB column
families. It may share the same physical RocksDB instance only if it uses
separate column families and can be dropped or rebuilt without corrupting the
selected chain. The default implementation should be ephemeral or separately
namespaced until operational retention needs are clear.

### `ChainSelectionStrategy`

New pluggable strategy:

```java
public interface ChainSelectionStrategy {
    ChainSelectionDecision evaluate(ChainSelectionContext context);
}
```

Initial strategy: `TrustedOrQuorumCandidateWithinRollbackWindow`.

Rules:

- candidate must intersect selected chain within the rollback horizon. By
  default this is derived from Shelley genesis as `ceil(k / activeSlotsCoeff)`
  slots so the retained slot range covers roughly `k` expected blocks;
- candidate must have continuous headers back to the intersection;
- candidate eligibility is evaluated against peer trust, validation level, and
  configured quorum policy;
- a candidate from a trusted peer with higher block number can be selected after
  body availability or fetch eligibility checks;
- a candidate from an untrusted peer cannot force canonical rollback only
  because it is longer. It must either satisfy `consensus-lite` or stronger
  validation, or meet a configured quorum/agreement rule;
- equal-length candidates in public relay mode must use a deterministic,
  consensus-compatible tie-break when that validation data is available. The
  `keep-current` tie-break is only acceptable for `trusted-single` and
  `trusted-failover` style high-availability operation, not for claiming public
  relay convergence;
- selected body hash must match the selected header;
- no claim of full Praos validation.

Future strategies can add:

- header issuer verification;
- operational certificate checks;
- KES period checks;
- VRF and leader eligibility checks;
- nonce and ledger view integration;
- Genesis-mode bootstrap judgement;
- policy requiring agreement from multiple peers.

### `ValidationPipeline`

Validation is a seam, not a first-phase requirement for `trusted-single` or
`trusted-failover`. For untrusted multi-peer canonical selection, validation or
quorum is a precondition for rollback/adoption.

```text
Candidate header received
  -> structural decode and continuity checks
  -> optional Shelley+ header signature validation
  -> candidate store
  -> chain selection
  -> block fetch
  -> body hash check
  -> optional block/body validation
  -> canonical apply
```

Initial validation levels:

- `none`: trusted mode, current behavior. This is the default for header and
  body validation.
- `structural`: for Shelley and later headers, decode the header CBOR, verify
  the decoded fields match the original header bytes, verify the block/header
  hash, and enforce required field sizes for issuer keys, VRF material,
  operational certificate material, body hash, and KES signature material. Byron
  validation is not added in phase 9.
- `header-signature`: all `structural` checks plus Shelley+ KES signature
  verification over the serialized header body and operational-certificate cold
  signature verification over `kesVkey || counter || kesPeriod`.
- `consensus-lite`: future VRF proof, leader eligibility, stake distribution,
  overlay schedule, and consensus chain-selection checks.
- `full`: future ledger-integrated validation target.

Body validation is intentionally scaffolded but disabled in phase 9:

- `yano.upstream.validation.body-level=none` is the only built-in body preset.
- `BodyValidator`, `BodyValidationContext`, `BodyValidationResult`, and
  `BodyValidationPipeline` are available for library embedders.
- `YanoAssembly.bodyValidation(...)` can install custom body validators.
- Future body presets can add body hash checks, body size/count checks, witness
  shape checks, transaction checks, and ledger-integrated block validation
  without changing the public assembly boundary.

Unsupported validation levels must fail fast. They must not silently downgrade
to `structural`.

`header-signature` is not enough to treat a single untrusted peer as canonical
authority. It proves that the header was signed by the operational key certified
by the issuer key, but it does not prove that the issuer was eligible to lead
the slot or that the block body and ledger transition are valid.

Required validation coverage for a full Cardano node:

- Network and envelope validation: network magic, chain-sync era/header type,
  CBOR well-formedness/canonical encoding where required, and configured era
  boundaries.
- Header structure: block number, slot, previous-hash continuity, header hash,
  protocol version, maximum header size, and rollback-window constraints.
- Shelley+ header cryptography: KES signature over the header body, KES period
  and max-evolution checks, operational-certificate cold signature and counter
  checks, issuer key/hash checks, VRF proof verification, nonce VRF/leader VRF
  input construction, and VRF output matching.
- Consensus and leader eligibility: active slot coefficient, stake snapshot and
  pool stake fraction, leader value threshold, decentralization/overlay schedule
  for early Shelley eras, Praos/Genesis chain-selection rules, density/tie-break
  rules, common-prefix/finality bound `k`, and hard-fork transition rules.
- Block body integrity: block body hash, body size, transaction count/shape,
  witness set shape, auxiliary data hash, invalid transaction indexes, and era
  specific body layout.
- Transaction ledger rules: UTxO consumption/production, fees and minimum ADA,
  validity interval, collateral and reference inputs, native scripts, Plutus
  scripts/ex-unit budgets/cost models, datums/redeemers, minting/burning,
  withdrawals, certificates, deposits/refunds, metadata, and governance actions.
- Ledger state transitions: delegation and stake-pool state, stake snapshots,
  rewards, epoch boundary processing, nonce evolution, protocol parameter
  updates, treasury/reserves, DRep/committee/governance state, and hard-fork
  ledger-state migration.
- Mempool and relay policy: transaction admission against current ledger state,
  duplicate/replay rejection, size/ex-unit limits, and forwarding policy.
- Persistence/replay validation: durable header/body continuity, rollback
  recovery, snapshot/replay consistency, and deterministic re-application of
  ledger transitions after restart.

## Configuration

Keep existing config valid:

```yaml
yano:
  client:
    enabled: true
  remote:
    host: preprod-node.world.dev.cardano.org
    port: 30000
    protocol-magic: 1
```

This maps to:

```yaml
yano:
  upstream:
    mode: trusted-single
```

New config:

```yaml
yano:
  upstream:
    mode: trusted-single # preset: trusted-single | trusted-failover | static-multi | rooted-relay | p2p-relay

    peers:
      - id: relay-1
        host: relay-1.example.com
        port: 3001
        source: local-root
        priority: 10
        trust: trusted
      - id: relay-2
        host: relay-2.example.com
        port: 3001
        source: local-root
        priority: 20
        trust: trusted

    selection:
      policy: trusted-or-quorum-within-rollback-window
      # Optional. Omit or set to 0 to derive ceil(k / activeSlotsCoeff)
      # from Shelley genesis.
      rollback-window-slots: 0
      require-body-before-adoption: true
      trust-policy: trusted-only # trusted-only | quorum | validated
      quorum: 2
      tie-break: deterministic # deterministic | keep-current-ha-only

    validation:
      level: none      # header validation: none | structural | header-signature
      body-level: none # body validation: none

    sync:
      bulk-source: single-trusted # single-trusted | selected-candidate
      fan-in-start: near-tip       # disabled | near-tip | always

    failover:
      cooldown-ms: 30000
      max-failures-before-cooldown: 3

    tx:
      forwarding: active-selected # active-selected | all-hot-trusted | disabled

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
      seeds: []
      peer-snapshot-urls: []
      peer-snapshot-files: []
      peer-snapshot-limit: 128
      allow-private-addresses: false
      allowlist: []
      denylist: []
```

Compatibility rules:

- If `yano.upstream.mode` is absent and `yano.remote.host` is present, use
  `trusted-single`.
- If `yano.upstream.peers` is absent in `trusted-single`, synthesize one peer
  from `yano.remote.*`.
- `yano.remote.*` remains supported until a major-version deprecation decision.
- `trusted-single` must not require candidate stores, peer stores, or discovery.
- `enableClient=false` disables upstream controllers as it disables sync today.
- Multi-peer presets with `validation.level=structural` or
  `validation.level=header-signature` must either use `trust-policy=trusted-only`
  with trusted peers, or a configured quorum rule. A single untrusted peer must
  not be allowed to drive canonical rollback.
- `tie-break=keep-current-ha-only` is valid only for single-active
  high-availability presets.
- In `p2p-relay` mode, if discovery is enabled and no explicit
  `peer-snapshot-urls` are configured, known public networks auto-load the
  corresponding Cardano Operations Book peer snapshot for `mainnet`,
  `preprod`, or `preview`.

## Options Considered

### Option A: Extend Current `SyncSubsystem` With Lists

Add `List<PeerSession>` directly to `SyncSubsystem`.

Pros:

- smallest short-term code movement;
- easy to understand from current code.

Cons:

- `SyncSubsystem` becomes a large mode switch;
- trusted single, failover, and full relay concerns mix together;
- hard to keep candidate and canonical write rules clear;
- difficult for embedders to add custom upstream behavior.

Rejected.

### Option B: Create A Separate Gradle Network Module Now

Move peer selection, peer store, discovery, and chain selection into a new
module immediately.

Pros:

- clearer packaging;
- reusable by future tools.

Cons:

- current peer lifecycle depends heavily on runtime chain state, event bus,
  rollback, and ledger apply code;
- premature module extraction would either leak runtime details or force broad
  refactoring before behavior is proven.

Rejected for the first implementation. Revisit after multi-peer mode stabilizes.

### Option C: Make Yaci `PeerClient` Multi-Peer

Change the Yaci helper layer so one client handles many peer sessions.

Pros:

- lower-level abstraction could be reusable.

Cons:

- chain selection and canonical application are Yano runtime concerns;
- mini-protocol worker code should remain one connection/session at a time;
- larger risk to the proven single-peer path.

Rejected.

### Option D: Add Pluggable Upstream Controllers Above `PeerSession`

Keep Yaci and `PeerSession` single-peer. Add a configurable controller layer
that can run one peer, fail over across peers, or manage multiple active peers
with candidate chain selection.

Pros:

- preserves current behavior;
- matches the subsystem design;
- gives clear mode-specific implementations;
- allows staged rollout;
- keeps future validation as a pipeline seam;
- allows embedders to provide custom upstream controllers later.

Accepted.

## Implementation Plan

### Phase 0: Document Invariants And Status Model

Scope:

- Add invariant tests or design assertions for:
  - one `CanonicalApplier`;
  - unselected headers do not mutate canonical `ChainState`;
  - body hash matches selected header before apply;
  - rollback events are emitted only for selected-chain rollback;
  - selected-chain rollback closes or fences the current `LedgerApplyProcessor`
    generation before replacement work can apply;
  - existing `trusted-single` status remains compatible.
- Introduce `UpstreamStatus` with fields that can represent one or many peers.
- Align names in code and docs with this ADR:
  - `HeaderFanIn`, not generic "header intake";
  - `CanonicalApplier`, not `CanonicalSyncWriter`;
  - `BodyFetchScheduler`, not mode-local body fetcher names.

Acceptance:

- Current single-upstream tests still pass.
- Existing REST status can keep old fields while adding optional upstream mode
  and peer summary fields.

### Phase 1: Config Types And Compatibility Adapter

Scope:

- Add `UpstreamConfig`, `UpstreamPeerConfig`, `UpstreamPreset`,
  `ChainSelectionConfig`, `UpstreamValidationConfig`, and `GovernorConfig`.
- Map current `remoteHost`, `remotePort`, and `protocolMagic` to a synthetic
  `trusted-single` peer.
- Extend app config binding in `YanoProducer`.
- Add validation rules per mode.

Acceptance:

- Existing `application.yml` works unchanged.
- New `trusted-single` config works without `yano.remote.host`.
- Invalid combinations fail fast:
  - multi-peer mode with no peers and discovery disabled;
  - p2p discovery enabled without a persistent store if required;
  - validation level `full` before implementation.

### Phase 2: Introduce `UpstreamController` And Wrap Current Behavior

Scope:

- Move current single-peer start/stop/recovery path behind
  `SingleActiveUpstreamController`.
- Make `SyncSubsystem` delegate to the controller.
- Keep direct `HeaderSyncManager` and `BodyFetchManager` use for
  `trusted-single`.
- Preserve transaction forwarding to the active peer.

Acceptance:

- No behavior change in default relay/indexer mode.
- Current `SyncSubsystemTest`, peer recovery tests, and pipeline tests pass.
- Startup and shutdown lifecycle remains under `RuntimeKernelStages`.

### Phase 3: Trusted Failover

Scope:

- Extend `SingleActiveUpstreamController` for `trusted-failover`.
- Add `PeerRegistry` and mode-local peer health map.
- Only one active sync peer at a time.
- Reuse current `PeerSessionSupervisor` per active peer.
- On terminal or stale peer failure, choose next eligible peer by priority and
  cooldown.

Acceptance:

- Single configured peer behaves like `trusted-single`.
- With two static peers, failure of peer A starts peer B from durable local
  cursors.
- No chain selection claim is made because only one peer is active.
- This should be the first shippable non-current behavior because it gives
  relay/indexer high availability without new chain-selection risk.

### Phase 4: `HeaderFanIn` For Multi-Peer

Scope:

- Add `HeaderFanIn` that accepts peer-scoped candidate headers and does not
  write canonical `ChainState`.
- Add `CandidateHeaderStore` and `PeerTipStore`.
- Add a multi-peer ChainSync listener path beside the current direct
  `HeaderSyncManager` path.
- Keep bulk sync on one trusted/bootstrap source by default. Candidate fan-in
  should initially start near tip, where rollback depth and candidate storage
  are bounded.
- Keep body fetching initially selected-peer only.

Acceptance:

- Running two peers stores candidate headers separately.
- Canonical `header_tip` does not advance until chain selection adopts headers.
- Candidate pruning works behind rollback horizon.
- Full-history catch-up does not require holding candidate headers from many
  peers unless explicitly configured.

### Phase 5: Chain Selection And Canonical Header Adoption

Scope:

- Add `ChainSelectionStrategy` and first
  `TrustedOrQuorumCandidateWithinRollbackWindow` implementation.
- Add `CanonicalApplier` for selected header adoption and selected-chain
  rollback.
- Adopt selected headers into canonical `ChainState` in order.
- Map chain-selection rollback to the existing `LedgerApplyProcessor`
  generation model:
  - quiesce or close the current generation;
  - rollback canonical state and derived stores;
  - open a new generation before applying replacement bodies.
- Preserve backpressure semantics for body apply.
- Reject or observe-only any single untrusted longer candidate unless
  consensus-lite validation or quorum policy authorizes adoption.

Acceptance:

- Static multi-peer mode follows the best eligible trusted, validated, or
  quorum-backed candidate.
- Equal-length candidates in relay mode use deterministic tie-breaking once the
  required header data is available. Without that support, equal-length forks
  are observed but not advertised as full relay convergence.
- Fork inside rollback window triggers one canonical rollback and replay.
- Fork outside rollback window is rejected or marked operator-action-required.

### Phase 6: Body Fetch Scheduling

Scope:

- Add `BodyFetchScheduler` for selected-chain gaps.
- Start with selected-peer fetching, then allow healthy hot peers that advertise
  the selected blocks to serve body ranges.
- Add retry/failover and stale body rejection.
- Apply `upstream.tx.forwarding` policy for transaction forwarding:
  - `active-selected`: forward to the current selected/active sync peer;
  - `all-hot-trusted`: forward to trusted hot peers only;
  - `disabled`: do not forward upstream.

Acceptance:

- Body fetch can recover when selected peer fails but another hot peer has the
  selected range.
- Block body hash must match selected header before canonical apply.
- Existing ordered `LedgerApplyProcessor` generation rules still fence stale
  work.
- Transaction forwarding target is deterministic and visible in status.

### Phase 7: Rooted Relay And Governor

Scope:

- Add `PeerStore` persistence.
- Add root/bootstrap peer loading.
- Add cold/warm/hot peer states.
- Add `PeerGovernor` with targets, backoff, score, churn, and connection caps.
- Keep discovery disabled by default.

Acceptance:

- Rooted relay maintains configured warm/hot targets from static roots.
- Peer failures update store and backoff state.
- Restart preserves known peer metadata enough to avoid starting from only the
  static configured roots.

### Phase 8: Discovery

Scope:

- Add Yaci peer-sharing client integration.
- Add official peer-snapshot loading from URL and local file sources.
- Add address hygiene:
  - protocol magic match;
  - allowlist and denylist;
  - private address policy;
  - per-subnet limits where practical.
- Persist discovered peers through the peer store.

Acceptance:

- `p2p-relay` learns peers from root peers, peer-sharing, and optional peer
  snapshots.
- Discovered peers can become warm/hot through governor policy.
- Discovery can be disabled while keeping rooted relay behavior.

### Phase 9: Optional Validation

Scope:

- Add `ValidationPipeline` stages for Shelley and later headers:
  - `none`: skip upstream header validation;
  - `structural`: validate header CBOR shape, decoded field consistency,
    header hash, required key/hash/signature material sizes, and KES period
    bounds shape;
  - `header-signature`: run `structural`, then verify the Shelley+ KES
    signature over the serialized header body and the operational-certificate
    cold signature.
- Wire validation into the selected canonical peer path and observer fan-in.
- Expose validation level, accepted header count, rejected header count, and
  latest rejection stage/reason in upstream status.
- Leave Byron header cryptographic validation, VRF proof validation, leader
  eligibility, body validation, and ledger validation as future stages.

Acceptance:

- `validation.level=structural` rejects malformed Shelley+ header bytes and
  header-hash mismatches before canonical storage or candidate fan-in.
- `validation.level=header-signature` rejects bad Shelley+ KES signatures and
  bad operational-certificate cold signatures.
- Unsupported levels fail fast rather than silently downgrading.
- Rejected headers are visible in upstream status and logs.

## Testing Strategy

- Unit tests:
  - config compatibility mapping;
  - peer selection priority and cooldown;
  - candidate header continuity;
  - chain selection tie-breaking;
  - `CanonicalApplier` single-writer behavior;
  - validation pipeline decisions.

- Integration tests:
  - existing single-upstream sync path unchanged;
  - trusted failover with a TCP fault proxy;
  - static multi-peer with two controlled devnet peers and an injected fork;
  - body fetch failover from selected chain;
  - rollback within and beyond configured rollback window.

- Soak tests:
  - preprod/mainnet trusted-single baseline;
  - static-multi observation mode with two or more public relays;
  - static-multi canonical adoption with trusted devnet peers or a configured
    quorum;
  - restart during header catch-up;
  - kill -9 during body apply and recovery from durable cursors.

## Operational Observability

Expose at least:

- upstream mode;
- configured peer count;
- connected warm and hot peer count;
- selected peer or selected candidate;
- per-peer tip, lag, state, failure count, cooldown deadline;
- candidate adoption count;
- selected-chain rollback count;
- body fetch retry/failover count;
- validation rejection count by reason;
- terminal upstream failure reason.

REST should keep existing fields and add multi-peer fields as optional sections.

## Migration And Compatibility

- Default mode remains `trusted-single`.
- Existing `yano.remote.*` properties continue to work.
- Existing indexer deployments do not need candidate stores or peer stores.
- Multi-peer modes are opt-in. Canonical adoption from untrusted peers should
  remain experimental until validation or quorum policy is implemented and
  tested.
- Full P2P relay mode should not be presented as trustless until validation and
  Genesis/Praos security assumptions are explicit.

## Risks And Mitigations

- Risk: unselected peer mutates canonical state.
  - Mitigation: candidate store and `CanonicalApplier` boundary; tests that
    assert canonical tips do not move on candidate intake.

- Risk: chain selection causes excessive rollback churn.
  - Mitigation: deterministic relay tie-break when available, minimum
    superiority rule, rollback window checks, adoption metrics, and
    observe-only behavior when policy preconditions are not met.

- Risk: multi-peer refactor regresses trusted single mode.
  - Mitigation: keep trusted-single wrapper first, preserve current direct path,
    run existing recovery and sync tests in every phase.

- Risk: body fetch from a non-selected peer returns stale or wrong body.
  - Mitigation: body hash check against selected header before apply.

- Risk: discovery dials bad or private addresses.
  - Mitigation: address hygiene, allow/deny lists, private-address policy,
    backoff and ban states.

- Risk: users assume full Cardano consensus validation.
  - Mitigation: explicit validation levels and documentation; unsupported full
    validation fails fast.

## Open Questions

- Should `CandidateHeaderStore` use separate column families in the existing
  RocksDB process, or a separate peer-state database? In either case it must not
  share canonical chain column families.
- What is the default rollback window for each public network when no explicit
  value is configured?
- Should `trusted-failover` keep standby peers cold or maintain warm keepalive
  connections for faster failover?
- When body fan-out is enabled, should only peers on the selected candidate be
  eligible, or can any peer serve a body if the hash matches?
- What public API should embedders use to provide a custom
  `UpstreamController`?
