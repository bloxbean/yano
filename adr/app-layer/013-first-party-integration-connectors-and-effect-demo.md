# ADR-013: First-Party Integration Connectors, No-Code Effect Demo, and Composition Roadmap

## Status

Accepted — implementation in progress; Phases 1.0--1.3 completed on 2026-07-16

The number is local to the `adr/app-layer` series. Root-level ADR-013 is an
unrelated node plugin and event-gap assessment.

| Milestone | Scope | Status |
|---|---|---|
| 1 | First-party connectors, evidence workflow, Compose/host demo, network/data lifecycle | In progress — Phases 1.0--1.3 accepted; Phase 1.4 next |
| 2 | Effect ownership, result continuation, and executor observability bridge | Planned — requires focused sub-design and minor framework changes |
| 3 | Out-of-box deterministic composite state machine and stock component preset | Planned — requires a dedicated composition sub-ADR |

Milestones are accepted and evidenced independently. Completing Milestone 1
does not silently mark the framework/composition milestones implemented.

## Date

2026-07-15

## Authors

BloxBean Team

## Parent / References

- ADR-005 — deterministic app-chain state-machine and atomic commit boundary
- ADR-006 — finalized-stream sinks, enterprise evidence, encrypted bodies,
  retention, metrics, and dashboards
- ADR-008.4 — Cardano script anchoring and stable L1 observations
- ADR-010 — deterministic effects, finality gates, at-least-once execution,
  result incorporation, and executor SPI
- ADR-010.1 — replay-stable effect-emission versioning
- Planned ADR-010.2 — production Cardano transaction preparation, wallet
  coordination, policy, and reconciliation
- ADR-011 — manifested multi-contribution plugin bundles, lifecycle, health,
  metrics, and operations
- ADR-012 — an oracle use case that will later consume hardened Cardano
  publication
- [DPP possible-design note](../app-chain/dpp-possible-design.md) — an
  informative downstream consumer of object storage, IPFS, Kafka, and later
  Cardano publication; it is not part of this decision

---

## 0. In plain words

ADR-010 gives Yano a safe way for a deterministic app chain to authorize work
outside consensus. The state machine writes an immutable instruction, the
Effect Runtime waits for the required finality, an executor performs the
external operation, and a signed result returns to deterministic state.

Yano currently demonstrates this with webhooks and an early Cardano payment
executor. Real applications also need to publish an acknowledged business
event to Kafka, preserve a document in an object store, and pin a known CID in
IPFS. Those operations should not be copied into every application or hidden
inside custom state machines. They should be first-party, separately
deployable integrations with shared safety, idempotency, configuration,
receipt, health, and test conventions.

This ADR adds three integration bundles:

| Bundle | Initial contributions | Future scope within the same technology boundary |
|---|---|---|
| `appchain-kafka` | Existing finalized-block Kafka sink plus new `kafka.publish` effect | Kafka-specific health, metrics, schemas, and additional explicit Kafka operations |
| `appchain-ipfs` | `ipfs.pin` effect | Pin reconciliation, retrieval verification, and separately designed add/unpin operations |
| `appchain-objectstore-s3` | `object.put` effect for S3-compatible stores | Additional safe S3-compatible operations; other provider APIs remain separate bundles |

The existing `appchain-kafka-sink` project is renamed to `appchain-kafka`.
The plugin id, Java package, finalized-sink scheme, and existing sink
configuration remain stable. The artifact is broadened because the sink and
effect share the Kafka client, connection/security machinery, health model,
and operational ownership. Their producers, cursor/result semantics, and
configuration activation remain independent.

The connectors are demonstrated with a no-code product-batch inspection or
compliance-evidence workflow. A sample certificate is staged in MinIO and
added to local IPFS. A deterministic evidence state machine records its hash,
emits `object.put` and `ipfs.pin`, and waits for both results. A supplied
automation runner then submits an idempotent continuation command; the state
machine verifies the incorporated storage results and emits `kafka.publish`.
A user can see the state proof, finality, Cardano app-chain anchor, storage
receipts, CID pin status, Kafka receipt, retries, and terminal business status
without writing Java.

The same scenario must work in two deployment styles:

1. a one-command Docker Compose environment containing the three-node Yano
   cluster and all external components; and
2. a normal deployment in which operators install the same plugin bundles and
   point the same scenario runner at independently managed Kafka, S3/IPFS, and
   Yano endpoints.

Devnet is the reproducible default. Preview and preprod are opt-in profiles.
Mainnet is structurally supported but explicitly guarded and cannot use a
sample or default signing key. L1 state, app-chain state, and connector data
are stored separately by network so an operator can reset the demo or app
chain without discarding an expensive public-network sync.

Delivery is deliberately staged. The first milestone ships the connectors and
demo using the current framework. The second adds bounded framework
improvements for explicit effect-type ownership and direct deterministic
result-to-effect continuation. The third provides an out-of-box composite
state machine so applications can combine compatible registry, document
trail, approval/evidence, and future domain components under one atomic state
root without hand-writing all composition plumbing.

## 1. Context and verified baseline

### 1.1 Effect system

ADR-010 is implemented and supplies:

- immutable deterministic effect records;
- authenticated effect commitments and historical proofs;
- `APP_FINAL`, `L1_ANCHORED`, and `ZK_SETTLED` gates;
- in-process and external-worker execution modes;
- retries, backoff, expiry, parking, quarantine, and bounded worker admission;
- node-local execution status and submitted-reference persistence;
- first-result-wins `~fx/result` incorporation; and
- `AppEffectExecutor` and `AppEffectExecutorFactory` plugin SPIs.

The delivery ceiling remains:

> Exactly-once incorporation with at-least-once external execution.

Every connector in this ADR must therefore tolerate the same effect being
presented again after a timeout, process crash, node restart, lost response,
or executor failover.

### 1.2 Existing Kafka sink

`appchain-kafka-sink` is a T3 plugin bundle contributing
`FinalizedStreamSinkFactory` under scheme `kafka`. It publishes one JSON
record for each finalized app block, maintains progress through the runtime's
durable sink cursor, enables Kafka producer idempotence, and documents
at-least-once delivery keyed by `(chainId, height)`.

Its current technical identity is already technology-wide rather than
sink-specific:

- Java package: `com.bloxbean.cardano.yano.appchain.kafka`;
- plugin id: `com.bloxbean.cardano.yano.appchain.kafka`;
- sink scheme: `kafka`; and
- configuration: `yano.app-chain.sinks.kafka.*`.

Only the Gradle project/directory, Maven artifact, bundle filename, README,
and build references contain the `-sink` suffix. Yano is still pre-release,
so this is the least disruptive point at which to correct that artifact name.

### 1.3 Multi-contribution plugin bundles

ADR-011 permits one manifested bundle to advertise multiple typed
contributions. A Kafka bundle may therefore contribute both a finalized sink
and an effect-executor factory without adding a generic service locator or a
new plugin mechanism.

Contribution products remain fresh and lifecycle-owned per chain. A mutable
Kafka producer, IPFS client wrapper, or S3 client lifecycle must never be
silently shared across independently created chain products unless a future
SPI explicitly defines shared ownership.

### 1.4 Current gaps

Yano does not yet ship:

- an acknowledged per-action Kafka effect;
- an S3/MinIO object publication effect;
- a real IPFS executor (the user guide contains only an SPI example);
- a common first-party connector contract and conformance suite;
- a deterministic reference state machine that composes several effects and
  their results into a useful business workflow; or
- a complete no-code environment with Kafka, MinIO, IPFS, observability, data
  management, and public-network profiles.

## 2. Decision summary

Yano will adopt the following decisions.

### C1. Organize deployable bundles by external technology

A bundle is named for a cohesive external protocol/provider boundary when its
contributions share client dependencies, endpoint and credential machinery,
health/metrics semantics, security boundary, and operational owner.

The provider-neutral wire model is a small, published, SDK-free library rather
than a core/runtime API or a sibling-plugin dependency:

```text
appchain/appchain-integration-contracts/
```

It contains only typed v1 codecs, bounds, fingerprints, error codes, detail
documents/archive conventions, CDDL, and golden vectors. Deterministic state
machines and all three bundles depend on the same bytes-only contract. It has
no Kafka, S3, IPFS, runtime, network, or credential dependency.

The published JAR carries the normative CDDL and literal golden vectors under
`META-INF/yano/contracts/connectors/v1/`. Thin artifacts and native-catalog
builds use one host-pinned contracts version. Every self-contained drop-in
bundle must instead relocate the contracts implementation into its own
connector-specific internal namespace (for example,
`...kafka.internal.contracts.v1`). Relocated types must never appear in a host
SPI signature; only the frozen bytes cross that boundary. This prevents two
independently installed bundles from resolving an unrequested shared contract
implementation through the ADR-011 parent-first loader. Packaging tests must
install multiple bundles together and prove that neither unrelocated contract
classes nor cross-bundle class identity are required.

The initial deployable modules are:

```text
appchain/extensions/appchain-kafka/
appchain/extensions/appchain-ipfs/
appchain/extensions/appchain-objectstore-s3/
```

The name is not permission to turn a bundle into a miscellaneous utility
module. Domain workflows, DPP rules, oracle rules, UI code, and unrelated
vendor SDKs remain outside the connector.

### C2. Rename the Kafka artifact and retain runtime identity

`appchain-kafka-sink` becomes `appchain-kafka`:

```text
Gradle project :appchain-kafka
artifact       yano-appchain-kafka-<version>.jar
bundle         yano-appchain-kafka-<version>-bundle.jar
plugin id      com.bloxbean.cardano.yano.appchain.kafka  (unchanged)
Java package   com.bloxbean.cardano.yano.appchain.kafka  (unchanged)
sink scheme    kafka                                     (unchanged)
sink config    yano.app-chain.sinks.kafka.*              (unchanged)
```

The old and new bundles must never be installed together because they would
declare the same plugin identity and sink contribution.

### C3. Keep contribution semantics and lifecycles separate

`appchain-kafka` will contain:

- a `finalized-sink` contribution named `kafka`; and
- an `effect-executor` contribution named `kafka` handling
  `kafka.publish`.

The two contributions may share immutable parsing, validation, serializer,
and producer-construction helpers. They must not share:

- a producer instance;
- a sink cursor;
- effect status or result state;
- shutdown ownership; or
- activation merely because the other contribution is configured.

### C4. Keep effect payloads small, deterministic, and secret-free

Effects contain committed instruction data, not credentials, arbitrary
network endpoints, or large documents. Targets are selected through stable
aliases resolved against executor-local configuration.

### C5. Use one reusable scenario across Compose and normal deployment

The evidence scenario, fixtures, assertions, and verifier are deployment
neutral. Docker Compose and host/normal deployment only supply endpoints,
credentials, processes, and storage paths.

### C6. Isolate data and configuration by Cardano network

Devnet, preview, preprod, and mainnet must not reuse the same L1 or app-chain
database directory. Every persisted network root carries an identity marker,
and startup fails before launching nodes if that identity disagrees with the
selected network/genesis configuration.

### C7. Cleanup is explicit, granular, and safe

Stopping preserves data. Cleanup distinguishes app-chain state, external
connector data, and L1 state. Public-network L1 deletion is never implied by
ordinary demo cleanup.

### C8. V1 uses the existing framework without a new core SPI

ADR-010 and ADR-011 already supply every framework capability needed by the
three connectors and the demo. V1 assigns one exclusive executor owner to each
new action type:

```text
kafka.publish -> configured kafka executor
object.put     -> configured objectstore-s3 executor
ipfs.pin       -> configured ipfs executor
```

The current runtime resolves an effect to the first configured executor whose
`supports(type)` returns true. It does not generically prove that two
differently named third-party executors cannot claim the same type. Therefore
the v1 supported-installation contract forbids another enabled executor from
claiming these reserved first-party types. Generic overlap detection or
multi-provider routing is a deferred framework hardening, not a prerequisite
for this implementation.

`AppStateMachine.onEffectResult()` can update deterministic state but does not
receive an `AppEffectEmitter`. A result-dependent next effect is therefore
requested by an explicit idempotent continuation command. This avoids a core
API change: an automation runner may request the transition, while the state
machine alone verifies that incorporated results satisfy its preconditions and
emits the next effect from ordinary `apply()`.

### C9. Minor framework closure follows the working demo

After Milestone 1 is proven end to end, a bounded ADR-013 sub-design may add:

1. **Explicit effect-type ownership.** Enabled executors declare the exact
   types they own, and startup rejects zero/multiple owners for configured
   first-party types rather than relying on first-match `supports()` order.
2. **Emitter-capable result continuation.** A source-compatible deterministic
   callback overload receives `AppEffectEmitter`, allowing an incorporated
   storage result to emit the next effect directly. The engine invokes one
   versioned path, and ADR-010.1 activation/replay rules govern adoption.
3. **Executor observability bridge.** Lifecycle-owned executor instances expose
   cached connector health/metric snapshots through a bounded host contract,
   rather than trying to share mutable instances with independently created
   ADR-011 health/metrics providers.

These changes improve safety and ergonomics but are not allowed to delay or
destabilize the connector/demo baseline. They require focused determinism,
lifecycle, ordering, replay, and compatibility tests before activation.

### C10. Provide a separately designed composite state machine

Yano currently selects one `AppStateMachine` per chain. Running `doc-trail`,
`kv-registry`, `approvals`, and evidence logic as independent selected machines
is therefore not possible, and blindly invoking several complete machines
would give them overlapping block, state-key, query, and effect ownership.

A later milestone will ship an out-of-box composite provider, provisionally
`appchain-composite`, with a component contract designed for composition. It
must provide deterministic:

- component identity, version, configuration, and ordering;
- state-key namespaces and enforced reader/writer views;
- message/topic routing with a single declared owner by default;
- shared atomic commit and one MPF state root;
- global effect ordering/quotas plus effect-to-component result ownership;
- namespaced and aggregate committed queries;
- activation-height upgrades; and
- cross-component workflow coordination without network I/O or hidden mutable
  state.

