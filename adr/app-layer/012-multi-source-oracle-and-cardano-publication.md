# ADR-012: Deterministic Multi-Source Oracle and Cardano Publication

## Status

Proposed — design for review before implementation

The number is local to the `adr/app-layer` series. Root-level ADR-012 is an
unrelated node-mode ADR.

## Date

2026-07-15

## Authors

BloxBean Team

## Parent / References

- ADR-005 — deterministic app-chain state-machine contract and conformance
  boundary
- ADR-006 — enterprise evidence, retention, and future ZK settlement direction
- ADR-008.4 — stable L1 observations and the script-anchor thread-NFT and
  threshold-signing precedent
- ADR-010 — deterministic effects, finality gates, at-least-once execution,
  and authenticated result incorporation
- ADR-010.1 — replay-stable transition and emission upgrades
- ADR-011 — manifested plugins, committed queries, constrained domain APIs,
  and plugin operations
- Planned ADR-010.2 — shared Cardano executor safety, durable transaction
  preparation, wallet coordination, and production-funds policy

---

## 0. In plain words

An oracle brings a fact from outside a blockchain into a form that a smart
contract can consume. A price, temperature, shipment location, election
result, or registry entry does not become true merely because somebody writes
it into a datum. A Cardano script address also does not authenticate who
created an output at that address: the validator runs when an output is spent,
not when an arbitrary party sends a new output to it.

Yano can provide a stronger and auditable pipeline. Independent reporters
submit signed observations to an app chain. A deterministic oracle state
machine verifies their identities and enforces the configured source-diversity,
round, freshness, quorum, range, and outlier policy. It cannot discover whether
two source companies secretly use the same upstream provider. Every member
derives the same aggregate and evidence commitment. Once the aggregate is
finalized, the state machine emits a `cardano.oracle-update.v1` effect. A
Cardano publication service coordinates the threshold-authorized update of one
canonical script UTxO, identified by a unique thread NFT. The update is
considered published only after the exact successor UTxO is stable on Cardano.

This proves that authorized reports satisfied a declared policy, that the
app-chain members finalized the result, and that the result was published to
the canonical Cardano oracle UTxO. It cannot prove an external fact is
objectively true. Multiple genuinely independent sources and reporters reduce
the risk of error, manipulation, and correlated failure; they do not turn an
external claim into mathematical truth.

The production invariant is:

> No Cardano oracle update is accepted unless it is the unique successor of
> the canonical thread UTxO and its datum is byte-for-byte derived from a
> finalized oracle round under the committed value and publication policies.

### 0.1 What implementation enables

Implementing this ADR turns Yano into a configurable oracle pipeline. An
operator can define a feed, authorized reporters and sources, aggregation and
freshness rules, and a governed Cardano target. Yano can then accept signed
reports, derive one replay-stable value, finalize it, threshold-authorize its
publication, and maintain one canonical Cardano UTxO that consumer contracts
can identify and freshness-check.

Examples include:

| Feed | Reports and policy | Cardano consumer use |
|---|---|---|
| `ADA-USD` | Independent exchange reports; reject stale, duplicate, and outlier values; publish the deterministic median | Lending, collateral, settlement, or trading contracts reject counterfeit or expired prices |
| Cold-store temperature | Several signed sensor/inspector reports with range and quorum rules | Insurance or supply-chain contracts act only on a fresh canonical reading |
| Shipment status | Carrier, port, and inspector attestations with an evidence commitment | Escrow releases against the governed status while detailed evidence remains off-chain |
| Government/issuer registry | One explicitly declared authoritative signed source | Compliance or credential contracts consume the latest authenticated registry state without claiming source diversity |

For supported feeds, operators gain committed queries, evidence roots,
publication status, bounded health/metrics, and a dashboard. The reference
devnet flow can bootstrap a feed, run sample reporters, publish, and inspect the
datum without writing Java code. Integrating a new vendor-specific API may
still require a reporter adapter, but it never requires network I/O inside the
deterministic state machine.

The result proves policy-backed attestation and canonical publication, not
objective real-world truth. Independent sources reduce error, manipulation,
and correlated failure; they cannot make a dishonest external world
cryptographically honest.

## 1. Context and verified baseline

ADR-010 already supplies the generic outbound half of the pipeline:

- deterministic effect emission inside `AppStateMachine.apply()`;
- authenticated effect records and proofs;
- `APP_FINAL`, `L1_ANCHORED`, and `ZK_SETTLED` gates;
- retry, expiry, quarantine, and external-worker execution modes;
- `~fx/result` incorporation with first-result-wins semantics; and
- an `AppEffectExecutor` plugin boundary.

The current `appchain-effects-cardano` plugin supplies `cardano.payment`. It
can build, sign, submit, and re-poll a payment transaction, but its current
metadata breadcrumb does not close the crash window before `SUBMITTED` is
durable. ADR-010 therefore already requires a dedicated Cardano executor
safety design before material production funds are used. This oracle ADR
depends on that hardening; it does not quietly redefine the current payment
executor as production-safe.

The local Cardano transaction stack already has the necessary primitives for
oracle publication:

- create an output at a script address with an inline Plutus datum;
- spend a script UTxO and recreate a continuing script output;
- carry a unique native asset through the continuing output;
- attach or reference a validator and evaluate execution units; and
- inspect the resulting UTxO through Yano's L1 state.

`ScriptAnchorService` is a concrete precedent for the authoritative state
pattern: a one-shot thread NFT identifies one canonical UTxO; an update spends
that UTxO and creates one successor; members verify a proposed transaction and
threshold-sign its body. Oracle state must have a separate identity,
validator, datum, and lifecycle, but it should reuse the proven shape.

ADR-011 supplies the packaging and product surface: the oracle state machine,
domain query/API, health, and metrics can form a manifested bundle while the
Cardano executor remains a privileged typed contribution. Source adapters may
run as external reporter processes or trusted auxiliary plugins, but never as
I/O inside the deterministic transition.

What is missing is the domain protocol connecting these pieces:

1. a precise oracle trust claim;
2. signed observation, round, and feed-configuration contracts;
3. byte-for-byte deterministic validation and aggregation;
4. authenticated evidence and query semantics;
5. a canonical Cardano datum and thread-UTxO protocol;
6. threshold authorization tying the L1 update to finalized app-chain state;
7. crash-, retry-, contention-, and rollback-safe publication; and
8. a usable no-code demonstration and operational surface.

## 2. Trust claim and boundaries

### 2.1 What the system proves

For a published round, Yano can prove the following chain of statements:

1. Each retained report had a valid signature from an authorized reporter and,
   when available, valid source-native authentication.
2. Each retained report was bound to the chain, feed, value-policy version,
   source, reporter, and round for which it was evaluated.
3. The committed deterministic policy accepted the required source and
   reporter diversity, freshness, value bounds, and outlier constraints.
4. Every honest app-chain member derived the same aggregate, report set,
   evidence root, datum bytes, and effect payload.
5. The threshold-finalized app-chain state authorized that effect.
6. The Cardano update was authorized by the configured publication committee
   and created the unique successor of the canonical oracle UTxO.
7. The exact successor was observed at the configured Cardano stability depth.

An auditor can therefore verify "these authorized observations produced this
published value under policy set C" without trusting the executor's local
logs.

### 2.2 What the system does not prove

The protocol does not prove that:

- an exchange reported an economically fair market price;
- a physical sensor was calibrated or attached to the claimed object;
- several branded APIs do not share one upstream data provider;
- an authorized source was not compromised; or
- a quorum of independent parties cannot collude.

Those are properties of the feed's real-world trust model. The protocol makes
that model explicit, enforceable, and auditable instead of implying that
consensus manufactures truth.

### 2.3 Independent threshold layers

The following thresholds are independent and MUST NOT be conflated:

| Threshold | Meaning | What it protects |
|---|---|---|
| Source-group quorum | Number of declared independent upstream groups retained after filtering | One upstream group being wrong, unavailable, or manipulated |
| Reporter quorum | Number of distinct authorized reporter keys represented in retained evidence | Relay availability and concentration; per-source `2g+1` policy supplies reporter fault tolerance |
| App-chain finality threshold | Members certifying the deterministic app block | Fork safety and agreement that the rules were applied |
| Feed-governance threshold | Keys authorizing configuration, source, reporter, pause, and policy changes | Unauthorized changes to the oracle trust policy |
| Publication threshold | Cardano transaction signers authorizing the thread-UTxO update | A compromised coordinator or wallet publishing arbitrary state |

Three reporters querying the same exchange provide reporter redundancy, not
source diversity. Three branded APIs configured in one `independenceGroup`
likewise count as one upstream group. One reporter querying three genuinely
independent APIs may provide source diversity, but compromise of that reporter
can still falsify all three unless the source responses are independently
signed. Feed policy records both axes.

Multiple sources are not mandatory for every feed. A government registry,
issuer, or sports authority may intentionally be the sole authoritative
source. Such a feed MUST declare an `AUTHORITATIVE_SOURCE` trust mode and
authenticate that source; it must not claim multi-source confidence.

## 3. Goals and non-goals

### Goals

1. Accept signed external observations without performing external I/O inside
   deterministic execution.
2. Separate source diversity, reporter diversity, app-chain finality, and L1
   publication authorization.
3. Produce byte-identical aggregates, evidence roots, datums, and effects on
   every member and during replay.
4. Support fixed-point numeric feeds with explicit units, freshness, quorum,
   outlier, and circuit-breaker policies.
5. Commit accepted evidence in app-chain state and expose bounded,
   root-consistent audit queries.
6. Publish a canonical, authenticated oracle UTxO that Cardano contracts can
   consume as a reference input.
7. Make publication safe across crashes, retries, executor failover, UTxO
   contention, transaction expiry, and ordinary Cardano rollbacks.
8. Support feed bootstrap, configuration activation, key rotation, pause,
   retirement, and validator migration without replay forks.
9. Package a first-party oracle bundle and a no-code devnet demonstration.

### Non-goals

- Claiming objective truth or eliminating the economic oracle problem.
- Fetching HTTP, WebSocket, exchange, sensor, or registry data from
  `validate()`, `apply()`, or `onEffectResult()`.
- Treating a datum sent to a script address as authenticated solely because of
  that address.
- A generic arbitrary-script transaction builder or caller-selected redeemer.
- Floating-point arithmetic, locale-dependent parsing, or implicit unit
  conversion in consensus logic.
- Confidential observations in v1. App messages, authenticated state, effect
  payloads, transaction witnesses, and inline datums may be public.
- High-frequency sub-slot market data, cross-feed atomic batches, staking,
  slashing, or token-economic incentives in v1.
- Replacing specialized oracle feeds such as a protocol's native oracle
  network. Yano supplies a framework and a first-party reference profile.

## 4. Decision summary

1. The oracle is a domain protocol above ADR-010, not a mode of
   `cardano.payment`.
2. Reporters fetch nondeterministic data outside consensus and submit signed
   observations as ordinary app messages.
3. Feed policy is authenticated app-chain state. Each round is evaluated under
   the value policy active when the round starts.
4. The v1 aggregate is an unweighted deterministic median of normalized
   fixed-point independence-group values after deterministic filtering.
   Weighted feeds require a later schema/version.
5. Accepted evidence is canonically ordered and committed by a count-bound,
   domain-separated Merkle root. A separate count-bound decision commitment
   covers every processed report message and its final disposition.
