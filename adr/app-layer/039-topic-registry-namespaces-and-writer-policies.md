# ADR-039: Topic Registry, Namespaces and Writer Policies

## Status

Proposed — design only, for review. No implementation is part of this ADR.

The number is local to the `adr/app-layer` series. Root-level and
`adr/in-progress` ADR numbers are separate series; in particular, this is not
the in-progress ADR-039 on the canonical projection outbox.

## Date

2026-10-22

## Owners

Yano app-chain host: `runtime/appchain` (admission, block validity,
framework apply), `core-api` (registry types, payload/writer view, schema
evaluator), and the REST resource in `app`. Yano X owns authoring tools,
Studio, composites and stdlib reuse; see the companion
[Yano X ADR app-layer/038](https://github.com/bloxbean/yano-x/blob/main/adr/app-layer/038-topic-policy-integration-schemas-rules-and-tooling.md).

## Risk

**High.** The design adds consensus rules (block validity), a framework-owned
state namespace, signature verification of client evidence, and governance
paths. A defect can split members, admit forged writes, or lock owners out.

## Related decisions

- [ADR-005](005-yano-app-chain-framework.md) — the app-chain subsystem, the
  reserved `~` topic prefix (D7) and the member-signed envelope.
- [ADR-008.3](008.3-chain-governed-membership.md) — governance messages on a
  reserved topic, approval by threshold-many identical commands, activation
  lag, a framework handler atomic with the block. This ADR reuses that
  mechanism for topic governance instead of inventing a second one.
- [ADR-010.1](010.1-emission-versioning.md) — height-indexed framework epochs.
- [ADR-011](011-plugin-architecture.md) — the plugin API and its API levels.
- [ADR-016](016-authenticated-appchain-consensus-profile-and-typed-runtime-limits.md)
  — reserves `~yano/` for framework-authenticated state, commits
  `~yano/consensus-profile/v1`, and explicitly leaves "application-schema
  evolution" out of the v1 profile (§3.1). This ADR fills that gap for topics.
- Yano X ADR app-layer/025.1 (declarative value schemas for the authenticated
  map) — the source of the `yano-cbor-schema-ir-v1` evaluator that this ADR
  moves into core.
- Yano X ADR app-layer/031.3 and 031.4 — forbid-only admission rules for
  composite components; the companion ADR extends their views to typed topic
  payloads and verified writers.

---

## 0. In plain words

Every app message already carries a **topic**. Today the topic is only a
label: any client that can reach any member can post any body to any topic,
and the members cannot tell *who* wrote it — the envelope is signed by the
member that relayed it, not by the client.

This ADR lets a chain opt in to a **topic registry** that every member keeps
in consensus state:

- A **namespace** such as `acme/` has **owners** (keys, k-of-n). Only they
  can create topics under it, change their policies, or rotate themselves.
- Each **topic** has a **policy**: who may write (anyone, or a set of keys),
  whether those writers may **delegate** to short-lived **session keys**, and
  an optional **schema** for the body.
- Namespaces are created by **governance** (members vote), by **registrar**
  keys, by a **namespace owner** (sub-namespaces), or — if the chain allows
  it — by an **open first-come claim**.
- A client writing to a protected topic attaches a **writer proof**: its
  signature, or a session key's signature plus a **delegation** signed by an
  authorized writer. Every member checks the chain *owner → delegate →
  message* before the message can enter a block.
- Policy changes are signed chain transactions that take effect at a later
  height, never retroactively, so every historical message stays verifiable.

A chain that does not opt in behaves exactly as today.

![Context: clients, members, the topic registry and the anchor](images/039/context.svg)

---

## 1. Context

Facts this design builds on, checked at `origin/main` `a4e28babf` (yano) and
`origin/main` of yano-x on 2026-10-22:

1. **Envelope.** `AppMessage` (yaci `core/.../appmsg/model/AppMessage.java`)
   is v2: `[version, messageId, chainId, topic, sender, senderSeq, expiresAt,
   body, [authScheme, authProof]]`. `messageId` is BLAKE2b-256 over
   `[chainId, topic, sender, senderSeq, expiresAt, body]`. `sender` and
   `authProof` belong to the **relaying member**; `body` is opaque and "never
   interpreted by the transport or sequencing layers".
2. **Submission.** REST `POST /api/v1/app-chain/chains/{chain}/messages`
   takes `{topic, body | bodyHex}`; the member wraps it in an envelope it
   signs. `AppChainSubsystem.submitWithinGeneration` refuses empty bodies,
   bodies above `max-message-bytes` (65,536 by default), invalid topics, and
   any topic starting with `~` ("reserved for the framework").
3. **Admission hooks.** `AppStateMachine.validate` and `validateForBlock` are
   per-machine. Since commit `36c88a6ba` (host 0.1.0-pre17) a refusal at
   submit is HTTP 400 with a code (`validateLocalSubmission`); the proposer
   drops refused messages when building a block (`AppChainEngine`, the
   `validateForBlock` filter).
4. **Followers.** A follower validating a proposal checks every message's
   profile, id, expiry and member signature, and rejects the whole block if
   it contains an effect result from a non-designated signer
   (`authorizedResultMessage`) — a precedent for a policy rule in block
   validity. It then re-executes the block and compares the state root.
5. **Block time.** The proposer stamps `System.currentTimeMillis()`; the
   follower check in `validBlockProfile` is only `timestamp >= 0`. Nothing
   enforces monotonic or bounded time (see D12).
6. **Governance.** ADR-008.3 governs membership through `~governance/membership`
   (threshold-many identical commands, activation lag, framework handler,
   atomic commit). `submitPrivilegedSystemMessage` accepts only
   `~governance/` topics through an admin path.
7. **Framework state.** ADR-016 reserves `~yano/` for framework-authenticated
   state inside the committed state root.
8. **Schemas.** Yano X's `AuthenticatedMapSchema`
   (`state-machines/stdlib-contracts`) is a bounded, total evaluator of the
   canonical `yano-cbor-schema-ir-v1` (max 65,536 IR bytes, 2,048 nodes,
   depth 32, 262,144 evaluation steps; no references or recursion). The
   `cddl-yano-subset-v1` → IR compiler lives in Yano X `tooling/devtools`.
   Only authenticated-map collections use it today. `ordered-log`'s own
   documentation says "schemas and transitions need another machine or a
   plugin".

### 1.1 Problems

- **P1 — No consensus write authorization.** Write access is enforced only at
  each member's edge (API keys, CORS). It is not part of consensus, not
  portable across members, and not provable.
- **P2 — No structure for logs and custom machines.** Junk on an
  `ordered-log` topic is stored forever; every consumer re-validates.
- **P3 — No safe way to give end users write access.** Declaring keys at
  genesis does not scale to thousands of users or to rotating devices.
- **P4 — Topics cannot be created or governed on a running chain.** Any
  ownership convention is off-chain.
- **P5 — Block time is unchecked**, so any time-based rule (expiry) is only as
  honest as the proposer.

## 2. Goals and non-goals

**Goals**

- G1. Opt-in, consensus-enforced writer authorization per topic, the same for
  `ordered-log`, the other stock machines and custom plugins.
- G2. Namespaces with owners, created in four modes; owners create sub-topics
  and rotate their own keys; governance can recover.
- G3. Short-lived delegations (session keys) so applications can give end
  users write access without key ceremonies.
- G4. Optional per-topic body schemas (canonical CBOR, bounded evaluation).
- G5. Everything provable from the state root and anchored on L1 like any
  other state.
- G6. A small, maintainable core: one registry, one frame, one check
  pipeline, reusing existing governance and schema code.

**Non-goals**

- Read access control or confidentiality (all members see all bytes).
- Rate limiting and quotas (left as an extension point; see OQ7).
- Wallet-native key schemes (CIP-8/CIP-30 message signing) in v1 (OQ6).
- Changing the transport envelope or yaci wire protocols.
- Business rules beyond structure (that is what state machines and Yano X
  admission rules are for).

## 3. Terms

| Term | Meaning |
|---|---|
| **Topic** | The existing envelope field. Up to 256 UTF-8 bytes; must not contain NUL or start with `~`. |
| **Namespace** | A registered topic prefix ending in `/`, e.g. `acme/`, `acme/orders/`. |
| **Owners** | A key set with a threshold (k-of-n) that controls a namespace. |
| **Registry entry** | A namespace or exact topic record, with its policy and revision history. |
| **Policy** | Writers, delegation rights, schema and status that apply to topics under an entry. |
| **Writer** | A key allowed to write to a protected topic. |
| **Delegation** | A statement signed by a writer: "key X may write to scope S from T1 until T2". |
| **Session key** | The delegate key, typically generated in a browser or device. |
| **Writer proof** | The signatures (and optional delegation) attached to a message on a protected topic. |
| **Frame** | The canonical CBOR body wrapper that carries the payload and the writer proof. |
| **Payload** | The application's bytes inside the frame (or the whole body on an open topic). |

---

## 4. Decisions

### D1 — Opt-in per chain; disabled means unchanged

```yaml
yano.app-chain.chains[n].topics:
  mode: disabled | enabled            # default: disabled
```

With `mode: disabled` nothing in this ADR runs: no `~yano/topics/` keys are
written, no frame is parsed, and state roots are byte-identical to today
(I1). Enabling is a genesis choice for new chains, or a governed
height-activated change on an existing chain (D13). All members must agree;
the effective topic configuration is committed under `~yano/topics/v1/config`
at the activation height and verified on restart, the same way ADR-016
treats the consensus profile.

### D2 — The registry is framework-authenticated state under `~yano/topics/v1/`

The registry lives inside the chain's state root, next to the ADR-016
consensus profile, so every entry is provable and anchored with no new proof
machinery. Illustrative key layout (normative encoding fixed in M1):

| Key | Value |
|---|---|
| `~yano/topics/v1/config` | Effective chain-level topic configuration (D1, D4, D14). |
| `~yano/topics/v1/entry/<name>` | Namespace or exact-topic record: owners, policy, status, revision, `activeFrom`. |
| `~yano/topics/v1/history/<name>/<revision>` | Superseded revisions, kept so old messages stay verifiable (D13). |
| `~yano/topics/v1/schema/<irHash>` | Schema IR bytes, content-addressed (D10). |
| `~yano/topics/v1/used/<statementHash>` | Replay guard: the hash of a used write-statement (not of its signatures), removed after its `notAfter` (D9). |
| `~yano/topics/v1/revoked/<delegationHash>` | Early-revoked delegations, removed after their `notAfter` (D8). |
| `~yano/topics/v1/pending/<commandHash>` | Governance approvals in progress (reusing ADR-008.3 accounting). |

Application machines cannot write under `~yano/` (ADR-016 reserved prefix).

### D3 — Names: `/`-separated namespaces, longest prefix wins

- A namespace name is one or more segments of `[a-z0-9._-]{1,64}`, each
  followed by `/` (`acme/`, `acme/orders/`). An exact topic entry is any
  valid topic under a namespace.
- The policy for a message is the **exact topic entry if present, otherwise
  the longest registered namespace prefix** of its topic. Child entries
  replace inherited fields they set and inherit the rest.
- **Exclusivity (I6).** A new namespace may be created only if no ancestor
  namespace exists (a top-level claim), or by an owner of its nearest
  ancestor (a sub-namespace). So once `acme/` exists, nobody else can create
  `acme/x/` — name collisions are prevented by construction.
- Existing dot-style topics (`orders.v1`, `neon-rush.v1.s123`) are valid
  topics; they can be registered as exact entries or live under a namespace.
  Whether to also allow `.` as a namespace separator is OQ1.

### D4 — Four creation modes, chosen per chain

| Mode | Who creates a top-level namespace | Typical use |
|---|---|---|
| `governance` | threshold-many members submit the identical command (ADR-008.3) | consortium chains |
| `registrar` | k-of-n registrar keys declared in the topic configuration, rotated by governance | a platform team operating the chain |
| `open` | anyone, first come, by a claim signed by the future owners | public or developer chains |
| `namespace-owner` | the owners of an existing namespace, for sub-namespaces and topics beneath it | always available once a namespace exists |

```yaml
yano.app-chain.chains[n].topics:
  mode: enabled
  creation:
    governance: true
    registrars: { threshold: 1, keys: [<hex32>, <hex32>] }
    open-claims: false
  unregistered-topics: open          # or: reject (D14)
  max-delegation-ms: 3600000
  max-entries: 100000
```

`namespace-owner` is not a switch; it is how every namespace grows. An
**open claim** makes the claimant's keys the owners of a new top-level
namespace; from then on only those owners create sub-topics and rotate keys,
exactly as for any other namespace. Squatting and spam in open mode are
bounded by `max-entries` and OQ7.

![Namespaces, registry entries and the four creation modes](images/039/namespaces-and-creation.svg)

### D5 — Two command paths, both on reserved topics

1. **Owner commands** on `~topics/registry`: create a sub-namespace or topic,
   update a policy, rotate owners or writers, revoke a delegation, retire an
   entry, and (in `open` mode) claim a top-level namespace. Each command is
   signed by the owners (k-of-n) of the affected entry or its parent, or by
   registrars for top-level creation. **Any member may relay** these: REST
   accepts `~topics/registry` as the one user-submittable `~` topic, because
   the framework verifies the owner signatures inside the command, not the
   relaying member.
2. **Governance commands** on `~governance/topics`: create a top-level
   namespace (governance mode), replace owners of any entry (recovery),
   change registrars, change the chain topic configuration, or retire an
   abusive entry. These are approved exactly like membership changes:
   threshold-many members submit the identical command within the approval
   window, through the existing privileged admin path.

Both kinds of command take effect at `activation height = finalization
height + lag` (default lag 10, minimum 1), so every member sees a change
coming and a policy never changes inside the block that carried the command
(I5). Commands are canonical CBOR; a command that fails a check is a
deterministic no-op with a receipt, never a stall.

![Registry lifecycle: commands, approval, activation and revisions](images/039/registry-lifecycle.svg)

### D6 — What a policy contains

```text
policy = {
  writers:     open | { threshold k, keys [..] }      ; who may write
  delegate:    [ keys allowed to issue delegations ]  ; subset of writers; empty = no delegation
  maxDelegationMs: ≤ chain max-delegation-ms
  schema:      none | { encoding: canonical-cbor, ir: <irHash> }
  maxPayloadBytes: ≤ chain max-message-bytes
  status:      active | retired
}
```

- `writers: open` keeps today's behaviour for that topic but still applies
  `schema` and `maxPayloadBytes`. This is how "schema without
  authorization" works.
- A `retired` entry refuses all new messages; its history stays readable and
  provable.
- Policies only ever **forbid more**: the topic policy runs before the
  machine's own admission, and a machine may still refuse a message the
  policy allowed (I10).

### D7 — The writer proof travels in a body frame, not in the envelope

On a protected topic, the message body is a canonical CBOR **frame**:

```cddl
; illustrative — normative CDDL is frozen in M1
topic-frame = [
  1,                          ; frame version
  payload : bstr,             ; the application's bytes
  proof   : writer-proof,
]
writer-proof = [
  nonce      : bstr .size 16,
  notAfter   : uint,          ; ms, certified block time (D12)
  signatures : [+ [key : bstr .size 32, sig : bstr .size 64]],   ; Ed25519
  delegation : delegation / null,
]
write-statement = [ "yano-topic-write-v1", chainId : tstr, topic : tstr,
                    payloadHash : bstr .size 32, nonce, notAfter ]
```

Each signature is Ed25519 (RFC 8032 §5.1.6–5.1.7) over the canonical CBOR
(RFC 8949 §4.2.1) of `write-statement`. `payloadHash` is BLAKE2b-256 of
`payload`.

Why the body and not a new envelope field: the envelope, its message id and
the yaci gossip codec stay untouched (no transport change, no new wire
version), the proof is covered by `messageId` and by the existing
finalized-message proofs, and an off-chain verifier can check authorship from
the body alone.

#### D7a — Machines receive the verified payload as the body

For a message on a protected topic, the framework hands every state-machine
hook (`validate`, `validateForBlock` and `apply`) a **payload view** of the
message: the same `messageId`, chain, topic, sender and position in the
block, with `getBody()` returning the verified frame payload instead of the
frame. Existing machines and codecs therefore work unchanged on protected
topics — `balances`, `kv-registry` and the authenticated map decode their own
command format, and Yano X composites, which decode `source.getBody()` in
`EventBindingWorkflow` at admission and in apply, see the command rather than
`[version, payload, proof]`. On an open topic the view is the message itself.

The finalized block, `messageId`, message proofs and `ordered-log`'s record
keep the original framed bytes, so authorship stays provable (D15). Writer
information is available through a small core-api view:

```java
// illustrative
interface TopicMessageView {
    Optional<VerifiedWriter> writer(); // key and, if delegated, the issuing writer
    long policyRevision();
}
```

`AppBlockExecutionContext` exposes it per message. Machines must use the
provided `messageId` rather than recompute it from the view's body (they
already do; recomputation is a transport check).

