# ADR-037: Certified Generic Observation Framework

## Status

Proposed — architecture and phased implementation plan only.

This ADR deliberately ships in a separate review before implementation. The
number is local to the `adr/app-layer` series. Root-level ADR numbers are a
separate series.

## Date

2026-09-02

## Owners

Yano app-chain host and Yano X application, observer, and effect integrations.

## Related decisions

- [ADR-005](005-yano-app-chain-framework.md) defines the app-chain state
  machine, threshold finality, persistence, and anchoring model.
- [ADR-008.3](008.3-chain-governed-membership.md) defines height-versioned
  validator membership.
- [ADR-008.4](008.4-script-anchors-l1view.md) defines Cardano L1 observations.
- [ADR-010](010-deterministic-effect-system.md) and
  [ADR-013.1](013.1-effect-runtime-framework-closure.md) define deterministic
  effect intent, off-consensus execution, and result incorporation.
- [ADR-016](016-authenticated-appchain-consensus-profile-and-typed-runtime-limits.md)
  authenticates consensus-critical runtime configuration.
- [ADR-036](036-certified-view-change-and-durable-l1-observation-delivery.md)
  defines certified app-chain recovery, durable mandatory L1 delivery, and the
  retained rollback/quarantine boundary.
- Yano X [ADR-012][adr-012] proposes a deterministic multi-source oracle,
  external reporter keys, L1-slot rounds and heartbeat ticks, aggregation, and
  Cardano publication. Section 17.1 defines which parts this ADR supersedes.
- Yano X [ADR-042][adr-042] is the end-to-end Cardano observation and EUTxO
  settlement lifecycle reference.

[adr-012]: https://github.com/bloxbean/yano-x/blob/main/adr/app-layer/012-multi-source-oracle-and-cardano-publication.md
[adr-042]: https://github.com/bloxbean/yano-x/blob/main/adr/042-l1-observation-and-eutxo-settlement-lifecycle.md

## 0. Decision summary

Yano will provide a **generic observation framework** for bringing facts from
outside deterministic application state into an app-chain safely. It extends
the lifecycle pattern established by effects:

```text
deterministic state transition
        -> durable observation intent
        -> node-local acquisition and verification
        -> authorized, signed canonical reports
        -> framework-verified observation certificate
        -> existing Yano block consensus
        -> canonical ObservationResult
        -> deterministic state transition
```

The complete application model becomes:

```text
OBSERVE -> DECIDE -> EFFECT -> OBSERVE
```

The key decisions are:

1. Yano owns scheduling, validator participation, report authentication,
   quorum accounting, proposal/finality integration, persistence,
   deduplication, replay protection, recovery, resource bounds, and reserved
   system inputs. Applications never implement BFT consensus.
2. Applications and trusted plugins own source-specific acquisition,
   canonicalization, evidence verification, deterministic reconciliation, and
   application response to a finalized result.
3. The framework introduces six conceptual records:
   `ObservationDefinition`, `ObservationSubscription`, `ObservationRound`,
   `ObservationReport`, `ObservationCertificate`, and `ObservationResult`.
4. Reports are signed by a definition-pinned reporter set. Phase 1 defaults to
   active validators; a later external-reporter profile covers the Yano X
   ADR-012 model without making reporter keys validator keys. A certificate
   proves that a configured deterministic policy accepted authenticated
   reports. The certificate is evidence for an input; it is **not** a second
   finality protocol. The existing `PREPARE -> COMMIT` app-chain consensus is
   the only mechanism that makes the result authoritative application state.
5. The initial logical schedule is based on finalized app-chain height, not
   validator wall clocks or the current proposer-supplied app-block timestamp.
   A verified L1 slot is an optional anchor for L1-connected chains in Phase 2.
   Active subscriptions are indexed in a durable ordered due index and
   processed in bounded batches; there is no timer or thread per subscription.
6. Java generic types are a developer convenience only. Consensus and storage
   use canonical, versioned bytes and cryptographic identities.
7. Cardano `~l1/*` observations remain a specialized, stronger lane. They are
   not migrated through the generic report protocol. Common lifecycle code may
   be extracted only when it does not weaken ADR-036 or ADR-042 guarantees.
8. Observation semantics are composed from evidence validation,
   reconciliation, source-diversity, scheduling, and retention policies. They
   are not represented by a rigid `DETERMINISTIC/AGGREGATED/ATTESTED/PROVEN`
   class hierarchy.
9. Phase 1 delivers bounded one-shot attested observations first, then the
   higher-risk raw exact-match HTTPS adapter. Recurrence, aggregation, external
   reporter ingress, shared feeds, webhooks, and large proofs follow only after
   the safety-critical core is qualified.
10. The framework is disabled by default, reserves framework state under the
    already-unconditional `~yano/obs/` prefix, and initially activates only on
    a fresh chain whose distinct `observationProfileDigest` commits to the
    complete observation profile. Even the canonical disabled profile changes
    the consensus context and is therefore a fresh-chain cutover.

## 1. Context and problem

Deterministic state machines cannot perform HTTP calls, read local files, use a
live Cardano handle, consult a mutable service, or depend on wall-clock time.
Those operations can return different values on different validators and fork
the state root. Yet many useful applications need facts such as:

- a Cardano transaction or UTxO at a stable point;
- a shipment status from an ordinary REST API;
- an exchange rate from several independent venues;
- a manufacturer's signed inspection result;
- a Merkle inclusion proof, ZK proof, or TEE attestation; or
- a finalized event from another blockchain.

ADR-042 describes a specific and unusually strong case: every validator
follows the same Cardano ledger and independently recomputes the same observer
output. Yano ADR-036 implements the durable journal, stable mandatory prefix,
and restart-persistent rollback/quarantine rules. A generic Web2 source does
not provide those properties:

- two correct validators can query at different instants and receive different
  answers;
- a source can equivocate by client, geography, DNS answer, or credential;
- several validators querying one source do not create source diversity;
- absence, timeout, and malformed data are not equivalent to `false`; and
- one validator's local set of ready observations may differ from another's.

Copying the Cardano path would therefore either reject legitimate variance or
weaken Cardano's guarantees. Asking application developers to implement a
parallel voting protocol would duplicate membership, signatures, quorum
intersection, view change, recovery, and persistence, and would make the host
unable to enforce consistent safety bounds.

The missing abstraction is a host-owned bridge between nondeterministic
external acquisition and deterministic block execution.

## 2. Goals and non-goals

### 2.1 Goals

- Let deterministic application state create, cancel, and consume durable
  observation work without performing I/O.
- Support ordinary Web2 systems that expose normal HTTPS APIs and know nothing
  about Yano.
- Support exact, aggregated, attested, and proven use cases through composition.
- Authenticate which authorized reporter produced which canonical value for
  which source and round.
- Reconcile a bounded report set deterministically before it can influence
  replicated state.
- Distinguish validator diversity from independent source diversity.
- Survive restart, crash, proposer rotation, delayed messages, and temporary
  source failure.
- Bound CPU, memory, disk, wire traffic, response size, fan-out, retry rate, and
  application-created work.
- Preserve an auditable chain from subscription through reports, certificate,
  result, state transition, effect, and any later observation.
- Make trust assumptions visible in definition and result metadata.
- Preserve the stronger Cardano observation contract unchanged.

### 2.2 Non-goals

- Establishing that an externally reported fact is objectively true.
- Replacing Chainlink, Flare, API3, Cardano consensus, or another oracle
  network. Any of them may be an observation source.
- Allowing arbitrary applications to make validators call arbitrary URLs.
- General distributed job execution, web crawling, or a per-subscription cron
  service.
- Executing network access inside `AppStateMachine.apply()` or a deterministic
  policy.
- Making a result final without normal Yano app-block finality.
- Hiding source concentration behind validator quorum.
- Supporting floating-point consensus arithmetic.
- Sharing compatible subscriptions in the first implementation.
- Unbounded evidence, arbitrary dynamic code, confidential secrets in
  replicated state, or open permissionless observers.
- Migrating ADR-042 Cardano observations to the generic protocol in the first
  implementation.

## 3. Existing architecture and the integration seam

The implementation at this decision's date provides these relevant seams:

| Existing component | Property to retain |
|---|---|
| `AppStateMachine.apply()` | Pure deterministic transition run speculatively on a candidate over committed state; only finality commits the prepared batch |
| `AppEffectEmitter` and `FxKernel` | Deterministic intent, durable host lifecycle, asynchronous execution, deterministic result incorporation |
| `AppBlockExecutionContext` | Exposes only validated block facts, never live external handles |
| `L1ObservationService` and journal | Independently derived Cardano facts, durable eligibility, rollback barriers, mandatory canonical prefix |
| Consensus v2 | Authenticated context, prepared-value locking, view change, restart-safe finality |
| Governed membership | Height-versioned member set and quorum |
| Consensus profile commitment | Fail-closed identity for consensus-critical implementations and settings |

The effect path is the closest lifecycle template, but its member-attested
result is not a general oracle protocol. The Cardano L1 path is the strongest
verification template, but depends on a common canonical ledger and local
replay. The generic framework sits beside both and reuses their host-owned
patterns.

