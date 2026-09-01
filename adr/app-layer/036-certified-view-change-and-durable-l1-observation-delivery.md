# ADR-036: Quorum-Certified View Change and Durable L1 Observation Delivery

## Status

Accepted — implemented as a fresh-chain preview cutover on this branch; final
graduation still depends on the Phase 5 multi-node qualification gates.

The number is local to the `adr/app-layer` series. Root-level ADR numbers are a
separate series.

## Date

2026-09-01

## Context and related decisions

- [Yano issue #85](https://github.com/bloxbean/yano/issues/85) reports a real
  five-member, four-of-five Preprod stall and a stable deposit observation that
  expired before app-chain finality.
- The downstream Yano X analysis is
  `/Users/satya/work/bloxbean/yano-x/adr/041-trust-preserving-l1-observation-delivery-and-consensus-recovery.md`.
- [ADR-005](005-yano-app-chain-framework.md) defines threshold finality and the
  original sequencing choices.
- [ADR-008.2](008.2-rotating-sequencer.md) deliberately retained permanent
  one-vote-per-height locks and documented split-lock stalls as a residual risk.
- [ADR-008.3](008.3-chain-governed-membership.md) defines height-versioned
  membership.
- [ADR-008.4](008.4-script-anchors-l1view.md) defines follower-verified L1
  observations and stability gating.
- [ADR-016](016-authenticated-appchain-consensus-profile-and-typed-runtime-limits.md)
  authenticates the current deterministic runtime profile.
- [ADR-027](027-l1-rollback-safety-and-l1-state-observations.md) analyzes L1
  rollback and historical observation safety.

## 0. Decision summary

Yano will treat consensus recovery and delivery of consensus-critical L1 facts
as one protocol boundary. A recovered consensus round is not useful if the L1
fact that was waiting for it has disappeared.

The next app-chain protocol will provide:

1. a framework-owned, two-phase `PREPARE -> COMMIT` protocol at every
   `(height, view)`;
2. quorum-certified view change carrying the highest prepared certificate;
3. deterministic leaders derived only from committed chain state, view, and
   active membership—not from each node's newest L1 tip;
4. explicit quorum-intersection and Byzantine-fault assumptions validated in
   the consensus profile;
5. a per-validator durable observation journal and a first-class, non-expiring
   system-input lane in app blocks;
6. deterministic observation identity, order, mandatory prefix inclusion,
   finalized cursors, and atomic acknowledgement;
7. fail-closed live and recovery verification when local L1 evidence is
   unavailable; and
8. a fresh-chain-only activation path while Yano remains in preview.

The existing `admin/unlock-stale-round` endpoint is not part of protocol v2.
A timeout will never delete a safety lock. An operator may request
reconciliation of an L1 pointer but may
never provide an authoritative `~l1/*` claim.

Fixed sequencing remains the production default until the protocol and the
five-node qualification in this ADR are complete.

## 1. Problem and confirmed current behavior

### 1.1 Consensus can spend the available votes on competing blocks

The current consensus wire format has:

```text
vote = [height, blockHash, signature(blockHash)]
finality certificate = threshold signatures over blockHash
```

`AppBlock` v2, `ConsensusCodec`, the vote-lock keys in `AppLedgerStore`, and
the signed data contain no view. A member durably votes at most once per
height. That is a useful safety rule, but after correct nodes lock different
valid candidates there is no certified higher view in which they may safely
vote again.

ADR-008.2's adoption and re-gossip rules improve the common case. They cannot
recover a height after enough locks are split that no candidate can reach the
threshold. Restart correctly preserves the locks. Timer-based deletion would
restore liveness by discarding the evidence that protects safety.

Rotating sequencing makes the race observable because proposer eligibility is
calculated from each member's newest locally observed L1 slot. Around a window
boundary, correct geographically separated members can temporarily accept
different proposer views. A larger window reduces the probability but cannot
eliminate partitions, delayed L1 propagation, or partial-round failure.

### 1.2 Stable block observations can be silently lost

`L1ObservationService` currently maintains:

- a bounded in-memory follower-verification window; and
- an in-memory `pendingInjection` map capped by that same window size.

When an observation becomes stability-deep, every member removes it from
`pendingInjection`. Only the member that locally considers itself the proposer
then builds an ordinary member-signed app message. `injectObservation` puts the
message in the ordinary bounded pool on a best-effort basis. A full pool drops
it, restart loses the pending map, and the message is subject to ordinary TTL
expiry. No durable acknowledgement links the observation to finalization.

The epoch-observation spool already demonstrates a more durable lifecycle, but
the generic block-scoped path does not use it.

### 1.3 Inclusion verification is not completeness verification

A follower verifies a proposed observation that is present. It does not prove
that the proposer included the next stable observation or preserved a common
order. A proposer can omit an observation without making another included
observation invalid.

For a live proposal, `UNKNOWN` currently means that the observation is outside
the local verification window and is accepted because a future certificate is
expected to vouch for it. That is valid for catch-up of an already finalized
block. It is circular for a new value-bearing observation that has never been
finalized.

### 1.4 The current threshold does not state its fault model

Membership validation accepts any threshold from one through the member count.
Not every such profile has intersecting quorums. A view-change protocol cannot
repair a profile in which two disjoint groups can independently meet the
threshold.

The protocol must state separately:

- **safety:** how many Byzantine members may equivocate; and
- **liveness:** how many members may be unavailable.

## 2. Goals and non-goals

### 2.1 Goals

- Preserve safety across timeout, proposer failure, split proposals, restart,
  catch-up, and view changes.
- Recover automatically whenever a valid quorum can communicate under eventual
  synchrony.
- Use one recovery protocol for fixed and rotating leader policies.
- Keep stable L1 observations eligible until they finalize or are invalidated
  by canonical L1 rollback.
- Make omission, reordering, duplication, and conflicting re-observation
  consensus-visible.
- Add no operator, indexer, replay service, or proposer as an authority for an
  L1 fact.
- Enforce a clean fresh-chain cutover with no retained preview compatibility.
- Bound disk, CPU, memory, wire amplification, and future-view spam.

### 2.2 Non-goals

- Open, permissionless consensus or dynamic validator discovery.
- Slashing, stake weighting, or economic leader election.
- Making a configured stability depth a mathematical proof that Cardano can
  never roll back deeper.
- Treating the observation journal as authoritative app-chain state.
- Authorizing raw observation injection, direct state mutation, or a new
  deposit as repair for an old deposit.
- Requiring historical proof service in the first delivery phase. Near-live
  durable carry-forward ships before arbitrary pruned-history recovery.

## 3. Safety and liveness model

Let:

```text
n = active member count at height h
q = prepare, commit, and timeout quorum at h
f = configured maximum Byzantine members at h
```

Protocol-v2 activation is valid only if:

```text
2q - n > f       safety: every two quorums intersect in an honest member
q <= n - f       liveness: a quorum remains possible with f unavailable members
```

The familiar `n = 3f + 1, q = 2f + 1` profile satisfies both, but the formulas
permit stricter thresholds such as the incident's `n=5, q=4, f=1`.

If a deployment claims only crash-fault tolerance, it sets `f=0`; even then
`q > n/2` is required. A two-of-three profile is crash-safe but must not be
advertised as tolerating one Byzantine member. Profiles that fail these checks
cannot participate in the fresh protocol-v2 chain.

Safety assumes honest members:

- protect their signing keys;
- follow the persistent prepare/commit/lock rules;
- validate the consensus context and canonical encodings;
- independently run or verify configured L1 observers; and
- do not cross-sign another chain, profile, membership epoch, height, or view.

Liveness assumes eventual message delivery among at least `q` correct and
healthy validators. Fewer than `q` must halt safely.

## 4. Consensus protocol v2

### 4.1 Consensus identity

Every signed consensus object is domain-separated and binds:

```text
protocol version
app-chain genesis identity
chain id
height
view
active membership epoch digest
authenticated consensus-profile digest
observer-profile digest
phase and phase-specific payload
```

The canonical digest of those stable fields is the `consensusContextDigest`.
It is carried in the v3 app-block header and in every prepare, commit, timeout,
and new-view object. A signature from another chain, membership
epoch, observer set, profile, phase, height, or view is invalid.

Membership for height `h` is fixed by finalized history through `h-1`.
Governance finalized at `h` may affect `h+1`; it never changes the quorum
inside an active height.

### 4.2 Values, views, and hashes

The protocol distinguishes:

- `valueHash`: the hash of the proposed application value—parent, stable L1
  reference, deterministic inputs, timestamp, and resulting state root—without
  proposer, view, certificates, or recovery evidence;
- `proposalHash`: the canonical v3 header hash over
  `(consensusContextDigest, height, view, valueHash, proposer,
  justificationDigest)`; and
- finalized `blockHash`: the same `proposalHash`. The finality-certificate
  container is not part of that hash.

Carrying a prepared value into a higher view preserves `valueHash`; the new
proposal and final block record the higher view and its justification. This
avoids pretending that a signature for view 2 is a signature for view 3 while
still carrying the exact deterministic application value.

### 4.3 Normal round

Each `(height, view)` has two voting phases:

1. The deterministic leader broadcasts `PROPOSAL(h, v, value,
   justification)`.
2. A validator performs all structural, proposer, parent, L1, observation,
   message, state-root, and safe-proposal checks. It persists its prepare vote
   before broadcasting `PREPARE(h, v, proposalHash)`.
3. Any member may aggregate `q` valid prepares into `PreparedQC(h, v,
   proposalHash, valueHash)` and broadcast it.
4. On a valid `PreparedQC`, a validator persists its lock/highest prepared
   certificate and commit vote before broadcasting `COMMIT(h, v,
   proposalHash)`.
5. Any member may aggregate `q` valid commits into `FinalityCertV2`. The block
   and all state changes are committed atomically, then the certificate is
   gossiped.

Prepare and commit signatures cover different domain-separated digests. Both
bind `proposalHash`; the prepared certificate additionally exposes the
corresponding `valueHash` so a later view can carry the same value without
reusing a signature from the old view.

The persisted state per active height includes at least:

```text
current view
prepare vote per view
commit vote per view
highest PreparedQC
locked value and locked view
original canonical proposal value
highest known FinalityCertV2
new-view evidence needed for replay
```

A member signs at most one prepare and one commit per `(height, view)`. It does
not delete old evidence when entering a new view.

### 4.4 Timeout and new-view certificate

Entering a height starts a local monotonic timer. Timeout duration is bounded
exponential backoff from a configured base. Time is a liveness trigger only;
elapsed local time is never accepted as safety evidence.

On timeout, a member persists and broadcasts:

```text
TIMEOUT(
    contextDigest,
    height,
    targetView,
    highestPreparedQC or none,
    highestFinalityCertV2 or none
)
```

A `NewViewCert(targetView)` contains `q` distinct signed timeout records and a
deduplicated table of every referenced certificate. Canonical ordering is by
unsigned signer key. Each nested certificate is fully verified; a digest-only
claim without the referenced certificate is invalid.

The certificate is bounded by the 32-member framework limit, one timeout per
member per target view, one reference to each deduplicated QC, a configured
maximum future-view lead, and canonical CBOR preflight limits. Unsolicited
objects beyond the future-view lead are dropped before expensive signature
verification.

### 4.5 Safe proposal rule

For a proposal justified by `NewViewCert(h, v)`:

1. If any timeout evidence contains a valid finality certificate, the node
   applies/adopts that finalized value and does not vote for a replacement.
2. Otherwise select the valid `PreparedQC` with the highest view.
3. If a highest prepared certificate exists, the leader must propose exactly
   its `valueHash` and complete canonical value. Missing value bytes cause
   `DEFER`, not substitution.
4. If no prepared certificate exists, the leader may construct a fresh value
   using the deterministic input-selection rules.
5. Conflicting prepared certificates at the same highest view are evidence of
   a violated fault assumption or invalid signature processing. The chain
   quarantines the height and does not choose by hash or arrival time.
6. If canonical L1 rollback makes the selected prepared value externally
   invalid, the chain quarantines the height. It neither commits the invalid
   value nor replaces a value protected by `PreparedQC`; see §5.8.

A validator locked on `(lockedView, lockedValue)` may prepare a proposal only
when:

- it carries the same value; or
- its valid new-view justification selects a `PreparedQC` from a strictly
  higher view according to the rule above.

This is why a prepare phase is required. With the current one-phase vote, a
finality certificate may be assembled without every voter learning that it
exists. Commit voters in protocol v2 sign only after receiving the prepared
certificate, so any finality quorum contains `q` members that can carry the
safe prepared evidence into a later view.

### 4.6 Leader selection

Leader validity must be identical on every validator:

```text
base = H(chainGenesisId || height || parentBlockHash || membershipEpochDigest)
baseIndex = uint64(base[0..7]) mod n
initialIndex = fixedMemberIndex                         # fixed policy
             | baseIndex                               # rotating policy
leader(h, view) = sortedMembers[(initialIndex + view) mod n]
```

View 0 therefore starts at the configured fixed member or the rotating hash
selection. Every higher view advances deterministically through the sorted
member set. It never reads the local L1 tip or wall clock. The stable L1
reference remains part of proposal validity, not leader identity.

The current `SequencerMode` SPI mixes leader choice with live eligibility and
cannot govern protocol safety. For the built-in fixed and rotating modes,
protocol v2 therefore treats the configured mode only as the view-0 policy
selector and computes the leader inside the framework. The framework—not a
plugin—owns views, locks, certificates, and safe proposal validation. Custom
sequencer modes remain an extension compatibility surface and are outside the
certified built-in policy's production qualification.

### 4.7 Catch-up and evidence retention

Every finalized v3 block carries or references a self-contained bounded
`ConsensusJustification`:

- `GENESIS_OR_NORMAL` for view 0;
- `NEW_VIEW` with its `NewViewCert` and selected `PreparedQC` for view > 0.

The justification digest is committed by the block header. App-chain sync
delivers the justification and `FinalityCertV2` with the block. Catch-up
verifies the full membership epoch, consensus context, justification, prepared
certificate, commit certificate, parent, inputs, and re-executed state root.

After a height finalizes, non-final views may be compacted only after the
finalized block and required justification are durable. Evidence referenced by
the finalized block is retained with it. Local duplicate timeout/prepare gossip
may be pruned by bounded age and count.

## 5. Durable L1 observation protocol

### 5.1 Observations become first-class system inputs

Protocol v2 does not re-inject durable observations into the ordinary expiring
app-message pool. To keep the wire model small, app block v3 retains one
ordered envelope list and uses the reserved `~l1/<observer-id>` topic as its
system-input tag. The messages root commits each complete message identity,
including that topic and the observation body, so an observation cannot be
reinterpreted as an ordinary message. `AppBlockExecutionContext` exposes the
same immutable global order and structured `l1Observations()` view. Ordinary
public submission cannot create a system input.

For deterministic priority and simple completeness checks, every block takes
the mandatory L1-observation prefix first and fills remaining message/byte
capacity with ordinary app messages. A capability/ABI marker is required at
activation because a plugin that previously searched `messages()` for
`~l1/*` must move to the structured observation API.

The former preview block and six-field observation encodings are intentionally
not decoded. New v3 proposals use the revised v1 observation shape exclusively.

### 5.2 Observer profile identity

Observer settings affect consensus and are not covered completely by the
current runtime profile. Protocol v2 introduces a canonical observer profile:

```text
observer instance id
provider/type id
observer ABI version
canonical normalized deterministic settings
claim schema/version
ordering version
```

Providers expose canonical consensus identity bytes. Secrets, endpoints,
local paths, retry settings, and other operational values are excluded and may
not affect `observe`. Observer entries are sorted by UTF-8 instance-id bytes,
encoded canonically, hashed, and included in `consensusContextDigest`.

A retained journal whose observer-profile digest differs from the active
profile fails startup. An observer change requires an explicit consensus
profile activation, not a local configuration edit.

### 5.3 Observation v1 identity (fresh-chain schema)

`L1Observation` remains wire version 1 and adds an explicit `eventOrdinal`.
Yano is still preview software and this decision starts a fresh chain, so the
v1 schema is changed in place and the former six-field preview encoding is not
accepted. An observer emitting more
than one fact for one transaction or epoch anchor assigns stable ordinals in
canonical source order. Local collection iteration order is never an identity.

Two identifiers serve different purposes:

```text
sourceKey = H(
  "yano-l1-source-v1" || chainGenesisId || l1NetworkGenesisId ||
  observerProfileDigest || observerId || anchor || slot || blockHash || eventOrdinal
)

observationId = H(
  "yano-l1-observation-v1" || sourceKey || H(canonicalClaimBytes)
)
```

- `sourceKey` detects two different claims for the same source event and sends
  both to quarantine.
- `observationId` is the stable deduplication, proposal, cursor, proof, and
  acknowledgement identity.

The proposer, envelope signature, sender sequence, retry count, process, and
TTL are deliberately absent.

### 5.4 Canonical ordering

Journal and block order is the unsigned lexicographic order of:

```text
(slot, blockHash, observerIdUtf8, anchorTag, canonicalAnchorBytes,
 eventOrdinal, observationId)
```

The block hash makes a rolled-back fork distinguishable. It is not used to
choose between forks; canonical L1 application/rollback decides which journal
entry remains eligible.

The framework commits a cursor per observer stream under framework-owned
authenticated state. The cursor contains the last finalized ordering key and
observation ID. A digest of the sorted cursor vector is included in status and
evidence. Per-observer cursors avoid coupling an inactive observer's progress
to another observer, while the global ordering rule produces one deterministic
cross-observer block prefix.

### 5.5 Durable journal lifecycle

Each validator stores a node-local observation journal in a dedicated
`AppLedgerStore` column family so finalized acknowledgement can share the same
RocksDB write batch as the block, state root, and cursor update.

The lifecycle is:

```text
SEEN_UNSTABLE
  -> STABLE_PENDING
  -> IN_FLIGHT(height, view, valueHash)
  -> QC_PREPARED(height, view, valueHash)
  -> FINALIZED(height, blockHash)
  -> COMPACTED_TOMBSTONE

SEEN_UNSTABLE or STABLE_PENDING -> ORPHANED on ordinary L1 rollback
any state -> QUARANTINED on conflicting evidence or deep rollback
```

Required semantics:

- journal the observer result idempotently when its L1 block is applied;
- fsync/WAL-persist `STABLE_PENDING` before it becomes proposal-eligible;
- never remove an entry because it was drained, offered, relayed, prepared,
  timed out, or proposed;
- retain the canonical value bytes needed by a replacement leader;
- atomically advance authenticated cursors and mark included observations
  finalized with the app-block commit;
- recover idempotently if startup finds the block/cursor committed but a
  redundant lifecycle marker incomplete;
- retain compact tombstones or a cursor-backed source range sufficient to
  reject duplicate re-import after compaction; and
- rebuild only delivery metadata from canonical L1 plus finalized cursors.
  The journal never replaces committed app state.

### 5.6 Mandatory prefix validation

For proposal L1 reference `R`, every voter must have a healthy, canonical L1
view through `R` and complete observer execution through that point. It
computes:

```text
expected = canonical STABLE_PENDING entries after finalized cursors and <= R
prefix = largest leading prefix that fits the configured observation-count,
         total-input-count, claim-byte, and block-byte limits
```

The proposal is valid only if its observation inputs equal `prefix` byte for
byte. Therefore a voter:

- accepts the exact bounded prefix;
- defers when its canonical L1 view has not reached `R`;
- rejects a gap, omission, reorder, duplicate, conflicting claim, wrong fork,
  or non-maximal prefix;
- rejects an ordinary message placed before the mandatory observation prefix;
  and
- rejects a proposer-selected L1 reference that is outside the local complete
  observation horizon.

If the next single observation cannot fit an otherwise empty maximum-sized
block, the chain enters `OBSERVATION_UNENCODABLE` quarantine. It does not skip
the entry.

This rule intentionally favors safety over availability. Nondeterministic
observer behavior or inconsistent observer configuration becomes a visible
consensus halt instead of inconsistent value credit.

### 5.7 Backpressure and bounds

The journal has explicit byte and count bounds, but an unfinalized stable entry
is never silently evicted. Approaching the bound degrades readiness and stops
accepting new application work. Reaching it stops proposal and voting for the
affected chain until capacity is increased or finalized data is compacted.

Age is an alert and service-level objective, not an eviction rule. Operators
may retry or inspect an entry; they may not delete it, mark it finalized, or
advance its cursor.

All limits that affect proposal validity—per-block observation count, claim
bytes, total inputs, and block bytes—are authenticated in the consensus
profile. Local disk high-water marks may be stricter only by failing readiness,
never by accepting a different block.

### 5.8 Rollback

- Before stability: remove or mark orphaned entries above the rollback point.
- Stable but not protected by a `PreparedQC`: invalidate entries from the
  rolled-back branch. A proposal containing one fails L1 verification; view
  change may construct a fresh value from the new canonical journal prefix.
- Protected by `PreparedQC` but not known finalized: the value is no longer
  externally valid, yet the cross-view lock does not permit substitution. Enter
  `L1_INVALIDATED_PREPARED_VALUE` quarantine and retain all evidence. If a
  valid finality certificate is later discovered, adopt it and immediately
  escalate to the finalized deep-rollback case.
- Finalized observation rolled back on L1: app-chain state cannot roll back.
  The chain enters `DEEP_L1_ROLLBACK` quarantine and stops value-bearing
  observation, settlement, and related effects. Exit requires an explicit
  future governance/reconciliation decision, not journal mutation.

The journal retains enough block pointer and finalized-cursor evidence to
detect the third case after restart.

## 6. Live, catch-up, and historical verification

The verification verdict gains an explicit mode:

| Context | `OK` | `AHEAD` | `MISMATCH` | `UNKNOWN` |
|---|---|---|---|---|
| New live proposal | continue | defer | reject | reject/defer; never vote |
| New recovery proposal | continue | defer | reject | reject/defer; never vote |
| Already finalized catch-up | continue | pause | reject | accept only with valid historical finality and justification |

For near-live view change, the replacement leader reads the value from its
local durable journal or the prepared proposal. Every voter still compares it
with its own complete journal/L1 view.

Historical reconciliation accepts only a pointer:

```text
chain id, observer id, L1 network, anchor, optional slot/block hash,
event ordinal, explicit operator confirmation
```

It does not accept claim bytes or a prebuilt observation. Each validator must
either rerun the observer against a locally retained canonical block or verify
proof-carrying evidence against an independently derived Cardano commitment.
An archive/indexer may carry bytes and proofs but is not authoritative. A
threshold that cannot independently return `OK` halts.

Historical proof formats and retained-block lookup are Phase 4. Their absence
does not weaken the live rule and does not authorize raw replay.

## 7. Fresh-chain activation and compatibility

This decision is a preview-stage clean break. Deployment starts a fresh app
chain using app-block v3, consensus v2, and the revised observation wire v1.
There is no retained-chain transition certificate, legacy lock migration,
mixed live mode, or automatic decoding of the former observation shape.

Startup requires all of the following identities to match:

- app-block and consensus wire versions;
- consensus context and runtime profile digest;
- active membership epoch digest and fault assumption;
- observer profile, observation wire, ordering, and cursor versions;
- application capability-manifest digest and required observation ABI; and
- state commitment profile, format fingerprint, and genesis identity.

Any existing preview chainstate is incompatible and must be archived or
discarded explicitly before creating the new chain. The protocol-v2 engine
never invokes `admin/unlock-stale-round`; certified view change is its only
normal recovery path.

## 8. Storage and component boundaries

### 8.1 `core-api`

- app-block v3 and reserved-topic system-input CDDL/API;
- observation v1 ordinal, source key, and observation ID;
- versioned prepare, commit, prepared-QC, timeout, new-view, and
  finality evidence types;
- canonical codecs, domain-separated digests, bounds, and conformance vectors;
- observer consensus identity and application capability requirements.

### 8.2 `runtime`

- replace height-only `PendingRound` with `(height, view, phase)` state;
- framework-owned leader policy, timers, safe proposal rule, aggregation, and
  persistence;
- journal column family, cursor guard, mandatory prefix builder/validator,
  rollback/quarantine, and atomic acknowledgement;
- live/catch-up verification mode separation;
- fresh-chain protocol/profile preflight;
- catch-up verification of all v3 evidence; and
- removal of block-observation dependence on `pendingInjection`, ordinary pool
  capacity, and message TTL.

The existing epoch spool is migrated behind the same system-input and cursor
contract after the generic block path. It is not rewritten merely to make its
internal lifecycle names match.

### 8.3 `app`

- read-only status and bounded diagnostics;
- authenticated pointer-only reconciliation endpoints;
- readiness/health exposure; and
- metrics registration without claims, customer addresses, signed
  transactions, credentials, or arbitrary plugin exception text.

### 8.4 Downstream applications and Yano X

Downstream code owns observer-specific claim semantics and state-level
idempotency. Yano owns delivery identity, ordering, persistence, verification,
and consensus recovery.

Yano X continues to consume exact published Yano artifacts. It must not add a
sibling source/build dependency. It keeps fixed sequencing as its deployment
default until the release passes the qualification gates in this ADR.

## 9. Configuration and defaults

Names are illustrative until implementation freezes the typed configuration:

```yaml
yano.app-chain.consensus.max-byzantine-members: 1
yano.app-chain.consensus.round-timeout-ms: 10000
yano.app-chain.consensus.timeout-max-exponent: 5
yano.app-chain.consensus.max-future-view-lead: 8

yano.app-chain.sequencer.mode: fixed | rotating

yano.app-chain.observation.max-per-block: 128
yano.app-chain.observation.max-claim-bytes-per-block: 262144
yano.app-chain.observation.journal.max-entries: 100000
yano.app-chain.observation.journal.max-bytes: 1073741824
```

The released defaults must be derived from benchmarks and failure tests, not
copied from this example. Consensus-affecting fields are typed and
authenticated. Purely local timeout tuning may vary only where the wire and
validity rules do not change; the effective value is still visible in status.

## 10. Operations and observability

Per-chain status exposes bounded values for:

- protocol version, height, view, phase, leader, timer/backoff, and context
  digest;
- local prepare/commit lock and highest prepared/finality evidence digests;
- timeout records collected toward the next view;
- last automatic recovery reason and duration;
- current view, prepare/commit locks, and recovery quarantine state;
- observer profile digest and complete L1 observation horizon;
- journal count/bytes by lifecycle, oldest stable-pending age, and capacity
  high-water mark;
- finalized cursor per observer and local acknowledgement lag; and
- quarantine state and non-sensitive reason code.

Metrics include counters/histograms for view changes, invalid evidence,
prepare/commit latency, recovery duration, future-view drops, journal writes,
replay, finalized acknowledgement, backlog age, capacity pressure, omission
rejection, historical verification verdicts, and rollback quarantine.

Readiness fails when the node cannot durably persist consensus evidence or a
stable observation, cannot establish a complete L1 horizon, has an ambiguous
observer result, reaches journal capacity, or enters deep-rollback quarantine.

## 11. Implementation plan

### Phase 0 — containment and executable reproduction

- Keep fixed sequencing as the documented production default.
- Forbid raw observation replay and automatic stale-lock deletion.
- Add a deterministic five-node/four-of-five test fixture that reproduces
  competing view-0 proposals and a stable observation during the stall.
- Capture invariants and golden wire/identity vectors before implementation.

Exit: the fixture captures the former split-lock and observation-loss failure
as executable invariants without production credentials or infrastructure.

### Phase 1 — protocol types, identities, and storage foundation

- Freeze consensus context, fault-profile validation, tagged signature
  digests, canonical CDDL, block v3, revised observation v1, and evidence bounds.
- Add framework-owned built-in leader selection, observer consensus identity,
  and capability gates.
- Add journal and consensus-evidence column families plus crash fault seams.
- Implement fresh-chain version/profile preflight.

Exit: codec/property/fuzz tests reject non-canonical, oversized, cross-domain,
wrong-profile, wrong-membership, and replayed objects; restart restores every
persisted phase exactly.

### Phase 2 — durable observation delivery under fixed leadership

- Journal block observations before eligibility.
- Build tagged system inputs directly from the mandatory stable prefix.
- Commit per-observer cursors and atomically acknowledge finalization.
- Separate live/recovery verification from certified catch-up.
- Implement rollback, capacity backpressure, quarantine, metrics, and status.
- Migrate epoch observations to the common input/acknowledgement contract where
  necessary without regressing their existing durable spool.

Exit: fixed-leader crash, restart, TTL passage, ordinary pool saturation, and
app-block failure cannot lose or duplicate an observation. Omission and
reordering are rejected.

### Phase 3 — certified views

- Implement prepare/commit phases, PreparedQC, timeout/new-view aggregation,
  safe proposal selection, and deterministic higher-view leaders.
- Integrate prepared values with the observation journal prefix.
- Validate full evidence during catch-up and snapshot restore.
- Remove the preview one-phase live path rather than mixing two consensus
  state machines.

Exit: fixed and rotating policies recover automatically under skew, proposer
failure, partial rounds, restart, and healed partitions while safety tests
attempt conflicting certificates.

### Phase 4 — fresh-chain activation and historical reconciliation

- Enforce app-block v3/consensus v2/revised-observation-v1 from genesis.
- Reject retained preview chainstate and every former observation encoding.
- Add local historical block lookup and deterministic observer rerun.
- Add proof-carrying historical verification only if retention is insufficient.
- Expose authenticated pointer-only reconciliation and complete audit events.

Exit: no mixed-version chain can start; a missed historical deposit can be
recovered only when a quorum independently verifies a pointer-only request.

### Phase 5 — qualification and graduation

- Run the exact released JVM ZIP in the Yano X 13-chain, five-node Preprod
  topology.
- Force L1-tip skew, fixed/rotating leader failure, abrupt restart, partitions,
  pool pressure, journal pressure, ordinary and deep rollback simulations,
  catch-up, and fresh-chain restart.
- Verify identity/root/certificate/cursor agreement and the settlement
  application's exactly-once reserve invariants.
- Update the consensus guide, API docs, operator runbook, release notes, and
  production-readiness claims.

Exit: only after all acceptance criteria below pass may certified rotating
sequencing be described as production-ready for geographically distributed
value-bearing chains.

## 12. Acceptance criteria

### 12.1 Consensus safety and liveness

- Five members with threshold four and `f=1` forced into competing initial
  proposals converge through a certified higher view without lock deletion.
- Fixed-leader failure uses the same view-change path.
- One unavailable member permits progress; two unavailable members halt.
- A 3/2 partition cannot finalize either side; healing converges safely.
- A valid existing finality certificate always wins over replacement.
- A highest prepared value is carried byte-for-byte into the new view.
- Crash at every persist/broadcast boundary preserves locks and recovery.
- Conflicting, malformed, under-threshold, stale-member, wrong-context,
  wrong-phase, future-view, and replayed evidence is rejected.
- Model/property tests cannot construct two conflicting finality certificates
  without exceeding the configured fault assumption.

### 12.2 Observation delivery

- A stable observation survives proposer crash, view change, graceful restart,
  abrupt restart, arbitrary ordinary-message TTL passage, and pool saturation.
- The replacement leader proposes the same logical observation ID and claim.
- The exact maximal bounded prefix is mandatory; omission, reorder, duplicate,
  fork mismatch, and conflicting source claims fail closed.
- A crash before/after app-state commit and journal acknowledgement converges to
  exactly one finalized cursor transition.
- Compaction and restart do not permit duplicate import.
- Journal exhaustion degrades/halt readiness without deleting pending facts.

### 12.3 L1 and historical safety

- Rollback before finality removes or invalidates only the orphaned branch.
- A pre-QC orphan can be replaced; an observation protected by `PreparedQC`
  cannot commit after local canonical rollback and quarantines rather than
  unlocking to a conflicting value.
- Rollback behind finalized observation enters explicit quarantine.
- `UNKNOWN` never votes `OK` for a new live or recovery fact.
- Certified historical catch-up remains possible outside the live window.
- A malicious/unavailable proof carrier cannot change accepted facts.

### 12.4 Compatibility and cross-node agreement

- Former preview chainstate and wire versions fail startup/validation; the
  chain begins with one unambiguous v3 genesis identity.
- All members agree after failure/recovery on height, view, block/value hash,
  state root, prepared/finality/new-view evidence, membership epoch digest,
  consensus and observer profile digests, capability-manifest digest,
  commitment profile/fingerprint, genesis ID, and observation cursors.
- No endpoint can create a system observation from caller-authored claim bytes.

## 13. Alternatives considered

### Larger rotating windows

Useful operational tuning, but it only changes the probability of L1-tip skew.
It does not recover split locks or proposer failure. Rejected as the solution.

### Automatic timer-based unlock

A timer proves no absence of a delayed certificate. It can cause an honest
member to sign conflicting values. Rejected.

### Fixed proposer only

Avoids the observed rotating boundary trigger but centralizes availability and
censorship and still loses observations on crash/TTL/pool pressure. Retained as
interim containment only.

### One-phase view change over current votes

A commit certificate can be assembled without its voters learning that it was
assembled. A timeout quorum may therefore lack proof of which locked candidate
must be carried. Rejected in favor of an explicit prepared certificate before
commit.

### Durable retry into the ordinary app-message pool

This survives restart but preserves proposer-dependent envelopes, TTL,
sender-sequence changes, optional inclusion, and pool competition. Rejected in
favor of a first-class deterministic system-input lane.

### External indexer or operator-authored replay

Acceptable only as an untrusted evidence carrier whose bytes every voter
verifies. Rejected as an authority.

### L1-enforced inbox

Can strengthen completeness for a specific bridge contract but does not solve
generic observers or consensus recovery. Deferred as a product-specific layer.

### Retained-chain transition

Rejected for the preview release. It adds ambiguity analysis and compatibility
surface without preserving production state. A future released protocol would
require a separate transition ADR rather than weakening this clean cutover.

## 14. Consequences

### Positive

- Consensus recovery no longer deletes safety evidence.
- Fixed and rotating leader policies share one principled recovery mechanism.
- L1 facts remain pending until finalized and are no longer coupled to ordinary
  message TTL or pool capacity.
- Proposer omission and reordering become voter-verifiable.
- Historical repair does not create an operator or indexer oracle.
- The clean cutover avoids a retained-state ambiguity protocol during preview.

### Costs and risks

- Normal finality gains a prepare network step and more durable writes.
- App-block, certificate, observation, plugin capability, sync, and storage
  formats require versioning.
- New-view evidence can be materially larger and requires strict canonical
  bounds and deduplication.
- The observation lane and cursor rules are consensus-critical and can halt on
  plugin nondeterminism.
- Existing preview app-chain state must be archived and restarted from genesis.
- Historical proof support may require additional Cardano block/commitment
  retention.
- Adversarial, crash-consistency, fresh-start, and multi-node testing becomes a
  release gate rather than optional soak coverage.

These costs are preferred to silent loss of value-bearing L1 facts or automatic
deletion of the locks that prevent conflicting finality.

## 15. Deferred questions that do not block the core design

The following are implementation measurements or later protocol extensions,
not permission to weaken the decisions above:

1. Exact released timeout and journal-capacity defaults after benchmark data.
2. Whether NewView evidence is always embedded or content-addressed in sync
   while remaining self-contained and retention-safe.
3. The historical Cardano proof format after local block pruning.
4. Governance for exiting `DEEP_L1_ROLLBACK` quarantine.
5. Whether a product-specific L1 inbox adds stronger anti-censorship guarantees.

None permits raw observation injection, `UNKNOWN` acceptance for a new fact,
timer-based lock deletion, or an unauthenticated in-place protocol switch.
