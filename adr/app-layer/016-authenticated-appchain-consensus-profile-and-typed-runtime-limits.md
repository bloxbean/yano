# ADR-016: Authenticated App-Chain Consensus Profile and Typed Runtime Limits

## Status

Accepted and implemented — v1 contract and runtime guard complete

The number is local to the `adr/app-layer` series. Root-level ADR-016 is an
unrelated genesis-configuration refactor.

## Date

2026-07-17

## Parent and related decisions

- [ADR-005](005-yano-app-chain-framework.md) defines deterministic state
  application and the authenticated MPF root.
- [ADR-010](010-deterministic-effect-system.md) defines consensus-affecting
  effect settings and the `~fx/` reserved state namespace.
- [ADR-010.1](010.1-emission-versioning.md) defines the later height-activated
  upgrade process for effect consensus settings.
- [ADR-013](013-first-party-integration-connectors-and-effect-demo.md) and
  [ADR-013.2](013.2-deterministic-composite-state-machine.md) expose the
  concrete cross-limit validation currently duplicated by first-party
  machines.
- [ADR-014](014-appchain-adr013-external-review-readiness-and-feasibility-fable.md)
  identifies local/default drift in consensus limits; §7.1 requires typed
  values backed by an authenticated identity.
- [ADR-015](015-governed-composite-profile-evolution.md) consumes this decision
  but does not own the general framework contract.
- [App-layer open items](open_item.md) tracked delivery as `APP-007`; completed
  evidence is retained in this ADR and ADR-014.

## 0. In plain words

Several values decide whether a block is valid and what state root it produces:
the maximum messages and bytes in a block, whether sender sequences are
enforced, whether effects are enabled, how many effects may be emitted, and
how result/expiry commitments work.

Today the runtime parses those values, while some state machines parse selected
copies from a string map with their own defaults. Two members with different
defaults can accept different invariants and discover the mismatch only when a
block stalls.

This ADR creates one normalized, typed profile. The framework:

```text
node configuration
       |
       v
one normalized AppChainConsensusProfile
       |                         |
       |                         +--> typed read-only state-machine context
       v
canonical v1 bytes at ~yano/consensus-profile/v1
       |
       v
authenticated by every app-block state root
```

On a new chain, height 1 commits the exact profile bytes. On restart or replay,
the framework verifies those bytes before a plugin can participate. A local
configuration edit cannot silently change consensus behavior.

This is an authenticated **framework-owned deterministic-runtime profile**, not
a claim that arbitrary plugin settings, governance policy, or deployment
secrets are automatically authenticated.

## 1. Problem

`EffectsSettings` already parses the effect kernel's effective settings once,
but `CompositeStateMachine` and `EvidenceRegistryConfig` independently read
keys such as `effects.max-per-block`, payload bounds, expiry bounds, and result
windows from `AppStateMachineContext.settings()`. The evidence provider cannot
currently see `block.max-messages`, so the demo launcher separately enforces:

```text
block.max-messages * 2 <= effects.max-per-block
```

This creates three distinct hazards:

1. a first-party machine and the runtime can normalize different defaults;
2. two members can start with different node-local values and validate
   different deterministic invariants; and
3. a retained ledger does not authenticate the runtime values under which its
   root was produced.

A typed Java record alone fixes only the first hazard. Hashing the raw
configuration file is also insufficient: it includes order, aliases, secrets,
executor-local settings, and values that do not affect consensus. The contract
must commit the normalized effective subset.

## 2. Decision summary

1. Add the immutable public `AppChainConsensusProfile` value to `core-api`.
2. Add a compatible default method to `AppStateMachineContext`:

   ```java
   default Optional<AppChainConsensusProfile> consensusProfile() {
       return Optional.empty();
   }
   ```

   Existing plugin binaries and sources remain compatible. The normal Yano
   runtime always overrides it. First-party machines that need cross-limit
   validation require the value and fail startup if it is absent.