The first distribution should include compatible registry, document-trail,
approval, and evidence components. Domain products such as DPP may reuse them,
but a generic composite does not replace DPP-specific identities, schemas,
lifecycle rules, or cross-component invariants.

## 3. Goals and non-goals

### Goals

1. Ship production-shaped Kafka, S3-compatible, and IPFS integrations through
   the existing ADR-010/011 SPIs.
2. Establish stable v1 action schemas, target aliases, safety policy,
   idempotency behavior, receipts, errors, health, and metrics.
3. Make each executor safe under retry, restart, duplicate delivery, timeout,
   and lost acknowledgement within the limits of the external system.
4. Keep credentials, arbitrary endpoints, and large content out of
   authenticated effect records.
5. Preserve the existing finalized-block Kafka export while adding distinct
   acknowledged per-action publication.
6. Provide a useful no-code evidence workflow that demonstrates concurrent
   effects, incorporated results, a result-gated continuation effect, proofs,
   anchoring, failure, and recovery.
7. Provide a one-command Compose setup with all required external components.
8. Support the same demo against normally deployed or managed services.
9. Make devnet the deterministic CI baseline and public networks opt-in by
   changing a network profile/command rather than application code.
10. Preserve expensive L1 state while allowing app-chain and demo data to be
    cleaned independently.
11. Supply unit, connector integration, plugin packaging, three-node, restart,
    and optional public-network verification.
12. Follow the working v1 with explicit effect-type ownership and an optional
    deterministic result-continuation emitter under replay-safe activation.
13. Provide an out-of-box composite state-machine path for compatible stock
    components and future domain bundles.

### Non-goals

- Exactly-once external side effects in the presence of arbitrary process and
  network failure.
- Performing Kafka, S3, or IPFS I/O inside deterministic state-machine
  execution.
- A generic caller-supplied URL, broker, bucket, topic, or credential relay.
- Uploading large documents as effect payload bytes.
- Treating an object-store receipt, IPFS pin receipt, or Kafka acknowledgement
  as proof that the underlying business assertion is true.
- Making IPFS suitable for confidential plaintext.
- Shipping Google Cloud Storage or Azure Blob SDKs inside the first S3 bundle.
- Folding DPP, oracle, ERP, or other domain semantics into generic connectors.
- Production-hardening `cardano.payment` or adding native-asset transfer in
  this ADR; those depend on ADR-010.2.
- Kubernetes operators, Helm charts, Terraform, or managed-cloud provisioning
  in v1. Normal deployment documentation remains portable to those systems.
- Automatically running value-moving tests on mainnet.
- Treating arbitrary existing `AppStateMachine` implementations as safely
  composable without a component contract and namespace/routing rules.
- Claiming that generic composition alone supplies DPP or other domain
  compliance semantics.

## 4. Architectural boundaries

### 4.1 Deterministic plane

The state machine may:

- validate a versioned command;
- store a content hash, CID, object locator alias/key, business state, and
  expected effect set;
- emit deterministic effect intents;
- receive an authenticated terminal effect result;
- validate that the result corresponds to the expected effect and state;
- transition business status; and
- accept an explicit idempotent continuation command and emit a follow-up
  effect from ordinary `apply()` only after committed preconditions hold.

It must not:

- resolve DNS;
- connect to Kafka, S3, or IPFS;
- inspect executor configuration;
- use local file-system state;
- trust a wall-clock timestamp returned by an external service for ordering;
  or
- change deterministic state based on an uncommitted local attempt.

### 4.2 Execution plane

The executor may:

- resolve a committed target alias through local configuration;
- authenticate to the configured service;
- validate local policy and payload bounds;
- probe for an earlier successful operation;
- perform one bounded external attempt;
- return `SUBMITTED`, `CONFIRMED`, or retryable/definitive failure under
  ADR-010; and
- expose bounded health and metrics.

Its attempt count, transient errors, connection pool, client metadata, and
latency are node-local operational facts. Only the bounded terminal receipt
that returns through `~fx/result` becomes authenticated state.

### 4.3 External service

The external service supplies its own acknowledgement or stored state. That
evidence has different strength for each connector:

| Connector | What confirmation establishes | What it does not establish |
|---|---|---|
| Kafka | A broker acknowledged a record at the returned topic/partition/offset | Every consumer processed it or the business fact is true |
| S3-compatible store | The configured destination contains the expected immutable bytes/version under the selected policy | The source document's business claims are true |
| IPFS | The configured provider reports the expected CID pinned under the selected policy | Permanent global availability or confidentiality |

## 5. Common connector contract

### 5.1 Stable action identity and schema version

Initial effect types are exact, stable strings:

```text
kafka.publish
object.put
ipfs.pin
```

Every payload begins with an explicit positive schema version. V1 decoders
must reject unknown versions definitively rather than guessing. The action
type is not suffixed with `v1`; the payload version permits compatible routing
while ADR-010.1 governs replay-stable emission changes.

Canonical CBOR is the normative binary encoding for consensus-generated
payloads and receipts. A JSON representation may be accepted by client/demo
tooling, but it must map unambiguously into the same typed model and limits.
V1 codecs admit only definite arrays, byte/text strings, unsigned integers,
booleans, and null; maps, tags, negative numbers, floats, indefinite forms,
and non-preferred encodings are rejected. A raw iterative preflight limits a
document to 512 data items and eight nested array levels, and validates every
declared length before the object-building decoder sees it. These fixed safety
bounds are part of the v1 contract rather than operator settings.

The published aggregate CDDL is the self-contained structural schema. Some
semantic constraints are not portably expressible in RFC 8610: Kafka header
ordering, uniqueness, aggregate byte size and reserved-prefix exclusion, plus
the special `.`/`..` physical-topic exclusion. The CDDL comments, exact rules
in this ADR, golden vectors, and executable codec negative tests are therefore
also normative; passing a CDDL validator alone is necessary but not sufficient
for a conforming encoder.

### 5.2 Target aliases

A payload names only configured aliases, for example:

```text
target = "archive"
topic  = "evidence-ready"
```

Executor-local configuration maps those aliases to actual endpoints, buckets,
topics, credentials, TLS trust, and policy. Missing or disabled aliases are
definitive policy failures, not invitations to use a payload URL.

Alias syntax is restricted to a small portable character set and bounded
length. Unknown fields, invalid Unicode, oversized values, or non-canonical
encodings are rejected consistently. V1 commands are fixed arrays rather than
maps, so duplicate or unknown fields are structurally impossible.

V1 aliases and configured `target-id` values are printable lowercase ASCII
matching `[a-z][a-z0-9-]{0,62}` (1--63 bytes). They are immutable logical
names: an operator does not repoint `archive-v1` or `primary-v1` while any
effect using it may still execute. A changed real destination receives a new
alias, and every failover executor resolves an alias identically.

Each configured profile also has a non-secret immutable `target-id`. It uses
the same grammar as an alias but is an independently versioned identity, not
necessarily the payload alias. Every confirmed receipt binds a 32-byte,
connector-domain-separated fingerprint of
that id and the resolved resource. Credentials are excluded from the
fingerprint descriptor. This makes the receipt auditable even though the
alias-to-real-destination mapping is executor-local rather than consensus
state.

V1 fingerprints use BLAKE2b-256 over the printable ASCII domain separator
immediately followed by the indicated bytes (there is no implicit delimiter):

| Fingerprint | Domain | Exact input after domain |
|---|---|---|
| Kafka destination | `yano-kafka-destination-v1` | canonical CBOR `[target-id, physical-topic]` |
| Object destination | `yano-object-destination-v1` | canonical CBOR `[target-id, destination-bucket, normalized-prefix, relative-key, encryption-policy-id, retention-policy-id]` |
| Object version | `yano-object-version-v1` | 1--1024 printable ASCII bytes of the immutable provider version id |
| IPFS target | `yano-ipfs-target-v1` | canonical CBOR `[target-id]` |
| IPFS CID | `yano-ipfs-cid-v1` | canonical binary CIDv1 bytes |

The Kafka physical-topic descriptor matches `[A-Za-z0-9._-]{1,249}` and is
neither `.` nor `..`. Encryption- and retention-policy ids use the alias
grammar. Every descriptor is credential-free and its literal preimages and
expected outputs are part of the published golden vectors.

### 5.3 Idempotency key

The canonical ADR-010 effect id/hash is the idempotency identity. Executors
must transmit or record it wherever the external system permits and probe
external state before repeating a non-idempotent operation.

No connector may generate a random idempotency key, derive it from attempt
number, or accept a caller-selected replacement.

### 5.4 Bounded receipts

ADR-010's authenticated terminal envelope has fixed bounds:

```text
externalRef  canonical connector handle, at most 128 bytes
detailHash   optional 32-byte commitment to an off-chain detail document
```

The result envelope already binds the effect id and outcome; connector handles
must not waste the 128-byte budget repeating them. Each connector in this ADR
defines a compact canonical `externalRef` schema containing only the fields
the state machine needs. The originating effect record supplies target alias,
object key, topic alias, CID, expected digest, and other instruction data.

Anything larger belongs in a canonical detail document. An executor may return
`detailHash` only after durably archiving the exact detail bytes under a
configured executor policy and retaining a way for an authorized operator to
retrieve them by hash. If it has no such archive, it omits `detailHash`; it
must not return a commitment to an ephemeral response.

Connector detail documents use canonical CBOR and
`blake2b-256("yano-fx-detail-v1" || canonicalDetailBytes)` unless a later
framework-wide detail-document profile supersedes it through an explicit
version. The domain separator and codec are part of the golden vectors.

The v1 envelope is:

```text
[ version=1, effectIdHash=bstr32, actionCode=(1 Kafka / 2 object / 3 IPFS),
  connectorSpecificDetail ]
```

The action code and detail shape are coupled; a Kafka code cannot wrap an
object or IPFS detail. Object `retentionMode` is `0` (`NONE`) only with a null
`retainUntilMs`, and is `1` (`GOVERNANCE`) or `2` (`COMPLIANCE`) only with a
non-null unsigned millisecond value. Object provider-version ids are 1--1024
printable ASCII bytes, optional ETags are 1--256 printable ASCII bytes, and
optional IPFS provider references are 1--256 printable ASCII bytes. Other
Unicode and control characters are rejected rather than normalized.

Its canonical encoding is at most 8,192 bytes. Connector detail fields are an
exact allowlist of stable acknowledgement data. Observation time, attempt
number, and other re-probe-volatile fields are excluded so reconciliation of
the same success produces the same detail bytes and hash.

The supplied filesystem archive uses create-if-absent durable writes and the
hash-derived key
`v1/blake2b-256/<first-two-hex>/<remaining-hex>.cbor`. Retrieval accepts a
hash, never a path; rehashes and strictly decodes the bytes; and is exposed
only through an authorized operator surface. If archival is disabled, a
connector omits `detailHash`. If required archival fails after the external
action, a retry probes that action before any mutation.

The reference file archive deliberately requires a private POSIX filesystem
with owner-only directories/files, hard-link create-if-absent, file fsync, and
directory fsync; it probes those capabilities during construction and fails
closed. Operators provision a dedicated filesystem quota/high-watermark and
monitor capacity. Exhaustion is `DETAIL_ARCHIVE_FAILED`, never permission to
return a hash for non-durable bytes. Provider-native archives may implement
the same interface only when they preserve these create-if-absent, durability,
retrieval, and hash-verification semantics.

The reference implementation assumes the runtime UID and its private archive
tree are trusted against concurrent path replacement; no other process may
write that tree under the same UID. Crash-left `.detail-*.tmp` files contain
only private detail documents and are never treated as committed entries.
Operators may remove them only while all archive writers are stopped.

Receipts and detail documents must not contain credentials, whole source
documents, unrestricted response bodies, complete client configuration, or
unbounded vendor error messages.

Service wall-clock time remains node-local in v1 and cannot control
deterministic ordering, expiry, replay, or a detail hash.

The 128-byte/32-byte authenticated limits are framework invariants, not
operator-tunable connector settings. Detail-document and normalized-error
maximum sizes have safe hard ceilings. Oversized vendor responses are reduced
to allowlisted fields; node-local log text is redacted and truncated.

### 5.5 Gate, result policy, and expiry

Generic applications choose an ADR-010 finality gate and whether an outcome
must return to the chain. The reference evidence workflow uses:

| Action | Gate | Result policy | Reason |
|---|---|---|---|
| `object.put` | `APP_FINAL` by default | `CHAIN` | Business state waits for archived storage confirmation |
| `ipfs.pin` | `APP_FINAL` by default | `CHAIN` | Business state waits for configured pin confirmation |
| `kafka.publish` | `APP_FINAL` | `CHAIN` | The demo displays incorporated notification status |

An operator may select `L1_ANCHORED` as the storage-publication gate in a
separately identified immutable chain profile. It is not changed at runtime.

Every `CHAIN` effect has a deterministic expiry within the configured ADR-010
result window. The evidence machine handles `FAILED`, `CANCELLED`, and
`EXPIRED` explicitly. A generic fire-and-forget Kafka notification may use
`ResultPolicy.NONE`, but then application state and the UI cannot depend on an
incorporated Kafka receipt.

### 5.6 Error classification

Only the following bounded ASCII machine codes may enter
`EffectExecution.Failed.reason`; vendor text remains redacted node-local data:

| Code | Default classification |
|---|---|
| `INVALID_PAYLOAD`, `UNSUPPORTED_VERSION` | Definitive |
| `UNKNOWN_TARGET`, `TARGET_DISABLED`, `POLICY_DENIED` | Definitive |
| `TARGET_CHANGED`, `AUTH_UNAVAILABLE` | Retryable then parked for operator action |
| `RATE_LIMITED`, `SERVICE_UNAVAILABLE` | Retryable with backoff |
| `ACK_UNKNOWN` | Retryable; probe before another mutation where the protocol permits |
| `SOURCE_UNAVAILABLE`, `CONTENT_UNAVAILABLE` | Retryable; deterministic expiry remains authoritative |
| `CONTENT_NOT_FOUND` | Definitive only when configured policy proves no allowed source can supply it |
| `SOURCE_MISMATCH`, `DESTINATION_CONFLICT`, `PROVIDER_REJECTED` | Definitive |
| `DETAIL_ARCHIVE_FAILED`, `SHUTDOWN`, `INTERNAL_ERROR` | Retryable then parked |