```text
                         finalized app block
                                |
             +------------------+------------------+
             |                                     |
             v                                     v
    Observation kernel                         Effect kernel
   watch/cancel/result                        emit/result/expire
             |                                     |
             v                                     v
 node-local source workers                  node-local executors
             |                                     |
             +------------ external world --------+
```

## 4. Trust and fault model

Yano consensus proves agreement on a result under the configured validator
fault model. It does not prove that an HTTP server, signer, sensor, exchange,
or TEE told the truth.

Each definition must make these dimensions explicit:

| Dimension | Examples |
|---|---|
| Provenance | HTTPS endpoint, Cardano chain, signer key set, exchange, device |
| Evidence validation | canonical JSON projection, signature, Merkle proof, ZK verifier |
| Reporter reconciliation | exact quorum, threshold value, median of authorized reports |
| Source reconciliation | exact agreement, median, weighted median, threshold of distinct sources |
| Freshness anchor | observation round, source timestamp window, external block/slot |
| Evidence retention | inline, bounded digest plus retrieval reference, result only |

The result must expose enough committed metadata for an application and
operator to understand what was established. For example:

```text
"a quorum of Yano members reported that source S returned canonical value V"
```

is different from:

```text
"authorized signer K cryptographically attested claim V"
```

and from:

```text
"proof P verified statement V against committed root R".
```

The app-chain consensus safety assumptions remain those in ADR-036. Source
truth has a separate assumption: a compromised source can make all honest
validators agree on a false value. Policy and source diversity can reduce that
risk but cannot remove it.

Let:

```text
n = members pinned for the observation round
f = maximum Byzantine members from the authenticated chain profile
q = Yano PREPARE/COMMIT quorum
p = reporters pinned for the observation round
g = maximum faulty reporters claimed by the observation policy
r = reporter threshold selected by the installed observation policy
s = distinct-source threshold selected by the installed observation policy
```

`q` always finalizes the containing app block. `r` and `s` establish what the
observation certificate claims and must not be confused with `q`. In the
default active-member reporter profile, `p = n` and `g = f`. An
external-reporter profile pins its own keys and fault claim; a member gateway
relays the signed report but gains no reporter weight from the outer envelope.

Every v1 reporter threshold must satisfy:

```text
2r - p > g
```

so two sufficient report sets intersect in at least one non-faulty reporter.
For an unsigned/raw source, the built-in exact active-member policy also uses
`r = q`; the report itself is the attestation. For external signed or proven
evidence, `r < q` is admissible only when the certificate's own canonical bytes
prove that the accepted claim is unique for `(definitionDigest, canonical
parameters, round)`. For example, a proof may be checked against a root fixed
by the round, or the external signer may bind the round identifier and permit
at most one valid claim for that identifier. Authenticity alone is
insufficient: if a carrier can sign several historical statuses, a single
Byzantine relay must not choose which still-valid status wins. A verifier must
not depend on an absent report or on knowing that a sequence was the latest one
observed elsewhere. Latest-of-N, median, or any other choice among several
independently valid claims is closure-certified and cannot use the
lower-threshold exception. Otherwise the definition uses `r = q` exact
agreement over claim bytes. The keys, `g`, `r`, and the certificate-local
uniqueness proof are operator-installed, profile-committed, and never
application-selected.

## 5. Responsibilities

### 5.1 Yano host responsibilities

Yano owns:

- reserved observation-topic admission and canonical codecs;
- definition and profile identity validation;
- deterministic subscription IDs, round IDs, and result IDs;
- committed subscription lifecycle and ordered due index;
- reconstruction of local work after restart;
- active membership snapshot and quorum rules for each round;
- report domain separation, signature verification, uniqueness, and replay
  prevention;
- canonical report ordering and bounded certificate verification;
- proposal admission, block ordering, finality, catch-up, and state replay;
- atomic persistence of framework records with the finalized block;
- expiry and cancellation semantics;
- backpressure, quotas, health, metrics, and evidence bounds; and
- deterministic delivery of finalized results to the application.

### 5.2 Application and provider responsibilities

Application developers define:

- which registered observation definition they need;
- public canonical parameters for one subscription;
- schedule, expiry, and terminal conditions;
- how a finalized `ObservationResult` changes application state; and
- whether to emit a subsequent effect or observation intent.

An operator-trusted observation provider defines:

- how node-local acquisition obtains a candidate report;
- canonical parsing and normalization;
- source and evidence identity;
- deterministic evidence validation;
- deterministic report reconciliation or aggregation; and
- its complete consensus identity and resource requirements.

Providers do not count consensus votes, select finality thresholds, mutate app
state, or call the consensus engine.

## 6. Persistent concepts and identities

The exact Java API is deferred, but the wire concepts and ownership boundaries
are decided here.

### 6.1 `ObservationDefinition`

A definition answers: **how can this kind of fact be acquired, validated, and
reconciled?** It is installed and operator-approved as part of the chain
profile, not supplied as executable code by an app message.

Its canonical identity commits to at least:

```text
framework ABI version
definition id and schema version
parameter schema digest
report schema and canonical codec version
result schema and canonical codec version
source identity schema
reporter mode, reporter-set identity, fault bound, and threshold
acquisition adapter identity
normalization identity and version
evidence verifier identity and version
reconciliation policy identity and parameters
source-diversity policy
freshness and source-version-anchor interpretation
ordering version
consensus-critical resource bounds
```

Network endpoints, public paths, source keys, and source sets that affect what
is observed are consensus-critical identity. Node-specific credentials,
timeouts, proxies, and retry jitter are operational and must never enter the
identity or replicated state. A provider must reject a configuration that
cannot keep those categories separate.

`definitionDigest = H(canonicalDefinitionIdentityBytes)`.

### 6.2 `ObservationSubscription`

A subscription answers: **which concrete fact does this application currently
need?** It is derived deterministically during block execution.

Conceptually it contains:

```text
subscriptionId
applicationId / route
definitionDigest
canonical public parameters
creationHeight
firstDueAnchor (APP_HEIGHT in Phase 1)
cadence in selected logical-anchor units, if recurring
subscription expiry anchor
completion policy
status
nextDueAnchor
nextRoundNumber
lastResultId / last accepted value digest
```

The ID is derived, not caller-selected:

```text
subscriptionId = H(
  domain || chain genesis identity || creation height ||
  deterministic observation-emission ordinal ||
  definition digest || canonical parameters
)
```

A round is identified by the tuple `(subscriptionId, roundNumber)`; v1 does not
introduce a second, independently encoded `roundId`.

One observation emitter is shared across all deterministic callbacks in the
block and assigns a zero-based ordinal in the callback order fixed in section
7.6. Effect and observation ordinals are separate domain-tagged counters; they
cannot collide and do not make the two APIs consume each other's capacity.
This is analogous to deterministic effect IDs. Identical public definitions
created by different applications remain distinct subscriptions in the first
implementation.

### 6.3 `ObservationRound`

A round answers: **which bounded attempt is establishing this subscription at
logical time N?** It binds:

```text
subscriptionId
roundNumber
due anchor code and value
openingHeight
inclusive reportDeadlineAnchor
resultInclusionGraceHeights
absoluteMaxRoundHeight
resultExpiryHeight once collection closes
definitionDigest
parametersDigest
membership epoch and member-set digest
member count, finality quorum, and pinned maximum Byzantine members
reporter mode, reporter-set digest, reporter count/fault bound/threshold
source-set digest
policy digest
```

The round pins the active membership snapshot at its opening height. A
membership change does not reinterpret existing signatures. New rounds use the
new membership. A removed member remains a valid reporter only for that bounded
old round; key revocation for compromise requires an explicit emergency rule
and cannot be inferred from later membership. The profile sets
`maxRoundHeights`, and no application-selected expiry may exceed it.

Reports may be signed while the locally verified selected anchor is at or
before `reportDeadlineAnchor`, inclusive. The first finalized kernel pass whose
selected anchor is later closes collection and sets `resultExpiryHeight` from
that block height plus the profile-committed grace, bounded by
`absoluteMaxRoundHeight`. A valid certificate may be incorporated through
`resultExpiryHeight`, inclusive, and certificate processing at that height
occurs before the expiry sweep. The grace is a bounded operational inclusion
window, not proof that each member receives a turn: ADR-036 selects a proposer
by a per-height hash rather than round robin. Eventual inclusion is conditional
on a certificate reaching at least one honest proposer selected within the
window. A future availability certificate may provide a stronger rule; grace
arithmetic alone cannot. Fixed-proposer censorship remains possible and is
reported as a liveness failure. A recurring subscription never opens round
`k + 1` while `k` is open; close, expiry, or cancellation determines the next
due entry.

A certificate-monotonic value may be incorporated as soon as it reaches its
threshold, before the report deadline. An anchor-derived collection-close
transition fixes the deadline state only; it does **not** commit a report set,
because reports are diffusion-only in the protocol defined here. Therefore v1
cannot yet certify a non-monotonic policy. Phase 3 must first define a bounded
sequenced closure proof that makes the complete eligible report/source set
self-contained and follower-verifiable. `resultExpiryHeight` governs any round
still open after collection closes.