6. The authoritative publication type is `cardano.oracle-update.v1` with
   `ResultPolicy.CHAIN`; `L1_ANCHORED` is the production default gate.
7. The effect contains the exact datum bytes and a hash of the complete
   consensus-authorized target profile. Local executor configuration may
   select credentials and backends but may not reinterpret the target.
8. The canonical Cardano state is one continuing script UTxO identified by a
   unique thread NFT. Consumers identify it by network, validator hash, policy
   id, and asset name, never by "the first output at this address."
9. Production updates require a publication threshold. A single publisher key
   is permitted only in an explicitly unsafe preview/demo profile.
10. A reusable Cardano publication/co-sign service durably replicates the fully
    signed transaction before submission and thereafter only polls or
    resubmits those exact bytes.
11. `~fx/result` records an execution outcome, while a stable L1 observation
    verifies the exact successor before domain state becomes `PUBLISHED`.
12. A generic append-only `cardano.datum-publish` may be designed separately,
    but it is not an authenticated canonical oracle.

## 5. End-to-end architecture

```text
 external sources
      │  nondeterministic reads, optional source-native signatures
      ▼
 reporter processes / auxiliary plugins
      │  signed oracle/report.v1 app messages
      ▼
 ┌──────────────────────────────────────────────────────────────┐
 │ deterministic oracle state machine                          │
 │ signatures → round → source/reporter quorum → normalization │
 │ → equivocation/outlier/freshness policy → median            │
 │ → evidence root → exact OracleDatum bytes                   │
 └──────────────────────────────────────────────────────────────┘
      │ finalized state + cardano.oracle-update.v1 effect
      │ production gate: app block already L1-anchored
      ▼
 Cardano publication coordinator
      │ prepare exact tx → member verification → threshold witnesses
      │ durable journal → submit/re-poll same tx
      ▼
 canonical thread-NFT oracle UTxO
      │ stable L1 observer verifies outref, token, and datum hash
      ▼
 ~fx/result + oracle publication state + audit/query/UI
      │
      └── Cardano consumer contracts use the UTxO as a reference input
```

### 5.1 Responsibility boundaries

| Component | Responsible for | MUST NOT do |
|---|---|---|
| Source adapter | Fetch/subscribe, normalize transport, retain source response, submit a signed report | Write app state directly or claim consensus acceptance |
| Reporter | Attest exactly what it observed under a stable key | Reuse a signature across chains, feeds, configurations, or rounds |
| Oracle state machine | Verify and deterministically select/aggregate reports | Perform I/O, use wall clock, floats, unordered iteration, or local config |
| Effect system | Authenticate intent, gate execution, retry, and incorporate outcome | Decide whether the real-world observation is true |
| Publication coordinator | Build the exact authorized update and collect witnesses | Choose a different datum, feed, script, token, or value |
| Publication signers | Independently verify finalized state, effect, UTxO, and tx body | Blindly sign coordinator-provided bytes |
| Oracle validator | Enforce one canonical successor and authorized transition | Discover off-chain sources or recompute their aggregate |
| L1 observer | Verify stable on-chain inclusion of the exact successor | Treat mempool acceptance or shallow inclusion as final |
| Consumer contract | Check oracle identity, feed, status, and freshness | Trust any output merely because it is at the same address |

## 6. Identities, feed configuration, and target binding

### 6.1 Stable identities

- `appChainId` identifies the app chain whose state authorizes publication.
- `feedId` is a permanent ASCII identifier such as `ADA-USD` or
  `warehouse-7/temperature`.
- `sourceId` identifies a configured real-world origin, not merely an HTTP
  endpoint alias.
- `reporterId` is
  `blake2b-256("yano/oracle/reporter/v1" || ed25519VerificationKey)`.
- `valuePolicyVersion` is a monotonically increasing unsigned integer paired
  with an immutable `effectiveRound`; it identifies the source, reporter,
  aggregation, and evidence policy that produced a value.
- `publicationPolicyVersion` identifies the current Cardano endpoint,
  committee, target bounds, and lifecycle policy. It activates only after its
  matching on-chain transition is stably observed.
- `round` is a monotonically increasing unsigned integer derived from the
  committed round clock.
- `publicationSequence` counts successful canonical UTxO state transitions and
  is consecutive even when oracle rounds are skipped or coalesced.
- `transitionAttempt` is a monotonically increasing app-chain counter assigned
  to every emitted Cardano publication/lifecycle attempt, including attempts
  later proven not published. It prevents a replacement against the same
  predecessor/successor core from reusing a correlation identity.
- `targetProfileHash` binds the complete consensus-relevant Cardano target.
- `transitionId` is a precomputable commitment to an action, predecessor, and
  successor datum core. It correlates bootstrap, publish, update, rotation,
  migration, and retirement. The later-assigned ADR-010 effect id is recorded
  in app state, transaction metadata, and the receipt, but is not
  self-referenced by the effect payload or datum.

### 6.2 Consensus-authorized feed configuration

Configuration is split so a committee rotation cannot rewrite the provenance
of the currently published value.

The immutable feed identity/clock contains:

```text
appChainId, feedId
roundStartL1Slot, roundLengthSlots, reportGraceSlots
threadPolicyId, threadAssetName
```

Each `ValuePolicyV1` contains:

```text
valuePolicyVersion, effectiveRound
unit, canonicalScale, minimumValue, maximumValue
trustMode
sources[]: sourceId, independenceGroup, authProfile,
           sourceVerificationKey?, allowedReporters[]
reporters[]: reporterKey, enabled
minimumSourceGroups, minimumReporters, minimumReportersPerSource
declaredMaxFaultySourceGroups, declaredMaxFaultyReportersPerSource
maximumSourceDeviationPpm, maximumSourceDeviationAbsolute
maximumRoundJumpPpm, maximumRoundJumpAbsolute
maximumObservationAgeSlots
maximumReportBytes, maximumSourceAssertionBytes
maximumSources, maximumReporters, maximumRoundsClosedPerBlock
reportRetentionRounds
```

Each `PublicationPolicyV1` contains:

```text
publicationPolicyVersion
publicationCommitteeKeys[], publicationThreshold
networkId, protocolMagic, validatorHash
datumSchemaVersion
minimumOutputLovelace, maximumOutputLovelace
publicationValiditySlots, publicationDeadlineSlots
maximumTransactionTtlSlots, minimumStabilityMarginSlots
maximumFeeLovelace, maximumTopUpLovelace
```

Feed governance state separately contains
`governanceSequence`, sorted `governanceKeys[]`, and
`governanceThreshold`. Global chain settings bound feed count, pending updates,
and all per-policy limits.

`canonicalScale` is in `0..18`. Numeric values, report bodies, source
assertions, collections, open rounds, and closure work all have exact
consensus limits. A configuration that claims tolerance of `f` faulty source
groups must require at least `2f + 1` retained groups for the lower-median
claim. `AUTHORITATIVE_SOURCE` has `f = 0` and may require one authenticated
group. A `REPORTER_ATTESTED` source that claims tolerance of `g` faulty
reporters must require at least `2g + 1` distinct authorized reporters for that
source. The global reporter quorum is an availability/concentration control;
per-source correctness comes from this bound.

The round clock origin and length are immutable for a feed. A clock change
creates a new feed identity or follows an explicit migration protocol; it is
not an ordinary configuration update. A configuration update names a future
`effectiveRound`, is accepted only before that round begins, and is selected
by round number rather than node-local arrival time.

All collections are canonically sorted before encoding. Provenance and
publication hashes are independent. The immutable thread-token identity is
deliberately excluded from `publicationPolicyHash`: it is bound separately by
the feed identity and `targetProfileHash`. This permits the one-shot minting
policy id to be computed without directly or indirectly hashing itself.

```text
valuePolicyHash = blake2b-256(
    "yano/oracle/value-policy/v1" || canonicalValuePolicyCbor
)

publicationPolicyHash = blake2b-256(
    "yano/oracle/publication-policy/v1" || canonicalPublicationPolicyCbor
)
```

The Cardano subset is independently encoded:

```text
TargetProfileV1 = {
  publicationPolicyVersion,
  networkId, protocolMagic, validatorHash,
  threadPolicyId, threadAssetName, datumSchemaVersion,
  publicationCommitteeKeyHashes[], publicationThreshold,
  minimumOutputLovelace, maximumOutputLovelace,
  publicationValiditySlots, publicationDeadlineSlots,
  maximumTransactionTtlSlots, minimumStabilityMarginSlots,
  maximumFeeLovelace, maximumTopUpLovelace
}

targetProfileHash = blake2b-256(
    "yano/oracle/target/v1" || canonicalTargetProfileCbor
)
```

Committee key hashes are canonical Cardano payment key hashes in ascending
byte order and are bounded in count. This policy is also present in the
current on-chain datum because a Plutus validator cannot recover it from an
opaque publication-policy hash.

Feed-governance keys are Ed25519 verification keys used by the app-chain
machine; they need not be Cardano payment keys. Only one governance update may
be pending per feed. It is authorized by the currently active governance
state; another update cannot be queued until the prior one activates and any
required on-chain transition is stably observed. An update is
signed by the current governance threshold over:

```text
blake2b-256(
    "yano/oracle/governance/v1" || appChainId || feedId ||
    previousGovernanceStateHash || newValuePolicyHash ||
    newPublicationPolicyHash || newGovernanceStateHash ||
    newGovernanceSequence || effectiveRound
)
```

The sequence increments by one. The new configuration may rotate governance
keys, but authorization always comes from the current active governance state.
App-chain finality certifies that this deterministic authorization check and
state transition occurred; finality by itself does not grant feed-admin
authority.

A value policy and any app-chain governance-key rotation become active at the
update's effective round. Control messages from that point use the new
governance state. A changed publication policy instead becomes `PENDING_L1`:
normal Cardano publication pauses while the old on-chain committee authorizes
the Rotate/Migrate transition. The stable observation first records
`L1_APPLIED`; only the same order-independent result/observation join used for
normal publication marks the new policy `ACTIVE` and releases updates.
`CONFIRMED` plus the observation becomes `PUBLISHED`; a terminal result plus
the observation becomes `PUBLISHED_AFTER_TERMINAL`; observation alone remains
`OBSERVED_AWAITING_RESULT`. The update record remains pending, and no later
governance update is accepted, until all of its axes have activated. The
currently published Live datum retains the
value-policy version/hash that produced its value while its publication-policy
version/hash may advance through a standalone lifecycle transition.

An unexecutable publication-policy change is not an infinite governance lock.
The then-active app-chain governance threshold may authorize cancellation of
only the pending publication axis after its lifecycle effect deadline plus
stability margin has passed with no matching stable successor or contradictory
CONFIRMED result. Cancellation preserves already-active value and governance
history, keeps the old publication policy active, resumes ordinary publication,
clears the pending publication candidate, and permits a later fresh update. It
can never retract an observed L1 transition or reuse its transition id.

Operational values such as backend URL, API credentials, wallet secret,
timeouts, and local database path are not part of consensus configuration.
They cannot change the destination, token, datum schema, signer threshold, or
other authorization semantics.

### 6.3 Target aliases are convenience, not authority

An effect may carry a bounded human-readable alias such as `ada-usd`, but the
effect MUST also contain `targetProfileHash`. Before building a transaction,
the executor recomputes the hash from its configured target profile and fails
closed on mismatch.