Codes match `[A-Z][A-Z0-9_]{0,63}`. Failed results cannot carry a
`detailHash` under ADR-010 v1, so unrestricted failure details are never
smuggled into `externalRef`.

### 5.7 Configuration and secrets

Configuration follows existing contribution namespaces:

```text
yano.app-chain.sinks.kafka.*
yano.app-chain.effects.executors.kafka.*
yano.app-chain.effects.executors.ipfs.*
yano.app-chain.effects.executors.objectstore-s3.*
```

Connection secrets live only in executor-node configuration, mounted secret
files, environment secret references, or later KMS/Vault providers. They are
never placed in:

- effect payloads;
- app-chain messages;
- authenticated state;
- receipts;
- metrics labels;
- dashboard JSON; or
- example public-network configuration files.

The v1 implementation may repeat connection properties between the Kafka sink
and effect namespaces. A shared profile namespace is deferred until a concrete
need justifies changing how factory configuration is supplied.

### 5.8 Client and lifecycle ownership

Every factory invocation creates fresh per-chain contribution products as
required by ADR-011. Shared code may create clients; client instances remain
owned and closed by the product that received them.

`close()` must be bounded, idempotent, interrupt-aware, and safe when creation
partially fails. Tests cover failed construction, concurrent execution and
close, blocked client callbacks, and native/JVM plugin teardown.

### 5.9 Safety policy

Every executor provides:

- endpoint/target allowlists;
- strict TLS by default outside the local devnet profile;
- request and response size bounds;
- concurrency and rate bounds;
- timeouts no longer than the runtime's bounded attempt model;
- credential redaction;
- SSRF, path traversal, header injection, and log injection defenses relevant
  to the protocol; and
- a fail-closed production/public-network profile.

### 5.10 Health and metrics

Milestone 1 uses the existing plugin inventory, Effect Runtime statistics,
effect status/operations APIs, and scenario-runner service checks. ADR-011 v1
constructs bundle health/metrics providers independently and gives them no
executor instance or connector configuration. A bundle must not work around
that boundary with mutable static registries or duplicate live clients.

Milestone 2 adds the lifecycle-owned executor observability bridge. After that
bridge, each connector exposes cached bounded-cardinality snapshots containing:

- configured/active state per contribution;
- last successful probe/attempt age;
- bounded status: ready, degraded, or unavailable;
- attempts, confirmations, retryable failures, definitive failures, and
  conflicts by action type;
- latency histogram by action type/target class, not raw target name when the
  target set is unbounded; and
- current in-flight count.

Metrics must not label by effect id, object key, CID, Kafka message key, error
text, bucket, raw topic, or external receipt.

Health/Prometheus/UI reads use cached operational state. They must not perform
a live Kafka metadata call, S3 request, or IPFS RPC on every scrape/request;
bounded background probes update the cache.

The absence or degradation of a local executor never changes consensus state
by itself. Effects remain unsupported/pending locally while another authorized
executor or external worker may execute them; a `CHAIN` effect closes only
through an incorporated result or its deterministic chain-wide expiry.

## 6. `appchain-kafka`

### 6.1 Bundle contents

The renamed bundle contains:

```text
com.bloxbean.cardano.yano.appchain.kafka
├── sink/       finalized-block export
├── effects/    kafka.publish executor and codec
├── config/     strict Kafka property and alias parsing
└── internal/   producer construction and bounded helpers
```

Existing public/source package moves are not required merely to match this
illustrative internal layout. Avoid needless compatibility churn.

Its manifest declares both contributions and the JAR merges both
ServiceLoader descriptors. Native build-time inclusion and JVM drop-in bundle
packaging must exercise both contributions.

### 6.2 Finalized sink versus per-action effect

| Property | Finalized Kafka sink | `kafka.publish` effect |
|---|---|---|
| Unit | Whole finalized app block | One explicitly authorized business message |
| Ordering/progress | Height order with durable sink cursor | ADR-010 effect queue, gate, retry, and result lifecycle |
| Result in app state | None | Terminal receipt through `~fx/result` |
| Failure coupling | One blocked sink delivery delays subsequent block export | One effect can retry/park independently |
| Primary use | Projection, indexing, analytics, replay | Acknowledged integration command/business notification |
| Dedupe key | `(chainId, height)` | ADR-010 effect id/hash |

Bulk read models should continue to use the finalized sink. Applications must
not emit one `kafka.publish` effect per block message merely to reconstruct a
projection already available through the ordered sink.

### 6.3 `kafka.publish` v1 payload

The normative typed model contains:

```text
version       positive integer, exactly 1
target        configured Kafka connection profile alias
topic         configured topic alias
key           bounded byte/string key
contentType   bounded lowercase media type without parameters
body          bounded inline opaque bytes
headers       bounded allowlisted application headers
```

It must not contain:

- bootstrap servers;
- SASL/TLS credentials;
- arbitrary Kafka client properties;
- a transactional id;
- a raw unrestricted topic name;
- caller-supplied `yano-*` headers; or
- an unbounded document.

V1 never dereferences a body. An application may encode a committed
object/CID reference inside its own opaque event format, but that does not
create a connector-side fetch or SSRF boundary. The encoded command is at
most 12,288 bytes; key at most 256; body at most 8,192; and at most 16 unique,
sorted application headers use at most 2,048 aggregate bytes. An empty key
means the executor uses the 32-byte effect id hash.

The `contentType` grammar is exactly
`[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+`, 1--127 printable ASCII bytes;
parameters and whitespace are not valid. An application header name is
exactly `[a-z0-9][a-z0-9._-]{0,31}` (1--32 bytes) and must not begin with the
reserved `yano-` prefix. Header values
are opaque bytes. Duplicate names are rejected and names are encoded in
lexical order.

The executor injects the following exact reserved headers. Their names and
encodings are v1-stable connector behavior:

| Header | Value encoding |
|---|---|
| `yano-effect-id` | lowercase 64-character hex effect-id hash, US-ASCII |
| `yano-chain-id` | exact effect-record chain id, UTF-8 |
| `yano-effect-type` | literal `kafka.publish`, US-ASCII |
| `yano-payload-version` | literal `1`, US-ASCII |
| `yano-origin-height` | canonical unsigned decimal height, US-ASCII |
| `yano-origin-ordinal` | canonical unsigned decimal ordinal, US-ASCII |
| `yano-content-type` | the validated payload content type, US-ASCII |

The application cannot supply or override a `yano-*` header. Reserved values
are individually bounded to 256 bytes and together to 1,024 bytes.
The implementation freezes a hard body/header limit below the chain's generic
effect-payload ceiling. Partition selection is derived from the configured
topic policy and stable key; a caller cannot inject an arbitrary partitioner
class.

### 6.4 Kafka acknowledgement and duplicate behavior

The producer uses `acks=all`, producer idempotence, bounded delivery timeout,
and safe ordering defaults. The compact authenticated `externalRef` is
canonical CBOR and fits well below 128 bytes:

```text
[ version=1, destinationFingerprint=bstr32, partition, offset ]
```

`destinationFingerprint` is
`blake2b-256("yano-kafka-destination-v1" ||
canonicalCBOR([targetId, configuredPhysicalTopic]))`.

The effect record already commits the target, topic alias, key, body, and
effect id. An optional archived detail document may additionally retain the
resolved-topic fingerprint and allowlisted broker acknowledgement fields; its
hash is returned only under §5.4.

If the broker acknowledgement is known but detail archival fails, the
executor returns `Submitted(receipt)` so the runtime persists the receipt. A
retry with that reference validates its destination and retries only archival;
it never republishes. Continued archive failure is retryable and eventually
parks under the normal attempt budget. A malformed or newer node-local
submitted reference is an operational `INTERNAL_ERROR`, never a definitive
business failure, and also never authorizes republishing.

Kafka producer idempotence does not provide cross-process exactly-once
delivery after an acknowledgement is lost. A crash after broker commit and
before Yano persists `CONFIRMED` may produce a duplicate after restart.
Therefore:

- the effect id is carried in a reserved header;
- the stable message key should normally be derived from the business entity
  or effect id;
- downstream consumers must deduplicate by effect id where duplicates matter;
  and
- the connector documentation must not claim Kafka exactly-once semantics.

Kafka transactions or an external deduplication ledger are deferred and would
require a separate failure/recovery design.

## 7. `appchain-objectstore-s3`

### 7.1 Scope and naming

The initial module targets the S3 protocol/API and therefore uses the honest
name `appchain-objectstore-s3`. It supports AWS S3, MinIO, Ceph, and compatible
services within a tested compatibility profile.

Google Cloud Storage and Azure Blob Storage have different SDK, identity,
conditional-write, retention, and dependency boundaries. Future support uses
separate bundles such as:

```text
appchain-objectstore-gcs
appchain-objectstore-azure
```

A small shared `appchain-objectstore-api` is introduced only if real reusable
code emerges. The ADR-010 effect payload/receipt contract, not a speculative
Java abstraction, is the primary portability boundary.

Before installing two different provider bundles that both claim
`object.put`, Yano must define unambiguous executor selection. V1 supports
exactly one active owner, `objectstore-s3`, for `object.put` per chain. A
second executor claiming the type is an unsupported configuration because the
current generic runtime does not yet detect every predicate overlap.
Multi-provider routing/fail-fast overlap hardening is deferred in §21.

### 7.2 Pre-staged object model

V1 does not carry document bytes in the effect. The normal flow is:

1. an authenticated gateway/demo script canonicalizes the document;
2. it computes the required digest and size;
3. it places the object in a configured staging location;
4. it submits one target alias, relative keys, digest, size, and business metadata;
5. deterministic state records that commitment and emits `object.put`;
6. the executor verifies the staging object and promotes those exact bytes
   with a conditional create into an immutable/versioned destination; and
7. the receipt returns the durable destination identity and integrity data.

This avoids replicated large payloads, executor-side arbitrary HTTP fetches,
and ambiguous hashing of transformed data.

### 7.3 `object.put` v1 payload

```text
version          exactly 1
target           configured immutable staging/archive pair alias
sourceKey        normalized relative staging key
destinationKey   normalized relative archive key
digestAlgorithm  fixed/allowlisted algorithm, initially SHA-256
digest           expected content digest
size             exact expected byte count
contentType      bounded informational type
retentionClass   configured policy alias, optional
```

The payload cannot select an endpoint, credential, arbitrary bucket, local
path, HTTP URL, encryption key, Object Lock duration, ACL, or unrestricted S3
header.

Keys are normalized and validated. Absolute paths, `..`, control characters,
ambiguous Unicode, empty segments, configured-prefix escape, and any attempt
to encode a separator are definitive failures.

The encoded command is at most 2,048 bytes. Each key is 1--512 printable
ASCII bytes and contains at most 32 slash-separated segments. Every segment
is 1--128 bytes and matches
`[A-Za-z0-9][A-Za-z0-9._~!$'()+,;=@-]{0,127}`; `.` and `..` are forbidden.
The whole key cannot start or end with `/` and cannot contain backslash or
percent encoding. A configured destination prefix uses the same key grammar,
or the empty string for no prefix, and therefore never has a trailing slash.
The destination bucket used in a fingerprint matches
`[A-Za-z0-9][A-Za-z0-9._-]{0,254}`. V1 objects are at most 16 MiB; target
policy may lower that ceiling. SHA-256 is encoded as algorithm code `1` with
raw 32-byte digest.

### 7.4 Idempotency and conflict rule

For a given effect and destination:

- if no destination exists, perform a conditional create/copy;
- if an existing immutable version has the exact expected size and verified
  digest/effect metadata, return it as confirmed;
- if a destination exists with different bytes or ownership metadata, return
  a definitive conflict and never overwrite it; and
- if acknowledgement is unknown, probe destination/version state before
  attempting another write.

S3 ETag is not a universal content hash. Multipart upload and encryption can
change its meaning. SHA-256 or another explicitly configured checksum must be
verified independently of ETag.

Bucket versioning alone is not idempotency: an unconditional retry can create
another version. The target compatibility profile must prove an atomic
destination no-overwrite/conditional-create operation. If a provider cannot
enforce it for server-side copy, the executor uses a bounded verified transfer
plus conditional destination write or rejects that profile; it never falls
back to unconditional overwrite.

V1 freezes the bounded verified-transfer path as the baseline: read exactly
the committed staging length, require EOF, compute SHA-256 locally, then issue
one `PutObject` with `If-None-Match: *`, exact content length, and SHA-256
checksum. Server-side `CopyObject` is not a v1 optimization because the command
does not pin a source version and a mutable staging key would introduce a
source-check/source-copy race.

### 7.5 Storage safety and receipt

The target profile controls:

- endpoint and region;
- allowed source/destination buckets and prefixes;
- managed-AWS expected source/destination bucket owners;
- TLS/trust and addressing mode;
- credential provider;
- server-side encryption policy;
- bucket versioning requirement;
- optional Object Lock/WORM retention profile;
- maximum object size; and
- operation timeouts/concurrency.

V1 uses a bounded single-operation transfer profile suitable for passport and
evidence documents. Multipart upload/copy is disabled until its upload-id
journal, part reconciliation, abort cleanup, checksum, crash, and retention
semantics are specified and tested.

The compact authenticated `externalRef` is canonical CBOR:

```text
[ version=1,
  destinationFingerprint=bstr32,
  objectVersionFingerprint=bstr32,
  verifiedDigest=bstr32,
  size=uint ]
```

The destination fingerprint commits the non-secret configured target id,
destination bucket/prefix and relative key, encryption-policy id, and
retention-policy id under domain `yano-object-destination-v1`. The version
fingerprint commits the mandatory immutable provider version id under
`yano-object-version-v1`.

