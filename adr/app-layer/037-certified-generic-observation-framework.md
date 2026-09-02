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
- Yano X [ADR-042][adr-042] is the end-to-end Cardano observation and EUTxO
  settlement lifecycle reference.

[adr-042]: https://github.com/bloxbean/yano-x/blob/main/adr/042-l1-observation-and-eutxo-settlement-lifecycle.md

## 0. Decision summary

Yano will provide a **generic observation framework** for bringing facts from
outside deterministic application state into an app-chain safely. It extends
the lifecycle pattern established by effects:

```text
deterministic state transition
        -> durable observation intent
        -> node-local acquisition and verification
        -> member-signed canonical reports
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
4. Reports are signed by active validators. A certificate proves that a
   configured deterministic policy accepted a canonical set of authenticated
   reports. The certificate is evidence for an input; it is **not** a second
   finality protocol. The existing `PREPARE -> COMMIT` app-chain consensus is
   the only mechanism that makes the result authoritative application state.
5. The initial logical schedule is based on finalized app-chain height, not
   validator wall clocks or the current proposer-supplied app-block timestamp.
   Active subscriptions are indexed in a durable due-height index and
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
9. Phase 1 supports bounded, one-shot exact-match and externally attested
   observations. Recurrence, aggregation, shared feeds, webhooks, and large
   proofs follow only after the safety-critical core is qualified.
10. The framework is disabled by default, reserves `~obs/` from genesis, and
    initially activates only on a fresh chain whose authenticated consensus
    profile commits to the complete observation profile.

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

ADR-042 solves a specific and unusually strong case: every validator follows
the same Cardano ledger, derives the same stable prefix, and independently
recomputes the same observer output. ADR-036 makes that prefix durable and
mandatory. A generic Web2 source does not provide those properties:

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
- Authenticate which validator reported which canonical value for which source
  and round.
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
| `AppStateMachine.apply()` | Pure deterministic transition over a finalized encoded block |
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
| Validator reconciliation | exact quorum, threshold value, median of member reports |
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
r = member-report threshold selected by the installed observation policy
s = distinct-source threshold selected by the installed observation policy
```

`q` always finalizes the containing app block. `r` and `s` establish what the
observation certificate claims and must not be confused with `q`.

For an unsigned/raw source whose value is vouched for only by member reports,
the built-in exact policy uses `r = q` by default and requires the ADR-036
quorum-intersection checks. At minimum `r > f` is necessary to ensure that a
certificate is not composed entirely of Byzantine reporters, but that weaker
threshold does not establish a consistent source view and is not a built-in
default. For a self-verifying external signature or proof, `r` may be lower
because every finality voter verifies the same external evidence; the result's
trust comes from the pinned external keys/proof system rather than the number
of fetchers. Any `r < q` choice and its trust rationale are operator-installed,
profile-committed, and unavailable as an application-selected parameter.

## 5. Responsibilities

### 5.1 Yano host responsibilities

Yano owns:

- reserved `~obs/*` admission and canonical codecs;
- definition and profile identity validation;
- deterministic subscription IDs, round IDs, and result IDs;
- committed subscription lifecycle and due-height index;
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
acquisition adapter identity
normalization identity and version
evidence verifier identity and version
reconciliation policy identity and parameters
source-diversity policy
freshness interpretation
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
firstDueHeight
cadence in finalized heights, if recurring
expiryHeight
completion policy
status
nextDueHeight
nextRoundNumber
lastResultId / last accepted value digest
```

The ID is derived, not caller-selected:

```text
subscriptionId = H(
  domain || chain genesis identity || creation height ||
  creating transition route || deterministic emission ordinal ||
  definition digest || canonical parameters
)
```

This is analogous to deterministic effect IDs. Identical public definitions
created by different applications remain distinct subscriptions in the first
implementation.

### 6.3 `ObservationRound`

A round answers: **which bounded attempt is establishing this subscription at
logical time N?** It binds:

```text
subscriptionId
roundNumber
dueHeight
openingHeight
closing/expiry height
definitionDigest
parametersDigest
membership epoch and member-set digest
validator report threshold
source-set digest
policy digest
```

The round pins the active membership snapshot at its opening height. A
membership change does not reinterpret existing signatures. New rounds use the
new membership. An old round may finish only within its bounded closing height
and only under its pinned quorum.

### 6.4 `ObservationReport`

A report is one member's canonical claim for one source in one round. It is
node-local until diffused, but its signed bytes are protocol data:

```text
report version
chain genesis identity and chain id
authenticated consensus-profile digest
definitionDigest
subscriptionId and roundNumber
round membership digest
reporter public key
sourceId
outcome: VALUE | UNAVAILABLE | INVALID
canonical value bytes or value digest
bounded canonical evidence or evidence digest
source freshness fields
report sequence/nonce defined by the round
```

The signature covers every field, including value and evidence digests. Domain
separation prevents a report from being reused as a consensus vote, effect
result, report for another definition, chain, source, subscription, or round.

`UNAVAILABLE` and `INVALID` are evidence about an attempt. They are never
silently converted to a boolean value. Operational failures that are unsafe to
canonicalize, such as a local plugin crash, need not be signed at all.

Each member may contribute at most one report per `(round, source)` to a
certificate. Conflicting signed reports by the same member are equivocation
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
result digest
```