This prevents two executors from resolving the same alias to different
networks, validators, tokens, or datum schemas. A caller-supplied arbitrary
script address, validator, datum, redeemer, or thread token is forbidden for
`cardano.oracle-update.v1`.

### 6.4 Canonical encoding profile

V1 chain, feed, source, independence-group, unit, and target-alias identifiers
use bounded printable ASCII with a frozen grammar; Unicode normalization and
locale rules are not part of consensus. Recommended ids follow
`[A-Za-z0-9][A-Za-z0-9._/-]{0,63}` with field-specific shorter limits where
appropriate.

Every protocol CBOR value uses the RFC 8949 Core Deterministic Encoding rules,
definite lengths, shortest integer/length forms, and canonical map-key order.
Floats, indefinite items, duplicate map keys, implementation-specific tags,
and noncanonical alternatives are rejected rather than normalized after
signature verification. O-M0 freezes exact CDDL and golden bytes.

## 7. Observation and signature contract

### 7.1 Logical report v1

The implementation MUST freeze a canonical CBOR/CDDL representation before
the first implementation release. Its logical fields are:

```text
oracle-report-v1 = {
  version:                1,
  app-chain-id:           tstr,
  feed-id:                tstr,
  value-policy-version:  uint,
  round:                  uint,
  source-id:              tstr,
  reporter-key:           bstr .size 32,
  observed-for-l1-slot:   uint,
  value:                  int,
  scale:                  uint,
  source-evidence-hash:   bstr .size 32,
  source-assertion:       bstr / null,
  reporter-signature:     bstr .size 64
}
```

`value × 10^-scale` is the reported quantity. Floating point, exponent
notation, `NaN`, infinity, implicit currencies, and implicit unit conversion
are forbidden. Values are decoded into arbitrary-precision integers and are
bounded before arithmetic.

`source-evidence-hash` commits to the exact upstream response, sensor frame,
or signed source assertion retained by the reporter. Large or private source
bodies remain outside the effect and datum. If they must be independently
auditable, they can be encrypted in evidence storage and addressed by this
hash or by a separately committed object-store reference. A hash proves
integrity if the body is later available; it does not prove availability.

### 7.2 Domain-separated reporter signature

V1 reporter signatures are Ed25519 over a 32-byte digest. The reporter signs:

```text
blake2b-256(
    "yano/oracle/report/v1" || canonicalReportWithoutReporterSignature
)
```

The signed bytes bind the app chain, feed, value-policy version, round,
source, reporter, claimed observation slot, numeric representation, and source
evidence. A valid signature from another chain, configuration, source, or
round cannot be replayed.

When an upstream source supplies native authentication, `source-assertion`
contains the complete bounded canonical signed assertion needed by consensus.
It may not be a signature over unavailable bytes. The versioned source-auth
profile in feed configuration defines:

- deterministic signature algorithm and source verification key;
- maximum assertion bytes and canonical encoding;
- extraction of source identity, unit, integer value, scale, source timestamp
  or sequence, and replay identity;
- mapping of source time/sequence to the oracle round; and
- equality and latest-assertion selection within a round.

`apply()` verifies the assertion without I/O and requires every extracted field
to match the report. When present, `source-evidence-hash` must equal the
following value:

```text
blake2b-256("yano/oracle/source-evidence/v1" || canonicalSourceAssertion)
```

V1 selects the valid assertion with the greatest source-native sequence/time
within the round; equal sequence/time with
different signed bodies makes that source `AMBIGUOUS` for the round. An old
assertion cannot be relabelled with a current `observedForL1Slot`.

Without a supported self-contained source-auth profile, the source is
`REPORTER_ATTESTED`: the reporter attests what it observed and the trust is in
the reporter, not cryptographic proof from the upstream source.

### 7.3 Admission is not consensus validation

`AppStateMachine.validate()` may cheaply reject malformed, oversized, or
obviously unauthorized reports, but admission results are not consensus
state. `apply()` MUST repeat every signature, configuration, round, bounds,
deduplication, and policy check deterministically before a report affects the
aggregate or evidence root.

Every report receives a deterministic disposition such as `ACCEPTED`,
`DUPLICATE`, `EQUIVOCATION`, `BAD_SIGNATURE`, `UNKNOWN_SOURCE`,
`UNAUTHORIZED_REPORTER`, `WRONG_VALUE_POLICY`, `WRONG_ROUND`, `STALE`, `FUTURE`,
`OUT_OF_RANGE`, `AMBIGUOUS_SOURCE`, `CAPACITY_REJECTED`, or `OUTLIER`. Any
non-byte-identical signed report under the same unique report key is
equivocation, even when its numeric value is unchanged. Public APIs may expose
bounded reason codes, not unbounded exception text.

The inner report is signed by the reporter key. A reporter submits it to an
authorized Yano gateway/member, which verifies bounded admission and wraps it
in the ordinary member-signed app-message envelope. Reporter keys do not
implicitly become app-chain membership keys. Consensus safety comes from
revalidation of the inner signature and policy in `apply()`.

## 8. Deterministic rounds and aggregation

### 8.1 Round clock

Production rounds use the verified `AppBlock.l1Slot` from ADR-008.4, not a
node's wall clock or an adapter's local arrival time:

```text
round(slot) = floor((slot - roundStartL1Slot) / roundLengthSlots)
roundEnd(r) = roundStartL1Slot + ((r + 1) * roundLengthSlots) - 1
closeAt(r)  = roundEnd(r) + reportGraceSlots
```

The feed is inactive before `roundStartL1Slot`; negative rounds do not exist.
Every add/multiply is checked against the protocol's unsigned-integer bounds.

The value policy with the greatest `effectiveRound` not exceeding the round
governs it. Because policy updates must finalize before their future effective
round, a change cannot take effect halfway through an open round.

The machine maintains a committed monotonic oracle L1 high-water mark:

```text
effectiveStableSlot = max(previousStableSlot, block.l1Slot)
```

A zero `block.l1Slot` does not move the high-water mark. Reports are rejected
with `NO_L1_REF` only while no nonzero stable slot has ever been committed.
For every finalized app block, the state machine uses this order:

1. If `effectiveStableSlot == 0`, reject oracle reports as `NO_L1_REF`. A zero
   slot in the current block reuses a previously committed nonzero high-water
   mark.
2. Pre-scan every report message in the block, independent of its position
   relative to tick or governance messages.
3. If `observedForL1Slot > effectiveStableSlot`, record `FUTURE`. If
   `effectiveStableSlot - observedForL1Slot` exceeds the active value policy's
   `maximumObservationAgeSlots`, record `STALE`.
4. Otherwise map `observedForL1Slot` to its round and value policy.
5. Accept a report only when `effectiveStableSlot` is no later than the round
   end plus `reportGraceSlots`; otherwise record `LATE`.
6. After all reports are processed, close due rounds oldest-feed/oldest-round
   first, up to `maximumRoundsClosedPerBlock`.

A report in a block whose effective stable slot equals the close boundary is
eligible; closure happens at end-of-block. Reports in a later block never
reopen the round. This removes message-order ambiguity.

Slot passage alone does not create app-chain blocks. Redundant members submit
bounded, member-signed `oracle/tick.v1` messages when a round is due and no
ordinary report is likely to create a block. A tick contains no trusted time;
it merely triggers a block whose verified `l1Slot` drives closure. Duplicate
ticks are harmless. If closure work reaches the per-block cap, subsequent
ticks deterministically continue with the oldest due rounds. Missing ticks
affect liveness, not safety, and health exposes the closure lag.

### 8.2 Duplicate and equivocation rules

The unique report key is `(feedId, valuePolicyVersion, round, sourceId,
reporterId)`.

- Byte-identical resubmission is a duplicate and has no additional weight.
- Any non-byte-identical signed report under the same key is reporter
  equivocation, even when the numeric value matches.
- Equivocation excludes every report from that reporter for that feed and
  round, across all sources, and records both hashes for audit.
- Iteration and output ordering use canonical byte ordering, never map
  insertion order.

These rules make retries harmless and prevent a reporter from multiplying its
weight by submitting many messages.

### 8.3 Normalization

Each report is converted to the configured `canonicalScale` using exact base
10 integer arithmetic:

- scaling up multiplies by a checked power of ten;
- scaling down is allowed only when the discarded digits are all zero in v1;
- a lossy rounding policy requires a future configuration/schema version;
- normalized values outside configured integer or value bounds are rejected;
  and
- no platform primitive overflow is permitted; implementations use bounded
  `BigInteger`-equivalent arithmetic.

### 8.4 Two-axis aggregation algorithm

When a round closes, every member performs these exact steps:

1. Select the value policy active at round start.
2. Remove invalid, duplicate, equivocated, stale, future, unauthorized, and
   out-of-range reports.
3. Group the remaining reports by `sourceId`, then by the source's declared
   `independenceGroup`.
4. For a source-authenticated source, select the latest valid self-contained
   assertion according to its source-auth profile. Collapse identical relays
   while retaining each distinct valid relay reporter for availability
   accounting. Conflicting assertions with the same source sequence/time make
   that source ambiguous and exclude it.
5. For a `REPORTER_ATTESTED` source, require
   `minimumReportersPerSource` distinct non-equivocating reporters and derive
   the source value as the lower median of their normalized values. A policy
   claiming tolerance of `g` faulty reporters for that source requires
   `minimumReportersPerSource >= 2g + 1`.
6. If several source aliases share one `independenceGroup`, derive one group
   value as the lower median of their source values. One group contributes one
   aggregate vote regardless of alias or relay count.
7. Count retained independence groups and distinct contributing reporters.
   Require both `minimumSourceGroups` and `minimumReporters`. Reporter count
   for source-authenticated assertions is relay availability, not additional
   truth weight.
8. Sort group values numerically, breaking equal values by canonical group id,
   and compute the preliminary lower median at index
   `floor((n - 1) / 2)`.
9. For reference value `m`, define the permitted source deviation as
   `max(maximumSourceDeviationAbsolute,
   floor(abs(m) * maximumSourceDeviationPpm / 1_000_000))`. Reject an entire
   group when its deviation exceeds that value. This remains meaningful for
   zero and negative-valued feeds.
10. Recompute source-group and reporter quorum after outlier removal. Reports
    from an outlier group do not count toward either threshold.
11. Compute the final aggregate as the lower median of retained group values.
12. Enforce `minimumValue` and `maximumValue`. When a prior valid aggregate
    `p` exists, permit a round jump of at most
    `max(maximumRoundJumpAbsolute,
    floor(abs(p) * maximumRoundJumpPpm / 1_000_000))`. The first valid round
    has no prior-value jump check.
13. Canonically order retained reports, calculate both evidence commitments,
    and atomically record the round result. When that result later becomes the
    next eligible publication, construct its exact Live successor, transition
    id, and effect from the then-current canonical predecessor and active
    publication policy. A finalized aggregate is never re-evaluated.

The lower median is intentionally specified for even counts; implementations
must not silently average, round, use floating point, or select according to
arrival order. Weighted aggregation, TWAP, VWAP, and cross-feed formulas are
future versioned policies.

### 8.5 No quorum and circuit breakers

If either quorum is missing after filtering, the round becomes `NO_QUORUM` and
no Cardano update is emitted. If a value exceeds the absolute/relative jump
limit, the round becomes `CIRCUIT_OPEN` and no update is emitted. The previous
Cardano datum is not rewritten with a misleading fresh timestamp; it expires
at its existing `validUntilL1Slot` so consumers can fail closed.