This must remain at most 128 bytes in its canonical encoding. The full
provider version id, retention response, and allowlisted request fields may be
placed in a durably archived detail document committed by `detailHash` under
§5.4. Raw credentials, encryption material, arbitrary response headers, and
the complete object key are never placed in authenticated receipt state.

### 7.6 Frozen S3-compatible v1 profile

Destination bucket versioning is mandatory. `require-versioning=false` is not
a supported v1 setting, and the executor checks that the provider still
reports `ENABLED` before mutation. Every successful or reconciled destination
must expose a bounded printable immutable provider version id; Java `null` or
S3's literal unversioned `null` sentinel is target drift, not a successful
receipt.

The compatibility profile also requires a destination policy with one
connector writer and no `DeleteObject`/`DeleteObjectVersion` permission for
that writer or any concurrent application principal. Connector and application
principals also cannot change bucket lifecycle configuration, and no lifecycle
expiration or noncurrent-version cleanup rule may apply to the archive prefix.
Administrative deletion or lifecycle change is an offline, fenced operation.
S3's `If-None-Match: *` tests only the current
representation: without this deployment invariant, another principal could
create and delete between the history probe and PUT, allowing a second version
behind a delete marker. The executor proves no-resurrection under the declared
exclusive/create-only policy; it cannot infer an arbitrary bucket IAM policy
atomically from the object API.

The executor probes the current destination before every mutation. If it is
absent, it performs a bounded exact-key version-history check. A prior version
or delete marker is a definitive conflict: v1 does not resurrect a deleted
object or create a second version after out-of-band deletion. An inaccessible
probe is never treated as absence. A present candidate is downloaded within
the command/target bound and independently rehashed; ETag and user metadata
alone are not content verification.

For provider SHA-256 responses, `FULL_OBJECT` (or a legacy response with no
declared checksum type) must equal the locally computed whole-object digest.
AWS multipart `COMPOSITE` checksums use the canonical
`base64-digest-partCount` shape with a positive part count no greater than
10,000; the connector validates that bounded shape but never mistakes it for a
whole-object digest. The command digest and the connector's local hash remain
authoritative. A successful single-request destination PUT may omit the
response checksum, but if it supplies one it must be a matching full-object
checksum; a composite or unknown checksum type makes the acknowledgement
unknown and forces state reconciliation.

The exact v1 user-metadata set is:

| Key | Value |
|---|---|
| `yano-schema` | literal `1` |
| `yano-action` | literal `object.put` |
| `yano-effect-id` | lowercase 64-character effect-id hex |
| `yano-sha256` | lowercase 64-character content-digest hex |
| `yano-size` | canonical unsigned decimal size |
| `yano-content-type` | validated command content type |
| `yano-target-id` | configured non-secret target id |
| `yano-encryption-policy` | configured encryption-policy id |
| `yano-retention-policy` | configured retention-policy id |
| `yano-retention-class` | command alias, or literal `none` |
| `yano-retain-until` | canonical epoch milliseconds requested for Object Lock, or literal `none` |

Missing, different, or additional user metadata is a destination conflict.
The content type, actual encryption response, retention mode/deadline, version
id, bytes, digest, size, and destination fingerprint must also reconcile. The
provider version id, ETag, content type, metadata fields, and KMS identifier
must be bounded printable ASCII before receipt or detail construction. The
provider retention deadline must equal `yano-retain-until`; a known successful
PUT must additionally equal the deadline requested by that call. On unknown-
acknowledgement recovery, the mutually matching provider value and immutable
metadata value preserve the original deadline without recomputing it.
Changing encryption keys, modes, retention duration/mode, buckets, or prefixes
requires a new corresponding policy/target id.

Encryption modes are exactly `none`, `sse-s3`, and `sse-kms`. `none` is allowed
only for an explicit private/local compatibility endpoint; managed/public AWS
profiles require `sse-s3` or `sse-kms`. The provider-reported KMS key identity
must exactly equal configured policy; managed AWS therefore requires the full
key ARN in the configured region, while a custom compatible provider requires
its exact stable returned key identity. KMS key material/identity remains local
configuration and is never copied into user metadata, receipts, details, or
logs.

A null command retention class requires effective mode `NONE`. A configured
class maps to `GOVERNANCE` or `COMPLIANCE` plus a bounded number of days. The
initial mutation computes its provider deadline from the executor clock. On
reconciliation, the existing same-effect/same-policy object and provider-
reported deadline are authoritative; the executor does not recompute a later
deadline and thereby reject its own prior write. Bucket-default retention may
not silently substitute for an unrequested class.

Custom endpoints require explicit static credentials. Plain HTTP is accepted
only by the `local-demo` profile for loopback/private hosts with path-style
addressing. Ambient default, environment, or named-profile credentials are
accepted only when no custom endpoint is supplied and the SDK resolves the
managed regional AWS endpoint; this prevents instance credentials being sent
to an arbitrary compatible server. Public/custom non-local endpoints require
HTTPS and hostname verification.

A managed AWS profile (one with no custom endpoint) requires
`source-expected-owner` and `destination-expected-owner`, each exactly 12
decimal digits. The adapter sends the matching AWS `expectedBucketOwner` guard
on every bucket-versioning, version-inventory, `HEAD`, `GET`, and `PUT`
operation. If source and destination name the same bucket, both configured
owners must match. Custom and local compatible endpoints reject these settings
because they do not provide AWS account-ownership semantics. Expected-owner
values are executor-local and are omitted from diagnostics, payloads, receipts,
details, user metadata, and logs. Changing either value requires a new
`target-id`; it must never silently repoint an existing target identity.

V1 permits one in-flight transfer per configured target, in addition to the
Effect Runtime's bounded worker pool and retry backoff. Contention returns the
non-consuming `Retry(100ms)` outcome; it never spends an attempt or queues
another 16 MiB body inside the connector. To keep executor construction
bounded, the local-demo profile accepts only canonical private/loopback/ULA
numeric endpoint literals or the exact name `localhost`, which is canonicalized
directly to `127.0.0.1` without invoking a resolver. Link-local metadata-service
space and service-discovery names are rejected. A Compose/host launcher that
uses service discovery resolves it outside the effect executor under its own
timeout and injects the resulting literal endpoint. JVM/environment proxy
discovery is disabled for this profile.

If a conditional write reports 409/412 or its acknowledgement may be lost,
the executor probes first: exact state confirms, mismatched state conflicts,
and unresolved absence is `ACK_UNKNOWN`. It never falls back to an
unconditional write. If a provider acknowledgement is known but optional
detail archival fails, the executor returns `Submitted(receipt)`. Later
attempts validate the receipt, re-probe the exact current version, and retry
only archival; malformed node-local submitted state is retryable
`INTERNAL_ERROR` and never authorizes another mutation.

## 8. `appchain-ipfs`

### 8.1 V1 is pin-only

`ipfs.pin` pins a known CID using a configured IPFS target/provider. It does
not accept document bytes, fetch an arbitrary URL, or silently implement
`add`.

Phase 1.3 ships one synchronous Kubo HTTP RPC adapter using only
`/api/v0/pin/ls` and `/api/v0/pin/add`. It does not implement the distinct IPFS
Pinning Service API. Remote pinning, asynchronous provider request handles,
and provider polling remain technology-internal future work and require a new
immutable target identity when introduced.

The adapter parses only the three exact Kubo response envelopes it consumes.
Its dependency-free byte parser enforces strict UTF-8/JSON syntax, decoded
duplicate-name rejection, canonical CID comparison, and fixed document,
nesting, token, field-name, string, and number bounds. It is deliberately not
a reusable JSON library or data-binding surface.

The release/demo compatibility target is the exact official
`ipfs/kubo:v0.42.0` image, recorded for Phase 1.3 as
`ipfs/kubo@sha256:8907cb0cc1ad5798f6bb1bb1341a800990c268e021cedfa317e8aa1a33864214`.
The adapter accepts only a conservative allowlist of
documented/tested response shapes; a malformed or version-unknown mutation
acknowledgement is `ACK_UNKNOWN` and is reconciled by probing, never guessed as
success or treated as a definitive failure. Changing to an untested Kubo RPC
profile is an explicit operator compatibility decision and release testing
must record the exact image digest.

Generating a CID depends on chunking, DAG layout, codec, hash algorithm, and
client version/options. Hiding those choices in `pin` would make the committed
CID and external result less predictable. A future `ipfs.add-and-pin` must
define those parameters explicitly and separately.

### 8.2 `ipfs.pin` v1 payload

```text
version             exactly 1
target              configured IPFS provider/profile alias
cid                 canonical binary CIDv1
recursive           configured/allowlisted boolean
replicationPolicy   configured policy alias, optional
```

It cannot contain a provider API URL, bearer token, arbitrary gateway URL,
local path, or plaintext document. The generic CID parser has a defensive
96-byte ceiling, but the normative v1 wire policy is narrower: canonical
binary CIDv1 is exactly 36 bytes because it permits only one-byte raw (`0x55`)
or dag-pb (`0x70`) codecs with sha2-256/32. Client/demo tooling may accept a
valid CIDv0 text only by converting its dag-pb/sha2-256 multihash to minimally
encoded CIDv1; normative payloads never contain text or CIDv0. A target may
narrow that policy. The encoded command is at most 256 bytes.

### 8.3 Idempotency and receipt

The executor:

1. parses and canonicalizes the CID;
2. enforces codec/hash and target policy;
3. checks current pin state;
4. returns confirmed if the required pin already exists;
5. otherwise requests the pin once;
6. re-probes/reconciles according to the provider's bounded status model; and
7. returns a receipt only when the required state is reported.

Kubo pin state is an idempotent set. A reported recursive pin satisfies either
a recursive or direct request; a direct pin satisfies only `recursive=false`.
An indirect pin does not satisfy either request. The executor may upgrade a
direct pin to recursive but never unpins or downgrades an existing recursive
pin. Every Kubo call carries the effect-id hash in a fixed
`X-Yano-Effect-Id` header for audit correlation; Kubo does not claim that
header as a provider deduplication mechanism.

A remote-pinning request id may be returned as ADR-010 `SUBMITTED` and polled
on later attempts. A provider-declared malformed/forbidden CID is definitive.
Temporarily unavailable content is `CONTENT_UNAVAILABLE` and remains retryable
until deterministic effect expiry. A provider's terminal content-not-found
response is `CONTENT_NOT_FOUND` and definitive only when the configured
recoverability policy proves that no other allowed source can supply it.
For the initial Kubo target, a known `not pinned` probe is simply absent state.
A missing/unavailable block during `pin/add` is `CONTENT_UNAVAILABLE`, not
definitive `CONTENT_NOT_FOUND`, because one Kubo node cannot prove that every
allowed recovery source is exhausted.

The effect record already carries the canonical CID, so the compact confirmed
`externalRef` binds the configured provider and the exact CID without
repeating up to 96 bytes:

```text
[ version=1, targetFingerprint=bstr32, cidFingerprint=bstr32 ]
```

The fingerprints use domains `yano-ipfs-target-v1` over `[targetId]` and
`yano-ipfs-cid-v1` over the canonical CID bytes. A remote pinning provider may
instead return a pollable ADR-010 `SUBMITTED` ref:

```text
[ version=1, targetFingerprint=bstr32, providerRequestId=bstr(1..88) ]
```

Both forms remain canonical and at most 128 bytes.

The initial Kubo adapter never emits a remote-pinning submitted handle. If
detail archival fails after a verified Kubo pin, it stores the already
confirmed receipt bytes as ADR-010 `SUBMITTED` state, re-probes without
re-pinning, and retries only archival. Receipt and remote-handle encodings must
never be guessed from their CBOR shape: a future remote adapter is selected by
an explicitly different target identity and interprets only its own submitted
schema.

`providerRequestId` is a non-secret, opaque polling handle only. A provider
whose polling credential is itself secret must keep that credential in local
target configuration and expose a separate non-secret handle; bearer tokens,
signed URLs, and credential-bearing response fields are never valid submitted
references.

The effect record supplies the target and replication policy, while a
`CONFIRMED` outcome supplies the status. A remote provider request id and
allowlisted status fields may be placed in a durably archived detail document
committed by `detailHash`. Provider references and status text are bounded and
normalized.

`replicationPolicy` is an allowlisted operator policy label, not proof that a
Kubo node has independent replicas. A null command value requests no extra
assertion; a non-null value must exactly match the selected target's configured
label. Changing endpoint, authentication, recursive semantics, allowed codecs,
or replication policy requires a new `target-id`.

### 8.4 Availability and confidentiality

A pin receipt proves only that the configured provider reported the CID
pinned. It is not a guarantee of indefinite global availability. The demo and
operations surface should expose last reconciliation and provider health.
Confirmation is a point-in-time result: a later unpin does not rewrite an
already incorporated app-chain result.

IPFS is public and persistent by default. Confidential DPP, oracle, customer,
or enterprise documents must either:

- remain in a private object store; or
- be encrypted before IPFS publication under a separately designed key and
  disclosure lifecycle.

Deleting a key is not the same as deleting public ciphertext, and this ADR
does not claim GDPR erasure merely from crypto-shredding.

## 9. Relationship to Cardano effects

The current `appchain-effects-cardano` module and `cardano.payment` action are
not renamed or expanded by this ADR. Cardano is an unusually broad and
security-sensitive name inside a Cardano node project; wallet and transaction
publication deserve an explicit privileged boundary.

The implementation order remains:

1. complete ADR-010.2 production transaction safety;
2. harden `cardano.payment`;
3. add a separately specified native-asset transfer action; and
4. add domain-specific oracle/DPP script publication, mint, burn, and datum
   actions through their own ADRs/profiles.

The evidence demo already receives Cardano assurance from the app-chain
state-root anchor. It does not need to manufacture an unrelated payment merely
to demonstrate Cardano.

## 10. Reference evidence workflow