3. Construct the profile once from normalized `AppChainConfig` and
   `EffectsSettings`. State-machine providers and the effect kernel receive the
   same immutable instance.
4. Freeze a dependency-light public v1 commitment codec and proof key in
   `core-api`.
5. Reserve `~yano/` for framework-authenticated state. The v1 marker key is:

   ```text
   ~yano/consensus-profile/v1
   ```
6. At app height 1, the framework writes the canonical profile value before
   effect callbacks and ordinary machine application. At every later height it
   verifies byte equality before invoking application code.
7. At startup with a nonzero retained tip, the runtime proves/reads the marker
   against the committed root and fails before networking/consensus if it is
   absent, malformed, or different.
8. Prototype histories are disposable. This corrected profile becomes the v1
   baseline; no compatibility mode silently adopts a marker into an old
   nonempty history.
9. Future changes use explicit height-indexed framework epochs under
   ADR-010.1. Editing local configuration never mutates the active profile.

## 3. Effective v1 profile

The v1 record contains the normalized values below.

| Field | Type | Why it is committed |
|---|---|---|
| `schemaVersion` | fixed `1` | Codec/semantic identity. |
| `maxMessageBytes` | positive int | Finalized message structural validity. |
| `maxBlockMessages` | positive int | Proposal/finalized block validity and verification work. |
| `maxBlockBytes` | positive long | Proposal/catch-up wire and block validity bound. |
| `l1StabilityDepth` | nonnegative int | Whether/how stable L1 references are required and checked. |
| `enforceSenderSeq` | boolean | Changes follower validity for sender sequences. |
| `effectsEnabled` | boolean | Changes whether emission is allowed and whether expiry/result interpretation runs. |
| `effectsMaxPerBlock` | int | Deterministic emission cap. Zero only in the normalized disabled profile. |
| `effectsMaxPayloadBytes` | int | Deterministic effect payload cap. Zero only when disabled. |
| `effectsMaxExpiryBlocks` | long | Deterministic intent validity bound. Zero only when disabled. |
| `effectsResultWindowBlocks` | long | Deterministic result incorporation window. Zero only when disabled. |
| `effectsDefaultGate` | explicit byte enum | Changes the normalized default finality gate. |
| `effectsOutcomeCommitment` | explicit byte enum | Chooses per-effect versus per-block authenticated outcome leaves. |
| `effectsStrictReservedPrefix` | boolean | Changes which application writes are rejected. It remains effective when effects are disabled. |
| `effectResultSigners` | sorted list of 32-byte keys | Changes which member-attested results are incorporated. Empty means any active member. |

When effects are disabled, the effective profile canonicalizes unused effect
limits to zero, the gate to `APP_FINAL`, the outcome commitment to
`PER_EFFECT`, and result signers to empty. Raw ignored settings cannot create a
second identity for identical behavior.

Result signer keys are lowercase-decoded, exactly 32 bytes, unique, sorted by
unsigned byte order, bounded by `AppChainConfig.MAX_MEMBERS`, and required to
belong to the configured genesis membership in v1. Governed rotation/recovery
is deliberately deferred to the effect-policy epoch ADR tracked by `APP-006`.

### 3.1 Explicit exclusions

The v1 marker does not commit:

- signing private keys, API keys, credentials, wallet material, paths, URLs, or
  executor target configuration;
- pool capacity, local retry/backoff, threads, metrics, logging, UI, sinks, or
  retention implementation settings;
- `defaultTtlSeconds` and admission-only `maxTtlSeconds`, which do not change
  follower application of a finalized block;
- arbitrary `pluginSettings`; or
- complete membership, sequencer, anchor, or application-schema evolution.

Membership epochs, finality certificates, composite/application markers, and
their owning ADRs remain authoritative for those domains. A future broader
chain manifest may bind their digests together, but this ADR must not market a
partial profile as a complete deployment identity.

## 4. Canonical encoding and digest

The marker value is fixed-width big-endian binary plus a bounded signer tail;
it is not Java serialization, JSON, or CBOR.