A later configuration may explicitly define a degraded policy, but v1 does
not silently publish a last-known-good value as if it were current. Pause,
override, or circuit reset is an authenticated configuration/governance action
with a future effective round, never an operator-local switch that changes
consensus behavior.

### 8.6 Consensus resource bounds

The implementation validates exact global and per-feed limits for report body
bytes, source assertion bytes, feeds, configured sources/groups/reporters,
open rounds, accepted reports per round, governance updates, evidence leaves,
and rounds closed per block.

Accepted report cardinality is bounded by the configured
`(sourceId, reporterId)` matrix. Invalid or unauthorized reports do not create
one persistent state key each. The machine updates fixed-size disposition
counters and a rolling decision commitment, retaining at most a configured
number of samples selected by lowest canonical message id. Overflow is a
deterministic `CAPACITY_REJECTED` disposition, not an allocation failure.

Closed-round details follow deterministic retention. Aggregate, value-policy
hash, counts, accepted evidence root, round-decision commitment, publication
identity, and pruning boundary remain after detailed report pruning.

## 9. Authenticated evidence and app-chain state

### 9.1 Accepted evidence and round-decision commitments

Each retained report leaf is:

```text
leaf = blake2b-256("yano/oracle/evidence-leaf/v1" || canonicalReportCbor)
```

Leaves are sorted by `(sourceId UTF-8 bytes, reporterKeyHash, leafHash)`. Merkle
nodes are:

```text
node = blake2b-256("yano/oracle/evidence-node/v1" || left || right)
```

An odd final node is paired with itself. The count-bound root is:

```text
acceptedEvidenceRoot = blake2b-256(
    "yano/oracle/evidence-root/v1" || canonicalUint(leafCount) || treeRoot
)
```

An empty accepted set has no evidence root and cannot produce a valid round.
Exact canonical integer and CBOR encodings must be covered by golden vectors.

Accepted evidence alone does not explain rejection. The machine therefore
maintains two additional bounded commitments:

1. `ingressDecisionAccumulator` folds every report message in finalized block
   order as `(messageId, bodyHash, preliminaryDisposition)`. Reports beyond a
   storage cap are still folded as `CAPACITY_REJECTED` without allocating a
   per-report state key.
2. `finalCandidateDecisionRoot` is a count-bound Merkle root over the bounded
   candidate set in canonical report-key order with each final disposition,
   including `ACCEPTED`, `EQUIVOCATION`, `AMBIGUOUS_SOURCE`, and `OUTLIER`.

The final commitment is:

```text
accumulator[0] = blake2b-256("yano/oracle/decision-init/v1")
accumulator[i] = blake2b-256(
    "yano/oracle/decision-step/v1" || accumulator[i - 1] ||
    messageId || bodyHash || canonicalDispositionCode
)

roundDecisionRoot = blake2b-256(
    "yano/oracle/round-decision/v1" ||
    canonicalUint(reportMessageCount) || ingressDecisionAccumulator ||
    canonicalUint(candidateCount) || finalCandidateDecisionRoot ||
    canonicalUint(capacityRejectedCount)
)
```

The accepted Merkle tree supports compact inclusion proofs. The decision
commitment supports deterministic reconstruction from retained state plus app
block history; it does not promise an inclusion proof for every pruned invalid
message.

### 9.2 State records

Authenticated app-chain state records:

- immutable feed identities and value/publication/governance policy histories;
- report disposition and canonical report hash;
- source values and the retained report set for each round;
- aggregate, accepted evidence root, round-decision root, counts, and status;
- exact datum bytes and datum hash;
- emitted effect id, transition attempt/id, and current publication state;
- candidate and stable Cardano output references; and
- pause, rotation, migration, retirement, and reconciliation/incident history.

Full report retention is bounded by feed policy, but pruning must preserve the
round aggregate, both commitments, counts, value-policy hash, and enough
metadata to distinguish retained proof, pruned proof, unavailable external
evidence, and unknown round. Large source bodies are never copied into
app-chain state by default. An evidence hash proves integrity, not data
availability; APIs must report that distinction.

ADR-011.3 committed queries expose root-fixed feed, round, report, evidence,
and publication views. A domain API may compose those views, but it cannot add
uncommitted HTTP-fetched data to a response advertised as state-root-bound.

## 10. Cardano publication model

### 10.1 Why append-only output creation is not authoritative

Creating a new output with an inline datum is useful for audit drops and
demonstrations, but it has four structural problems as an oracle:

1. anyone can create a lookalike output at the same script address;
2. "latest" becomes ambiguous under concurrent or adversarial outputs;
3. every output locks minimum ADA until an explicit collection path spends it;
   and
4. output creation does not execute the destination validator.

A future `cardano.datum-publish.v1` may support an explicitly append-only,
non-authoritative use case. It is not the v1 oracle publication protocol.

### 10.2 Canonical continuing state UTxO

The v1 oracle uses one unique thread NFT with a stable token identity and a
governed current endpoint:

```text
OracleTokenIdentity = (networkId, threadPolicyId, threadAssetName)
OracleEndpoint      = (validatorHash, datumSchemaVersion)
```

The canonical current state is the only UTxO at the expected validator that
contains exactly one unit of this asset. Counterfeit outputs at the same
address are ignored. Consumers locate the UTxO by asset identity, validate the
script and datum schema, and use it as a reference input without spending it.
The token identity remains stable across validator migration; consumers must
also use the currently governed endpoint and must not accept the token at an
arbitrary script.

V1 uses one generic, unparameterized oracle validator per datum ABI version.
Every datum carries its immutable thread policy id and asset name; the
validator checks that the input actually contains exactly one unit of that
asset and that the successor preserves it. The validator hash therefore does
not depend on a per-feed policy id, avoiding a validator-hash/policy-id cycle
and permitting one reproducible observer-supported script artifact.

Each normal update consumes the current state UTxO and creates exactly one
successor at the configured validator carrying the same thread NFT and the
exact app-chain-authorized inline datum.

### 10.3 Bootstrap

Feed bootstrap is an idempotent privileged operation that:

1. consumes a declared one-shot seed input;
2. mints exactly one configured thread NFT;
3. creates exactly one initial oracle UTxO at the expected validator;
4. installs the byte-exact configured genesis datum with feed/token identity,
   publication-policy hash, publication sequence zero, transition id,
   committee, and no claim of a current value;
5. records the bootstrap transaction and output index in authenticated app
   state after stable L1 observation; and
6. refuses to bootstrap again if the thread asset already exists or the seed
   input is spent.

The production minting policy is parameterized by the seed input, token name,
app-chain/feed hashes, and the complete initial `PublicationPolicyV1` fields.
Its `validatorHash` is the single source of truth and must identify the
supported generic v1 validator; there is no second validator-hash parameter.
Those fields exclude the thread policy id as
specified in section 6.2, so they determine the one-shot script and its
currency symbol without a self-reference. The policy derives the publication
hash from those independent fields and the target hash after adding its own
currency symbol, consumes the seed, mints exactly one unit, and requires
exactly one output at that validator with an
`OracleGenesisV1` datum whose `threadPolicyId` equals the policy's own currency
symbol and whose other identity, policy, committee, threshold, sequence, and
transition fields match. It reconstructs the exact bootstrap
`OracleTransitionCommitmentV1` using its own currency symbol and rejects a
different `transitionId`. It also enforces the initial publication threshold
through transaction signatories. It permits no other mint.

The reproducible construction order is:

1. freeze the generic validator artifact and independent initial publication
   policy;
2. instantiate the minting policy from the seed and independent parameters,
   then derive its policy id;
3. construct the immutable token identity and complete target profile using
   that policy id;
4. derive the bootstrap transition id from the now-complete Genesis successor;
   and
5. build the mint transaction whose policy verifies those derived fields,
   including its own currency symbol.

The policy verifies the Genesis fields rather than being parameterized by a
datum hash that itself contains the policy id; this makes construction
non-cyclic. A seed or funding key alone cannot omit required signers, mint the
unique token to a wrong address/datum, or permanently brick the feed.

Bootstrap, validator artifacts, policy id, script hash, seed outpoint, and
initial datum have reproducible golden vectors. Preview convenience funding is
outside the consensus protocol.

## 11. Oracle datum and effect contracts

### 11.1 Logical `OracleDatumV1` state sum

One all-purpose `VALID` datum cannot represent bootstrap or retirement and an
opaque publication-policy hash cannot tell Plutus which committee must sign. V1
therefore uses explicit Plutus constructors.

```text
OracleGenesisV1 = Constr 0 [
  appChainIdHash          : bytes(32),
  feedIdHash              : bytes(32),
  threadPolicyId          : bytes(28),
  threadAssetName         : bytes(0..32),
  publicationPolicyVersion: integer >= 1,
  publicationPolicyHash   : bytes(32),
  publicationSequence     : integer = 0,
  transitionId            : bytes(32),
  publicationKeyHashes    : list<bytes(28)>,
  publicationThreshold    : integer >= 1
]

OracleLiveV1 = Constr 1 [
  appChainIdHash          : bytes(32),
  feedIdHash              : bytes(32),
  threadPolicyId          : bytes(28),
  threadAssetName         : bytes(0..32),
  valuePolicyVersion      : integer >= 1,
  valuePolicyHash         : bytes(32),
  publicationPolicyVersion: integer >= 1,
  publicationPolicyHash   : bytes(32),
  publicationSequence     : integer >= 1,
  oracleRound             : integer >= 0,
  value                   : integer,
  scale                   : integer 0..18,
  roundEndL1Slot          : integer >= 0,
  validUntilL1Slot        : integer > roundEndL1Slot,
  finalizedAppHeight      : integer >= 1,
  sourceCount             : integer >= 1,
  sourceGroupCount        : integer >= 1,
  reporterCount           : integer >= 1,
  aggregationCode         : integer = 1,       // v1 lower median
  acceptedEvidenceRoot    : bytes(32),
  roundDecisionRoot       : bytes(32),
  transitionId            : bytes(32),
  publicationKeyHashes    : list<bytes(28)>,
  publicationThreshold    : integer >= 1
]

OracleRetiredV1 = Constr 2 [
  appChainIdHash          : bytes(32),
  feedIdHash              : bytes(32),
  threadPolicyId          : bytes(28),
  threadAssetName         : bytes(0..32),
  valuePolicyVersion      : integer >= 1 / null,
  valuePolicyHash         : bytes(32) / null,
  publicationPolicyVersion: integer >= 1,
  publicationPolicyHash   : bytes(32),
  publicationSequence     : integer >= 1,
  retiredAtL1Slot         : integer >= 0,
  previousTransitionId    : bytes(32),
  transitionId            : bytes(32),
  retirementReasonCode    : integer
]
```

Keys are strictly sorted and unique; the threshold cannot exceed their count.
List and numeric bounds are enforced by both decoder and validator. The two
Retired value-policy fields are both present when retiring Live and both null
when retiring Genesis; mixed presence is invalid. Genesis is not a usable
value. A consumer rejects Genesis, Retired, an unknown constructor, token
identity, governed endpoint/policy, or an expired Live datum.