### 6.4 `ObservationReport`

A report is one authorized reporter's canonical value for one source in one
round. For the Phase 1 active-member profile it is node-local until diffused;
an external-reporter profile accepts the same inner signed contract through a
member gateway. Its signed bytes are protocol data:

```text
report version
chain genesis identity and chain id
stable app-chain consensus-profile digest
observationProfileDigest
definitionDigest
subscriptionId and roundNumber
round membership digest
reporter-set digest and reporter public key
sourceId
canonical value bytes or value digest
bounded canonical evidence or evidence digest
source version/freshness anchor fields
```

The signature covers every field, including value and evidence digests. Domain
separation prevents a report from being reused as a consensus vote, effect
result, report for another definition, chain, source, subscription, or round.

V1 has no report nonce and no supersession. A reporter signs at most one value
for `(definition, subscription, round, source)` for the life of the round. Any
second non-byte-identical report is equivocation. The durable journal persists
that signing lock before diffusion and binds the journal to the local node
identity so copied chainstate cannot inherit another node's signing history.

Phase 1 does not sign `UNAVAILABLE` or `INVALID`. A timeout, parser rejection,
or local plugin failure remains node-local and the round eventually becomes
`EXPIRED`; it never locks an honest reporter out of a later successful fetch.
A future closure-certified policy may add a single terminal no-value report
with a definition-committed earliest signing height, but may not add report
supersession.

Each reporter may contribute at most one report per `(round, source)` to a
certificate. Conflicting signed reports by the same reporter are equivocation
evidence and never permit last-write-wins handling. Local knowledge of a
conflicting report cannot by itself make block validity differ between nodes;
certificate validity depends only on encoded evidence and profile rules. For
raw exact-match, `r = q` plus quorum intersection and the honest no-double-sign
rule prevents two conflicting values from both obtaining valid report
certificates. Evidence-based policies instead derive uniqueness from the
pinned external signature/proof rules.

### 6.5 `ObservationCertificate`

A certificate contains the minimal canonical report set and policy output
needed to verify a result:

```text
certificate version
round identity and pinned membership digest
definition/policy/source-set digests
canonical ordered reports or bounded report commitments
policy output bytes
policy trace/metadata required for deterministic verification
resultId
```

Certificate verification is a pure, bounded function:

1. decode canonical bytes and reject duplicate or unordered reporters/sources;
2. verify every report's domain, round, reporter set, and signature;
3. require exactly `r` reports for a certificate-monotonic value and verify
   size, count, source, freshness, and evidence bounds;
4. run the deterministic evidence validators;
5. run the deterministic policy over a canonical report order;
6. recompute output, certificate identity, and `resultId`; and
7. require the definition's reporter and source thresholds.

The verifier sees only the reports encoded in the certificate; it cannot prove
that another node has no additional report. An admissible policy must therefore
be either:

- **certificate-monotonic:** once a certificate proves result `R`, adding any
  other valid report cannot produce a conflicting valid result; or
- **closure-certified:** a separately sequenced closure proof makes a fixed
  eligible report/source set consensus-visible before applying a non-monotonic
  aggregation. This mechanism is an open Phase 3 design, not part of v1.

For raw exact quorum, `r = q`, quorum intersection, and honest no-double-signing
make the selected value certificate-monotonic. Within that value group the
proposer should use the lexicographically lowest `r` reporter keys it has as a
compact construction convention. That is not a validity rule: any exactly-`r`
valid subset proving the same result is accepted, regardless of reports a
follower happens to hold. A median is not certificate-monotonic: adding a later
value can change it. Phase 3 must therefore introduce and review a sequenced
closure mechanism; a fixed required source set alone is insufficient unless the
certificate also proves one terminal value or explicit unavailability for
every required source. Candidate mechanisms include sequenced bounded report
commitments or a separately certified closure input, but this ADR does not
choose one. A simple "first 5 of 10" median is inadmissible. A policy that
cannot prove a unique result from its encoded evidence is rejected.

A certificate does **not** finalize itself. It is carried in a reserved
framework input and becomes authoritative only after the containing app block
obtains the normal Yano commit certificate.

### 6.6 `ObservationResult`

A result is the deterministic application-facing projection of a valid
certificate:

```text
resultId = H(subscriptionId || roundNumber || definitionDigest || status || valueDigest)
subscriptionId and roundNumber
definitionDigest
status: VALUE | NO_RESULT | EXPIRED | CANCELLED
canonical value bytes, if any
value/evidence digest
certificate digest as non-identity audit metadata
source and reporter counts
source freshness summary
finalized app height
```

Names may be refined during API design, but `NO_RESULT`/`EXPIRED` must remain
distinct from a domain value such as `false`, zero, `NOT_FOUND`, or
`PROCESSING`. Different sufficient reporter subsets for the same value produce
the same `resultId`; certificate gossip deduplicates by `(subscriptionId,
roundNumber, resultId)` and may retain one bounded certificate digest for audit.
`CANCELLED` is always a terminal audit status; section 24 leaves its application
callback behavior open for Phase 0.

### 6.7 V1 canonical encoding

V1 uses RFC 8949 deterministic CBOR with fixed-length arrays and frozen field
positions for reports, certificates, results, definitions, subscriptions, and
rounds. It permits definite lengths and shortest integer/length encodings only;
floats, maps with unconstrained ordering, indefinite items, duplicate keys,
unknown fields, and trailing bytes are rejected. Text fields use bounded UTF-8
with a field-specific grammar; numeric values use bounded integers.

Phase 0 publishes exact CDDL, maximum encoded sizes, field indexes, digest
domains, and golden bytes before production code. The logical field order is
the order listed in sections 6.1–6.6. At minimum:

```text
reportSignature = Sign(reporterKey,
  H("yano/observation/report/v1\0" || canonicalReportWithoutSignature))

certificateDigest =
  H("yano/observation/certificate/v1\0" || canonicalCertificate)

resultId = H("yano/observation/result/v1\0" || subscriptionId ||
  canonicalRoundNumber || definitionDigest || statusCode || valueDigest)
```

The outer app-message signature is transport authorization and is not part of
the report, certificate, or result identity.

## 7. Protocol flow

### 7.1 Create and schedule

During deterministic application execution, the application emits a watch
intent. The observation kernel validates it against the installed definition,
assigns its ID, writes the committed subscription record, and adds a due-index
entry atomically with the block and app state root.

Candidate execution is speculative, as it is today for effects. No HTTP call,
source lookup, worker wakeup, or timer creation occurs until the prepared batch
actually finalizes and commits.

### 7.2 Open a round

When the candidate block's profile-selected logical anchor reaches
`nextDueAnchor`, each validator deterministically discovers the same due entry.
The kernel writes the explicit round record with the membership, reporter,
fault, threshold, deadline, and policy snapshot into the prepared framework
batch. Local workers may begin only after seeing that record committed.
Because the kernel runs before
`apply()`, a subscription created at height `H` may be due no earlier than
`H + 1`.

This is also an emitter validation rule, not merely a consequence of callback
order: every `watch` emitted by `apply()`, a result callback, or an expiry
callback must have `firstDueAnchor` strictly later than the current selected
anchor. The same rule applies to `APP_HEIGHT` and `VERIFIED_L1_SLOT` anchors, so
a callback can never create a round retroactively in the current kernel pass.

The runtime scans no more than configured entries and opens no more than a
configured number of rounds per block. Remaining entries stay ordered and due;
they are not skipped.

### 7.3 Acquire and report

Every validator reconstructs node-local work from committed open rounds. A
bounded worker pool calls the configured provider outside block execution. The
provider returns a canonical report candidate or a local failure. The host
validates bounds and identity, synchronously persists its signing lock and
canonical report, then signs/diffuses it to current round members. External
reporter mode accepts a definition-authorized inner signature through a member
gateway without converting the gateway into the reporter.

Retries use node-local queues and bounded backoff. Retry jitter affects only
when a node attempts I/O, never round identity, due anchor, expiry, or policy.

### 7.4 Certify

Any validator can collect reports and assemble a certificate. Peers verify,
persist, and gossip valid certificates. On every sender re-diffusion tick, open
rounds re-send their retained inner reports/certificates until finalized or
expired; the protocol does not rely on the ordinary 120-second envelope TTL.

The scheduled proposer reads its durable ready-certificate journal, filters
rounds already terminal in committed state, and constructs bounded
`~obs/result/v1` system inputs itself. Their exact sender-sequence rule is a
Phase 0 decision in section 24. The outer envelope is transport authorization,
not fact authority: followers require a valid signature from any active member
of the block's membership epoch and never compare that sender with the current
leader. This permits ADR-036 view-change recovery to re-propose the exact
prepared value under a different leader. Included results are sorted by
`(subscriptionId, roundNumber, resultId, certificateDigest)` and followers
enforce that order, but they do not require inclusion of every certificate they
know locally.

Generic observation readiness cannot use ADR-036's local mandatory-prefix
rule: correct validators may possess different reports or certificates at a
given instant. Rejecting every proposal that omits a locally known certificate
would fork proposal validity and harm liveness. Therefore Phase 1 guarantees:

- safety: an included result must carry a valid certificate;
- durability: open work, signed reports, and received certificates survive
  restart and are periodically re-diffused;
- conditional inclusion: under eventual synchrony, a valid certificate is
  included if it reaches an honest proposer selected before its bounded expiry;
  otherwise the round expires; and
- no stronger censorship resistance than ordinary app messages when all
  scheduled proposers are Byzantine.

This limitation is explicit. A later protocol may add a quorum-known
availability/ready certificate and a deterministic inclusion deadline, but it
must not be inferred from local possession alone.

### 7.5 Finalize and apply

Certificate handling separates intrinsic invalidity from state-relative
staleness:

- For a canonically decoded active known round, a wrong
  domain/profile/definition/round/reporter set, invalid signature or evidence,
  insufficient threshold, or a policy/result mismatch rejects the proposal
  before `PREPARE`.
- A bounded, canonically framed input for an unknown, already terminal,
  cancelled, superseded, or out-of-window round is a deterministic audit no-op.
  A second valid certificate for the same result/round in one block is also a
  no-op. Local journal contents never change that verdict.
- Two well-formed inputs for the same `(subscriptionId, roundNumber)` but
  different `resultId` values in one block reject the proposal; block order
  cannot be allowed to choose between conflicting results.

Phase 0 must choose one uniform rule for an oversized, noncanonical, or
undecodable result input whose round cannot be identified. Such an input cannot
be classified by state staleness, so the wire format is not frozen until the
reject-versus-bounded-no-op decision in section 24 is resolved.

This prevents a late but once-valid certificate from poisoning a proposal
after another block closed the round. The proposer performs the same stale
filter before using block capacity, but follower safety never depends on that
optimization.

For an active round, followers verify the certificate before signing
`PREPARE` or `COMMIT`. The observation kernel incorporates accepted results,
closes or reschedules the round, updates its indexes, and invokes the
deterministic application callback. All framework and application writes
commit atomically with the block. Catch-up applies the identical structural
versus stale rules and never repeats external I/O.

### 7.6 Wire namespaces and deterministic kernel order

State keys and wire topics have separate namespaces:

```text
~yano/obs/*                         framework state/commitment keys
~obs-diffusion/report/v1            diffusion-only signed reports
~obs-diffusion/certificate/v1       diffusion-only ready certificates
~obs/tick/v1                        reserved heartbeat candidate; Phase 0 open
~obs/result/v1                      certified result input
```

Only the two `~obs-diffusion/*` topics are routed to an idempotent
diffusion-only handler and rejected from blocks. The result topic may be
sequenced but is rejected from public submission and accepted only through its
exact host-owned codec/signing rules. Tick admission is intentionally left to
the Phase 0 decision in section 24. Every other unclassified topic below the
exact `~obs/` or `~obs-diffusion/` reserved roots is rejected at peer admission,
pool selection, proposal, and catch-up; it must never fall through as an opaque
application message.

The signed inner report/certificate has a stable identity. A periodic sender
tick may wrap it in a fresh, bounded member-signed diffusion envelope so
first-sighting transport deduplication and its 120-second TTL cannot suppress
recovery. The receiving handler validates the outer member, bounds the body,
deduplicates by inner identity, and durably stores valid inner data before
acknowledging/marking it handled. Startup supplies a bounded early-message
queue until the handler and journal are ready.

The app-block layout is follower-enforced:

```text
[maximal canonical ~l1/* prefix]
[included ~obs/result/v1 inputs in canonical order]
[other sequenceable system and application messages]
```

Generic results do not alter ADR-036's maximal `~l1/*` prefix computation.
Their own order is canonical only among included results; completeness is not
inferred from a follower's local journal.

Observation processing must live inside a generalized deterministic
`SystemInputKernel`, not the current post-`FxKernel` `frameworkStateHook`.
`AppChainEngine`, `FxBlockApplier`, and `StateMachineConformance` use the same
pipeline:

1. structurally pre-verify reserved inputs and their block layout;
2. incorporate valid effect results in block order;
3. incorporate valid observation results in their canonical block order;
4. run the effect expiry sweep;
5. run observation expiry, round close/open, and due-index work;
6. invoke `AppStateMachine.apply()` for the block; and
7. write effect/observation commitment leaves and prepare the atomic batch.

Result incorporation at `resultExpiryHeight` wins because it precedes the
observation expiry sweep. Cancellation emitted by `apply()` takes effect after
same-block results/expiry and closes the subscription at the end of that kernel
execution. It never re-enters the state machine with a same-block cancellation
callback. Whether `CANCELLED` remains audit-only or is delivered in a later
block is a Phase 0 API decision in section 24.

One `AppEffectEmitter` retains the existing effect ordinal across all effect
and observation callbacks plus `apply()`. One `AppObservationEmitter` likewise
retains an independent observation ordinal across all callbacks plus `apply()`.
Both callback sequences are fixed above, so replay assigns identical IDs.

```text
App transition --watch--> committed subscription/due index
                              |
                        committed due height
                              |
                              v
                       committed open round
                              |
           +------------------+------------------+
           |                  |                  |
           v                  v                  v
     validator A        validator B        validator C
       acquire             acquire             acquire
       validate            validate            validate
       sign report         sign report         sign report
           |                  |                  |
           +------------------+------------------+
                              |
                    deterministic certificate
                              |
                      existing Yano consensus
                              |
                    finalized ObservationResult
                              |
                    deterministic app callback
```

## 8. Observation semantics by composition

The familiar categories are documentation labels, not mutually exclusive
runtime types. A signed Merkle proof from an API can be both attested and
proven; a price can be both attested and aggregated.

### 8.1 Exact/shared-view observations

An exact policy requires a threshold of members to report the identical
canonical value for the same source and round. This is appropriate when one
answer should be reproducible but the source is not integrated through the
specialized Cardano lane.

`NOT_FOUND` is a domain value only if the definition specifies a trustworthy,
complete query at a fixed external reference. A timeout is a node-local
`UNAVAILABLE` diagnostic, not a value or Phase 1 report.
Raw exact-match definitions must include a source-native version anchor where
available—such as an immutable revision, ETag with defined semantics,
`updatedAt`, or sequence—in the canonical value. Disagreement can then be
diagnosed as version skew rather than an unexplained byte mismatch.

### 8.2 Attested observations

An attested policy verifies an external signature against a pinned signer set,
claim schema, domain, uniqueness, source-version, and validity rules. Member or
external reports certify that the attestation was obtained and verified. The
resulting fact is that the signer attested the claim, not that the claim is
objectively true.

Several validators fetching the same signed object can provide availability
and Byzantine-validator resilience without pretending to provide several
independent attestors.

### 8.3 Proven observations

A proven definition validates a bounded proof against a canonical public input
or committed root. Proof validation occurs deterministically during report and
certificate verification. Large proof systems require explicit CPU and byte
budgets and are deferred beyond Phase 1.

### 8.4 Aggregated observations

Aggregation has two independent axes:

1. **validator reconciliation per source** decides what source `S` reported;
2. **source aggregation** combines accepted values from distinct sources.

For an ADA/USD feed, five validators querying one CEX still contribute one
source. A robust policy might require exact or bounded agreement by a validator
quorum for each source, then take a fixed-point median across five distinct
accepted sources.

Aggregation must use integer or fixed-point values with explicit scale,
rounding, overflow behavior, canonical sort order, tie rules, and a round
closure rule. If a policy allows any five of ten sources to determine a median,
different valid subsets may yield different answers. Phase 3 must therefore
define whether all named sources, a fixed subset, or threshold-certified
per-source values/unavailability close the report set; it may not use the first
responses to arrive. IEEE-754 floating point, locale parsing, implicit
timestamps, and unordered collection iteration are forbidden.

### 8.5 Policy boundaries

`SignatureVerified`, `MerkleProofVerified`, and `ZkProofVerified` are evidence
validators, not quorum or aggregation policies. `FirstValid` and
`LatestTimestamp` are not built-in Phase 1 policies because arrival order and
source clocks are nondeterministic.

Initial built-ins are intentionally small:

- `ExactValueQuorum`;
- `ThresholdAttestation`; and
- later, `FixedPointMedian` with explicit source diversity.

Custom policies must be registered by operators, committed in the consensus
profile, deterministic, bounded, conformance-tested, and unavailable to
untrusted applications as dynamically supplied code.

## 9. Logical anchors and durable scheduling

### 9.1 Finalized height is the Phase 1 clock

The current app block carries a timestamp, but it is supplied by the proposer
and is not a sufficiently constrained shared clock for observation validity.
Node wall clocks can also disagree. The initial framework therefore expresses:

- first due;
- recurrence;
- round open/close;
- expiry; and
- deterministic retry/terminal transitions

in finalized app-chain heights.

Height measures deterministic chain progress, not elapsed minutes. A
message-driven app chain that produces no blocks does not advance a
height-based subscription. A transition at height `H` may emit a one-shot
intent due no earlier than `H + 1`; a future committed block opens it. Phase 1
promises no wall-clock cadence. Recurring `every N heights` subscriptions in
Phase 2 progress only while the app chain progresses.