```text
uint32  schemaVersion (=1)
uint32  maxMessageBytes
uint32  maxBlockMessages
uint64  maxBlockBytes
uint32  l1StabilityDepth
uint8   flags
uint32  effectsMaxPerBlock
uint32  effectsMaxPayloadBytes
uint64  effectsMaxExpiryBlocks
uint64  effectsResultWindowBlocks
uint8   effectsDefaultGate
uint8   effectsOutcomeCommitment
uint16  resultSignerCount
bytes32 resultSigners[resultSignerCount]
```

Flag bits are frozen:

- bit 0: `enforceSenderSeq`;
- bit 1: `effectsEnabled`;
- bit 2: `effectsStrictReservedPrefix`; and
- bits 3–7: zero and rejected on decode.

Gate codes are `0=APP_FINAL`, `1=L1_ANCHORED`, `2=ZK_SETTLED`.
Outcome codes are `0=PER_EFFECT`, `1=PER_BLOCK`. Counts, integer ranges,
canonical disabled values, signer order, and trailing bytes are validated on
decode and re-encode.

The display/status identity is:

```text
SHA-256("yano-app-chain-consensus-profile-v1\0" || canonicalProfileBytes)
```

The authenticated marker stores the canonical bytes themselves rather than
only the digest. A proof consumer can decode the exact limits and independently
recompute the digest. The value is at most 1,077 bytes for the v1 membership
bound.

The public contract exposes cloned key/byte arrays and immutable signer values;
callers cannot mutate the profile after construction.

## 5. Runtime integration

### 5.1 Construction

The runtime performs this order once per chain generation:

1. validate and normalize `AppChainConfig`;
2. parse/validate `EffectsSettings`;
3. create one `AppChainConsensusProfile`;
4. pass that instance through `AppStateMachineContext`;
5. construct the state machine; and
6. give the same instance to the deterministic kernel/profile guard.

No first-party provider reparses a framework key from `settings()`.

### 5.2 Startup

- Tip `0`: the marker must not already exist. Height 1 will create it.
- Tip `>0`: read `~yano/consensus-profile/v1` against the committed state root,
  decode canonically, and compare bytes to the effective local profile.
- Missing, malformed, or mismatching data fails chain construction before peer
  links, consensus scheduling, effect execution, or sinks start.

Error output includes chain id, tip, expected/observed digest, and the names of
different non-secret fields. It never prints private configuration or raw
credential-bearing settings.

### 5.3 Deterministic application

The kernel owns the transition guard:

1. at height 1, require the marker absent and write the exact bytes;
2. above height 1, require byte equality before interpreting results, sweeping
   expiry, calling `onEffectResult`, or calling `apply`;
3. reject application `put`/`delete` under the reserved `~yano/` prefix on
   every chain, independent of `effects.strict-reserved-prefix`; and
4. commit marker and application/effect writes in the existing atomic MPF
   `WriteBatch`.

The proposer and every follower execute the same guard. A mismatch cannot be a
deterministic no-op: it is a node/configuration incompatibility and must fail
before participation; if reached during replay it fails loudly rather than
creating a plausible different root.

Snapshots, state proofs, late join, and full replay need no separate storage
mechanism because the marker is an ordinary authenticated MPF leaf in a
framework-reserved namespace.

## 6. State-machine API and compatibility

`AppStateMachineContext.consensusProfile()` is a default method returning
empty, preserving existing source and binary plugins. The normal runtime
overrides it for all provider-created machines.

First-party evidence and composite providers call a `require` helper and derive
all cross-limit checks from the typed record. Direct unit/conformance callers
must supply a profile when testing those providers. Older independent plugins
that ignore the new method continue to load; the framework marker still
protects runtime consensus settings around them.

Library callers that inject an already-created `AppStateMachine` do not receive
a construction context. The framework still authenticates/enforces the
profile. A library machine that needs the typed limits must be constructed with
them by its caller or migrated to an `AppStateMachineProvider`; this ADR does
not add mutable runtime access to the transition API.