`transitionId` avoids a cyclic effect encoding and gives every lifecycle
successor one correlation key. It is computed before `effects.emit()` from a
canonical `OracleTransitionCommitmentV1` containing the action code,
transition attempt, target-profile hash, an optional tagged predecessor
(transaction hash/index, constructor, sequence, optional oracle round, and
datum hash), plus every successor field except `transitionId` itself:

```text
transitionId = blake2b-256(
    "yano/oracle/transition/v1" || canonicalTransitionCommitmentCbor
)
```

Bootstrap uses a `NONE` predecessor; retirement has a tagged Genesis-or-Live
predecessor. An absent predecessor oracle round is encoded as CBOR null, never
as an overloaded integer sentinel.

The ADR-010 `EffectId` is assigned only after the complete payload is passed
to `emit()`, so it cannot appear inside its own payload or datum. The assigned
effect id is instead recorded beside `transitionId` in app-chain state and in
the L1 receipt/metadata.

The exact Plutus Data CBOR, deterministic-CBOR profile, text-to-id hashing,
numeric byte bounds, and constructor indices are protocol data and require
golden test vectors before acceptance. A consumer transaction must have a
finite validity upper bound no later than `validUntilL1Slot`; merely reading an
old output at a later slot is not acceptable. No update is published for
`NO_QUORUM` or `CIRCUIT_OPEN`; the prior Live datum naturally becomes stale.

Publication-policy timing fields are slot durations from the value round's
end, not executor wall-clock durations:

```text
validUntilL1Slot       = roundEndL1Slot + publicationValiditySlots
publicationDeadlineSlot = roundEndL1Slot + publicationDeadlineSlots
```

Arithmetic is checked. A policy is invalid unless both of these hold:

```text
reportGraceSlots < publicationDeadlineSlots
publicationDeadlineSlots + minimumStabilityMarginSlots <= publicationValiditySlots
```

A backlogged result whose deadline cannot still be met is never emitted; it
becomes `SUPERSEDED` or fails the declared `PUBLISH_EVERY_ROUND` profile. Exact
datum bytes are therefore created only when a deterministic transition can
bind the active publication policy, current predecessor, publication sequence,
transition attempt, and a still-usable deadline.

### 11.2 `cardano.oracle-update.v1`

The effect payload is canonical CBOR with at least:

```text
oracle-update-v1 = {
  version:                    1,
  target-alias:               tstr,
  target-profile-hash:        bstr .size 32,
  feed-id-hash:               bstr .size 32,
  value-policy-version:       uint,
  value-policy-hash:          bstr .size 32,
  publication-policy-version:uint,
  publication-policy-hash:   bstr .size 32,
  oracle-round:               uint,
  publication-sequence:       uint,
  transition-attempt:         uint,
  transition-id:              bstr .size 32,
  expected-predecessor-tx:    bstr .size 32,
  expected-predecessor-index: uint,
  expected-predecessor-kind:  uint,          ; Genesis or Live
  expected-predecessor-seq:   uint,
  expected-predecessor-round: uint / null,
  expected-predecessor-datum: bstr .size 32,
  successor-datum-cbor:       bstr,
  successor-datum-hash:       bstr .size 32,
  accepted-evidence-root:     bstr .size 32,
  round-decision-root:        bstr .size 32,
  publication-deadline-slot:  uint,
  maximum-tx-ttl-slots:       uint,
  minimum-stability-margin:   uint
}
```

The state machine, not the executor, produces `successor-datum-cbor`. The
executor verifies every duplicated field and hash, then places those exact
bytes in the continuing output. It may not reconstruct a semantically similar
datum from JSON or local settings.

The effect does not freeze an exact transaction validity interval before its
`L1_ANCHORED` gate opens. At durable preparation time the publication service
chooses an interval within `maximum-tx-ttl-slots`; its upper bound cannot exceed
`publication-deadline-slot`, and enough datum lifetime must remain for
`minimum-stability-margin`. Every committee signer verifies the chosen
interval. If the gate opens too late, the effect fails without submission and
the state machine may supersede it with a fresher finalized round.

The default intent is:

```text
type    = cardano.oracle-update.v1
gate    = L1_ANCHORED
result  = CHAIN
scope   = oracle/<feedId>
```

An explicitly unsafe preview/demo profile may use `APP_FINAL` and a single
publisher key. Mainnet-capable configuration MUST reject that profile.

### 11.3 Lifecycle effect types

Normal data publication does not overload its payload with unrelated
administration. Bootstrap and governed on-chain transitions use separate exact
types and codecs:

```text
cardano.oracle-bootstrap.v1   Genesis creation and constrained one-shot mint
cardano.oracle-governance.v1  Rotate, Migrate, or Retire transition
```

Both use `ResultPolicy.CHAIN`; production uses `L1_ANCHORED` and the applicable
current publication threshold. Their payloads bind byte-exact predecessor and
successor state, target profiles, action, publication sequence, transition
attempt, deadline, and validity/fee bounds. A lifecycle action has no value
round: its absolute deadline is the checked sum of the nonzero committed L1
high-water slot at deterministic effect emission and the active publication
policy's `publicationDeadlineSlots` (the initial policy for Bootstrap). The
currently active policy governs transaction TTL, fee, top-up, and other
execution bounds for Rotate/Migrate/Retire; the successor policy governs only
after activation. They receive independent golden vectors and executor routing
tests. A generic caller cannot select an arbitrary redeemer or script.

### 11.4 Result and stable publication state

On stable success:

- `externalRef` is the ASCII `txHash#outputIndex` of the exact successor;
- `detailHash` commits to a canonical receipt containing network, block hash,
  slot, confirmation depth, predecessor, successor, datum hash, token, round,
  transition id, and effect id; and
- the executor returns `CONFIRMED` only after the configured stability rule.

The machine joins effect results and stable L1 observations by transition id,
successor outpoint, and datum hash in either arrival order. A confirmed result
without the observation is `AWAITING_L1_OBSERVATION`; an observation without
the result is `OBSERVED_AWAITING_RESULT`. Only a matching pair becomes
`PUBLISHED` and releases the next feed update. A false or premature executor
attestation therefore cannot make a nonexistent oracle output authoritative in
app-chain domain state.

## 12. Validator and authorization invariants

### 12.1 State transition matrix

| Input | Redeemer | Output | Required rule |
|---|---|---|---|
| Genesis | Publish | Live | Sequence becomes 1; the first valid oracle round may be greater than zero |
| Live | Update | Live at same validator | Sequence increments by one; oracle round strictly increases |
| Genesis or Live | Rotate | Same constructor at same validator | Sequence increments; current committee authorizes the exact successor committee |
| Genesis or Live | Migrate | Same constructor at precommitted validator | Sequence increments; token and any live value continue |
| Genesis or Live | Retire | Retired at current validator | Sequence increments; terminal state |
| Retired | any | none | No further spend is valid |

`publicationSequence` is consecutive for every canonical UTxO transition.
`oracleRound` need only strictly increase on Publish/Update. This permits
`NO_QUORUM`, `CIRCUIT_OPEN`, and coalesced rounds without breaking the on-chain
state machine.

### 12.2 Validator invariants

For every permitted transition from a nonterminal datum, the oracle validator
MUST enforce:

1. exactly one input at the current oracle validator contains exactly one unit
   of the policy id/asset name declared by its own datum;
2. exactly one successor at the redeemer-authorized endpoint contains exactly
   that NFT and preserves both immutable token-identity fields;
3. no transaction output contains another unit of the NFT and the transition
   does not mint or burn it;
4. feed and app-chain identity are unchanged;
5. the successor publication sequence is exactly the input sequence plus one;
6. the successor datum is well formed, bounded, and has the constructor
   permitted by the transition matrix;
7. every non-lovelace asset in the state output is exactly preserved;
8. successor lovelace is at least predecessor lovelace; fees, collateral, and
   change never drain the oracle state output; and
9. the current input datum's threshold of distinct listed publication key
   hashes is present in transaction signatories.

For Publish/Update, the successor is Live, preserves the active publication
policy, committee, and endpoint, strictly increases its oracle round relative
to any Live predecessor, and has a finite consuming-transaction validity upper
bound no later than its `validUntilL1Slot`. For Rotate/Migrate, the successor
preserves its Genesis-or-Live constructor and all existing
live-value/provenance fields while changing only the exact committed
publication policy, committee, endpoint where applicable, sequence, and
transition id. Retire creates the exact terminal Retired constructor and
cannot be used as a value. No redeemer can spend Retired.

The validator cannot recompute off-chain reports. Its job is continuity and
publication authorization. Publication signers bridge finalized app-chain
state to the Cardano transaction. Policy limits that are not efficiently
derivable on-chain, such as maximum fee, maximum TTL, target-profile match, or
maximum top-up, are checked independently by every publication signer.

### 12.3 Threshold publication authorization

The transaction coordinator is not itself an oracle authority. For production
updates it first discovers a threshold-sized responsive subset of the current
datum committee, then constructs one complete transaction body whose
`required_signers` are exactly that selected subset. Each signer independently
verifies against its own node:

- the app-chain block and effect are finalized;
- the effect is past its configured finality gate;
- the effect payload and datum hash match local authenticated state;
- the target profile hash matches the committed publication policy and
  immutable feed identity;
- the predecessor is the current stable thread-NFT UTxO;
- the body has exactly one valid successor with the exact datum bytes;
- fee, validity interval, collateral, change, top-up, and all other outputs
  obey policy; and
- the body requires exactly the selected current-committee signer subset.

Only then does it sign the Cardano transaction body hash. The coordinator
collects at least `publicationThreshold` valid witnesses and submits the
unchanged body. This follows the ADR-008.4 script-anchor pattern while using a
separate message domain, state identity, and validator.

Once any signer signs, predecessor, body, signer subset, validity interval, and
body hash are locked until the exact matching successor is stable or the
transaction's upper validity bound is stably past. A shallow observation does
not release the lock. A coordinator may not silently rebuild with another
subset. Each signer persists this lock before releasing its witness.

A single hot-wallet signature proves only that the wallet signed; it does not
prove the datum came from finalized app-chain state. Single-signer publication
is therefore a declared preview/demo trust mode, not the production default.

### 12.4 Rotation, migration, and retirement

- Reporter/source policy changes activate at a future round through a new
  value-policy version.
- Publication-committee rotation is authorized by the current datum committee.
  The successor datum preserves its Genesis/Live constructor and contains the
  sorted next key hashes and threshold from the committed pending publication
  policy.
- Validator migration spends the old canonical UTxO through an explicit
  migration redeemer and creates one successor of the same constructor at the
  committed new validator with the same thread NFT and any existing live
  value. It requires the current publication threshold and a precommitted
  target profile hash.
- Retirement creates a terminal datum under threshold authorization. It does
  not silently destroy the identity token. Consumers reject Retired or expired
  state.

Exact rotation, migration, and retirement redeemers and invariants must be
frozen with the validator ABI; an implementation without a tested migration
path is not production-complete.

The current publication threshold is a terminal trust and liveness assumption:
if that threshold is compromised it can authorize a malicious successor; if it
is permanently unavailable it can prevent rotation or retirement. App-chain
consensus cannot repair that on-chain authority by itself. An independently
authorized time-locked recovery branch would change the trust model and
requires a separate security decision; v1 does not imply one.

## 13. Publication lifecycle, ordering, and recovery

### 13.1 Per-feed ordering