![The body frame and what each signature covers](images/039/topic-frame.svg)

### D8 — Delegations and session keys

```cddl
delegation = [
  1,
  delegate  : bstr .size 32,  ; the session key
  scope     : tstr,           ; an exact topic or a namespace prefix ending in "/"
  notBefore : uint,           ; ms
  notAfter  : uint,           ; ms; notAfter - notBefore ≤ policy maxDelegationMs
  issuer    : bstr .size 32,
  sig       : bstr .size 64,  ; issuer's Ed25519 over the canonical array without sig,
]                             ;   prefixed by "yano-topic-delegation-v1" and chainId
```

A message with a delegation is valid when, under the policy active at the
block height:

1. `issuer` is listed in the policy's `delegate` set for the message's topic;
2. `scope` covers the topic, and is within what the issuer may write (I9);
3. `notBefore ≤ blockTime ≤ notAfter` for the delegation, and
   `blockTime ≤ notAfter` of the writer proof;
4. the delegation hash is not in `~yano/topics/v1/revoked/`;
5. the session key's signature over `write-statement` verifies;
6. the write-statement's hash is not in `~yano/topics/v1/used/` (D9).

A policy with `writers: {threshold k > 1}` does not accept delegated writes
(a delegation stands in for one writer); OQ5 discusses relaxing this.