For a chain without an L1 reference, supporting a user promise such as "every
five minutes" requires a separate consensus-time and heartbeat-block decision.
That decision must define who may produce otherwise empty ticks,
minimum/maximum timestamp movement, follower validation, view-change behavior,
idle resource cost, and recovery after long downtime. Local timers may wake a
node, but cannot make the time claim true by themselves. APIs must not
translate durations into an assumed app-block rate.

An external source timestamp may be validated as evidence relative to a
definition-specific anchor, but it never independently decides that a
subscription is due.

### 9.2 Optional verified L1-slot anchor

Yano X ADR-012 identifies a useful existing clock for L1-connected app chains:
the block's stable `l1Slot`. It is monotone-checked and voters verify it against
their own L1 view; zero means that no usable reference is present. A definition
may therefore select `VERIFIED_L1_SLOT` instead of `APP_HEIGHT` when
`l1.stability-depth > 0` and the observation profile binds that choice, the
round origin, and slot length.

Slot passage alone does not create an app block. The intended model uses
redundant member-generated heartbeat hints when a due slot is locally stable.
Such a hint contains no trusted time and carries no result; it only gives the
sequencer work from which to build a block. The proposed block's verified
`l1Slot`, not a hint sender's clock, opens/closes due rounds. Phase 0 must still
freeze the tick body, sender eligibility and sequence rules, admission/dedup
bounds, and the committed high-water-mark behavior before `~obs/tick/v1` is an
accepted input.

This adopts ADR-012's L1-slot heartbeat insight without making generic
observations part of the `~l1/*` mandatory fact lane. It gives a cadence in
Cardano slots, not universal wall-clock time. Chains with no verified L1
reference still need a future consensus-time decision for elapsed durations.

### 9.3 Durable due index

Canonical framework storage maintains an ordered index conceptually equivalent
to:

```text
~yano/obs/due/<anchor-code>/<anchor-value>/<subscription-id> -> round metadata
```

The current `AppStateReader` is point-read only, so the implementation must not
pretend that it can scan the MPF application trie. The due index belongs in a
dedicated framework column family, analogous to effect records/buckets, with a
pure kernel reader and writes staged atomically with block commit. The kernel
visits the lowest due keys in lexicographic order, performs a bounded scan,
opens a bounded number of rounds, and leaves an earlier unprocessed or
unencodable entry in place. It must never skip an earlier entry to process a
later one.

Deterministic summary/commitment leaves under `~yano/obs/` bind the framework
records into the app state root. A missing due record, commitment mismatch, or
partial restore fails loudly and requires index rebuild by finalized-block
replay; silently skipping corruption could fork the transition.

Node-local runtime state may use a priority queue, timing wheel, or RocksDB
ordered index to wake workers efficiently. It is a cache reconstructed from
committed subscription and round state, not authority. There is no scheduled
task or thread per subscription.

### 9.4 Scale and backpressure

Supporting 100,000 active subscriptions is an eventual qualification target,
not permission for 100,000 concurrent calls. The profile bounds at least:

```text
active subscriptions per chain/application/definition
rounds opened per block
open rounds
reports and sources per round
report/evidence/certificate bytes
results and bytes per block
tick inputs admitted per block
source requests per host/domain/definition
worker concurrency
retry attempts and local retained bytes
maximum round duration and report-to-result inclusion grace
re-diffusion cadence and reports/certificates sent per tick
```

Deterministic emission fails identically on every validator when canonical
limits are exceeded. Local resource exhaustion marks the observation subsystem
unhealthy for reporting and applies backpressure; it cannot fabricate a
negative result.

## 10. Persistence and recovery

### 10.1 Canonical replicated state

Framework-owned records, committed atomically with each finalized app block in
a dedicated column family, contain:

- definitions/profile digest reference;
- subscription status and next round;
- ordered due-anchor entries;
- open/closed/expired round summaries;
- incorporated result identity and replay markers;
- bounded counters needed for deterministic limits; and
- application-visible commitments required for proofs/audit.

The state trie stores deterministic framework commitments and proof-visible
summaries rather than being used as an ordered scheduler database. Framework
trie keys use the already unconditional `~yano/obs/` reservation, even when
observations are disabled. Wire topics use the exact allowlist in section 7.6.
This prevents historical application keys and unclassified system messages
from colliding with later framework state.

The new column family is appended without renumbering existing handles, joins
the same `commitBlock` write batch, and is included in snapshot and integrity
verification. Prefix-seek/pagination follows the existing epoch-spool pattern.
A framework commitment mismatch or missing record fails loudly and is rebuilt
only by finalized-block replay.

The current `FinalizedMessageIndex.APPLICATION_ONLY` excludes `~` topics, so
observation audit/proof queries use these dedicated result records and
`~yano/obs/` commitments. They must not claim that the existing application
message index proves `~obs/result/v1` inclusion.

### 10.2 Node-local operational state

A synchronously durable local store contains:

- acquired report candidates and signed reports;
- received peer reports and certificates;
- retry attempts and next local wake time;
- evidence cache/retrieval state; and
- diagnostic failure details.

The store records and verifies the owning member key before loading any signing
lock. Copying another validator's chainstate must fail closed rather than make
the new node inherit, overwrite, or conflict with the old validator's signed
reports.

An external reporter likewise needs its own identity-bound durable signing lock
for the one-report rule. A member gateway can validate and retain an external
signature but cannot prove that the external key did not sign a conflicting
report elsewhere; the external-reporter fault model must account for that.

Local retry state is not replicated because DNS failure and HTTP timing differ
per node. Anything that changes application-visible round status must instead
be encoded in a finalized block transition.

### 10.3 Restart

On startup, before reporting healthy, a validator:

1. loads the committed observation profile and checks its local providers;
2. verifies/rebuilds framework indexes from authoritative records as needed;
3. enumerates committed open and due rounds in bounded pages;
4. reconciles the local report journal with committed closed results;
5. requeues unfinished local work; and
6. re-gossips valid retained reports and certificates.

A crash between acquisition and persistence merely repeats acquisition. A
crash after signing must retain the exact signed report; the node must not sign
any second report for the same `(round, source)` after restart.

### 10.4 Rollback and replay

App-chain blocks are finalized and replayed through the existing ledger path;
observation state follows the same atomic block boundary. L1 rollback remains
the responsibility of ADR-036's specialized journal. An external source later
changing its answer starts or completes another generic round; it does not
rewrite an already finalized observation result.

## 11. Developer-facing model

The API names remain illustrative until Phase 0 API review. Compatibility is a
decision: the current abstract three-argument `apply(context, state, effects)`
remains unchanged. Core API adds a default four-argument overload which
delegates to it, plus a default no-op `onObservationResult`. The runtime calls
the new overload. Observation-aware machines opt in through their capability
manifest; existing source and binaries keep their current behavior.

The intended shape is:

```java
default void apply(
        AppBlockExecutionContext context,
        AppStateWriter state,
        AppEffectEmitter effects,
        AppObservationEmitter observations
) {
    apply(context, state, effects); // compatibility default
}

default void onObservationResult(
        AppBlockExecutionContext context,
        ObservationResult result,
        AppStateWriter state,
        AppEffectEmitter effects,
        AppObservationEmitter observations
) {
    // Default no-op. An aware machine deterministically updates state and may
    // emit the next observation or effect.
}
```

An observation-aware implementation overrides the new overload and can record
intent without I/O:

```java
observations.watch(ObservationIntent.oneShot(
                "order-status-v1",
                canonicalOrderParameters)
        .firstDue(AppAnchor.height(context.block().height() + 1))
        .reportDeadline(AppAnchor.height(context.block().height() + 1_000)));
```

The named bounds are intentional. Phase 0 must decide the final API mapping
among report deadline, inclusion grace, subscription expiry, and the
profile-derived absolute maximum, including how anchor units and app heights
interact. A positional argument called only `expiry` is too ambiguous.

The API must also support deterministic cancellation and a bounded
`activeCount()` backpressure signal. It must not expose HTTP clients, mutable
provider objects, local report sets, credentials, or node-local health to the
state machine.

Applications receive canonical result bytes plus schema identity. Typed
`Observation<T>` adapters may decode them for convenience, but a Java class
name, reflection order, default JSON serializer, or generic type erasure is
never a consensus identity.

## 12. Web2 source profile and security

### 12.1 Web2 integration

An ordinary service may continue exposing:

```http
GET /api/orders/123
```

Yano operators install a definition that maps an approved parameter such as
`orderId` into an approved source template, projects selected response fields,
and emits canonical bytes. The Web2 service needs no Yano SDK.

Applications cannot submit a full URL, hostname, HTTP headers, parser, or
credentials as observation parameters. Definitions are operator-installed
capabilities; subscriptions provide only schema-validated values interpolated
into pre-approved locations.

### 12.2 SSRF and network isolation

A built-in HTTP adapter must enforce:

- HTTPS by default and an explicit scheme/host/port/path allowlist;
- no localhost, loopback, link-local, multicast, cloud metadata, validator
  control-plane, or private address ranges unless an operator explicitly
  creates an isolated private-source capability;