### 10.1 Purpose

The demo uses a realistic but bounded product-batch inspection/compliance
certificate workflow. It is not advertised as a complete DPP standard or a
generic workflow engine.

A small first-party/reference bundle, provisionally named
`appchain-evidence-registry`, supplies:

- a deterministic state machine;
- versioned command and query contracts;
- effect emission, result incorporation, and a result-gated continuation
  transition;
- a domain read API suitable for the demo UI;
- committed business-status queries, plus Milestone 2 connector
  observability when available; and
- sample client encoders/verifiers.

It may live under `appchain/examples` until its general-purpose product scope
is accepted. The connector correctness tests must not depend exclusively on
this one state machine.

### 10.2 Evidence command

The v1 command commits, at minimum:

```text
version
evidenceId / productBatchId
document digest algorithm + digest + exact size
staging object alias/key commitment
archive target/key commitment
canonical IPFS CID
Kafka notification topic alias
issuer/domain signature where the scenario enables actor identity
```

The command carries no document body, external credential, raw service
endpoint, or unrestricted bucket/topic.

### 10.3 State machine

```text
SUBMITTED
    |
    | emit object.put + ipfs.pin
    v
STORAGE_PENDING
    |                              |
    | both CONFIRMED               | FAILED / CANCELLED / EXPIRED
    v                              v
STORAGE_READY                 STORAGE_FAILED / PARTIAL / EXPIRED
    |
    | idempotent evidence.notify command
    | verifies STORAGE_READY, then emits kafka.publish
    v
NOTIFICATION_PENDING
    |                              |
    | CONFIRMED                    | FAILED / CANCELLED / EXPIRED
    v                              v
READY                    READY_NOTIFICATION_FAILED / EXPIRED
```

`object.put` and `ipfs.pin` are independent and may complete in either order.
The state machine records each expected effect id and accepts a terminal result
only for the matching evidence version and operation. Duplicate, stale, or
unrelated results cannot advance state.

`onEffectResult()` moves the record to `STORAGE_READY` after both storage
operations are confirmed; it does not emit another effect because the current
callback has no `AppEffectEmitter`. The scenario/automation runner observes
that committed status and submits `evidence.notify(evidenceId, version)`.
Ordinary `apply()` verifies `STORAGE_READY`, checks that no notification effect
has already been assigned, emits `kafka.publish`, and records its deterministic
effect id. Replayed or duplicate continuation commands are deterministic
no-ops or return the already-assigned result; they never emit a second logical
notification.

The automation runner is not trusted to decide readiness. It can only wake the
state machine, which derives readiness from authenticated state. If the runner
is unavailable, the record remains visibly `STORAGE_READY` and can be resumed
by any authorized runner. ADR-010.1 activation/version rules apply to future
emission changes.

A terminal connector failure does not cause an executor to mutate business
state directly. The result is incorporated, the state machine chooses its
documented failure state, and an explicit authorized retry/republication
command may create a new version/effect when policy permits.

### 10.4 Demo scenario

The scenario runner:

1. waits for all services to become healthy;
2. creates configured MinIO staging/archive buckets and Kafka topics through
   an idempotent initializer;
3. generates or selects a sample inspection certificate;
4. computes its exact SHA-256 digest and size;
5. uploads it into the configured MinIO staging prefix;
6. adds the same bytes to local Kubo with explicit deterministic add options
   and obtains a CID without satisfying the executor's target pin policy;
7. submits the evidence command to Yano;
8. waits for app-chain finality and the configured execution gate;
9. verifies the object-store and IPFS results;
10. waits for committed `STORAGE_READY` and submits the idempotent
    `evidence.notify` continuation command;
11. waits for the resulting Kafka effect and consumes/deduplicates the
    `evidence.available` event;
12. queries evidence status and verifies the composed state/effect proof;
13. displays the applicable Cardano app-chain anchor; and
14. prints stable machine-readable and human-readable PASS/FAIL output.

The script never declares success merely because an HTTP request returned
2xx. It verifies final business state, external receipts, external state, and
proof/anchor availability appropriate to the selected network profile.

## 11. Deployment model

### 11.1 One source of scenario truth

Repository layout:

```text
appchain/examples/appchain-evidence-registry/
app/appchain-effects-demo/
├── compose.yaml
├── config/
│   ├── common.env
│   ├── networks/
│   └── services/
├── samples/
├── scenarios/
├── dashboards/
├── demo.sh
└── README.md
```

Compose and normal deployment invoke the same scenario runner, sample
contracts, verifier, assertions, and output format. Service URLs and secrets
are injected through a resolved environment/configuration file.

### 11.2 Docker Compose — primary no-code experience

The default Compose stack contains:

- three Yano app-chain members;
- local Yano/Cardano devnet production and app-chain script anchoring;
- Apache Kafka in KRaft mode;
- MinIO;
- Kubo/IPFS;
- idempotent bucket/topic/service initialization;
- the evidence scenario bundle and connector bundles;
- a lightweight evidence/status UI; and
- optional Prometheus and Grafana services.

All images are version-pinned. Services have real health checks, bounded
startup waits, restart policies appropriate to a demo, non-root operation
where images permit it, and localhost-only host port exposure by default.

Suggested UX:

```bash
# Essential stack; devnet is the default
./demo.sh up --deployment compose --network devnet
./demo.sh run --network devnet

# Add metrics and dashboards
./demo.sh up --deployment compose --network devnet --observability

# Stop while preserving all state
./demo.sh stop --deployment compose --network devnet
```

The demo does not rely on unpinned `latest` images or an interactive sequence
of undocumented container commands.

### 11.3 Normal/host deployment

The same workflow is supported when Yano and dependencies are operated
normally:

1. deploy or select Kafka, S3-compatible storage, and IPFS;
2. install the three self-contained plugin bundle JARs in the configured JVM
   plugin directory, or include them at build time for native Yano;
3. configure contribution namespaces and target aliases;
4. start three Yano members through `cluster.sh` or ordinary service
   deployment;
5. configure the scenario runner with those endpoints; and
6. execute the same scenario and verifier used by Compose.

Example:

```bash
YANO_URL=http://127.0.0.1:7070 \
MINIO_URL=http://127.0.0.1:9000 \
IPFS_API_URL=http://127.0.0.1:5001 \
KAFKA_BOOTSTRAP=127.0.0.1:9092 \
./demo.sh run --deployment host --network devnet
```

Normal deployment must not depend on Compose DNS names, Docker-only secret
paths, or container-specific assumptions.

### 11.4 Executor topology and failover

Installing a connector bundle on every member for packaging/replay symmetry
does not authorize every member to execute it. Milestone 1 designates exactly
one active executor member for the three demo types:

```properties
# designated executor member only
yano.app-chain.effects.executor.enabled=true
yano.app-chain.effects.executor.identity=evidence-executor-0
yano.app-chain.effects.executor.types=kafka.publish,object.put,ipfs.pin

# all other members
yano.app-chain.effects.executor.enabled=false
```

Alternatively, an operator may use several executor members only with explicit
disjoint `effects.executor.types` partitions. An empty type list means all
types and is forbidden on more than one active member in the supported demo
topology.

ADR-010 intentionally provides no cross-node mutual-exclusion lease for
in-process executors. Failover is therefore an explicit operator action:

1. fence/stop the old executor member and verify it can no longer make calls;
2. activate the exact type partition on the replacement with an explicit
   identity;
3. inspect the runtime's quarantine/ownership diagnostics;
4. requeue or adopt unresolved effects through the authorized operator flow;
   and
5. rely on connector-specific external probes/idempotency for any attempt whose
   acknowledgement was lost during the handover.

The demo never starts two overlapping owners merely to simulate availability.
Milestone 2 detects local type conflicts but does not pretend that local
startup validation is a distributed executor lease. A future automated
cross-node ownership protocol, if required, needs its own safety design.

## 12. Network profiles

### 12.1 Supported profiles

```text
devnet   mandatory default and full CI profile
preview  opt-in public test-network smoke profile
preprod  opt-in public test-network/release profile
mainnet  guarded configuration/startup profile; no automatic value-moving test
```

The command line and environment forms are equivalent:

```bash
./demo.sh up --deployment compose --network preview

DEMO_NETWORK=preview \
docker compose --env-file config/networks/preview.env up -d
```

### 12.2 Profile contents

Version-controlled network profiles contain only non-secret data:

- network name and protocol magic;
- genesis and topology/relay selection;
- expected genesis/config fingerprints;
- L1 stability/finality settings;
- app-chain anchor policy and default cadence;
- explorer URL template;
- default host port base; and
- safe demo timeouts appropriate to the network.

Keys, mnemonics, API tokens, object-store credentials, Kafka credentials, and
private TLS material are supplied separately.

### 12.3 Network-neutral versus network-bound components

Kafka, IPFS, and object storage are Cardano-network-neutral. The selected
Cardano network affects:

- Yano L1 synchronization/production;
- app-chain anchoring;
- later `cardano.*` effects; and
- explorer/anchor links in the UI.

No effect payload may select or override a Cardano network. A configured
Cardano executor is permanently bound to its declared network for its product
lifetime and verifies network identity before signing.

### 12.4 Public-network requirements

Preview/preprod use requires:

- suitable public relays, a configured local upstream, or a prepared
  network-matched L1 state directory;
- sufficient sync time and disk;
- an explicitly provided funded anchor wallet when script anchoring is
  enabled;
- an explicitly provided result/effect signer policy; and
- public-network-safe TLS/auth configuration for externally reachable
  services.

The tooling must state clearly whether it is waiting for L1 sync, app-chain
startup, anchor wallet funds, stable anchor confirmation, or an external
connector. It must not display a generic hanging `node 0 ...` message for all
failure classes.

Mainnet additionally requires an explicit command acknowledgement such as:

```bash
./demo.sh up --network mainnet --enable-mainnet
```

No sample/default funded mainnet key is shipped. Until ADR-010.2 is complete,
the mainnet profile disables value-moving Cardano effects even if the generic
connector demo is otherwise configured.

## 13. Persistent data layout and identity safety

### 13.1 Default bind-mounted layout

Compose uses a project-local, gitignored bind-mount root by default:

```text
app/appchain-effects-demo/.demo-data/
├── devnet/
│   ├── network-identity.json
│   ├── l1/
│   │   ├── node0/
│   │   ├── node1/
│   │   └── node2/
│   └── instances/
│       └── default/
│           ├── appchain-identity.json
│           ├── anchor/
│           ├── appchain/
│           │   ├── node0/
│           │   ├── node1/
│           │   └── node2/
│           ├── kafka/
│           ├── minio/
│           ├── ipfs/
│           └── observability/
├── preview/
├── preprod/
└── mainnet/
```

Operators may move the root, especially for large public-network state:

```bash
./demo.sh up --network preprod \
  --instance default \
  --data-dir /Volumes/yano-demo/preprod
```

The L1 store is reusable by several sequential demo instances on the same
network. App-chain, anchor-binding, and connector data remain instance-scoped.
Secrets live outside this cleanup-managed tree.

### 13.2 Identity marker

Before any node or connector starts, the launcher atomically creates or
validates `network-identity.json`, containing at least:

```text
schemaVersion
networkName
protocolMagic
genesis/config fingerprints
demo layout version
```

It also creates or validates `appchain-identity.json` for the selected
instance:

```text
schemaVersion
instance id
chain ids and app-chain genesis/profile fingerprint
member-set/configuration digest
anchor mode
anchor script/thread-NFT identity and last adopted height, when bootstrapped
```

If an existing marker differs from the selected profile, startup fails with a
specific diagnostic and the exact safe cleanup/migration commands. Missing or
inconsistent per-node state must not be silently adopted under another
network or genesis.

The marker is an operator-safety guard, not a security signature. Genesis and
runtime validation remain authoritative.

### 13.3 Separate L1 and app-chain lifecycle

App-chain databases may be disposable during pre-release development only
when their block history can be replayed/restored or the operator intentionally
creates a new app-chain instance. Deleting every member's app-chain database
also deletes the only local history and cannot be described as an ordinary
derived-cache cleanup.

Public-network L1 sync can be expensive and is not implicitly disposable. Its
path and cleanup operation therefore remain separate from every app-chain
instance.

An existing Cardano anchor UTxO commits the prior instance's chain identity,
height, and state. A fresh app-chain genesis must never silently reuse that
anchor binding. Resetting an anchored app chain must choose one explicit path:

1. restore/replay the same app-chain identity and verify/adopt its existing
   anchor; or
2. create a new instance/chain identity and bootstrap a new anchor while
   preserving the already-synchronized L1 database.

Kafka, MinIO, and IPFS data are also separated so connector scenarios can be
replayed without deleting either chain state.

## 14. Stop, reset, and cleanup

### 14.1 Commands

```bash
# Stop processes/containers; preserve everything
./demo.sh stop --network devnet --instance default

# Delete app-chain databases only when restoring/replaying the same identity
./demo.sh clean --network devnet --instance default --appchain \
  --restore-from /path/to/snapshot-or-history

# Reset an anchored demo safely: remove instance state, preserve L1, and use a
# new chain/anchor identity on the next start
./demo.sh clean --network devnet --instance default --demo-state --new-instance next

# Delete Kafka, MinIO, IPFS, and demo projection data
./demo.sh clean --network devnet --instance default --connectors

# Explicitly delete shared L1 state
./demo.sh clean --network devnet --l1

# Delete all non-secret data for the selected network
./demo.sh clean --network devnet --all
```

`docker compose down` stops services and preserves bind-mounted data. The
documentation must not use `down -v` as the normal reset interface because it
is too coarse and does not uniformly govern bind mounts.

### 14.2 Cleanup safety invariants

Cleanup:

- resolves and prints the network, selected categories, and exact paths;
- refuses an empty path, filesystem root, home directory, repository root, or
  path outside the validated demo-data root;
- does not follow a symlink outside that root;
- requires interactive confirmation unless `--yes` is supplied;
- refuses to act while managed services are running unless `--force` is
  explicitly selected and safely implemented;