Certificate verification is a pure, bounded function:

1. decode canonical bytes and reject duplicate or unordered members/sources;
2. verify every report's domain, round, membership, and signature;
3. verify size, count, source, freshness, and evidence bounds;
4. run the deterministic evidence validators;
5. run the deterministic policy over a canonical report order;
6. recompute output, certificate identity, and result digest; and
7. require the definition's validator and source thresholds.

The verifier sees only the reports encoded in the certificate; it cannot prove
that another node has no additional report. An admissible policy must therefore
be either:

- **certificate-monotonic:** once a certificate proves result `R`, adding any
  other valid report cannot produce a conflicting valid result; or
- **closure-certified:** the certificate proves a fixed eligible report/source
  set before applying a non-monotonic aggregation.

For raw exact quorum, `r = q`, quorum intersection, and honest no-double-signing
make the selected value certificate-monotonic. Within that value group the
canonical certificate uses the lexicographically lowest sufficient reporter
keys, not an arrival prefix. A median is not certificate-monotonic: adding a
later value can change it. Phase 3 must therefore use a fixed required source
set whose per-source values are already certified, or a separately committed
round-closure snapshot. A simple "first 5 of 10" median is inadmissible. A
policy that cannot prove a unique result from its encoded evidence is rejected.

A certificate does **not** finalize itself. It is carried in a reserved
framework input and becomes authoritative only after the containing app block
obtains the normal Yano commit certificate.

### 6.6 `ObservationResult`

A result is the deterministic application-facing projection of a valid
certificate:

```text
resultId
subscriptionId and roundNumber
definitionDigest
status: VALUE | NO_RESULT | EXPIRED | CANCELLED
canonical value bytes, if any
value/evidence/certificate digests
source and reporter counts
source freshness summary
finalized app height
```

Names may be refined during API design, but `NO_RESULT`/`EXPIRED` must remain
distinct from a domain value such as `false`, zero, `NOT_FOUND`, or
`PROCESSING`.

## 7. Protocol flow

### 7.1 Create and schedule

During deterministic application execution, the application emits a watch
intent. The observation kernel validates it against the installed definition,
assigns its ID, writes the committed subscription record, and adds a due-index
entry atomically with the block and app state root.

No HTTP call, source lookup, or timer creation occurs during apply.

### 7.2 Open a round

When committed height reaches `nextDueHeight`, each validator deterministically
discovers the same due entry. The kernel opens a round with the membership and
policy snapshot. Opening is a canonical transition; local workers may begin
after seeing it committed.

The runtime scans no more than configured entries and opens no more than a
configured number of rounds per block. Remaining entries stay ordered and due;
they are not skipped.

### 7.3 Acquire and report

Every validator reconstructs node-local work from committed open rounds. A
bounded worker pool calls the configured provider outside block execution. The
provider returns a canonical report candidate or a local failure. The host
validates bounds and identity before signing and persisting the report, then
diffuses it to current round members.

Retries use node-local queues and bounded backoff. Retry jitter affects only
when a node attempts I/O, never round identity, due height, expiry, or policy.

### 7.4 Certify

Any validator can collect reports and assemble a certificate. Peers verify and
gossip valid certificates. A proposer includes ready certificates in canonical
order under the block byte and result-count limits.

Generic observation readiness cannot use ADR-036's local mandatory-prefix
rule: correct validators may possess different reports or certificates at a
given instant. Rejecting every proposal that omits a locally known certificate
would fork proposal validity and harm liveness. Therefore Phase 1 guarantees:

- safety: an included result must carry a valid certificate;
- durability: open work and received reports survive restart;
- eventual inclusion: under eventual synchrony, a valid certificate reaches
  an honest proposer and remains eligible until included or expired; and