Only one Cardano publication or lifecycle effect per feed may be open at a
time. Later rounds can finalize in app-chain state while publication is in
flight, but they remain `READY`, not independently executable against the same
predecessor. A pending Rotate/Migrate action likewise waits for any earlier
effect to reach a safe terminal join, then deterministically binds the current
canonical predecessor; governance acceptance does not guess its future
outpoint or transition id.

An effect result alone does not release the next update. The current
publication must reach `PUBLISHED` through the order-independent result/L1
join, or reach a safe non-publication decision after the consensus-known
effect `publicationDeadlineSlot` plus stability margin is past. The actual
prepared transaction upper bound is node-local receipt data and is never used
as a consensus input. Only then does a later deterministic `apply()` emit the
next eligible update.

If several rounds accumulated, v1 publishes the newest still-fresh valid round
and marks older unpublished rounds `SUPERSEDED`; their evidence remains
auditable. This avoids paying to publish already stale intermediate prices
while preserving history.

Feeds whose legal/audit semantics require every round to be published must use
an explicit `PUBLISH_EVERY_ROUND` profile with adequate cadence and funding.
They fail closed on backlog rather than silently coalescing.

The correlation rules are:

| Effect fact | Stable L1 fact | Domain action |
|---|---|---|
| CONFIRMED | matching successor | `PUBLISHED`; advance predecessor |
| absent/pending | matching successor | store `OBSERVED_AWAITING_RESULT`; do not replace |
| FAILED/EXPIRED | matching successor | `PUBLISHED_AFTER_TERMINAL`; preserve terminal effect history and advance predecessor |
| CONFIRMED | absent | `AWAITING_L1_OBSERVATION`; do not advance |
| FAILED/EXPIRED | absent before publication deadline | fence replacement; the prepared tx may still land |
| CONFIRMED | absent after publication deadline + margin | halt: executor result and verified L1 history conflict |
| any | mismatched token/datum/outpoint for the transition id | halt the feed and raise a security incident |

Every prepared transaction upper bound is at or before the committed
`publicationDeadlineSlot`. After that deadline plus stability margin is past,
it can no longer land without already having produced an injectable stable
observation. If no matching observation exists and no CONFIRMED result claims
otherwise, app-chain state may record `NOT_PUBLISHED` and emit a new effect
against the still-expected predecessor. Publication signers independently
verify that predecessor before signing; a competing spend therefore fails
closed.

### 13.2 Durable prepare-before-submit journal

Before the first network submission, the Cardano publication service durably
stores and fsyncs at least:

```text
chain id, effect id/hash, feed, optional round, target profile hash
transition id, transition attempt, and publication sequence
predecessor outref and datum hash
successor datum bytes/hash and expected output index
unsigned body hash, complete signed transaction bytes, transaction hash
validity interval, signer set, creation time, and journal state
```

The coordinator then distributes the complete signed transaction and journal
identity to the selected publication signers. At least the publication
threshold must durably acknowledge the same transaction hash before any node
may submit it. Signers have already persisted the body lock before releasing
their witnesses. A failover coordinator retrieves and reuses those exact
bytes; it does not rebuild from effect data.

Cardano cannot cryptographically prevent a holder of the complete witness set
from broadcasting before those acknowledgements. Such a broadcast is a
publication-protocol violation and operator incident, but it must remain
recoverable: before releasing a witness, every selected signer persists the
exact body bytes and its exact witness. A failover can retrieve the locked
body and original threshold witness bytes, canonically reassemble the same
transaction, and reconcile/resubmit it; it may not re-sign, change the signer
set, or perform coin selection. Thus a Byzantine coordinator can damage
availability and operations, but cannot change the threshold-approved
successor or force an ambiguous rebuild.

Only then may it submit. Every retry after an ambiguous response polls by
transaction hash or resubmits the exact signed bytes. It never performs new
coin selection, changes fees or TTL, chooses a new predecessor, or rebuilds a
different transaction under the same effect id.

If the transaction expires without appearing on-chain, the journal records a
terminal expired preparation. A replacement requires a new deterministic
effect or explicitly versioned recovery instruction; it is not an invisible
executor retry.

Loss of every replicated journal copy after ambiguous submission is a
catastrophic operational fault. Backups and retention must cover at least the
maximum validity and reconciliation window. V1 does not claim safe failover by
rebuilding when no copy of the prepared bytes survives.

### 13.3 Wallet and UTxO coordination

- All Cardano effects sharing a funding wallet use one wallet-level
  coordinator, not one Java `synchronized` lock per executor instance.
- Oracle updates are additionally single-flight per thread NFT.
- Coin selection excludes UTxOs reserved by durable prepared transactions.
- Publication policy plus global limits bound fee, collateral, min ADA, datum
  bytes, validity interval, and outstanding prepared transactions.
- The publisher wallet is dedicated and minimally funded. It cannot authorize
  an oracle transition without the publication committee.
- Signing secrets use `SignerProvider` or opaque KMS/HSM references; mnemonics
  do not appear in effect payloads, app state, logs, metrics, or dashboard data.

### 13.4 Confirmation and rollback

`L1_ANCHORED` gates the app-chain effect before execution. It does not make the
later oracle transaction final.

The executor and L1 observer require the exact successor at a configured
Cardano stability depth. A transaction hash alone is insufficient: the output
index, validator, NFT, datum hash, block hash, and slot must match.

An ordinary rollback before stability prunes the pending L1 observation and
keeps the journal submitted/pending while the exact transaction remains valid.

ADR-008.4 treats observations deeper than the configured stability depth as
final for app-chain purposes. Current `L1Observer` has no consensus protocol
for retracting an already incorporated stable observation. A rollback deeper
than that assumption is therefore fail-stop in oracle v1: publication halts,
health becomes `DOWN`, and operators perform a separately governed/manual
reconciliation after establishing the canonical L1 identity. Old effect and
observation history is never rewritten. Automatic authenticated orphan
observations require a new foundation ADR covering rollback callbacks,
negative-state verification, retained L1 history, and follower disagreement;
this ADR does not pretend that capability already exists.

## 14. Configuration, plugins, and secrets

### 14.1 Proposed module ownership

```text
appchain/extensions/appchain-oracle/
    deterministic oracle machine, codecs, queries, domain API, operations view

appchain/extensions/appchain-effects-cardano-oracle/
    cardano.oracle-update.v1 effect adapter and stable receipt handling

runtime/core Cardano publication service (planned ADR-010.2)
    committed effect/proof access, target verification, tx builder/evaluator
    member signer resolution, co-sign transport, replicated journal/fencing
    L1 UTxO view, submission, and lifecycle-safe shutdown

appchain/onchain/appchain-oracle-onchain/
    thread policy, validator artifacts, ABI, and conformance vectors

appchain/extensions/appchain-oracle-l1-observer/
    pure observer for supported oracle validator versions and datum claims

appchain/examples/appchain-oracle-demo/
    manifested bundle/config, sample reporters, scripts, and no-code UI recipe
```

The exact Gradle layout may be adjusted, but deterministic domain logic,
privileged Cardano execution, and on-chain artifacts remain separately owned
and testable. The recommended oracle executor module is separate from the
existing `appchain-effects-cardano` payment plugin, so a payment-only node does
not acquire oracle scripts, codecs, or lifecycle behavior. Distributions may
co-package both modules. `cardano.payment`, native-asset transfer, and oracle
publication share wallet/journal infrastructure without sharing effect payload
semantics.

The current `AppEffectExecutorFactory.create(chainId, settings)` and
`EffectExecutionContext` do not expose committed-state proofs, member signers,
member diffusion, transaction evaluation/submission, L1 UTxOs, or durable
shared storage. `ScriptAnchorService` is runtime-internal and cannot simply be
called by a plugin. O-M0/ADR-010.2 MUST therefore define a reusable typed
publication/co-sign foundation and its lifecycle boundary before O-M3. This is
an explicit dependency, not assumed existing plumbing.

Most implementation is therefore isolated in new optional modules, but this is
not a zero-touch plugin-only feature. The required shared changes are narrow
and reusable:

- `core-api` gains capability contracts through which a privileged executor
  can request authenticated effect/proof data, L1 UTxO access, signer
  resolution, transaction services, and durable publication coordination;
- `runtime` implements the generic publication/co-sign service, wallet-level
  reservation, signer locks, and replicated prepare-before-submit journal;
- `app` supplies the first-party oracle operations adapter, REST/UI wiring, and
  Micrometer export; and
- existing Cardano effect modules may adopt the same hardened foundation later.

Oracle reports, aggregation, policy, datum, validator, and feed lifecycle rules
MUST NOT enter Yano's ledger, consensus, P2P, or Cardano protocol core. Those
remain extension-owned. The shared API/runtime work belongs in ADR-010.2 and
must be generic enough for payments, native assets, and future Cardano effects,
not designed as an oracle back door into runtime internals.

The oracle L1 observer is pure over each L1 block. V1 app-chain genesis
pre-registers a bounded subscription set of exact
`(validatorHash, threadPolicyId, threadAssetName)` identities, including an
unminted bootstrap identity and any permitted migration endpoints. Every
member derives the same observer settings/fingerprint from that committed
genesis configuration. V1 does not dynamically add a new thread-token identity
after genesis; doing so requires a state-fed observer-subscription contract in
a later ADR.

The observer filters exact token identities before decoding or counting oracle
outputs. An attacker sending lookalike datums or unrelated assets to the public
generic validator therefore cannot consume the subscribed observation budget.
Quantity one guarantees a unique current UTxO, but it does not imply only one
transition per block: successive transactions in ledger order may spend
successive versions of the same token. The production protocol never signs a
second transition before the first is stable. If a block nevertheless contains
more than one transition for a subscribed token, v1 emits one canonical
`MULTIPLE_ORACLE_TRANSITIONS_IN_BLOCK` continuity incident containing the
bounded count, first/last transaction identities, and a commitment over all
ordered transitions, and halts that feed for manual reconciliation. It never
selects the first/last transition silently or truncates the block.

For a subscribed token, the observer always emits either a canonical valid
claim containing endpoint, token, transaction hash/index, predecessor,
successor output index, datum hash, constructor, transition id, and sequence,
or a canonical `INVALID_ORACLE_SUCCESSOR` claim with bounded reason code and
raw datum hash. The multi-transition incident above takes precedence for that
token/block. Expected malformed data is never converted into a swallowed
plugin exception or silent truncation. Adding a validator version requires all
members to install it and pre-register its endpoint before a governed
migration.

### 14.2 Reporter deployment

V1 reporters may run as external processes using `AppChainClient`. This keeps
API keys, vendor libraries, and nondeterministic scheduling outside consensus
and avoids adding a generic source SPI prematurely.

A manifested auxiliary plugin may later host source adapters when there is a
concrete lifecycle contract. Such a plugin still submits signed app messages;
installing it on a member never grants direct state mutation or deterministic
execution privileges. Multiple adapters using one reporter key count as one
reporter.

### 14.3 Configuration and secret classes

| Data | Location | Replicated/authenticated |
|---|---|---|
| Feed/source/reporter policy | Oracle app-chain state | Yes |
| Target identity and committee | Oracle app-chain state and datum | Yes |
| Source API credentials | Reporter secret store | No |
| Reporter private key | Reporter signer/KMS | No |
| Publisher funding key | Executor signer/KMS | No |
| Member publication keys | Existing member signer providers | Public keys only in policy |
| Backend URL and journal storage policy | Publication service configuration | No |
| Prepared transaction/body locks | Replicated publication journals | No, but quorum-durable/fenced |
| Reports and evidence commitments | App-chain state under retention policy | Yes |
| Inline datum and tx witnesses | Cardano L1 | Public and durable |