- never deletes the secrets directory;
- never deletes another network profile's directory; and
- requires an additional explicit acknowledgement before removing preview,
  preprod, or mainnet L1 state.

In addition, cleanup refuses to delete an anchored instance's complete
app-chain history while retaining and reusing its old anchor binding unless a
validated restore/replay source is supplied. A new-instance reset archives or
removes the old instance binding and creates new chain ids before anchor
bootstrap; it does not mutate the shared L1 genesis or synchronized chain
state.

Tests use temporary directories and sentinels to prove that every cleanup
mode deletes exactly its declared category and nothing else.

## 15. Configuration examples

### 15.1 Kafka finalized sink

Existing keys remain valid:

```properties
yano.app-chain.sinks.kafka.bootstrap-servers=kafka:9092
yano.app-chain.sinks.kafka.topic=appchain-finalized-blocks
yano.app-chain.sinks.kafka.acks=all
```

### 15.2 Kafka effect executor

Illustrative shape:

```properties
yano.app-chain.effects.executors.kafka.targets.primary.target-id=primary-v1
yano.app-chain.effects.executors.kafka.targets.primary.bootstrap-servers=kafka:9092
yano.app-chain.effects.executors.kafka.targets.primary.acks=all
yano.app-chain.effects.executors.kafka.targets.primary.security-profile=local-demo
yano.app-chain.effects.executors.kafka.topics.evidence-ready.target=primary
yano.app-chain.effects.executors.kafka.topics.evidence-ready.name=evidence.available.v1
```

### 15.3 S3-compatible object executor

```properties
yano.app-chain.effects.executors.objectstore-s3.targets.archive.target-id=archive-v1
yano.app-chain.effects.executors.objectstore-s3.targets.archive.endpoint=http://127.0.0.1:9000
yano.app-chain.effects.executors.objectstore-s3.targets.archive.region=us-east-1
yano.app-chain.effects.executors.objectstore-s3.targets.archive.security-profile=local-demo
yano.app-chain.effects.executors.objectstore-s3.targets.archive.path-style=true
yano.app-chain.effects.executors.objectstore-s3.targets.archive.credentials-provider=static
yano.app-chain.effects.executors.objectstore-s3.targets.archive.credentials.access-key-id=${MINIO_ACCESS_KEY}
yano.app-chain.effects.executors.objectstore-s3.targets.archive.credentials.secret-access-key=${MINIO_SECRET_KEY}
yano.app-chain.effects.executors.objectstore-s3.targets.archive.source-bucket=evidence-staging
yano.app-chain.effects.executors.objectstore-s3.targets.archive.source-prefix=incoming
yano.app-chain.effects.executors.objectstore-s3.targets.archive.destination-bucket=evidence-archive
yano.app-chain.effects.executors.objectstore-s3.targets.archive.destination-prefix=verified
yano.app-chain.effects.executors.objectstore-s3.targets.archive.encryption-policy-id=local-none-v1
yano.app-chain.effects.executors.objectstore-s3.targets.archive.encryption-mode=none
yano.app-chain.effects.executors.objectstore-s3.targets.archive.retention-policy-id=worm-v1
yano.app-chain.effects.executors.objectstore-s3.targets.archive.retention-classes.evidence-30d.mode=governance
yano.app-chain.effects.executors.objectstore-s3.targets.archive.retention-classes.evidence-30d.days=30
yano.app-chain.effects.executors.objectstore-s3.targets.archive.require-versioning=true
yano.app-chain.effects.executors.objectstore-s3.targets.archive.max-object-bytes=16777216
```

The example references credentials through environment-backed secret
substitution; it does not commit their values. Managed AWS profiles omit
`endpoint` and may instead select an allowlisted ambient credential provider.

Configured object prefixes are either empty or the same normalized relative
key form used by commands, without a leading or trailing slash. The executor
joins a non-empty prefix and relative key with exactly one `/`; `verified/`
is invalid rather than silently normalized to `verified`.

### 15.4 IPFS executor

```properties
yano.app-chain.effects.executors.ipfs.enabled=true
yano.app-chain.effects.executors.ipfs.targets.local.target-id=local-kubo-v1
yano.app-chain.effects.executors.ipfs.targets.local.api-url=http://127.0.0.1:5001
yano.app-chain.effects.executors.ipfs.targets.local.security-profile=local-demo
yano.app-chain.effects.executors.ipfs.targets.local.allowed-codecs=raw,dag-pb
yano.app-chain.effects.executors.ipfs.targets.local.recursive=true
yano.app-chain.effects.executors.ipfs.targets.local.replication-policy=demo-single
yano.app-chain.effects.executors.ipfs.targets.local.connect-timeout-ms=5000
yano.app-chain.effects.executors.ipfs.targets.local.request-timeout-ms=30000
yano.app-chain.effects.executors.ipfs.targets.local.close-timeout-ms=5000
```

`local-demo` accepts only exact `localhost` or canonical numeric
private/loopback/ULA HTTP endpoints, rejects link-local and service-discovery
names, does not invoke DNS inside the executor, and accepts no authentication
material. Host mode uses the stable loopback literal. Compose assigns Kubo a
fixed instance-scoped address recorded by the demo identity marker and injects
that literal before node launch; it does not pass a service-discovery name to
the executor. If the literal or other target semantics change, the launcher
must mint a new `target-id` rather than silently repointing the old identity.

The production `bearer-tls` profile requires HTTPS, normal JVM trust and
hostname verification, and a bounded bearer token held only in local secret
configuration. Both profiles disable redirects and ambient proxies. The base
URL is an origin only: user info, paths, query, and fragments are rejected.
Payloads still select only `local` or another configured alias. The configured
codec set is a canonical subset of `raw,dag-pb`; `recursive` is exact per
target, and `replication-policy` is an operator label rather than verified
replica count.

## 16. Authentication and security

### 16.1 Demo authentication

The local devnet demo may generate a clearly labelled development-only API
key and service credentials on first start, store them under a gitignored
secret path, and print where they are stored. It must not embed them in an
image, committed configuration, HTML source, effect payload, or log URL.
Keys are generated per demo instance with restrictive host permissions; there
is no universal checked-in devnet key reused by public-network profiles.

The status/evidence UI may prompt for the Yano API key where protected plugin
and effect operations require it. Public read-only evidence views should use a
separately designed least-privilege route rather than making every UI action
administrative. The UI follows the existing protected-key handling model,
escapes every external string, and does not persist a privileged key in
generated files, server logs, or durable browser storage.

### 16.2 External exposure

Compose binds service administration/API ports to loopback by default. Public
deployment guidance requires a reverse proxy or service network with TLS,
OIDC/mTLS/API-key policy, request limits, and audit logging appropriate to the
operation.

MinIO administration, Kafka administration, and the IPFS RPC API are never
presented directly as public demo endpoints.

### 16.3 Supply chain and plugin packaging

- Container images and plugin bundle versions are pinned.
- Plugin manifests and bundle digests appear in the ADR-011 inventory.
- JVM bundles contain required third-party clients but exclude host Yano API
  classes.
- Native inclusion generates the same contribution catalog.
- Dependency licenses and known-vulnerability scanning are part of release
  checks.
- Installing both old and renamed Kafka bundles fails clearly rather than
  selecting one by classpath order.
- Documentation states that in-process plugin bundles are trusted privileged
  code, not sandboxed extensions.

## 17. Operations and UI

The existing Yano status/plugin/effect pages remain the primary node-level
operations surface. The demo may add an evidence-focused page but must consume
bounded APIs and metrics rather than scraping logs.

The evidence view shows:

- evidence/product batch id and version;
- document digest and size;
- deterministic business status;
- originating app block and finality;
- object-store target alias, version, checksum, and result status;
- CID and pin status;
- Kafka topic alias, partition/offset receipt, and result status;
- attempts/last normalized error through authorized operations views;
- state/effect proof verification result; and
- latest applicable Cardano app-chain anchor and lag.

The UI distinguishes:

- intent authorized;
- execution pending;
- external service confirmed;
- result incorporated into app-chain state;
- app-chain state anchored on Cardano; and
- document/business claim independently valid.

It must not collapse these into a single misleading `verified` badge.

## 18. Testing and verification

### 18.1 Unit tests

Each connector tests:

- canonical payload and receipt codecs with golden vectors;
- unknown versions, duplicate keys, malformed encodings, and every bound;
- alias, endpoint, topic/bucket/prefix/CID policy;
- normalized error classification;
- idempotency probes and each connector's applicable existing-state model
  (Kafka consumer dedupe, object match/conflict, or IPFS idempotent pin set);
- timeout and unknown-acknowledgement behavior;
- receipt truncation/redaction;
- concurrent execute/close and failed construction; and
- the existing Effect Runtime status/metric integration without
  connector-controlled high-cardinality identities.

### 18.2 Connector integration tests

Container-backed integration tests use real compatible services:

- Apache Kafka for publish acknowledgement, broker restart, duplicate retry,
  authentication failure, and sink/effect coexistence;
- MinIO for conditional creation, checksum, versioning, conflicting destination,
  restart, and optional retention profile; and
- Kubo for already-pinned, new pin, invalid CID, unavailable content,
  restart, and reconciliation.

Mock-only tests are insufficient for release.

### 18.3 Plugin packaging tests

Verify:

- renamed Kafka thin JAR and bundle JAR;
- merged ServiceLoader entries for both Kafka contributions;
- manifest contribution inventory;
- JVM drop-in loading;
- build-time/native catalog inclusion;
- absence of bundled host API classes;
- duplicate old/new plugin rejection; and
- clean shutdown of every contribution product.

### 18.4 Three-node end-to-end test

The mandatory devnet E2E test:

1. starts all external services and three Yano members;
2. bootstraps script anchoring;
3. proves exactly one member owns the exact
   `kafka.publish,object.put,ipfs.pin` partition and the others cannot dispatch
   those effects;
4. runs the evidence scenario;
5. verifies identical deterministic state on every member;
6. verifies both storage effects, their incorporated results, and the
   continuation-command-gated Kafka effect;
7. verifies external object bytes/checksum, CID pin state, and consumed Kafka
   event;
8. verifies state/effect proof and L1 anchor visibility on proposer and
   members;
9. restarts the executor node between external acknowledgement and/or result
   incorporation using controlled fault seams;
10. performs one explicitly fenced executor failover/requeue and proves the
    old owner makes no later call;
11. proves duplicates do not corrupt business state and conflicts fail closed;
12. verifies plugin inventory, Effect Runtime status/metrics, cached UI state,
    and direct scenario service checks; and
13. stops cleanly while preserving state for a restart pass.

### 18.5 Deployment and cleanup tests

Run the same scenario through:

- Compose endpoints; and
- normally started/host endpoints.

Test every network identity mismatch and cleanup category in temporary roots.
Assert that app-chain cleanup preserves L1, connector cleanup preserves both
chain layers, stop preserves everything, and secrets/sibling networks are
never removed. Verify that same-identity restore adopts the expected anchor
and that new-instance reset produces new chain/anchor identity without
modifying the preserved L1 database.

### 18.6 Network verification matrix

| Network | Required verification |
|---|---|
| Devnet | Full automatic functional, restart, proof, anchor, cleanup, and observability E2E |
| Preview | Opt-in smoke: sync/start, evidence flow, stable anchor, external receipts, restart |
| Preprod | Opt-in release smoke with the same core assertions and operator-supplied funds/secrets |
| Mainnet | Configuration, identity, startup guard, and non-value-moving connector validation only until separately approved |

Public-network failures are reported as external/opt-in evidence and do not
make ordinary offline unit builds depend on internet availability.

## 19. Staged implementation roadmap

### 19.1 Milestone 1 — Connectors and no-code demo, no framework changes

This is the first implementation target and may be completed, reviewed, and
released independently. It uses the current ADR-010/011 contracts plus the
explicit `evidence.notify` continuation command described in §10.

#### Phase 1.0 — Freeze contracts and conformance fixtures

- Finalize v1 CBOR payload/receipt schemas and CDDL/golden vectors.
- Freeze compact `externalRef` encodings, binary-CID conversion, detail
  document canonicalization/hash, configured durable archival, and authorized
  retrieval-by-hash conventions.
- Add reusable executor conformance fixtures for retry, redaction, lifecycle,
  receipt bounds, and existing runtime statistics.
- Finalize target alias and error-classification conventions.

**Implementation record (accepted 2026-07-16).** The published
`appchain-integration-contracts` module now contains the exact canonical-CBOR
codecs, CDDL, golden vectors, bounded receipt/detail models, CID normalization,
domain-separated fingerprints, error taxonomy, and durable reference detail
archive. `appchain-testkit` supplies a reusable real-runtime executor harness
and conformance suite covering invalid-input/no-I/O behavior, retries,
unknown acknowledgements, reconciliation, idempotency, restart, bounded and
idempotent shutdown, failed construction, secret redaction through cleanup,
receipt/detail limits, and runtime statistic/status shapes.

Recorded Phase 1.0 evidence:

- 50 contract/archive/CID tests and 42 focused conformance tests pass, along
  with the complete `appchain-testkit` test task and both module Javadocs.
- The generated aggregate CDDL compiles with an independent RFC 8610 tool and
  validates all ten published command, receipt, submitted-reference, and
  detail vectors.
- A hostile-input audit exercised 100,000 malformed values against all seven
  public decoders without an unexpected throwable; explicit allocation,
  nesting, item-count, text/binary, snapshot, and diagnostic-work bounds are
  regression-tested.
- Published JAR/POM and dependency inspection confirms that the contract
  module has no runtime, Kafka, S3, IPFS, Cardano client, credential, or
  network dependency; aside from the shared build convention's SLF4J API, its
  only direct implementation dependencies are the CBOR codec and BLAKE2b
  provider.
- Independent API/design/security reviews found no unresolved correctness or
  high-severity issue. Their bounded findings—pre-clone limits, archive path
  handling, post-close/failed-construction log capture, exact numeric metrics,
  non-zero unknown-ack mutation, and definitive content-not-found
  classification—are implemented and tested.