- no stronger censorship resistance than ordinary app messages when all
  scheduled proposers are Byzantine.

This limitation is explicit. A later protocol may add a quorum-known
availability/ready certificate and a deterministic inclusion deadline, but it
must not be inferred from local possession alone.

### 7.5 Finalize and apply

Followers verify each certificate before signing `PREPARE` or `COMMIT`. The
observation kernel incorporates results before ordinary application messages,
analogous to effect result incorporation. It closes or reschedules the round,
updates the due index and subscription, and invokes the deterministic
application callback. All framework and application writes commit atomically
with the block.

Catch-up re-verifies the encoded certificate and applies the same kernel. It
does not repeat external I/O.

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
complete query at a fixed external reference. A timeout is `UNAVAILABLE`.

### 8.2 Attested observations

An attested policy verifies an external signature against a pinned signer set,
claim schema, domain, and validity rules. Validator reports certify that the
attestation was obtained and verified. The resulting fact is that the signer
attested the claim, not that the claim is objectively true.

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

## 9. Logical time and durable scheduling

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
height-based subscription. Phase 1 therefore opens one-shot observations
immediately from the state transition that requests them (or at the next block
boundary) and promises no wall-clock cadence. Recurring `every N heights`
subscriptions in Phase 2 progress only while the app chain progresses.

Supporting a user promise such as "every five minutes" requires a separate
consensus-time and heartbeat-block decision. That decision must define who may
produce otherwise empty ticks, minimum/maximum timestamp movement, follower
validation, view-change behavior, idle resource cost, and recovery after long
downtime. Local timers may wake a node, but cannot make the time claim true by
themselves. Until that protocol exists, APIs and documentation must not
translate durations into an assumed block rate.

An external source timestamp may be validated as source evidence relative to a
definition-specific anchor, but it never decides independently whether a
subscription is due. Cardano slot/epoch remains specific to the Cardano lane.

Future bounded consensus time requires a separate decision that defines clock
skew, proposer constraints, median/previous timestamp rules, and replay
behavior. This ADR does not smuggle wall-clock semantics through `nextDue`.

### 9.2 Due-height index

Canonical framework storage maintains an ordered index conceptually equivalent
to:

```text
~obs/due/<height>/<subscription-id> -> round metadata
```

The current `AppStateReader` is point-read only, so the implementation must not
pretend that it can scan the MPF application trie. The due index belongs in a
dedicated framework column family, analogous to effect records/buckets, with a
pure kernel reader and writes staged atomically with block commit. The kernel
visits the lowest due keys in lexicographic order, performs a bounded scan,
opens a bounded number of rounds, and leaves an earlier unprocessed or
unencodable entry in place. It must never skip an earlier entry to process a
later one.

Deterministic summary/commitment leaves under `~obs/` bind the framework
records into the app state root. A missing due record, commitment mismatch, or
partial restore fails loudly and requires index rebuild by finalized-block
replay; silently skipping corruption could fork the transition.

Node-local runtime state may use a priority queue, timing wheel, or RocksDB
ordered index to wake workers efficiently. It is a cache reconstructed from
committed subscription and round state, not authority. There is no scheduled
task or thread per subscription.

### 9.3 Scale and backpressure

Supporting 100,000 active subscriptions is an eventual qualification target,
not permission for 100,000 concurrent calls. The profile bounds at least:

```text
active subscriptions per chain/application/definition
rounds opened per block
open rounds
reports and sources per round
report/evidence/certificate bytes
results and bytes per block
source requests per host/domain/definition
worker concurrency
retry attempts and local retained bytes
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
- due-height entries;
- open/closed/expired round summaries;
- incorporated result identity and replay markers;
- bounded counters needed for deterministic limits; and
- application-visible commitments required for proofs/audit.

The state trie stores deterministic framework commitments and proof-visible
summaries rather than being used as an ordered scheduler database. The
`~obs/` trie and system-topic prefixes are reserved from genesis even when
observations are disabled, exactly to prevent historical application keys from
colliding with later framework state.

### 10.2 Node-local operational state

A synchronously durable local store contains:

- acquired report candidates and signed reports;
- received peer reports and certificates;
- retry attempts and next local wake time;
- evidence cache/retrieval state; and
- diagnostic failure details.

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
a conflicting value for the same `(round, source)` after restart.

### 10.4 Rollback and replay

App-chain blocks are finalized and replayed through the existing ledger path;
observation state follows the same atomic block boundary. L1 rollback remains
the responsibility of ADR-036's specialized journal. An external source later
changing its answer starts or completes another generic round; it does not
rewrite an already finalized observation result.

## 11. Developer-facing model

The API names remain illustrative until Phase 0 API review. The intended shape
mirrors effects:

```java
void apply(
        AppBlockExecutionContext context,
        AppStateWriter state,
        AppEffectEmitter effects,
        AppObservationEmitter observations
) {
    // Deterministically records intent; performs no I/O.
    observations.watch(ObservationIntent.oneShot(
            "order-status-v1",
            canonicalOrderParameters,
            context.block().height() + 10,
            context.block().height() + 1_000));
}