No observation or datum should contain PII, credentials, private source bodies,
or confidential business data. Publish a hash/CID and keep encrypted content
under an explicit data-availability policy when confidentiality is required.

## 15. API, observability, and no-code demonstration

### 15.1 Committed queries and domain views

The oracle bundle exposes bounded committed queries for:

- feed identity, active/pending policies, and target hash;
- current round, round clock, close slot, and quorum progress;
- accepted/rejected report counts and bounded dispositions;
- source/group values, aggregate, accepted evidence root, decision root, and
  inclusion proof;
- current publication state and in-flight effect;
- canonical/stable Cardano output reference and datum hash; and
- pause, stale, circuit, continuity halt, migration, and retirement state.

Read authorization follows the chain's read policy. Reporter submission and
feed administration are separately authenticated operations; viewing a public
feed must not require an administrator key merely because plugin operations do.

### 15.2 Health and metrics

ADR-011.4's `PluginHealthSource` and `PluginMetricsSource` describe a bundle's
node-local lifecycle and expose activation-frozen, unlabeled custom series.
Their v1 contexts do not provide an app-chain query facade, and their metric
contract permits only the host-added `plugin` and `metric` tags. They therefore
MUST NOT be used to pretend that dynamic per-feed state metrics already fit the
generic plugin-telemetry SPI.

Oracle v1 instead defines a narrow first-party host-owned
`OracleOperationsView` over committed oracle queries and the local publication
service. The app adapter exports its cached snapshots to the oracle API,
dashboard, health view, and Micrometer. Its possible `(chain, feed)` series are
allocated from the startup-frozen set of genesis-pre-registered feed
identities; governance or message contents cannot create labels at runtime.
The existing ADR-011.4 contribution remains bundle-level installation,
lifecycle, and sampler telemetry. A general context-enabled plugin
health/metrics v2, if justified by other domains, requires a separate plugin
ADR and is not assumed here.

Suggested bounded metrics include:

```text
yano_oracle_feed_status{chain,feed,status}
yano_oracle_round_total{chain,feed,outcome}
yano_oracle_report_total{chain,feed,disposition}
yano_oracle_source_group_quorum{chain,feed}
yano_oracle_reporter_quorum{chain,feed}
yano_oracle_last_valid_round{chain,feed}
yano_oracle_last_publication_slot{chain,feed}
yano_oracle_publication_lag_rounds{chain,feed}
yano_oracle_circuit_open{chain,feed}
yano_oracle_continuity_halt_total{chain,feed,reason}
```

Feed ids must belong to that startup-frozen allowlist. Reporter keys, source
URLs, transaction hashes, errors, and arbitrary user input are not metric
labels. Detailed or later-governed state remains in the bounded committed
domain API rather than creating new metric series.

The oracle operations view reports a feed as `UP` only when the active policy
is valid, required L1 view is fresh, publication continuity is reconciled, and
the current datum has not expired. Missing quorum may be `DEGRADED` before
expiry and `DOWN` after expiry. A continuity violation, target-hash mismatch,
journal corruption, or unauthorized successor is `DOWN`. Feed health is an
operator signal; it does not alter app-chain consensus or generic node
liveness/readiness.

### 15.3 No-code devnet demonstration

The first-party demo should start from the existing app-chain cluster tooling
and require no Java code:

1. Start a three-member devnet app chain with script anchoring and the oracle
   bundle.
2. Bootstrap an `ADA-USD` oracle thread UTxO from a documented command or UI
   action.
3. Start three sample reporter processes representing at least three named
   sources and distinct reporter keys.
4. Submit normal values, one deliberate outlier, and one duplicate.
5. Show source-group/reporter quorum, report dispositions, median, both
   evidence commitments, finalized height, transition id, and effect id in
   the UI.
6. Show publication witness collection, transaction status, and stable
   `txHash#outputIndex`.
7. Decode the inline datum and verify that its value, evidence roots, and
   transition id match committed app-chain state and the separately displayed
   effect/receipt.
8. Run a sample consumer transaction that uses the UTxO as a reference input
   and rejects an expired or counterfeit datum.

The demo UI may simulate source values only when clearly labelled. A production
example uses real external sources under their license and rate-limit terms.

## 16. Versioning and governance

### 16.1 Replay-stable machine changes

Oracle transition, report, aggregation, evidence, and effect-emission changes
follow ADR-010.1. Old branches remain available below their activation height;
new code cannot reinterpret historical rounds under new rules.

Value-policy versions are data, not local settings. Each has a future
`effectiveRound`, was finalized before that round began under the feed's
immutable clock, and is recorded identically by every member. A round is
permanently tied to its selected value policy. Publication-policy versions use
the two-phase stable-L1 activation rule. State-machine code changes still use
ADR-010.1 activation heights.

### 16.2 Independent version axes

The following versions are explicit and independent:

- report schema version;
- value-policy and publication-policy versions;
- aggregation/evidence algorithm code;
- effect type/schema (`cardano.oracle-update.v1`);
- datum constructor/schema;
- validator/script version; and
- target profile hash.

Changing one does not silently reinterpret another. An incompatible effect
payload receives a new exact type string so old pending effects remain
executable by the old executor. A datum/validator change follows the governed
migration path rather than changing bytes at an existing script identity.

### 16.3 Configuration governance

Genesis installs the bounded v1 feed/token subscription set and first policies.
Later source or reporter changes, value-policy thresholds, pause/resume,
committee rotation, circuit reset, and pre-registered endpoint migration are
authenticated oracle-governance messages. Dynamic creation of a new thread
token/observer subscription is deferred beyond v1.

Only one governance update may be pending. The machine verifies the current
governance threshold, current governance-state hash, next sequence, complete
new value/publication/governance hashes, effective round, and activation lead
time in `apply()`. A value-only change activates at its effective round. A
governance-key rotation also activates at that round, after which control
messages require the new keys. A publication-policy change pauses ordinary
publication, performs the old-policy-authorized L1 transition, records
`L1_APPLIED` on its exact stable observation, and activates only after the
matching result/observation join. The composite update remains pending until
every changed axis activates, so no later update may be pre-authorized through
an intermediate key rotation. The narrowly defined, safely fenced
pending-publication cancellation in section 6.2 is the only exception and is
not a replacement configuration.
The first implementation may use a static genesis governance committee, but it
cannot treat an executor-local edit or app-chain finality alone as feed-admin
authorization.

## 17. Threat model and failure handling

| Threat / failure | Required response |
|---|---|
| Reporter submits twice | Canonical duplicate; one weight |
| Reporter signs conflicting values | Exclude reporter for the round; record equivocation |
| Several reporters use one source | Reporter quorum may rise; source-group quorum does not |
| Several source aliases share one upstream | One configured independence group and one aggregate vote |
| One reporter claims several unsigned sources | Source-group policy exposes upstream claims; reporter quorum still limits one reporter |
| Old source-signed response is replayed | Source-auth profile binds timestamp/sequence, value, unit, and round; reject |
| Source API compromised | Group diversity, outlier bounds, circuit breaker; no claim of objective truth |
| All sources collude or share upstream | Not cryptographically solvable; explicit trust-model limitation |
| Member has different local source/backend file | Irrelevant to `apply()`; committed policies are authoritative |
| Executor alias points to wrong script/network | Target-profile hash mismatch; fail closed before signing |
| Attacker sends fake datum to oracle address | Ignored because it lacks the unique thread NFT |
| Coordinator proposes unauthorized datum | Independent signers reject; validator requires threshold |
| Funding key compromised | Key alone cannot meet production publication threshold; minimally fund and rotate |
| Current publication threshold compromised | It can corrupt or maliciously rotate state; terminal trust failure without a separately designed recovery branch |
| Current publication threshold unavailable | Publication and on-chain rotation can halt; app-chain consensus cannot bypass it |
| Crash before journal write | Nothing submitted; safe retry |
| Crash after journal write, before submit | Recover quorum-replicated exact bytes and submit |
| Crash after submit, before local status | Reconcile/poll replicated transaction hash; never rebuild |
| All prepared-transaction journals lost | Catastrophic operational fault; do not rebuild after ambiguous submission |
| Competing update spends predecessor | Per-feed lock and validator continuity; reconcile canonical UTxO |
| Transaction TTL expires | Terminal preparation; new recovery effect required |
| Shallow Cardano rollback | Remain pending until stable |
| Matching L1 update arrives after FAILED/EXPIRED | Preserve terminal effect history but advance canonical predecessor as `PUBLISHED_AFTER_TERMINAL` |
| Deep rollback after stable observation | Fail-stop/manual governed reconciliation; no automatic v1 retraction protocol |
| Quorum unavailable | `NO_QUORUM`; no fresh publication; existing datum expires |
| Extreme aggregate movement | `CIRCUIT_OPEN`; no publication pending governance action |
| Evidence pruned | Preserve root/count/round/value-policy hash and return explicit pruned semantics |
| External evidence body unavailable | Report commitment without claiming data availability |
| Invalid-report spam | Fixed counters/rolling commitment/bounded samples; deterministic capacity disposition |
| Sensitive source body submitted | Reject by size/schema where possible; operational incident if already replicated |

## 18. Alternatives considered

### A. Extend `cardano.payment` with optional datum and script address

Rejected. Payment and oracle update have different payloads, authorization,
UTxO continuity, validator, confirmation, and result semantics. They should
share hardened Cardano infrastructure, not one overloaded effect contract.

### B. Append a new datum output for every round

Rejected as the authoritative profile. It is easy to implement but does not
establish a unique current value, permits counterfeit lookalikes, and
accumulates minimum-ADA outputs. It remains a possible non-authoritative
utility effect.

### C. Let every app-chain member fetch every source in `apply()`

Rejected. Timing, network response, rate limit, and failure differences would
produce different state roots or duplicate external traffic. Reporters turn
nondeterministic observations into signed deterministic input data.

### D. Let the effect executor fetch and aggregate sources

Rejected for authoritative feeds. This reduces the trust model to one
executor's uncommitted observation and makes replay/audit incapable of
reconstructing the decision. The executor publishes, it does not discover the
value.

### E. Use one publisher key with a thread NFT

Rejected as the production default. The NFT establishes continuity but a
compromised publisher could still spend the valid UTxO and write an arbitrary
successor. Threshold signers must verify finalized state before authorizing.

### F. Count only app-chain finality as the oracle quorum

Rejected. Members can agree perfectly that one bad source said something.
Source-group, reporter, finality, and publication thresholds address different
failures.

### G. Publish the full observation set in the datum

Rejected. It increases transaction size, min ADA, script cost, and privacy
exposure. The datum carries the aggregate and two decision/evidence
commitments; authenticated app-chain queries or evidence storage provide
details.

### H. Publish no expiry and let consumers infer freshness

Rejected. Consumers need an unambiguous slot bound and must fail closed when
updates stop.

## 19. Delivery roadmap and acceptance gates

### O-M0 — Protocol freeze and safety dependency

- accept or revise this ADR;
- complete the shared Cardano executor safety ADR-010.2;
- define the reusable publication/co-sign service because the current
  `AppEffectExecutor` context cannot supply proof, signer, member-transport,
  L1-state, transaction, or replicated-journal capabilities;