Bundle-relocation and multi-bundle class-identity tests remain assigned to
Phases 1.1--1.3 because only deployable connector bundles can prove those
packaging boundaries. No core, runtime, or framework SPI changed in Phase 1.0.

#### Phase 1.1 — Broaden the Kafka bundle

- Rename project/directory/artifact to `appchain-kafka`.
- Preserve plugin id, package, sink scheme, and sink configuration.
- Update settings, application bundle inclusion, documentation, packaging
  tests, and distribution references.
- Add `kafka.publish`, separate producer lifecycle, receipts, existing runtime
  statistics, and integration tests.

**Implementation record (accepted 2026-07-16).** The pre-release Kafka sink
project is now `appchain-kafka`; its plugin identity and runtime namespaces are
preserved while the same technology-owned bundle contributes both the
finalized-block sink and acknowledged `kafka.publish` executor. The executor
implements the frozen Phase 1.0 command, receipt, destination fingerprint,
error, and optional detail-archive contracts. Strict alias-only configuration,
local-demo/TLS/mTLS/SASL-TLS profiles, explicit producer ownership, bounded
acknowledgement/shutdown, effect-id binding, reserved dedupe headers, and
archive-only submitted-reference recovery are implemented without a core,
runtime, or SPI change.

Recorded Phase 1.1 evidence:

- The offline module gate passes 46 tests (with the network test skipped),
  including Phase 1.0 conformance, invalid-input/no-I/O, exact retryability,
  authentication/authorization/rate-limit normalization, lifecycle races,
  unknown acknowledgement, no-republish archive recovery, redaction, sink
  ordering, and artifact-boundary checks. Module Javadoc completes without a
  warning.
- An opt-in test against the official Kafka 4.3.1 image passes all 47 tests.
  It creates three-partition topics, consumes and verifies exact effect keys,
  bodies, all frozen reserved headers, and receipt partition/offset, and
  interleaves the finalized sink with the effect executor. It also proves the
  chain-id sink key retains finalized-block order on one partition.
- Drop-in launch tests prove merged ServiceLoader contributions and private
  relocation of connector contracts, CBOR, and BLAKE2b classes. Thin/native
  catalog tests prove host class identity and execute the relocated
  fingerprint path. App packaging pins and verifies Kafka 4.3.1 and Bouncy
  Castle 1.84 rather than accepting older Quarkus BOM selections.
- The finalized sink's one pre-release observable correction is documented:
  the record key is now stable `chainId`, not changing block height. JSON and
  `(chainId,height)` dedupe semantics are unchanged.
- Independent correctness, security, packaging, and lifecycle review found no
  unresolved critical or high-severity issue. Findings around acknowledgement
  archival, effect-id integrity, submitted-state corruption, timeout
  coherence, native serializer construction, dependency isolation, and
  partial construction were fixed and regression-tested.

Real broker restart, authentication failure, duplicate-retry fault injection,
and a configured Kafka-client native-image smoke remain mandatory Milestone 1
release evidence under Phases 1.5 and 1.7; Phase 1.1 does not claim those later
system gates.

#### Phase 1.2 — S3-compatible object storage

- Add `appchain-objectstore-s3`.
- Implement the pre-staged immutable/versioned copy model.
- Add checksum, conflict, policy, receipt, runtime-status, and MinIO
  integration tests.

**Implementation record (accepted 2026-07-16).** The new
`appchain-objectstore-s3` technology-owned bundle contributes the exact
`object.put` executor without a core, runtime, effect-runtime, or plugin-SPI
change. It implements the Phase 1.0 command/receipt/detail contracts and the
frozen pre-staged, independently hashed, conditional-create, immutable-version
model. Provider-specific code remains behind a private neutral client boundary;
the thin artifact supports build-time/native inclusion and the drop-in bundle
privately relocates connector-contract dependencies.

The accepted profile requires versioning, bounded exact-key history, one
create-only writer, no applicable lifecycle deletion, no resurrection, and
exact encryption/retention identity. It includes one-in-flight target lanes,
bounded timeouts with SDK retries disabled, archive-only submitted recovery,
full/composite checksum distinction, exact account-owner guards for managed
AWS, numeric/no-DNS local endpoints, disabled ambient proxies, printable
provider observations, prompt body zeroization, and stable redacted errors.

Recorded Phase 1.2 evidence:

- The clean offline module gate passes 77 tests with the two opt-in MinIO tests
  skipped, zero failures/errors, clean Javadoc, and clean artifact-boundary
  verification. Coverage includes Phase 1.0 conformance, invalid-input/no-I/O,
  exact retryability, destination conflicts, unknown acknowledgement,
  no-resurrection, retention and KMS identity, multipart checksum shape,
  managed bucket ownership, response-close and reconciliation zeroization,
  lifecycle races, submitted-state recovery, diagnostics, and packaging.
- Both integration tests pass separately against the exact
  `minio/minio:RELEASE.2025-09-07T16-13-09Z` image. They verify real conditional
  versioned promotion, bytes/metadata/receipt identity, restart reconciliation
  without another version, and delete-marker conflict without resurrection.
- Application bundle verification, generated catalog correlation, thin/native
  class identity, and isolated drop-in launch pass. The 18 MiB bundle contains
  AWS SDK 2.48.0 with only the selected synchronous URLConnection transport;
  Apache, Netty, and other unused transport stacks are excluded and rejected by
  the artifact gate.
- Independent security, correctness, dependency, lifecycle, and packaging
  review found no unresolved critical, high, or medium issue after the fixes
  above.

Live Object Lock, explicit timeout/unavailable fault injection, configured
native-image startup, and a managed public-AWS smoke remain mandatory
Milestone 1 release evidence under Phases 1.5--1.7; Phase 1.2 does not claim
those later system gates.

#### Phase 1.3 — IPFS

- Add `appchain-ipfs`.
- Implement pin-only v1, CID policy, probe/reconciliation, receipt,
  runtime-status, and Kubo integration tests.

**Implementation record (accepted 2026-07-16).** The new `appchain-ipfs`
technology-owned bundle contributes the exact `ipfs.pin` executor without a
core, runtime, Effect Runtime, or plugin-SPI change. It implements strict
alias-only target configuration, canonical CID policy, explicit direct/
recursive/indirect pin reconciliation, lazy per-target client ownership,
bounded receipt/detail archival, and archive-only submitted-state recovery.
The initial provider adapter is deliberately limited to synchronous Kubo
`pin/ls` and `pin/add`; remote pinning remains a different future target
profile.

The Kubo boundary uses fixed POST routes and query names, no redirects or
ambient proxies, bounded whole-response deadlines and 16 KiB streaming bodies,
strict JSON/CID comparison, numeric/no-DNS local targets, and TLS/bearer remote
targets. Post-dispatch ambiguity is always `ACK_UNKNOWN` and re-probed;
recognized offline missing content is `CONTENT_UNAVAILABLE`; malformed
non-success probes stay retryable. Kubo 0.42's exact
`indirect through <ancestor-cid>` state is validated before the executor
upgrades it to an explicit pin.

Recorded Phase 1.3 evidence:

- The clean offline module gate passes 75 tests with the one opt-in Kubo test
  skipped, including the Phase 1.0 runtime conformance suite, invalid-input/
  no-I/O behavior, target/CID policy, direct and recursive satisfaction,
  indirect/direct upgrade, unknown acknowledgements, submitted recovery,
  archival, response bounds, timeouts, interruption, redaction, close races,
  and exact error retryability. Module Javadoc and artifact-boundary checks
  complete without a module warning.
- The opt-in test passes against the telemetry-disabled, offline official
  `ipfs/kubo:v0.42.0` image at the recorded digest. It provisions deterministic
  raw and multi-block fixtures outside the executor, proves recursive and real
  indirect state, absent-to-new pin, already-pinned reconciliation through a
  fresh client, repeated idempotent add, unavailable content, and cleanup.
- Thin/native catalog correlation, the first-party bundle scan, and isolated
  drop-in launch pass. The 56 KiB thin artifact retains host contract identity;
  the 9.4 MiB bundle privately relocates connector contracts, CBOR, and
  BLAKE2b, initializes the real Kubo/strict-parser path, contains neither
  Jackson nor Byte Buddy, excludes host API classes, and exposes one exact
  manifest/ServiceLoader contribution.
- Independent correctness, Kubo-compatibility, security, lifecycle, packaging,
  and dependency reviews found no unresolved critical, high, or medium issue
  after fixes for mutation acknowledgement uncertainty, indirect state,
  probe error retryability, body-read deadlines, property forwarding, private
  dependency isolation, strict response parsing, and stale user-guide
  configuration. The parser additionally completed 100,000 randomized bounded
  inputs across all three entry points without an unsanitized exception.

An actual daemon restart during an open attempt, public authenticated proxy
smoke, configured native-image startup, and the complete multi-service fault
matrix remain mandatory Milestone 1 release evidence under Phases 1.5--1.7;
Phase 1.3 does not claim those later system gates.

#### Phase 1.4 — Evidence reference bundle

- Implement versioned commands/state/queries.
- Emit concurrent storage effects, incorporate their results, and gate Kafka
  publication behind an idempotent continuation command.
- Add proof-aware client/verifier and deterministic replay tests.

#### Phase 1.5 — Compose and normal deployment demo

- Add all service definitions, profiles, initialization, sample data, UI, and
  one scenario runner.
- Prove the runner against Compose and independently launched services.
- Document plugin installation and native inclusion.

#### Phase 1.6 — Persistence, cleanup, and public profiles

- Add network identity marker and fail-fast validation.
- Add separated bind mounts and safe granular cleanup.
- Complete devnet CI and opt-in preview/preprod smoke flows.
- Validate guarded mainnet configuration without automatic value movement.

#### Phase 1.7 — Final review and release closure

- Run independent architecture, security, operations, and determinism reviews.
- Fix all correctness and maintainability findings.
- Run full app-chain regression, JVM bundle, native catalog, Compose, host,
  restart, and cleanup tests.
- Update the user/operator guides, mark Milestone 1 Accepted with recorded
  evidence, and leave Milestones 2/3 visibly Planned.

Cardano payment hardening, native-asset transfer, DPP publication, and oracle
publication proceed under their separate ADRs after this connector baseline.

### 19.2 Milestone 2 — Minor framework closure

Milestone 2 begins only after the Milestone 1 demo and fault tests establish a
working baseline. A focused sub-ADR or accepted amendment must freeze the API
and activation behavior before implementation.

#### Phase 2.1 — Declarative effect-type ownership

- Define an enumerable ownership declaration for each executor/factory or its
  manifested contribution while retaining a migration path for legacy
  predicate-only `supports(type)` implementations.
- Validate configured/owned types before the Effect Runtime starts.
- Fail startup if a configured type has multiple owners, and report every
  conflicting plugin id, contribution scheme, executor id, and type without
  leaking configuration.
- Preserve the external-worker/type-partition ownership rules of ADR-010.
- Add duplicate-owner, legacy-provider, JVM bundle, native catalog, and
  lifecycle-timeout tests.

The exact declaration location—SPI, manifest schema, or both—is decided by the
sub-ADR. Classpath/service-loader iteration order is never an ownership rule.

#### Phase 2.2 — Emitter-capable result callback

The likely source-compatible shape is an overload such as:

```java
default void onEffectResult(
        AppBlock block,
        EffectResult result,
        AppStateWriter writer,
        AppEffectEmitter effects) {
    onEffectResult(block, result, writer);
}
```

The engine calls exactly one callback path. Requirements include:

- deterministic global effect ordinals across result callbacks and ordinary
  messages in the same block;
- existing per-block count/payload limits and atomic write/effect staging;
- no duplicate callback or effect during replay;
- defined ordering for multiple incorporated results;
- safe behavior for `FAILED`, `CANCELLED`, and `EXPIRED` outcomes;
- ADR-010.1 activation schedules and A±k replay matrices; and
- source/binary compatibility for existing state machines.

After activation, the evidence component may emit `kafka.publish` directly
when the second required storage result is incorporated. The explicit
`evidence.notify` command remains supported for chains whose profile predates
the activation and for operational recovery/interoperability.

#### Phase 2.3 — Executor observability bridge

- Define a lifecycle-owned, read-only snapshot contract between active
  executor products and host/plugin operations.
- Do not expose the client object, credentials, mutation methods, arbitrary
  labels, or live service calls to HTTP/Prometheus sampling threads.
- Publish cached ready/degraded/unavailable state, bounded counters/latency,
  in-flight work, and normalized last-success/failure age.
- Define creation, replacement, close, partial-startup rollback, class-loader,
  and native-image behavior.
- Wire ADR-011 health/metrics/dashboard views to snapshots without constructing
  a second connector client or using mutable static registries.
- Test blocked probes, stale snapshots, scrape storms, shutdown races,
  redaction, and bounded cardinality.

#### Phase 2.4 — Re-run connector/demo closure

- Run old-profile replay with the continuation command.
- Run new-profile replay with direct result emission.
- Prove byte-identical effects/roots on all members around the activation
  height.
- Re-run crash, packaging, native, Compose, host, preview/preprod opt-in, and
  cleanup suites.

### 19.3 Milestone 3 — Out-of-box composite state machine

This milestone requires a dedicated sub-ADR because composition changes the
application programming model and consensus-visible routing. It must not be
implemented as an unreviewed helper that merely loops over complete machines.

#### Phase 3.1 — Component contract and deterministic composition rules

Design a component abstraction with at least:

```text
component id + semantic/schema version
owned message topics/command families
owned state namespace
owned query namespace
validation/apply hook over a routed message
effect-result ownership and callback
optional aggregate-query contribution
activation schedule and deterministic configuration digest
```

The composite provider must commit its ordered component inventory and
configuration/profile identity as chain invariants. Every member fails startup
or refuses to join/vote if its effective committed profile, order, versions,
routes, or activation schedule differs.