**Flow.** An application authenticates its user by any means it likes
(app login, wallet, OAuth), the user's device creates a session key, the
application's issuer key signs a delegation, and the device then writes
directly to any member:

![Delegation flow: from app login to a verified write](images/039/delegation-flow.svg)

Revocation is by expiry first (keep lifetimes short); an issuer or the
namespace owners can also revoke a delegation early with an owner command,
which lands in `revoked/` until the delegation would have expired anyway.

### D9 — Replay protection is bounded by time

Identical bytes resubmitted through another member produce a new envelope
and a new message id, so the envelope cannot stop replays of the client's
intent. The write-statement can: it names the chain, topic, payload hash,
nonce and `notAfter`, so it identifies one intended write exactly.

The **replay identity is BLAKE2b-256 of the canonical write-statement**, and
deliberately *not* of the signature bundle or the delegation. Hashing the
signatures would let a relay replay one signed intent by attaching a
different valid set: under a 2-of-3 policy, signatures A+B and A+C give two
different proofs for the same statement. With the statement as the identity,
any second message carrying the same statement is refused, whatever valid
signatures or delegation accompany it.

The statement hash is recorded in `~yano/topics/v1/used/` when the message is
applied and removed once `blockTime > notAfter`. A message is refused if its
statement hash is present, if the same statement appears twice in one block,
or if `notAfter` is more than `max-delegation-ms` ahead of the block time.
State is therefore bounded by the write rate times the maximum lifetime
(I12).