- DNS resolution validation on every connection and after redirects;
- bounded redirects, with every target revalidated;
- connection, header, body, decompression, parse-depth, and execution limits;
- fixed accepted content types and canonical UTF-8 handling;
- no ambient proxy, filesystem, environment, or credential access;
- per-source and global rate/concurrency limits; and
- redacted logs and metrics.

In-process custom plugins are operator-trusted code and cannot be fully
sandboxed by an interface. Production documentation must say this plainly.
Out-of-process provider isolation is a future hardening option.

### 12.3 Canonicalization and parsing

Definitions select explicit fields and canonical encodings. Consensus must not
depend on JSON object order, whitespace, unknown fields, number exponent form,
locale, timezone database behavior, HTTP headers not named by the definition,
or library-specific serialization defaults. Numeric ranges, string
normalization, duplicate JSON keys, and timestamp formats fail closed.

### 12.4 Credentials

API keys and secrets remain node-local. They never appear in subscription
parameters, report bytes, logs, certificates, state proofs, snapshots, or the
consensus profile. The profile commits to the logical source identity and
credential role, not the secret value. Operators are responsible for giving
validators equivalent authorization when equal views are required.

### 12.5 Equivocation, amplification, and censorship

A source may return different values to different validators. The report set
makes this visible and the policy decides whether there is sufficient evidence;
the framework never silently chooses arrival order.

Validator fan-out multiplies source traffic. Profiles must state the number of
validators and logical sources, use source rate limits, and avoid claiming that
validator count equals source independence.

An honest proposer cannot include a result it has not received. A Byzantine
proposer can omit ready results as it can omit ordinary app messages. Proposer
rotation and durable gossip provide inclusion only if an honest proposer is
selected within the bounded eligibility window and receives the certificate.
Stronger mandatory inclusion requires a future quorum-known availability
protocol.

### 12.6 Replay

Every report binds chain genesis, consensus profile, observation profile,
definition, subscription, round, membership, reporter set, source, and
freshness fields. A valid old API response, signature, or proof cannot satisfy
a new round unless the definition explicitly validates that evidence for that
round.

## 13. Failure semantics

External systems fail normally. The framework separates three layers:

| Layer | Examples | Effect |
|---|---|---|
| Local acquisition | DNS, timeout, rate limit, plugin crash | Retry locally; Phase 1 signs no failure report |
| Evidence/report | malformed response, bad signature, stale proof, equivocation | Reject locally; never sign or convert it to domain `false` |
| Round/policy | insufficient reports/sources, disagreement, deadline | Remain open through the report deadline, then deterministically `EXPIRED` after the result-inclusion grace |

No local exception can be logged and then replaced with an empty successful
observation. A missing definition, codec, verifier, or policy implementation is
a consensus-profile/startup failure and the node cannot join. A transient
acquisition failure degrades reporting and triggers local retry, but a node
whose deterministic verifier is healthy may still verify and vote for a valid
certificate assembled by peers. Any certificate verification failure remains
fail-closed for an active round; the state-staleness no-op rules in section 7.5
apply to terminal/unknown rounds.

After `resultExpiryHeight` the kernel derives `EXPIRED` from committed round
state without a certificate. Phase 1 does not produce `NO_RESULT`; the status
is reserved for a future closure-certified policy with a single terminal
no-value report rule. Absence of node-local reports can never derive it.

Policies may allow a certificate for a negative domain value only when a
quorum produced that explicit canonical value under a complete-query
definition. Lack of evidence is never negative evidence by default.

## 14. Webhooks

An unauthenticated webhook is only a node-local wake hint:

```text
webhook says "order 123 changed"
        -> wake matching active subscription early
        -> validators independently query/verify
        -> normal reports, certificate, and app-chain finality
```

It cannot create a subscription, alter due/expiry heights, or enter app state.
A signed webhook may be the evidence acquired by an attested definition, but it
still binds a round and passes normal verification. Validators need not all
receive the webhook because committed scheduling remains authoritative.

## 15. Relationship with effects and workflows

Observations and effects are complementary directions across the deterministic
boundary:

```text
External World
      |
      v
 ObservationResult
      |
      v
 deterministic state transition
      |
      v
 EffectIntent
      |
      v
 external executor / Cardano
      |
      v
 later ObservationResult
```

For an order workflow this can express:

```text
observe Cardano payment through specialized ~l1 lane
    -> mark PAID
    -> watch Web2 shipment
    -> mark DELIVERED
    -> emit Cardano release effect
    -> observe Cardano settlement through specialized ~l1 lane
    -> mark COMPLETE
```

Observation plus effect forms a useful workflow substrate, but this ADR does
not add a general workflow DSL. The application state machine remains the
explicit deterministic workflow definition.

## 16. Yano ADR-036 and Yano X ADR-042 compatibility

Yano's Cardano observations remain under `~l1/*`. ADR-036 supplies their
implemented host guarantees; Yano X ADR-042 describes how downstream EUTxO and
settlement applications consume that lifecycle:

- every validator follows and retains a canonical Cardano view;
- configured providers declare canonical ABI/schema/ordering identity;
- the profile binds the L1 network genesis identity;
- facts become eligible only after Cardano stability;
- a global canonical prefix is mandatory and cannot skip an earlier fact;
- rollback invalidation and quarantine survive restart; and
- live voters require the fact in their independently verified window; retained
  canonical L1 block replay is currently used by callback-failure recovery, not
  as a general live-vote fallback.

The generic observation lane must not replace these properties with report
quorum, source policy, or best-effort inclusion. Cardano facts may be exposed
to applications through a future common read-only `CanonicalExternalInput`
view, and scheduling/journal utilities may be shared internally, but their
wire identity, verifier, rollback barrier, and mandatory inclusion remain
separate.

This separation also permits a different use case—such as a threshold-signed
third-party Cardano indexer result—to use the generic lane honestly without
being confused with an ADR-042 self-observed Cardano fact.

## 17. Relationship with Yano X ADR-012

### 17.1 Superseded and retained responsibilities

Yano X ADR-012 is proposed and unimplemented, but it already designs much of a
multi-source oracle: externally authorized reporter keys, domain-separated
reports, equivocation rules, independent reporter/source thresholds,
fixed-point aggregation, an L1-slot round clock with heartbeat ticks, evidence
commitments, and Cardano publication.

For an ADR-012 feed migrated to this framework, ADR-037 supersedes its
**ordinary-message report ingress, `ingressDecisionAccumulator`, round
lifecycle, reporter/source threshold execution, aggregation execution, and
result incorporation**. ADR-012 does not define a second BFT protocol—its
ordinary messages are finalized by normal Yano consensus—but those replicated
report mechanics are application-specific infrastructure that the generic host
should own. Reports no longer appear individually in finalized blocks; only
the reports encoded in a finalized observation certificate contribute to the
committed result/evidence digest. No implementation should build both
report/round layers for the same feed.

ADR-012 retains its **oracle feed-policy data and governance, source
independence declarations, prior-value circuit breaker, evidence presentation,
Cardano datum/thread-UTxO protocol, publication effect, and settlement
lifecycle**. Its fixed-point normalization/outlier/median algorithm is a
candidate Yano X-contributed, profile-identified observation policy, but it
cannot ship until Phase 3 defines closure certification.

ADR-012 resolves governed policy per round through `effectiveRound`; a recurring
subscription must not accidentally freeze policy at subscription creation.
Phase 3 must choose either deterministic policy selection from authorized state
at each round opening or explicit cancel-and-re-watch migration. Any selected
policy bytes/version remain bounded by a registered schema and host profile; an
application cannot supply executable policy code or bypass host bounds.

After ADR-037 exists, the oracle becomes:

```text
Yano X governed feed state
        -> Yano ObservationDefinition + pinned policy/source/reporter profile
        -> finalized ObservationResult
        -> Yano X prior-value circuit breaker and publication state
        -> cardano.oracle-update effect
        -> ADR-012 Cardano publication and observation lifecycle
```

Before Phase 3 implementation, the Yano X oracle owners must revise ADR-012 in
the Yano X repository to consume this framework, remove its
ordinary-app-message report/round implementation plan, and specify how its
latest-assertion selection becomes closure-certified. Until that revision,
this section is the controlling boundary where the two proposed ADRs overlap;
ADR-012 remains a proposal and is not silently changed by this Yano ADR.

### 17.2 External reporter keys

ADR-012 reporters are authorized external Ed25519 keys, not app-chain members.
The generic model supports that through a definition-pinned
`EXTERNAL_REPORTERS` profile. The external signature remains the inner report
authority; an active member gateway performs bounded admission and diffusion
but gains no report weight. The round pins `p`, `g`, `r`, the reporter-set
digest, source identities, and evidence rules independently of `n`, `f`, and
`q`.

The host must expose the canonical round descriptor, including its membership
and reporter-set digests, to authorized external reporters. Each reporter signs
that exact identity and durably enforces the one-report rule under its own key;
a gateway cannot synthesize or repair those obligations.

Phase 1 uses `ACTIVE_MEMBERS` to keep the first host protocol small. External
reporter ingress and the ADR-012 oracle migration enter with aggregation in
Phase 3. Both modes use the same certificate and result lifecycle.

### 17.3 L1-slot clock