The plugin TCCL facade already passes the host context through to the provider.
Its boundary tests must prove the new accessor remains visible under the plugin
classloader and returns the host-owned immutable value rather than a copied
string-map interpretation.

## 7. Cross-limit rules retired from launchers

After this ADR, the evidence provider validates at construction:

```text
effectsEnabled
effectsMaxPerBlock >= 2
block.max-messages * 2 <= effects.max-per-block
effectsMaxPayloadBytes >= the frozen storage/notification envelope bounds
configured expiry <= effectsMaxExpiryBlocks
configured expiry <= effectsResultWindowBlocks
required outcome commitment == PER_EFFECT
```

The composite provider uses `effectsMaxPerBlock` from the same record for its
reserved quota budget. Demo launchers may repeat these checks for friendly
diagnostics, but they are not the authority and tests must not depend on them
for correctness.

## 8. Security and failure model

- Authentication detects local drift; it does not make a malicious member's
  external effect attestation true.
- The marker contains no secrets or executor-local endpoints.
- Exact canonical bytes avoid configuration-order, alias, locale, or default
  ambiguity.
- The reserved prefix prevents a plugin from forging or deleting the marker.
- A corrupt retained marker fails closed with bounded diagnostics.
- Result signer validation prevents malformed/unbounded key lists and the
  obvious v1 configuration that designates a nonmember unable to attest.
- The marker does not repair a membership/sequencer/application identity gap;
  those remain separately tracked and must not be implied by UI wording.

## 9. Alternatives considered

### A. Typed context only

Rejected. It removes duplicate parsing but leaves two nodes free to expose
different typed values.

### B. Hash the complete property map

Rejected. Raw maps contain node-local values and secrets, accept aliases/order
differences, and do not represent normalized behavior.

### C. Put the profile digest in every app-block header

Rejected for v1. It changes the block wire/hash contract when an authenticated
state leaf provides replay, snapshot, and proof binding without that change.

### D. Let each state machine own its own copy

Rejected. Runtime block/effect validity exists outside the machine and copies
would recreate the drift this ADR removes. Machine-specific settings still use
their own authenticated configuration identity in addition to this marker.

### E. Auto-adopt the marker into retained histories

Rejected. A local startup write outside a finalized block would not be
consensus history. Prototype histories are disposable, so corrected v1 chains
start fresh.

## 10. Implementation plan

### Phase 16.0 — Freeze contract

- Add the public immutable record, explicit enums, marker key, codec, digest,
  canonical decoder, and golden vectors.
- Freeze field/range/default/signer validation and reserved-prefix rules.
- Add the compatible context accessor and facade forwarding tests.

### Phase 16.1 — Runtime guard

- Build one profile from `AppChainConfig` and `EffectsSettings`.
- Validate result signers and use the normalized profile in the effect kernel.
- Add startup committed-root verification.
- Add height-1 write/later-height equality checks and reserve `~yano/`.
- Expose bounded status/health fields and digest.

### Phase 16.2 — First-party consumers

- Remove framework-setting parsing/defaults from composite and evidence
  providers.
- Move cross-limit authority from demo launchers into the providers.
- Update conformance fixtures, plugin facades, scaffolds, and documentation.

### Phase 16.3 — Closure

- Run unit, property/boundary, replay, restart, snapshot, late-join, proof,
  plugin binary-compatibility, JVM/native packaging, and multi-member tests.
- Start a fresh demo chain because pre-profile histories are unsupported.
- Record the v1 golden key/value/digest and acceptance evidence here.

## 11. Acceptance criteria

ADR-016 may move to Accepted/Implemented only when:

1. one normalized profile instance feeds both runtime/kernel and provider
   context;
2. every field and enum has fixed canonical bytes, bounds, and golden vectors;
3. disabled-effect configurations with irrelevant raw values produce identical
   canonical bytes;