### D10 — Schemas use the existing IR, moved into core

- Core adopts the `yano-cbor-schema-ir-v1` evaluator (today Yano X's
  `AuthenticatedMapSchema`) as a core-api type: a total, bounded predicate
  over one canonical CBOR value, with no reflection (GraalVM native-image
  friendly, which core requires).
- Registry entries reference schema IR by BLAKE2b-256 hash; the IR bytes are
  stored once under `~yano/topics/v1/schema/<hash>` by the command that
  first uses them.
- Authoring (`cddl-yano-subset-v1` → IR) stays a tool. Yano X's compiler and
  CLI produce IR and registry commands (companion ADR).
- The payload must be canonical CBOR (RFC 8949 §4.2.1) when a schema is
  set. JSON is not accepted on schema-typed topics in v1; JSON clients
  convert before framing.
- Yano X's authenticated map then calls the core evaluator for its collection
  values, so one implementation serves both (companion ADR).

### D11 — One check pipeline, run in three places

The same function `TopicPolicy.check(message, parentState, blockTime,
blockContext)` runs:

| Where | Inputs | Effect of a refusal |
|---|---|---|
| **Ingress** (REST submit, and messages gossiped into the pool) | committed state at the tip, local clock as provisional block time | HTTP 400 with a code; never pooled |
| **Proposer** (building a block) | parent state, the candidate block time, messages already selected | message dropped from the candidate |
| **Follower** (validating a proposal) | parent state, the proposal's block time, earlier messages in the block | **block rejected** (next to `authorizedResultMessage`) |