void onObservationResult(
        AppBlockExecutionContext context,
        ObservationResult result,
        AppStateWriter state,
        AppEffectEmitter effects,
        AppObservationEmitter observations
) {
    // Deterministic state transition; may emit the next observation or effect.
}
```

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
rotation and durable gossip give eventual liveness under the normal honest
proposer assumption; stronger mandatory inclusion requires a future
quorum-known availability protocol.

### 12.6 Replay

Every report binds chain genesis, consensus profile, definition, subscription,
round, membership, source, and freshness fields. A valid old API response,
signature, or proof cannot satisfy a new round unless the definition
explicitly validates that evidence for that round.

## 13. Failure semantics

External systems fail normally. The framework separates three layers:

| Layer | Examples | Effect |
|---|---|---|
| Local acquisition | DNS, timeout, rate limit, plugin crash | Retry locally; optionally sign canonical `UNAVAILABLE` if definition permits |
| Evidence/report | malformed response, bad signature, stale proof, equivocation | Reject report or record canonical `INVALID`; never treat as domain `false` |
| Round/policy | insufficient reports/sources, disagreement, deadline | Remain open until closing height; deterministically expire unless a valid no-result certificate exists |

No local exception can be logged and then replaced with an empty successful
observation. A missing definition, codec, verifier, or policy implementation is
a consensus-profile/startup failure and the node cannot join. A transient
acquisition failure degrades reporting and triggers local retry, but a node
whose deterministic verifier is healthy may still verify and vote for a valid
certificate assembled by peers. Any certificate verification failure remains
fail-closed.

At the closing height the kernel can derive `EXPIRED` from committed round
state without a certificate. `NO_RESULT` requires a valid policy certificate,
for example a quorum of explicit canonical `UNAVAILABLE` reports; absence of
node-local reports cannot be used to derive it.

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

## 16. Cardano ADR-042 compatibility

ADR-042 observations remain under `~l1/*` with their current guarantees:

- every validator follows and retains a canonical Cardano view;
- configured providers declare canonical ABI/schema/ordering identity;
- the profile binds the L1 network genesis identity;
- facts become eligible only after Cardano stability;
- a global canonical prefix is mandatory and cannot skip an earlier fact;
- rollback invalidation and quarantine survive restart; and
- voter verification replays retained canonical L1 blocks when needed.

The generic `~obs/*` lane must not replace these properties with report quorum,
source policy, or best-effort inclusion. Cardano facts may be exposed to
applications through a future common read-only `CanonicalExternalInput` view,
and scheduling/journal utilities may be shared internally, but their wire
identity, verifier, rollback barrier, and mandatory inclusion remain separate.

This separation also permits a different use case—such as a threshold-signed
third-party Cardano indexer result—to use `~obs/*` honestly without being
confused with an ADR-042 self-observed Cardano fact.

## 17. Prior art and lessons

### 17.1 Flare Data Connector

[Flare FDC][flare-fdc] separates typed attestation requests, data providers,
voting rounds, finalization, Merkle commitments, and proof-based consumption.
It demonstrates the value of explicit source/attestation types and compact
committed results. Its Web2 guidance also shows why exact agreement on dynamic
HTTP responses can fail even for honest providers. Yano should borrow the
separation and determinism discipline, not assume that arbitrary Web2 data has
a single stable answer.

### 17.2 Chainlink

[Chainlink Data Feeds][chainlink-feeds], [Functions][chainlink-functions], and
Automation separate maintained feeds, custom off-chain computation, and
triggers. They are possible Yano sources rather than competitors that Yano must
reimplement. The main lesson is to distinguish an operator-maintained feed
from arbitrary user-supplied HTTP computation and to price/bound fan-out.

### 17.3 API3

[API3 data feeds][api3-feeds] emphasize first-party signed source data. This is
meaningfully different from several validators merely fetching one unsigned
endpoint. Yano's attested definitions should preserve the external signer and
source identity rather than relabel validator agreement as source attestation.

### 17.4 Cosmos vote extensions

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

## 18. Alternatives considered

### 18.1 Make every external fact an ordinary app message

Rejected. Ordinary messages authenticate a sender but do not bind a registered
source definition, round, validator report quorum, source diversity, evidence
policy, durable scheduling, or deterministic result lifecycle.

### 18.2 Let every application implement its own voting state machine

Rejected. This duplicates consensus-sensitive membership, signatures, quorum
intersection, replay protection, persistence, view/restart recovery, and
resource controls. It violates the core goal that developers define domain
semantics rather than BFT.

### 18.3 Generalize `L1Observation` directly

Rejected. Generic sources lack Cardano's shared canonical ledger, stability
point, rollback model, and complete deterministic prefix. A common superclass
would hide materially different trust and liveness guarantees.

### 18.4 Put reports inside PREPARE/COMMIT votes

Deferred. This resembles vote extensions but couples external-source latency
and certificate size to the finality hot path, complicates view change and
historical proof, and enlarges safety-critical codecs. A dedicated signed
report protocol feeding normal proposal validation keeps KISS boundaries.

### 18.5 Use block timestamps or validator wall clocks for cadence

Rejected for the initial protocol. Current timestamp constraints do not define
a strong shared clock and local clocks differ. Finalized height is replayable,
deterministic, and already available.

### 18.6 One scheduled task per subscription

Rejected. It does not scale or reconstruct cleanly. A durable ordered due index
plus bounded worker pools represents the same logical schedule.

### 18.7 Replicate HTTP failures and retry counters

Rejected. These differ by validator and are operational. Only round deadlines
and terminal results are canonical; retry machinery remains node-local.

### 18.8 Implement shared feeds immediately

Deferred. Deduplication can later use a digest of definition, parameters,
source set, policy, and cadence, but it adds ownership, retention, cadence, and
authorization complexity before the core protocol is proven.

## 19. Consensus profile and compatibility

The authenticated profile commits to:

- framework ABI/wire version;
- enabled definitions and their full canonical identities;
- reserved topic and state codec versions;
- logical-time and round rules;
- quorum and source-diversity rules;
- all consensus-critical limits;
- certificate ordering and policy versions; and
- result incorporation ordering.

A member missing a required definition or whose computed profile differs does
not join the chain. Operational settings may differ only where the definition
declares they cannot change canonical output.

While Yano remains in preview, initial activation is fresh-chain only. There is
no v0 observation wire compatibility to preserve. The first implemented wire
format is `v1`. `~obs/` is reserved even when disabled. Later in-place upgrades
require an explicit height-gated profile transition ADR.

## 20. Observability and operations

The host should expose, without leaking credentials or unbounded values:

- active/due/open/expired subscriptions and rounds;
- oldest due height and scheduling lag;
- local acquisition attempts, latency, classified failures, and rate limits;
- reports retained/sent/received/rejected and equivocation evidence;
- certificates ready/included/rejected and time-to-finality;
- results by status, definition, source count, and reporter count;
- local journal bytes, rebuild/replay progress, and quarantine state;
- provider/profile health and mismatch reasons; and
- source concentration indicators.

Readiness must distinguish inability to acquire new reports from inability to
verify an included certificate. The latter always prevents a vote. Metrics and
logs must use digests or bounded redacted values, never API secrets or full
sensitive claims.

## 21. Phased implementation plan

Each phase follows: implement, focused tests, adversarial review, learn, and
iterate before starting the next phase. Scope stays intentionally narrow.

### Phase 0 — protocol contracts and deterministic kernel skeleton

Deliver:

- canonical v1 codecs and domain-separated hashes for definition,
  subscription, round, report, certificate, and result;
- reserved `~obs/` topic and trie namespaces;
- observation profile commitment and startup compatibility guard;
- pure certificate/policy verification interfaces and conformance vectors;
- framework settings and strict resource bounds;
- model/property tests for canonical ordering, duplicate rejection, signature
  coverage, round replay, membership pinning, and malformed bytes; and
- no external acquisition or production scheduling yet.

Exit criteria:

- every consensus field is identified and signed/hashed where required;
- cross-JVM deterministic vectors pass;
- fuzzed decoders fail closed within bounds;
- the framework disabled path does not alter existing block/state roots; and
- ADR review has resolved all Phase 0 open questions.

### Phase 1 — one-shot exact and attested observations

Deliver:

- deterministic application `watch`/`cancel` API and result callback;
- one-shot height-triggered subscriptions and committed due index;
- bounded node-local provider workers and durable report journal;
- signed report gossip and exact-value certificate assembly;
- threshold external-signature verification;
- canonical result incorporation through existing block consensus;
- a restricted operator-approved HTTPS source adapter; and
- restart, proposer-rotation, partition, timeout, SSRF, replay, and
  equivocation regressions.

Exit criteria:

- a transient provider crash cannot fabricate a result or permanently lose
  work;
- an untrusted app cannot reach localhost/private/control-plane endpoints;
- a result cannot finalize with a forged, stale, cross-chain, cross-round, or
  insufficient certificate; and
- a one-shot Web2 and signed-attestation showcase runs on a multi-node devnet.

### Phase 2 — durable recurring subscriptions and recovery

Deliver:

- recurring height cadence, completion conditions, deterministic expiry, and
  bounded catch-up after long downtime;
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
  signing; and
- resource exhaustion causes bounded backpressure, not divergent state.

### Phase 3 — aggregation and source diversity

Deliver:

- fixed-point numeric schema and arithmetic helpers;
- two-stage validator-per-source reconciliation and cross-source aggregation;
- deterministic median with explicit even-count/tie/rounding rules;
- source diversity and minimum-source policies;
- an ADA/USD reference example using several logical sources; and
- permutation, outlier, equivocation, stale-source, and overflow property tests.

Exit criteria:

- every permutation of the same authenticated report set yields identical
  certificate and result bytes;
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

If real-duration schedules are required by then, first deliver and review a
separate consensus-time/heartbeat ADR and its adversarial tests. Phase 4 must
not infer five-minute semantics from an average app-block interval.

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

## 22. Required test matrix

At minimum, implementation must cover:

| Area | Required cases |
|---|---|
| Codec/identity | truncation, trailing bytes, duplicates, noncanonical order, cross-chain/profile/round replay |
| Signatures | value/evidence/source/round all covered, wrong member, old membership, equivocation |
| Policy | report permutations, minimal-certificate uniqueness, insufficient validator/source thresholds |
| Scheduling | same due height, earlier item too large, bounded scan, expiry, cancel, long downtime |
| Persistence | crash before/after sign, before/after gossip, certificate ready, proposal, final commit |
| Consensus | proposer rotation, view change, prepared value recovery, catch-up verification, omission by faulty proposer |
| Failures | DNS, timeout, 429, malformed/huge/compressed response, stale signature/proof, source disagreement |
| Security | SSRF address classes, DNS rebinding, redirect escape, secret redaction, parser depth/number bounds |
| Scale | 100k indexed subscriptions, bounded workers, disk limits, report/certificate amplification |
| Compatibility | observations disabled, effects only, Cardano `~l1/*`, EUTxO settlement lifecycle |

Model tests must exercise at least the ADR-036 `n=5, q=4, f=1` profile and
membership changes between rounds. Live qualification must show that an honest
node can assemble/include a certificate after a faulty proposer omits it and
that source disagreement halts the round rather than forking app state.

## 23. Review questions before implementation

The following must be resolved in the ADR review or Phase 0 API review:

1. Should round opening be an explicit committed system transition, or may it
   be derived from subscription/due state during the next block kernel pass?
   This ADR prefers explicit committed opening for audit and membership pinning.
2. Should Phase 1 certificates carry all bounded reports inline, or canonical
   report bytes plus evidence digests with an availability rule? This ADR
   prefers bounded inline reports/evidence first.
3. What exact chain health policy applies when a required provider is down but
   no round is currently open? Verification of included certificates remains
   unconditionally fail-closed.
4. Which plugin settings are logical source identity versus operational
   routing for each built-in adapter? Every provider must document this split.
5. Is a quorum-known availability certificate needed before recurring feeds,
   or is honest-proposer eventual inclusion sufficient for preview? It is not
   required for Phase 1 but must be revisited before graduation.
6. Which first non-Cardano use case should qualify the abstraction: signed
   shipment status, exact REST projection, or both? The plan recommends both
   exact REST and threshold attestation before aggregation.
7. Which concrete application requires elapsed-time scheduling, and can it use
   a source-native logical anchor instead? A separate consensus-time decision
   is required before exposing duration-based cadence.

## 24. Consequences

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