This ADR adopts ADR-012's verified L1-slot and redundant tick pattern as the
optional recurrence anchor in section 9.2. It rejects only unverified local
wall clocks and unconstrained proposer timestamps. It does not reject the
block's independently verified stable L1 reference.

## 18. Prior art and lessons

### 18.1 In-repository threshold collection

`ScriptAnchorService` already collects member witnesses to a threshold over
diffusion and periodically re-sends proposal/signature work. ADR-010 also names
script-anchor co-signing as its k-of-n precedent. Phase 1 should extract or
reuse a small generic authenticated threshold collector where its semantics
fit, rather than implement signature accumulation twice. Observation-specific
round identity, report equivocation, evidence validation, and durable journals
remain outside that collector.

### 18.2 Flare Data Connector

[Flare FDC][flare-fdc] separates typed attestation requests, data providers,
voting rounds, finalization, Merkle commitments, and proof-based consumption.
It demonstrates the value of explicit source/attestation types and compact
committed results. Its Web2 guidance also shows why exact agreement on dynamic
HTTP responses can fail even for honest providers. Yano should borrow the
separation and determinism discipline, not assume that arbitrary Web2 data has
a single stable answer.

### 18.3 Chainlink

[Chainlink Data Feeds][chainlink-feeds], [Functions][chainlink-functions], and
Automation separate maintained feeds, custom off-chain computation, and
triggers. They are possible Yano sources rather than competitors that Yano must
reimplement. The main lesson is to distinguish an operator-maintained feed
from arbitrary user-supplied HTTP computation and to price/bound fan-out.

### 18.4 API3

[API3 data feeds][api3-feeds] emphasize first-party signed source data. This is
meaningfully different from several validators merely fetching one unsigned
endpoint. Yano's attested definitions should preserve the external signer and
source identity rather than relabel validator agreement as source attestation.

### 18.5 Cosmos vote extensions

[Cosmos SDK vote extensions][cosmos-vote-extensions] are the closest protocol
analogy: validators create signed nondeterministic extension data and the next
proposer deterministically aggregates it for state-machine processing. Yano
should reuse that separation conceptually, while using a dedicated bounded
report protocol rather than changing PREPARE/COMMIT vote signatures or making
application developers implement aggregation.

[flare-fdc]: https://dev.flare.network/fdc/overview
[chainlink-feeds]: https://docs.chain.link/data-feeds
[chainlink-functions]: https://docs.chain.link/chainlink-functions/resources/architecture
[api3-feeds]: https://docs.api3.org/oev/in-depth/data-feeds/
[cosmos-vote-extensions]: https://docs.cosmos.network/sdk/latest/guides/abci/vote-extensions

## 19. Alternatives considered

### 19.1 Make every external fact an ordinary app message

Rejected. Ordinary messages authenticate a sender but do not bind a registered
source definition, round, validator report quorum, source diversity, evidence
policy, durable scheduling, or deterministic result lifecycle. This does not
say ADR-012 was unreasonable before a host framework existed; section 17
defines how its inner external signatures survive while its ordinary-message
round layer is superseded.

### 19.2 Let every application implement its own voting state machine

Rejected. This duplicates consensus-sensitive membership, signatures, quorum
intersection, replay protection, persistence, view/restart recovery, and
resource controls. It violates the core goal that developers define domain
semantics rather than BFT.

### 19.3 Generalize `L1Observation` directly

Rejected. Generic sources lack Cardano's shared canonical ledger, stability
point, rollback model, and complete deterministic prefix. A common superclass
would hide materially different trust and liveness guarantees.

### 19.4 Put reports inside PREPARE/COMMIT votes

Deferred. This resembles vote extensions but couples external-source latency
and certificate size to the finality hot path, complicates view change and
historical proof, and enlarges safety-critical codecs. A dedicated signed
report protocol feeding normal proposal validation keeps KISS boundaries.

### 19.5 Use block timestamps or validator wall clocks for cadence

Rejected for the initial protocol. Current timestamp constraints do not define
a strong shared clock and local clocks differ. Finalized height is replayable,
deterministic, and already available.

### 19.6 One scheduled task per subscription

Rejected. It does not scale or reconstruct cleanly. A durable ordered due index
plus bounded worker pools represents the same logical schedule.

### 19.7 Replicate HTTP failures and retry counters

Rejected. These differ by validator and are operational. Only round deadlines
and terminal results are canonical; retry machinery remains node-local.

### 19.8 Implement shared feeds immediately

Deferred. Deduplication can later use a digest of definition, parameters,
source set, policy, and cadence, but it adds ownership, retention, cadence, and
authorization complexity before the core protocol is proven.

## 20. Consensus profile and compatibility

Phase 0 introduces a separate canonical `ObservationProfileV1` and
`observationProfileDigest`. It mirrors ADR-036's `observerProfileDigest` rather
than adding dynamic definition fields to the closed
`AppChainConsensusProfile` v2 codec. `ConsensusContext` gains the new 32-byte
field alongside `observerProfileDigest`, so every proposal/vote context binds
both specialized L1 observation identity and generic observation identity.
Its existing `protocolVersion` field increments from 2 to 3 because adding a
signed field while retaining version 2 would make one version label describe
two encodings. The ADR-036 PREPARE/COMMIT/view-change algorithm remains the v2
algorithm; only the signed context schema changes for this fresh-chain cutover.

The observation profile commits to:

- framework ABI/wire version;
- enabled definitions and their full canonical identities;
- reserved topic and state codec versions;
- logical-time and round rules;
- quorum and source-diversity rules;
- all consensus-critical limits;
- certificate ordering and policy versions; and
- result incorporation ordering.

Disabled mode has one canonical zero/empty `ObservationProfileV1` encoding and
digest; non-zero limits or definitions are invalid when disabled. The exact
canonical bytes are written once at height 1 under
`~yano/obs/profile/v1` and checked at startup/replay, while its digest is folded
into every consensus context. This is a fresh-chain change even when disabled:
the additional consensus-context field changes block hashes and the height-1
marker changes state roots.

Reports span proposal heights and views. They bind the stable
`AppChainConsensusProfile` digest and `observationProfileDigest` directly; they
must never bind the height-specific `consensusContextDigest`. The round record
separately pins the opening-height membership, `f`, finality quorum, reporter
set, `g`, and report threshold.

A member missing a required definition or whose computed profile differs does
not join the chain. Operational settings may differ only where the definition
declares they cannot change canonical output.

While Yano remains in preview, initial activation is fresh-chain only. There is
no v0 observation wire compatibility to preserve. The first implemented wire
format is `v1`. State keys use `~yano/obs/`; wire topics use the exact section
7.6 allowlist. Later in-place upgrades require an explicit height-gated profile
transition ADR.

## 21. Observability and operations

The host should expose, without leaking credentials or unbounded values:

- active/due/open/expired subscriptions and rounds;
- oldest due height and scheduling lag;
- local acquisition attempts, latency, classified failures, and rate limits;
- reports retained/sent/received/rejected and equivocation evidence;
- certificates ready/included/rejected and time-to-finality;
- `certificate_ready_not_included_heights` to surface proposer censorship or
  propagation failure;
- results by status, definition, source count, and reporter count;
- local journal bytes, rebuild/replay progress, and quarantine state;
- provider/profile health and mismatch reasons; and
- source concentration indicators.

Readiness must distinguish inability to acquire new reports from inability to
verify an included certificate. The latter always prevents a vote. Metrics and
logs must use digests or bounded redacted values, never API secrets or full
sensitive claims.

## 22. Phased implementation plan

Each phase follows: implement, focused tests, adversarial review, learn, and
iterate before starting the next phase. Scope stays intentionally narrow.

### Phase 0 — protocol contracts and deterministic kernel skeleton

Deliver:

- canonical v1 codecs and domain-separated hashes for definition,
  subscription, round, report, certificate, and result;
- `~yano/obs/` state keys and the exact diffusion/sequenced topic allowlist;
- distinct `observationProfileDigest`, `ConsensusContext` binding, height-1
  profile marker, and startup compatibility guard;
- generalized system-input kernel order and backward-compatible default API
  methods;
- pure certificate/policy verification interfaces and conformance vectors;
- framework settings and strict resource bounds;
- model/property tests for canonical ordering, stale no-ops, structural
  rejection, duplicate/equivocation handling, signature coverage, round replay,
  pinned `f/g` and membership/reporter sets, and malformed bytes; and
- no external acquisition or production scheduling yet.

Exit criteria:

- every consensus field is identified and signed/hashed where required;
- cross-JVM deterministic vectors pass;
- fuzzed decoders fail closed within bounds;
- a canonical disabled profile produces stable golden block/state roots (not
  legacy pre-ADR-037 roots); and
- ADR review has resolved all Phase 0 open questions.

### Phase 1 — one-shot attested, then raw exact observations

Deliver:

- deterministic application `watch`/`cancel` API and result callback;
- one-shot height-triggered subscriptions and committed due index;
- bounded node-local provider workers and identity-bound durable report journal;
- re-diffused report/certificate transport and exact-value certificate assembly;
- external-signature verification with unique claim/source-version rules;
- canonical result incorporation through existing block consensus;
- an attested reference provider first, followed by a restricted
  operator-approved raw HTTPS source adapter with a source version anchor; and