Order of checks — first failure wins:

1. framework bounds (today's size and topic rules);
2. registry lookup (D3) and status; `unregistered-topics: reject` (D14);
3. frame decode and canonicality (protected topics);
4. writer proof: statement, signatures, threshold, delegation (D7, D8);
5. replay guard (D9);
6. payload size and schema (D10);
7. the machine's own `validate` / `validateForBlock`.

The follower-side rejection is what makes the policy a consensus rule; the
ingress and proposer checks only avoid wasted work. During apply the
framework records used write-statement hashes and pending registry commands **before** the
machine sees the block, in the same atomic commit (as ADR-008.3 does for
membership).

![The validation pipeline and where each check runs](images/039/validation-pipeline.svg)

Refusal codes (HTTP 400 at ingress, receipts for registry commands):

| Code | Meaning |
|---|---|
| `TOPIC_UNREGISTERED` | no entry covers the topic and the chain rejects unregistered topics |
| `TOPIC_RETIRED` | the entry is retired |
| `TOPIC_FRAME_INVALID` | the body is not a canonical frame on a protected topic |
| `TOPIC_WRITER_DENIED` | signatures missing, invalid, below threshold, or keys not writers |
| `TOPIC_DELEGATION_INVALID` | issuer not allowed, scope, time window, or revoked |
| `TOPIC_PROOF_EXPIRED` | `notAfter` passed or too far ahead |
| `TOPIC_PROOF_REPLAYED` | this write-statement was already used (with any signatures) |
| `TOPIC_PAYLOAD_TOO_LARGE` | above `maxPayloadBytes` |
| `TOPIC_SCHEMA_REJECTED` | not canonical CBOR, or the schema refused it |

### D12 — Certified block time gets bounds (prerequisite)

Delegation windows and replay expiry use the block timestamp, so the block
timestamp must be trustworthy within a bound. A follower votes for a
proposal only if:

- `timestamp ≥ parent.timestamp` (monotonic), and
- `timestamp ≤ localNow + max-forward-skew-ms` (default 5,000).

Recovery proposals that re-propose a prepared value keep their original
timestamp and are exempt from the forward bound (they already passed it).
A lower bound against local time (`timestamp ≥ localNow − skew`) would stop a
proposer freezing time but can harm liveness after view changes; it is OQ2.
`max-forward-skew-ms` joins the ADR-016 consensus profile. This decision is
useful on its own: the games and any expiry logic built on certified time
benefit today.

### D13 — Rotation, recovery and history

- **Rotation.** Owners rotate owners, writers, delegators and policies with
  an owner command signed by the *current* owners (k-of-n). It takes effect
  at its activation height.
- **Recovery.** If owners lose their keys, a governance command replaces
  them. This is the only override, and it is itself a threshold-approved,
  provable chain transaction.
- **History.** Every change writes a new revision; the previous revision
  moves to `history/`. A message is always judged by the revision active at
  its block height, so old messages stay verifiable after rotation (I11).
- **Enabling on a running chain.** A governance command switches `mode` to
  `enabled` at an activation height. Messages before it were never subject
  to policies; nothing is re-judged.

### D14 — Unregistered and system topics

- `unregistered-topics: open` (the default when enabled): topics no entry
  covers behave exactly as today. This allows gradual adoption.
- `unregistered-topics: reject`: every topic must be registered first. This
  closes the gap where a machine that ignores the topic (for example
  `kv-registry`) could be reached through an arbitrary topic.
- `~` system topics keep their own rules. `~topics/registry` and
  `~governance/topics` are handled by the framework; other `~` topics are
  unaffected.

### D15 — Visibility and proofs

- REST, read-only: `GET .../topics/entries/{name}`, `GET .../topics/resolve?topic=`
  (the effective policy and the entry it came from), `GET .../topics/schemas/{hash}`.
- A typed proof subject `topic-policy-v1` proves an entry (owners, policy,
  revision, `activeFrom`) at a height, so anyone can prove "this topic was
  owned by these keys when this message was written".
- Authorship of a message on a protected topic is provable with the existing
  finalized-message proof (the frame is in the body) plus a `topic-policy-v1`
  proof at the message's height.

---

## 5. Threat model and trust assumptions

| Actor | Trusted for | Not trusted for |
|---|---|---|
| Members (threshold) | ordering, finality, availability, governance approvals | individual honesty: one member may be malicious |
| A single member / proposer | nothing beyond its vote | inventing writes, freezing or skewing time, slipping invalid messages into blocks |
| Namespace owners | their own namespace's policy | other namespaces |
| Registrars | creating top-level namespaces | changing existing namespaces they do not own |
| Issuers (delegating writers) | the delegations they sign, within their scope | anything outside their scope or lifetime |
| End-user devices (session keys) | their own short-lived writes | everything else; assume they are lost or stolen |
| Clients and the network | nothing | — |

Secrets: owner, registrar, issuer and session private keys. None is ever sent
to a member; members verify public keys only.

Threats and mitigations:

- **Forged writes** through any member → signatures checked by every member;
  block rejected otherwise (D11, I3).
- **Replay** of a signed write through another member, topic or chain, or
  with a different valid signature subset or delegation → the statement binds
  chain and topic; the replay identity is the statement itself, not its
  signatures; `notAfter` bounds the guard (D9, I4).
- **Malicious proposer** including unauthorized messages → followers reject
  the block (D11).
- **Time manipulation** to revive expired delegations → monotonic time and a
  forward-skew bound; the remaining exposure is OQ2 (D12).
- **Stolen session key** → bounded by delegation lifetime and scope; early
  revocation (D8).
- **Stolen issuer key** → owners rotate it out; outstanding delegations die at
  expiry or are revoked (D13).
- **Stolen or lost owner keys** → governance recovery (D13).
- **Namespace squatting / registry spam** in open mode → first-come plus
  `max-entries`; quotas or deposits are OQ7.
- **Schema evaluation as a DoS** → IR limits and the step bound; payload
  bounded by `maxPayloadBytes` (I8).
- **Signature verification cost** → bounded by messages per block and
  signatures per proof (`threshold ≤ 16` in v1); counted in block budgets.

## 6. Invariants

| ID | Invariant | Decisions |
|---|---|---|
| I1 | With `mode: disabled`, state roots, blocks and REST behaviour are byte-identical to the pre-ADR implementation. | D1 |
| I2 | `TopicPolicy.check` is a pure function of (message, parent committed state, block time, earlier messages in the block); every member computes the same result. | D11 |
| I3 | A finalized block contains a message on a protected topic only if its writer proof satisfies the policy revision active at that height. | D6, D7, D11 |
| I4 | No write-statement is accepted twice while its `notAfter` has not passed, whichever valid signatures or delegation accompany it. | D9 |
| I5 | Registry changes take effect only at `finalization height + lag (≥ 1)`; no change is retroactive. | D5, D13 |
| I6 | A namespace is created only with no existing ancestor (top level) or by its nearest ancestor's owners; two owners never control overlapping prefixes. | D3, D4 |
| I7 | Every registry record, revision and schema is under `~yano/topics/v1/` in the committed state root and is provable. | D2, D15 |
| I8 | Schema evaluation terminates within the IR step bound on any input up to `maxPayloadBytes`. | D10 |
| I9 | A delegation's scope is within its issuer's writable scope; its lifetime is ≤ `maxDelegationMs`; it is honoured only inside its window on block time. | D8 |
| I10 | Topic policies only add refusals; they never make a machine accept a message it would refuse. | D6, D11 |
| I11 | Every historical message can be re-verified against the revision active at its height. | D13 |
| I12 | `used/` and `revoked/` entries are removed after expiry; their size is bounded by rate × maximum lifetime. | D8, D9 |
| I13 | Block timestamps are monotonic and at most `max-forward-skew-ms` ahead of the voter's clock when voted. | D12 |
| I14 | Application machines cannot read-modify `~yano/topics/` keys. | D2 |
| I15 | On a protected topic every machine hook receives the verified payload as the body, with the original `messageId`; finalized blocks, message proofs and `ordered-log` records keep the framed bytes. | D7, D7a |

## 7. Compatibility

- **Pre-release.** App chains have not had a public release. There is no
  compatibility mode; v1 formats start at version 1.
- **Envelope and transport:** unchanged (D7).
- **Plugin API:** additive — `TopicMessageView` and the schema evaluator in
  core-api; this is a new API level (ADR-011). Machines that ignore it keep
  working, including on protected topics, because they receive the payload as
  the body (D7a).
- **Chain identity:** the effective topic configuration is committed at its
  activation height (D1); a member started with a different configuration
  fails closed on restart, like ADR-016.
- **Yano X:** the authenticated map switches to the core schema evaluator
  with the same IR bytes, so existing collection schemas keep their
  meaning (companion ADR).
- **Consensus profile:** gains `max-forward-skew-ms` (D12), a profile
  version bump.

## 8. Alternatives considered

| Alternative | Why not |
|---|---|
| **A. A new envelope field for client auth (envelope v3)** | Changes the yaci wire format and gossip codec in another repository; every relay path must upgrade; the frame achieves the same with no transport change. Kept as a future option if frames prove awkward. |
| **B. Leave authorization to each machine** (status quo) | Every plugin re-implements signatures, delegation and replay protection differently; `ordered-log` cannot do it at all; nothing is uniform or provable. |
| **C. A `schema-log` machine in Yano X** (discussed before this ADR) | Solves schemas for logs only; custom plugins and other machines get nothing; authorization still missing. |
| **D. Node-local configuration of policies** | Members would disagree; not provable; the exact problem ADR-008.3 removed for membership. |
| **E. Policies inside the immutable consensus profile** | Every change would need a new profile epoch through governance; namespace owners could not self-serve. The profile keeps only the chain-level switches. |
| **F. Register every end-user key in the registry** | Unbounded registry growth and a governance or owner transaction per user; delegations are signed off-chain and cost nothing until used. |
| **G. JSON with JSON Schema** | Non-deterministic number and key handling; no existing bounded evaluator; canonical CBOR with the existing IR is already proven in the authenticated map. |
| **H. A separate voting protocol for topics** | ADR-008.3 already provides threshold approval on the ledger; a second protocol doubles the attack surface. |

## 9. Consequences

**Positive**

- One mechanism gives every machine consensus write authorization, schemas
  and delegated end-user writes, provable and anchored.
- Plugins drop their own parsing and signature boilerplate.
- Namespaces make multi-tenant chains self-service, with governance as the
  safety net.
- Certified time becomes trustworthy within a bound (D12), which fixes a
  latent weakness for every time-based rule.

**Costs**

- More consensus surface in core: registry state, block-validity rules, a
  schema evaluator and signature checks.
- Ed25519 verification per protected message on every member.
- Client applications take on key management (owners, issuers, session keys).
- Topic names become an API that is hard to change once registered.

## 10. Implementation milestones

| ID | Scope | Entry gate | Exit criteria |
|---|---|---|---|
| M0 | D12 certified time bounds | ADR accepted | I13 tests; profile bump; existing suites green |
| M1 | Freeze formats: config, entry, command, frame, delegation, statement CDDL; codes; vectors | M0 | Normative CDDL and test vectors committed; independent decoder agrees (V2) |
| M2 | Registry state, owner and governance commands, activation, revisions, REST reads | M1 | I2, I5, I6, I7, I11, I14 tests; multi-node replay and restart parity |
| M3 | Writer proofs, delegations, replay guard, follower block validity | M2 | I3, I4, I9, I12 tests; adversarial vectors (V3) pass on a 3-node cluster |
| M4 | Schema evaluator in core-api; topic schemas | M1 | I8 tests; Yano X authenticated-map vectors pass unchanged against the core evaluator (V4) |
| M5 | Payload view for machine hooks (D7a), `TopicMessageView`, API level, `topic-policy-v1` proof subject | M3, M4 | I15 tests: a framed `balances` command and a framed composite command decode and apply correctly at admission and in apply; plugin compatibility tests; proofs verified offline |
| M6 | Qualification | M5 | I1 byte-identity with `mode: disabled`; a multi-node cluster with rotation, recovery, enabling on a running chain, restart and catch-up; performance budget for signature checks recorded |

## 11. Verification and test-vector strategy

- **V1 — Determinism.** Every check is replayed offline from finalized
  blocks with byte-identical outcomes and state roots on all members.
- **V2 — Independent encoding.** Frame, delegation, statement and command
  vectors are produced by an independent implementation (a small script
  using a separate CBOR library and Ed25519 implementation), never by the
  code under test, and checked against RFC 8032 §7.1 test vectors.
- **V3 — Adversarial cases.** Forged signature, wrong chain or topic in the
  statement, threshold not met, scope widening, expired and not-yet-valid
  delegations, revoked delegations, replay through a second member, replay
  in the same block, the same write-statement resubmitted with a different
  valid signature subset (A+B, then A+C under 2-of-3) or a different
  delegation, non-canonical CBOR, oversized payload, schema bombs,
  a malicious proposer including a bad message, a proposer with a skewed
  clock, conflicting owner commands in one block, sub-namespace creation by a
  non-owner.
- **V4 — Schema parity.** The existing authenticated-map schema vectors pass
  unchanged against the core evaluator.
- **V5 — Disabled parity.** A chain with `mode: disabled` produces the same
  roots as `main` for the existing suites (I1).

## 12. Production and audit gates

- An external review of D7–D9 (statement design, replay, delegation) before
  any chain enables protected topics outside development.
- Key-management guidance for owners and issuers (hardware or KMS for owner
  keys; short delegation lifetimes) published with M6.
- Governance recovery rehearsed on a multi-node cluster (lost-owner drill).

## 13. Risks

| Risk | Mitigation |
|---|---|
| A block-validity bug splits members | V1/V3 on clusters; the rule lives in one function shared by all three call sites |
| Owners lose keys | governance recovery (D13); guidance to use k-of-n owners |
| Time skew rejects honest proposals | conservative default skew; monitoring of rejected proposals |
| Clients mis-implement frames | reference client libraries and vectors (Yano X ADR) |
| Registry growth in open mode | `max-entries`; OQ7 quotas or deposits |

## 14. Open questions

| ID | Question | Options | Lean |
|---|---|---|---|
| OQ1 | Namespace separator | (a) `/` only; (b) `/` and `.`; (c) any registered prefix | (a): one rule, simple to reason about; dot topics remain valid exact entries |
| OQ2 | Lower bound on block time | (a) none, monotonic + forward skew only; (b) also `≥ localNow − skew` for fresh proposals | (a) for v1; revisit after measuring view-change latencies |
| OQ3 | Frame vs envelope v3 | (a) body frame (D7); (b) new envelope field in yaci | (a) |
| OQ4 | Should `ordered-log`'s per-message record include the verified writer key | (a) no, the frame in the body is enough; (b) yes, for cheaper queries | (a) for v1 |
| OQ5 | Delegated writes under k > 1 writer thresholds | (a) refuse (v1); (b) delegations carry k issuer signatures | (a) |
| OQ6 | Key schemes beyond Ed25519 (CIP-8 wallet signatures, secp256k1) | add later as new `scheme` values | later |
| OQ7 | Quotas and anti-spam for open claims and writes | (a) `max-entries` only; (b) per-namespace message quotas in consensus; (c) deposits | (a) now, (b) as a follow-up ADR |
| OQ8 | Default `max-delegation-ms` | 1 hour vs 24 hours | 1 hour |

## 15. Related findings (out of scope)

- **Unchecked block time** (fact 5) affects existing applications that use
  certified time; D12 fixes it, and could ship ahead of the rest.
- Member-edge controls (API keys, CORS) remain useful for DoS protection and
  are not replaced by topic policies.

## Revision history

- r1 (2026-10-22): initial proposal.
- r2 (2026-10-22; responds to the automated reviews of `81e9fcd3a` on #197 and of `4ac4a9d8` on
  yano-x#80): #197 F1 → D9 replay identity is the write-statement hash, independent of signatures
  and delegation; D2, D8, D11 code table and apply note, threat model, I4 and V3 updated; diagrams
  relabelled. yano-x#80 F1 (framed bodies reach typed-kernel codecs; a core concern) → new D7a:
  machine hooks receive the verified payload as the body with the original `messageId`; new I15;
  compatibility, M5 and V3 updated.