Installed code may be a dormant superset during a rolling pre-activation
deployment; byte-for-byte equality of every JAR on disk is neither required
nor sufficient. The sub-ADR must define:

- the committed effective chain-profile/configuration digest;
- local startup and peer join/vote validation against that digest;
- how a fresh node learns and verifies the expected profile before
  participation;
- height-gated activation after every required member has compatible code; and
- retention of old component implementations/decoder branches until no
  replayable block or supported snapshot floor can require them.

#### Phase 3.2 — State, message, query, and effect isolation

- Prefix and enforce all component keys; a component cannot read or write a
  sibling namespace except through an explicit coordinator contract.
- Route each normal message to exactly one owner unless a versioned
  transaction/workflow route explicitly names several participants.
- Define deterministic validation ownership so mempool admission cannot vary
  with plugin discovery order.
- Apply routed components in committed order inside one atomic block batch and
  derive one MPF root.
- Prefix query paths and execute aggregate queries against the same committed
  snapshot.
- Allocate effect ordinals through the one framework emitter; record the
  owning component for deterministic result dispatch.
- Apply global and per-component quotas without making component order a
  starvation attack.

#### Phase 3.3 — Cross-component workflows

Cross-component invariants are owned by an explicit coordinator component or
versioned composite workflow, not by components reading arbitrary sibling
keys. Define atomic commands for scenarios such as:

```text
registry identity exists
    -> approval authorizes a document version
    -> document-trail entry commits evidence
    -> evidence component emits publication effects
```

Failure, retry, cancellation, expiry, and upgrade behavior must remain visible
and deterministic across the complete workflow.

#### Phase 3.4 — First-party composite distribution

Ship an `appchain-composite` bundle/profile with composition-compatible forms
of:

- key/value or product registry;
- document trail;
- approvals;
- evidence publication; and
- optional balances only where its semantics are explicitly required.

Provide configuration presets and client contracts rather than requiring Java
assembly. The initial evidence demo gains an optional composite profile; its
standalone profile remains as a regression fixture.

#### Phase 3.5 — Composite conformance and DPP readiness

- Replay and root equality across different local plugin discovery orders.
- Namespace escape and route-conflict rejection.
- Component add/upgrade/disable activation-height tests.
- Effect/result ownership and quota tests.
- Root-fixed component and aggregate query tests.
- Snapshot, restart, retention, proof, and L1 anchor tests.
- Compose and host no-code scenario using the composite preset.
- Document precisely which DPP building blocks are reusable and which DPP
  semantics still require the separate DPP ADR/bundle.

## 20. Compatibility and migration

### 20.1 Kafka rename

Because no stable release exists, v1 performs a direct artifact rename rather
than publishing permanent duplicate coordinates. Release notes document:

```text
:appchain-kafka-sink  -> :appchain-kafka
yano-appchain-kafka-sink-*-bundle.jar
                       -> yano-appchain-kafka-*-bundle.jar
```

The plugin id and runtime configuration remain unchanged, so plugin policy,
catalog identity, and sink operator configuration do not migrate.

The pre-release finalized sink formerly used decimal block height as the Kafka
record key. V1 uses the stable `chainId` instead, ensuring all finalized blocks
for one chain remain on one partition and therefore ordered on multi-partition
topics. The JSON body and consumer dedupe tuple `(chainId, height)` are
unchanged; pre-release consumers that inspected only the Kafka key must update.

If evidence appears that external pre-release consumers require a Maven
relocation POM, it may be added without shipping two runtime bundles. There is
no old-bundle compatibility shim on the plugin classpath.

### 20.2 Action evolution

V1 action and receipt schemas are immutable once released for a chain profile.
Compatible decoder extensions cannot change deterministic emission bytes.
Behavioral/payload changes use ADR-010.1 activation rules and a new schema
version with replay-matrix tests.

Executor implementations may improve retries, probes, client versions, and
operations without changing authenticated payload meaning or turning an old
definitive case into an unsafe mutation.

## 21. Deferred decisions

The following are deliberately deferred:

1. **Multiple object-store provider implementations for one chain.** Before
   GCS/Azure bundles claim `object.put`, define a single selected provider,
   explicit type partition, or deterministic local router. Current first-match
   executor routing must not decide by classpath order. Milestone 2 supplies
   the ownership validation on which that later provider design can build.
2. **Shared Kafka connection profiles.** Sink/effect config remains explicit
   until the factory/config API has a justified common-profile design.
3. **Kafka transactions/external dedupe ledger.** V1 is acknowledged
   at-least-once with effect-id consumer dedupe.
4. **`ipfs.add-and-pin`.** Requires fixed DAG/chunking/codec/hash semantics and
   a separate data-ingress/security design.
5. **IPFS unpin.** Requires retention, authorization, reference counting, and
   evidence/legal policy.
6. **Arbitrary object deletion or overwrite.** Not part of immutable
   `object.put`.
7. **Kubernetes/Helm/cloud provisioning.** Normal deployment contracts are
   designed to permit it later.
8. **Production Cardano actions.** ADR-010.2 and domain ADRs govern them.
9. **Complete DPP product.** The DPP possible-design note consumes these
   connectors but has separate schemas, identity, portal, and Cardano work.
10. **The exact component SPI and plugin-catalog shape.** Milestone 3 fixes the
    composition invariants here, while its required sub-ADR decides whether
    components are a new manifested contribution kind, a library assembled by
    one provider, or a staged combination with a migration path.

## 22. Alternatives considered

### A. One module per SPI role

Examples: `appchain-kafka-sink` and `appchain-effects-kafka`.

Rejected for Kafka because both artifacts would carry the same Kafka SDK,
connection/security helpers, plugin identity concerns, health model, and
operational ownership. It also makes future Kafka capabilities harder to
discover as one product integration.

### B. One `appchain-integrations` mega-bundle

Rejected. Kafka, S3, IPFS, and Cardano have unrelated credentials,
dependencies, vulnerabilities, privileges, lifecycle, and failure domains.
Operators must be able to install only what they use.

### C. Generic `appchain-objectstore` containing every vendor SDK

Rejected for v1. An S3-only implementation under a vendor-neutral name would
overpromise portability; bundling GCS/Azure later would inflate the privileged
plugin and mix auth/semantics. Use the S3-specific technology boundary first.

### D. Let effects contain arbitrary endpoints/topics/buckets

Rejected. It turns deterministic application input into SSRF, exfiltration,
credential-routing, and production-destination authority. Configured aliases
make operator policy explicit.

### E. Put document bytes in `object.put` or `ipfs.pin`

Rejected. Effect records are replicated and retained/provable; large or
confidential bytes cause state, privacy, and availability problems. Pre-stage
bytes and commit digest/reference.

### F. Use only the finalized Kafka sink

Rejected as the only mechanism. It is correct for projection but cannot model
an individually authorized notification with independent retry and a result
fed back to business state.

### G. Use only `kafka.publish` effects

Rejected. Recreating bulk projections from per-action effects loses the
ordered replay/cursor advantages of the existing finalized-block sink and
inflates authenticated effect state.

### H. Compose-only demo

Rejected. It can hide packaging, path, credential, and lifecycle assumptions
that fail in a normal deployment.

### I. Normal-deployment-only demo

Rejected. It does not provide the requested no-code, reproducible evaluation
experience and makes integration regressions difficult to reproduce.

### J. Docker named volumes with `down -v` as the only reset

Rejected. Operators cannot easily inspect/preserve selected layers, and
coarse deletion risks losing expensive L1 state. Bind-mounted, network-scoped
directories plus explicit cleanup provide clearer ownership.

### K. Invoke several existing `AppStateMachine` instances in a loop

Rejected. Complete machines currently assume ownership of the block,
state-reader/writer keyspace, query surface, and effect-result callback. Their
local ids and incidental key prefixes do not establish safe namespace,
routing, ordering, quota, upgrade, or cross-component semantics.

### L. Require every multi-feature application to build one bespoke machine

Rejected as the only long-term option. A domain application still owns its
business invariants, but repeating registry, trail, approval, effect routing,
query namespacing, and upgrade plumbing makes common applications harder to
extend and audit. The composite milestone supplies safe building blocks
without claiming that configuration invents missing domain rules.

## 23. Consequences

### Positive

- Applications gain reusable external actions without custom executor code.
- Kafka capabilities appear as one coherent plugin bundle while preserving
  sink/effect semantics.
- Optional dependencies remain out of the stock runtime.
- Target aliases and pre-staging create a safer production boundary.
- The evidence scenario exercises Emit → Execute → Result → explicit
  continuation → Emit in v1; Milestone 2 can collapse the explicit wake-up
  without changing the business precondition.
- Compose gives a fast local experience while normal deployment proves real
  plugin portability.
- Network isolation and granular cleanup prevent genesis mismatch and needless
  public-network resync.
- DPP, oracle, and document/evidence applications gain a shared connector
  foundation.
- Explicit effect ownership removes classpath-order ambiguity after Milestone
  2.
- Compatible applications gain reusable, namespaced composition and one
  atomic proof root after Milestone 3.

### Negative

- Three new privileged integrations materially expand dependency and security
  maintenance.
- One Kafka bundle has multiple contribution lifecycles that require careful
  testing and documentation.
- Cross-process Kafka duplicates remain possible and require consumer dedupe.
- Object pre-staging requires a gateway/script step before the effect.
- An IPFS pin is weaker than a permanent-availability guarantee.
- Compose plus host/public-network support creates a larger test matrix.
- Bind-mounted data requires careful cross-platform path/permission handling.
- The later framework callback and composition milestones expand the replay,
  compatibility, and consensus-visible test surface.
- Generic components cannot eliminate the need for domain-specific
  cross-component coordinators and invariants.

## 24. Acceptance criteria

### 24.1 Milestone 1 — connectors and demo

Milestone 1 may move to Accepted only when all of the following are true:

1. `appchain-kafka` replaces the old artifact and loads both contributions in
   JVM and native/build-time modes.
2. Existing Kafka sink configuration and behavior remain regression-tested.
3. `kafka.publish`, `object.put`, and `ipfs.pin` have frozen v1 codecs, golden
   vectors, policy limits, receipts, error tables, and existing Effect Runtime
   status/metric integration.
4. Real connector integration suites pass their applicable fault matrix:
   Kafka covers acknowledgement, duplicate/retry, timeout, broker restart,
   authentication failure, unavailable service, and sink/effect coexistence;
   MinIO covers conditional creation, exact match, destination conflict,
   timeout, restart, unavailability, versioning, checksum, and the selected
   retention profile; Kubo covers new/already/indirect pins, upgrade,
   unavailable content, unknown acknowledgement, timeout, restart, and
   reconciliation. A connector is not required to invent a conflict semantic
   that its external idempotent-set model does not have.
5. No connector payload or receipt accepts/leaks credentials or arbitrary
   endpoints.
6. The evidence state machine remains deterministic across replay; it reaches
   `STORAGE_READY` only after both configured storage results are incorporated,
   and an idempotent continuation command emits Kafka at most once logically.
7. The three-node devnet scenario verifies external state, receipts,
   deterministic state agreement, proof, Cardano anchor, one exact active type
   partition, and an explicitly fenced failover with no overlapping writer.
8. The identical scenario passes against Compose and normal deployment.
9. Stop/restart preserves data and all granular cleanup modes pass
   path/symlink/sibling-network safety tests.
10. Network identity mismatches fail before node launch with actionable
    diagnostics.
11. Preview and preprod profiles are documented and have recorded opt-in smoke
    evidence; mainnet is explicitly guarded and moves no value automatically.
12. Plugin inventory, Effect Runtime status/metrics, effect operations,
    scenario service checks, and the evidence UI correctly distinguish intent,
    execution, incorporation, anchoring, and business truth.
13. Full existing app-chain regression tests remain green.
14. Independent architecture, determinism, security, and operations reviews
    have no unresolved correctness or high-severity findings.

### 24.2 Milestone 2 — minor framework closure

Milestone 2 may move to Accepted only when:

1. every newly configured first-party action type has exactly one declared
   owner and duplicate ownership fails before runtime publication;
2. legacy executor behavior and migration are explicit and tested;
3. the emitter-capable result callback is source/binary compatible and the
   engine invokes exactly one callback path;
4. effect ordinals, caps, writes, and results remain byte-identical across
   replay and the activation boundary;
5. old profiles retain the continuation-command behavior and new profiles can
   emit Kafka directly after the second storage confirmation without logical
   duplication;
6. A±k, multi-result block, failed/cancelled/expired, restart, snapshot, JVM,
   native, and multi-node tests pass; and
7. connector health/metrics consume lifecycle-owned cached executor snapshots,
   create no duplicate connector clients, make no service call on scrape, and
   pass redaction/cardinality/shutdown tests; and
8. no unresolved correctness or high-severity framework review finding
   remains.

### 24.3 Milestone 3 — composite state machine

Milestone 3 may move to Accepted only when:

1. its dedicated sub-ADR freezes component identity, inventory commitment,
   ordering, namespaces, routes, upgrades, queries, effects, and result
   ownership;
2. every member rejects a mismatched effective component profile/configuration
   before participating in the chain, while permitting dormant implementation
   supersets that do not change that committed profile;
3. namespace escape, duplicate route ownership, plugin-discovery-order
   dependence, ambiguous result ownership, and quota starvation fail closed;
4. stock registry, document-trail, approvals, and evidence components can be
   selected through a versioned preset without Java assembly;
5. cross-component workflows commit atomically to one state root and remain
   deterministic across replay, restart, snapshot, and activation changes;
6. component and aggregate queries use one committed snapshot and retain
   bounded proof/response behavior;
7. the composite no-code scenario passes in Compose and normal three-node
   deployments with effects, proofs, and L1 anchoring; and
8. documentation distinguishes reusable composition plumbing from the
   domain-specific semantics still required by DPP and other products.

ADR-013 as a complete roadmap is marked fully implemented only after all three
milestones have their own recorded acceptance evidence. Their implementation
status remains independently visible in the table at the top of this ADR.