4. result signers are exact, unique, sorted, bounded member keys;
5. height 1 writes the marker atomically before machine transitions;
6. later apply, restart, catch-up, replay, and snapshot restore require the
   exact authenticated marker;
7. absent, malformed, tampered, locally drifted, and noncanonical profiles fail
   before participation with bounded non-secret diagnostics;
8. a state machine cannot put/delete any `~yano/` key;
9. old plugin binaries that ignore the context accessor still load and run;
10. first-party composite/evidence providers fail without the typed profile and
    never parse/default the replaced framework settings;
11. the evidence two-effects-per-message and composite quota invariants are
    enforced by providers, not only demo tooling;
12. proof clients can verify and decode the marker against a committed root;
13. three nodes with identical profiles produce identical roots, while a
    drifted node refuses to participate before block processing;
14. JVM and native plugin/runtime packaging tests pass; and
15. ADR-014 P0.2 and the completed tracker item APP-007 link the implementation
    and evidence.

## 12. Consequences

### Positive

- Runtime and first-party machines share one typed source of truth.
- Configuration drift is visible at startup rather than at the first affected
  block.
- A root/proof authenticates the effective framework limits.
- ADR-015 can govern composite profiles without reparsing framework settings.
- Existing external plugins retain source/binary compatibility.

### Costs and limitations

- Every new chain gains one authenticated leaf and a new reserved namespace.
- Current prototype ledgers must be deleted/rebuilt; there is no silent
  migration.
- The v1 profile is intentionally narrower than a complete chain manifest.
- Changing a committed value later requires a framework profile epoch, not a
  configuration edit.

## 13. Decision record

| ID | Decision |
|---|---|
| 016-D1 | Commit normalized effective values, never raw configuration. |
| 016-D2 | Expose the same immutable profile through a compatible context default method. |
| 016-D3 | Store canonical bytes at `~yano/consensus-profile/v1` in authenticated MPF state. |
| 016-D4 | Write at height 1 and verify at startup plus every later transition. |
| 016-D5 | Reserve `~yano/` independently of effect enablement/legacy prefix policy. |
| 016-D6 | Keep machine/plugin configuration identities separate and explicit. |
| 016-D7 | Treat corrected fresh histories as v1; do not auto-adopt retained state. |
| 016-D8 | Use ADR-010.1-style epochs for future committed-value changes. |

## 14. Implementation and verification evidence

The delivered v1 contract is `AppChainConsensusProfile` plus
`AppChainConsensusProfileCommitment` in `core-api`. The runtime creates one
normalized instance from `AppChainConfig` and `EffectsSettings`, supplies that
same instance through `AppStateMachineContext`, and enforces its authenticated
marker through `ConsensusProfileGuard` before application/effect transitions.
First-party composite and evidence providers consume the typed profile and no
longer own duplicate framework defaults.

The frozen public fixture is:

```text
key    = ~yano/consensus-profile/v1
digest = fe3f0092d18c6f95e662f558084b56dac8350516c88c9e6ab0fcf51343d00f95
```

The digest is for the complete golden profile in
`AppChainConsensusProfileCommitmentTest`; it is a codec fixture, not a universal
deployment profile id.

Verification completed on 2026-07-17:

- canonical key/bytes/digest, enum/flag/range/signer ordering, disabled-profile
  normalization, immutable array/list behavior, and context default-method
  compatibility tests;
- height-1 atomic marker creation, later equality checks, retained-state drift,
  missing/malformed marker, and reserved `~yano/` write rejection tests;
- result-signer membership validation and proof/decode coverage;
- first-party evidence/composite cross-limit and quota validation from the
  shared typed value;
- full `core-api`, `runtime`, composite, stdlib, evidence, and application test
  suites, including three-member root agreement, restart, replay, and snapshot
  paths; and
- JVM plugin packaging, release-contract, Compose/host parity, and retained
  deployment restart gates.

Future changes to any committed field remain governed by the height-indexed
framework-profile epoch work tracked as `FX-001`; a local configuration edit is
not an upgrade protocol.