- freeze report, value-policy, publication-policy, governance, evidence,
  lifecycle transition, effect, datum, receipt, and journal encodings under
  one deterministic CBOR profile with cross-module golden vectors and exact
  byte/integer/cardinality limits;
- freeze the generic validator artifact, non-cyclic mint-policy construction,
  precomputable transition-id contract, genesis subscription set, and
  two-phase publication-policy activation rule;
- freeze source-auth profiles, effective-round rules, tick/closure ordering,
  observer claims, signer locks, and result/observation precedence;
- decide network-specific stability defaults and production/demo policy gates;
  and
- publish threat-model and key-ceremony runbooks.

### O-M1 — Deterministic oracle bundle

- implement feed identity/policy state, signed report ingestion, round clock,
  duplicate/equivocation handling, normalization, quorum, outlier, circuit,
  lower median, dual evidence commitments, resource caps, tick closure, and
  committed queries;
- add height-activated code and effective-round policy replay
  compatibility; and
- provide sample external reporter clients with deterministic fixtures.

### O-M2 — On-chain identity and validator

- implement the constrained mint policy, Genesis/Live/Retired datums,
  publication sequence, update, Genesis/Live rotation and migration, and
  retirement paths;
- bundle reproducible artifacts and script identity metadata;
- add independent validator conformance vectors and budget/size gates; and
- implement a consumer reference-input fixture.

### O-M3 — Hardened Cardano publication

- add `cardano.oracle-update.v1` as the separate optional
  `appchain-effects-cardano-oracle` module;
- implement the foundation publication service, shared wallet coordination,
  signer body locks, and quorum-replicated prepare-before-submit journal;
- implement proposed-body verification and threshold witness collection;
- implement the supported-validator-family L1 observer, exact-successor
  confirmation for exact genesis-pre-registered token identities, canonical
  invalid-successor claims, and order-independent result/observation join; and
- implement pre-stability rollback handling and fail-stop detection for a
  violated deep-stability assumption.

### O-M4 — Operations and no-code product flow

- add feed/report/evidence/publication domain APIs;
- add the first-party host-owned oracle operations view, startup-bounded
  per-feed health/metrics, and bundle-level ADR-011.4 telemetry;
- extend the status UI with the oracle flow and datum decoder;
- provide cluster bootstrap/load/inspect commands; and
- document developer, reporter, operator, and consumer workflows.

### O-M5 — Adversarial and end-to-end closure

The ADR may move to Accepted/Implemented only after all gates below pass.

#### Determinism and replay

- Permuting message arrival within the same ordered block fixture cannot
  change the canonical policy result where ordering is declared irrelevant.
- Duplicate, equivocation, malformed signature, wrong domain, stale/future,
  self-contained source assertion/replay, integer-boundary, normalization,
  even-count median, zero/negative deviation, outlier, and circuit property
  tests pass.
- The same observations, policy set, predecessor, and transition attempt
  produce byte-identical aggregate, accepted evidence root, decision root,
  state root, transition id, datum, and effect payload on every member.
- Restart, snapshot restore, full replay, and mixed old/new binaries around
  every activation height reproduce historical roots.
- Byzantine reporter and correlated-source simulations demonstrate the
  declared fault threshold and its limitations.
- Report/tick/config messages in one block obey the specified pre-scan and
  end-of-block closure rule; idle catch-up is bounded and deterministic.
- Invalid-report floods do not create unbounded state keys or work.

#### Validator and Cardano protocol

- Counterfeit outputs at the same address are ignored.
- Missing, duplicated, minted, burned, or redirected thread-NFT transitions
  fail.
- Wrong feed, app chain, value/publication policy, publication sequence, round,
  datum constructor, validity, output value, successor count, or
  current-committee signatures fail.
- Bootstrap without the initial publication threshold, with a wrong seed,
  policy id, asset name, publication-policy/generic-validator mismatch,
  transition id, Genesis fields, or extra mint fails; golden vectors prove
  construction has no validator/policy hash cycle.
- Fee/value drain, extra output, signer substitution, replayed body, and
  unauthorized rotation/migration/retirement fail.
- Genesis and Live rotation/migration preserve constructor, token identity,
  sequence, and any live value while changing only the authorized policy or
  endpoint fields.
- Script execution units, transaction size, datum size, and collateral stay
  below explicit release gates.
- Consumer tests accept the canonical fresh reference input and reject fake,
  Genesis, expired, Retired, wrong-endpoint, and wrong-feed datums; their finite
  validity upper bound never exceeds datum expiry.

#### Execution and recovery

- Crash tests cover before journal write, after journal write, before submit,
  ambiguous submit, after submit, after inclusion, and before result
  persistence.
- Every post-prepare retry uses byte-identical signed transaction bytes.
- After a safely proven non-publication, any replacement increments the
  committed transition attempt and cannot reuse the prior transition id.
- Coordinator failover recovers quorum-replicated exact bytes; loss of every
  journal copy fails closed rather than rebuilding.
- Shared-wallet contention, competing UTxO spend, transaction TTL, effect
  expiry, signer timeout, subset lock, and partial quorum are tested.
- Signer locks survive restart and are released only after the exact successor
  is stable or the prepared body's upper validity bound is stably past.
- Result-before-observation, observation-before-result,
  publication-after-FAILED/EXPIRED, mismatched observation, and safe
  non-publication after validity expiry are tested.
- Shallow rollback does not confirm; a simulated rollback beyond stability
  halts rather than inventing an unsupported retraction protocol.
- Delayed publication respects per-feed ordering and the configured
  coalescing/every-round policy.

#### Cluster closure

- A three-member devnet test submits reports from independent reporter keys,
  finalizes an aggregate, anchors the effect block, threshold-signs the update,
  observes the stable UTxO on every node, and incorporates the matching result.
- Every node's committed query agrees on round, accepted evidence root,
  decision root, datum hash, transition id, effect id, and stable output
  reference.
- Bootstrap, update, rotate, migrate, and retire each correlate effect,
  journal, datum, observer claim, and receipt by the same transition id.
- A publication-policy update remains `PENDING_L1`, pauses ordinary updates,
  records `L1_APPLIED` on the matching stable Rotate/Migrate observation, and
  becomes active only after the result/observation join; its value/governance
  axes activate at the effective round, and a second pending governance update
  is rejected until every axis completes.
- Pending-publication cancellation is rejected before the lifecycle deadline
  fence, after a matching observation, or on a contradictory CONFIRMED result;
  after a proven non-publication it clears only the pending publication axis.
- A node with no Cardano executor still derives identical oracle/app-chain
  state.
- Unsubscribed lookalike outputs are ignored without consuming the observer
  budget; a malformed successor carrying a subscribed token produces the same
  bounded invalid-successor claim on every member.
- Two same-token transitions in one L1 block produce one canonical committed
  continuity incident and halt rather than silently selecting either output.
- A target-profile mismatch and an unauthorized publisher fail closed without
  submitting a transaction.
- The no-code UI demonstrates normal, duplicate, outlier, no-quorum, stale,
  and recovery paths.

## 20. Decisions, open questions, and consequences

### 20.1 Decisions

| ID | Decision |
|---|---|
| D1 | Model truth as policy-backed attestation, never as a consequence of consensus alone. |
| D2 | Keep nondeterministic source acquisition outside the state machine. |
| D3 | Track source-group, reporter, app-finality, and publication thresholds separately. |
| D4 | Use deterministic fixed-point lower-median aggregation for v1. |
| D5 | Commit accepted evidence with a Merkle root and all report decisions with a separate bounded commitment. |
| D6 | Bind every round to a pre-finalized value policy selected by immutable effective round; activate publication-policy changes only after their stable L1 transition. |
| D7 | Use `cardano.oracle-update.v1`, not `cardano.payment`, for publication. |
| D8 | Put exact datum bytes and the committed target-profile hash in the effect. |
| D9 | Use one threshold-authorized continuing thread-NFT UTxO as canonical state. |
| D10 | Allow single-publisher mode only as an explicitly unsafe preview/demo profile. |
| D11 | Quorum-replicate the fully signed transaction before submission and never rebuild after ambiguous submission. |
| D12 | Require stable exact-successor L1 observation before domain state becomes `PUBLISHED`. |
| D13 | Publish at most one in-flight update per feed and make backlog policy explicit. |
| D14 | Keep full source data out of the Cardano datum; publish an evidence commitment. |
| D15 | Use a precomputable transition id containing a monotonic attempt counter for every L1 action; do not self-reference the later-assigned effect id. |
| D16 | Represent Genesis, Live, and Retired as explicit datum constructors with on-chain committee policy. |
| D17 | Use verified L1-slot blocks plus redundant member ticks for deterministic round closure. |
| D18 | Treat rollback beyond the configured L1 stability assumption as fail-stop/manual in v1. |
| D19 | Keep value provenance and Cardano publication policy on separate version/hash axes. |
| D20 | Use one generic validator per datum ABI; bind each feed by token identity in the datum and target profile. |
| D21 | Pre-register exact thread-token observer subscriptions at genesis; dynamic feed-token creation is beyond v1. |
| D22 | Export per-feed operations through a first-party host view with startup-frozen series, not through ADR-011.4's unlabeled custom plugin metrics. |

### 20.2 Questions to close during O-M0

1. Should production publication use the app chain's current membership keys
   directly, or a separately governed subset whose keys are still resolved
   through `SignerProvider`?
2. What Cardano stability depth is the default for preview/preprod/mainnet,
   and which value is merely operational versus part of the feed policy?
3. Should the first release support both `COALESCE_TO_LATEST` and
   `PUBLISH_EVERY_ROUND`, or ship only the safer price-feed default and add the
   audit profile later?
4. Which source-native signature adapters are included in the reference demo,
   considering licensing, rate limits, and reproducibility?
5. Does evidence inclusion proof reuse a generic app-chain Merkle utility, or
   remain an oracle-owned codec with shared golden vectors?
6. Should a separately authorized, time-locked on-chain recovery branch exist,
   accepting that it adds another high-value trust root?

These questions affect implementation shape or defaults but do not reopen the
core trust, determinism, target-binding, thread-UTxO, or durable-submission
decisions.

### 20.3 Benefits

- A clear, auditable trust model instead of "a datum exists, therefore it is
  true."
- Deterministic multi-source aggregation with explicit failure semantics.
- A canonical Cardano value that downstream contracts can safely identify and
  freshness-check.
- No single transaction coordinator or funding key can rewrite production
  oracle state.
- Reuse of Yano's strongest existing primitives: signed app messages,
  deterministic state, threshold finality, L1 anchoring, effects, plugin
  bundles, thread-NFT scripts, and L1 observation.
- A compelling no-code demonstration of Yano as an application platform.

### 20.4 Costs and limitations

- Threshold publication adds latency, networking, key operations, and failure
  modes beyond a simple hot-wallet transaction.
- Declared source-group independence is a social/economic assertion and must
  be reviewed and maintained operationally.
- On-chain updates consume fees and cannot match high-frequency dedicated
  oracle networks.
- Evidence retention and reporter operation require capacity, privacy, and
  data-availability policy.
- Validator and datum upgrades require explicit migration rather than an
  in-place Java change.
- The protocol can make manipulation visible and harder; it cannot guarantee
  that all authorized real-world sources are honest.