- restart, proposer-rotation, partition, timeout, SSRF, replay, and
  equivocation regressions.

Exit criteria:

- a transient provider crash cannot fabricate a result or permanently lose
  work;
- an untrusted app cannot reach localhost/private/control-plane endpoints;
- a result cannot finalize with a forged, selectively stale, cross-chain,
  cross-round, or insufficient certificate;
- an old/duplicate certificate is a no-op and cannot poison a proposal; and
- a one-shot Web2 and signed-attestation showcase runs on a multi-node devnet.

### Phase 2 — durable recurring subscriptions and recovery

Deliver:

- recurring height cadence, optional verified-L1-slot cadence and host tick,
  completion conditions, deterministic report/result deadlines, and bounded
  catch-up after long downtime;
- durable open-round reconstruction and certificate re-gossip;
- due-index pagination, integrity checks, and rebuild tooling;
- deterministic per-app/definition quotas and local request rate limits;
- operational health/readiness and audit queries; and
- crash/kill/restart tests at every persistence transition.

Exit criteria:

- no round is skipped because a later one fits a block or worker queue;
- 100,000 dormant/active subscription records can be indexed and recovered
  without one timer/thread each;
- restart and proposer rotation preserve round identity and prevent double
  signing;
- every round has pinned `f`, a bounded lifetime, and a bounded operational
  post-report inclusion grace whose liveness assumption is tested explicitly;
  and
- resource exhaustion causes bounded backpressure, not divergent state.

### Phase 3 — aggregation and source diversity

Deliver:

- fixed-point numeric schema and arithmetic helpers;
- two-stage validator-per-source reconciliation and cross-source aggregation;
- definition-pinned external reporter keys and Yano X ADR-012 report/round
  migration;
- deterministic median with explicit even-count/tie/rounding rules;
- a reviewed bounded sequenced closure proof for every non-monotonic aggregate;
- source diversity and minimum-source policies;
- an ADA/USD reference example using several logical sources; and
- permutation, outlier, equivocation, stale-source, and overflow property tests.

Exit criteria:

- every permutation and every sufficient reporter subset for the same accepted
  value yields identical result bytes, though certificate audit digests may
  differ;
- five validators querying one source count as one source;
- missing/stale sources yield no result rather than an invented price; and
- integer overflow, scale mismatch, and ambiguous decimal input fail closed.

### Phase 4 — proofs, webhooks, and workflow integration

Deliver:

- bounded Merkle/proof evidence profiles selected by concrete demand;
- webhook wake hints with normal acquisition fallback;
- effect-to-observation lifecycle diagnostics and evidence linking;
- optional shared read-only external-input application view; and
- end-to-end payment/shipment/release/settlement examples.

If real-duration schedules are required for a chain without a verified L1-slot
anchor, first deliver and review a separate consensus-time/heartbeat ADR and
its adversarial tests. Phase 4 must not infer five-minute semantics from an
average app-block interval.

Large ZK/TEE proof systems require their own threat, verifier-version, CPU, and
evidence-retention review before being added.

### Phase 5 — qualification and graduation

Deliver:

- exact-version Yano and Yano X packaged distributions;
- five-node Preprod qualification with `f=1` Byzantine assumptions;
- proposer rotation, delayed/partitioned reports, node restarts, source
  equivocation/unavailability, membership transition, and long-running cadence;
- scale/resource soak tests and operator recovery drill;
- user guide, provider author guide, threat model, runbook, and upgrade guide;
- independent code/protocol review and all repository CI gates.

The feature remains preview and disabled by default until these gates pass.

## 23. Required test matrix

At minimum, implementation must cover:

| Area | Required cases |
|---|---|
| Codec/identity | truncation, trailing bytes, duplicates, noncanonical order, cross-chain/profile/round replay |
| Signatures | value/evidence/source/round all covered, wrong reporter set, old membership, any second report/equivocation |
| Policy | report permutations, different sufficient subsets with identical result ID, insufficient reporter/source thresholds, selective stale attestation |
| Scheduling | same due anchor, inclusive report/result deadlines, earlier item too large, bounded scan, L1 tick, expiry, cancel, long downtime |
| Persistence | copied-owner journal, crash before/after sign, before/after gossip, certificate ready, proposal, final commit |
| Transport | diffusion topics rejected from blocks, exact result/tick allowlist and chosen sender-sequence rules, early handler queue, bounded TTL re-diffusion |
| Consensus | stale certificate no-op versus the Phase 0 malformed-input rule, same-round conflicting results, result ordering, proposer rotation, view change, prepared recovery, catch-up, faulty omission |
| Failures | DNS, timeout, 429, malformed/huge/compressed response, stale signature/proof, source disagreement |
| Security | SSRF address classes, DNS rebinding, redirect escape, secret redaction, parser depth/number bounds |
| Scale | 100k indexed subscriptions, bounded workers, disk limits, report/certificate amplification |
| Compatibility | default API methods and old machines, canonical disabled-profile roots, effects only, Cardano `~l1/*`, EUTxO settlement lifecycle |

Model tests must exercise at least the ADR-036 `n=5, q=4, f=1` profile and
membership changes between rounds. It must include two builders with different
sufficient report subsets plus one equivocating reporter and prove identical
`resultId`. Live qualification must show that an honest node can
assemble/include a certificate after a faulty proposer omits it and that source
disagreement expires the round rather than forking app state.

## 24. Review questions before implementation

The review has decided explicit committed round records, bounded inline Phase
1 report/evidence, attested-first delivery, the state-stale no-op rule, and the
optional verified-L1-slot clock. The following questions remain intentionally
open rather than being hidden in an illustrative API or topic name.

### 24.1 Phase 0 wire and API decisions

1. Which settings of each concrete provider affect logical source identity and
   which are operational routing? Every Phase 1 provider must document and
   golden-test that split before its identity is frozen.
2. How is an oversized, noncanonical, or undecodable `~obs/result/v1` input
   classified when its round cannot be recovered? The choice must be uniform
   and must explicitly justify either rejecting the proposal/catch-up block or
   using a bounded no-op like the effects precedent. State-relative staleness
   is consulted only after canonical round identity is available.
3. What are the exact sender-sequence and admission rules for
   `~obs/result/v1` and `~obs/tick/v1`? The tick decision must freeze its body,
   sender eligibility, per-member/per-anchor deduplication, pool and block
   bounds, and committed anchor high-water mark. A zero-sequence exact-topic
   exemption and durable positive member sequences are alternatives, not both.
4. Which internal bound does each application API argument set: first due,
   report deadline, subscription expiry, inclusion grace, or absolute maximum?
   The decision must define conversion when due/deadline use L1 slots but grace
   and the absolute maximum use app heights, including stalled chains.
5. Is `CANCELLED` an audit-only terminal status or a result callback delivered
   in a later block? Same-block callback re-entry remains forbidden either way.

### 24.2 Decisions required by later phases

1. Is a quorum-known availability certificate needed before recurring feeds,
   or is honest-proposer inclusion within the bounded grace sufficient for
   preview? It is not required for Phase 1 but must be resolved before
   graduation.
2. What bounded sequenced closure proof commits the complete eligible report
   set for Phase 3 non-monotonic aggregation? Diffusion possession and an
   anchor-derived close transition are insufficient.
3. Should a double-signed report proof become a committed audit input in Phase
   3, or remain bounded local evidence? If committed, its exact topic must be
   added to the section 7.6 allowlist and observation profile. It cannot
   retroactively invalidate a finalized result.
4. Which application requires elapsed-time scheduling without Cardano, and can
   it use a source-native logical anchor instead? A separate consensus-time
   decision is required before exposing duration-based cadence on such chains.

## 25. Consequences

### Positive

- Applications gain a coherent, host-owned way to consume external facts
  without violating deterministic execution.
- Consensus and recovery infrastructure stays centralized in Yano.
- The trust/evidence/source model is explicit rather than implied by validator
  agreement.
- Scheduling survives restart and scales through ordered indexes and bounded
  workers.
- Effects and observations form a composable external workflow loop.
- Cardano's stronger observation guarantees remain intact.

### Negative

- The runtime gains a new signed protocol, durable journal, state kernel, and
  security-sensitive network adapter.
- Validator report fan-out increases network and source load.
- Under `n=5, q=r=4, f=1`, raw exact Web2 needs four byte-identical reports;
  one unavailable honest reporter plus one Byzantine withholder prevents a
  result. This is the explicit availability cost of the raw trust model.
- Generic sources cannot offer Cardano's local mandatory-prefix completeness;
  their liveness depends on report/certificate availability and an honest
  proposer unless a later availability protocol is added.
- Provider identity, canonicalization, secrets, and source-diversity
  configuration require careful operator discipline.
- Height-based cadence is less intuitive than wall-clock cadence and depends
  on app-chain progress.

These costs are acceptable only with the phased KISS scope and qualification
gates above. Implementation must start in a follow-up PR after this ADR is
reviewed; this architecture PR does not silently commit the project to later
aggregation, proof, or shared-feed complexity.
