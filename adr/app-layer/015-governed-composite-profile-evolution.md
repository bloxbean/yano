# ADR-015: Governed Composite Profile Evolution

## Status

Accepted and implemented — governed profile epoch v1

The number is local to the `adr/app-layer` series. Root-level ADR-015 is an
unrelated preview-network feasibility document.

## Date

2026-07-17

## Parent and related decisions

- [ADR-013.2](013.2-deterministic-composite-state-machine.md) defines the
  implemented, genesis-fixed composite profile and its deterministic routing,
  namespace, quota, query, proof, workflow, and effect-ownership rules.
- [ADR-010.1](010.1-emission-versioning.md) defines replay-stable, height-based
  activation and the rule that an operator configuration edit is not an
  upgrade protocol.
- [ADR-008.3](008.3-chain-governed-membership.md) supplies the existing
  threshold-approval and activation-lag pattern.
- [ADR-014](014-appchain-adr013-external-review-readiness-and-feasibility-fable.md)
  identifies the genesis-frozen profile as a material long-lived-chain limit.
- [App-layer open items](open_item.md) is the live implementation tracker.

## 0. In plain words

ADR-013.2's fixed mode decides a composite app chain's complete component
arrangement at genesis. That remains safe and replayable, but adding a
component, changing a quota, or introducing an unplanned compatible generation
requires a new chain.

This ADR keeps the safety and removes that operational limitation:

```text
current authenticated profile
          |
          v
members submit one exact target profile
          |
          v
threshold approval + every member declares the exact code ready
          |
          v
future activation height H is committed on the chain
          |
          v
height < H uses the old profile; height >= H uses the new profile
```

The deployment is deliberately **deploy first, activate second**. Operators
place a bundle containing the dormant target implementation on every member,
restart it, and ask each node to attest readiness. The chain changes only after
the exact target bytes, authorization, readiness, and height are finalized.

This is governed profile evolution, not Java hot reload. A local YAML edit,
new JAR, or API call cannot change consensus behavior by itself.

## 1. Context and problem

ADR-013.2 already has most of the foundations needed for safe evolution:

- canonical profile bytes and a domain-separated profile digest;
- absolute component/workflow activation intervals;
- immutable component-generation identities;
- a state-and-result compatibility identity for replacements;
- exact effect-owner links back to the emitting generation;
- deterministic route, namespace, workflow, and quota validation; and
- an authenticated `~composite/profile/v1` marker checked on startup and every
  transition.

Its v1 safety boundary is intentionally stronger than its operability:

- the entire profile schedule is fixed at genesis;
- every generation that has started reserves quota forever;
- a proof client pins one profile digest for the chain lifetime; and
- installed code may be a dormant superset, but the chain cannot authorize a
  new effective subset.

Forecasting the complete lifetime schedule at genesis is not realistic for a
long-lived DPP, evidence, registry, or oracle chain. Free-form runtime mutation
would be worse: members could select different component code or activation
heights and halt at the first divergent state root.

The required change is therefore not mutability. It is a deterministic,
authenticated, threshold-authorized sequence of immutable **profile epochs**.

## 2. Decision summary

Yano will add an explicit governed mode for composite profiles with these
properties:

1. Each active profile is one immutable, append-only profile epoch.
2. A proposal contains the exact canonical target profile bytes, base digest,
   membership-epoch digest, and future activation height.
3. The current membership threshold authorizes the proposal. Authorization and
   code readiness are separate facts.
4. Every member in the proposal's bound membership epoch must publish a signed
   readiness declaration for the exact proposal and profile digest.
5. Activation occurs only at the committed app-chain height. Missing approval,
   readiness, code, or an unchanged membership epoch makes the proposal void;
   it never falls back to a local choice.
6. Every node has a deterministic catalog mapping an approved profile digest
   to the exact component and workflow products that execute it.
7. The active marker remains at `~composite/profile/v1` for proof compatibility;
   authenticated epoch records preserve the full profile history.
8. Effect results continue to route to the exact emitting component generation,
   even after that generation leaves the active profile.
9. Retired-generation quota remains reserved for a bounded result-drain window,
   then is released deterministically.
10. Incompatible state-schema migration is not hidden inside profile activation.
    It requires a separately specified deterministic migration workflow or a
    new component namespace.

ADR-013.2 fixed mode remains valid for chains that intentionally never evolve.
Governed mode is the recommended mode for new long-lived composite chains.

## 3. Goals and non-goals

### 3.1 Goals

- Add, retire, or rearrange compatible component/workflow generations without
  starting a new chain.
- Change committed profile configuration identities and quota allocations under
  deterministic validation.
- Make approval, readiness, activation, replay, restart, catch-up, snapshots,
  queries, and proofs select the same profile epoch on every member.
- Fail before activation when one member lacks the target code.
- Keep result callbacks and continuation effects correct across an epoch
  boundary.
- Give operators a bounded, inspectable proposal and readiness workflow.
- Let strict proof clients pin one epoch while governance-aware clients follow
  an authenticated epoch chain.

### 3.2 Non-goals

- Arbitrary plugin/JAR hot loading or unloading inside a running JVM.
- Dynamically discovering independent component plugins and composing them by
  YAML. The selected composite bundle still owns its complete executable
  catalog, as decided by ADR-013.2.
- Local administrator override of the effective profile.
- Executing network, filesystem, database, or other external I/O during a
  profile transition.
- A generic bulk state scanner or unbounded one-block migration facility.
- Automatically proving that a readiness declaration is truthful. It is a
  signed operational attestation; a Byzantine member can lie and then halt its
  own node.
- Changing the app-block wire format or the effect record format.
- Solving result-signer rotation, executor quorum, or external-execution truth.
  Those are separate effect-governance concerns tracked in `open_item.md`.

## 4. Safety invariants

The implementation MUST preserve all of the following:

1. **Chain state is the authority.** Local config and catalog contents can make
   a node ready or unready; they cannot select an active profile.
2. **One profile per height.** Profile selection is a pure function of finalized
   history and `block.height()`.
3. **Block-start selection.** Result callbacks, expiry callbacks, workflows,
   and ordinary messages in one block all use the same active profile epoch.
4. **Exact bytes.** Members authorize and attest the digest of the complete
   canonical target profile, not a preset name or an open-ended version range.
5. **No discovery-order decisions.** Executable catalog entries are keyed by
   digest and validated before the state machine becomes available.
6. **No silent migration.** A reused component namespace keeps its committed
   state-and-result compatibility identity.
7. **Exact callback ownership.** Profile evolution never reroutes an existing
   CHAIN result to a newer generation merely because it has the same component
   id.
8. **Atomic transition.** The active marker, epoch record, component writes,
   effect writes, and block/state-root commit succeed or fail together.
9. **Fail closed.** Unknown profile schemas, malformed governance commands,
   unsupported catalogs, quota overflow, stale bases, or ambiguous routes
   cannot activate.
10. **Replay independence.** Wall clock, file timestamps, JAR order, operator
    timing, and network arrival order never select an epoch.

## 5. Profile epochs and authenticated state

### 5.1 Epoch identity

A profile epoch is logically:

```text
ProfileEpochV1 = [
  schemaVersion,             ; 1
  epochNumber,               ; monotonically increasing uint64
  fromHeight,                ; exact inclusive app height
  previousProfileDigest,     ; 32 bytes, zero only for epoch 0
  canonicalProfileBytes,     ; existing ADR-013.2 profile encoding
  proposalHash               ; 32 bytes, zero only for epoch 0
]
```

Its digest remains the existing ADR-013.2 digest:

```text
SHA-256("yano-composite-profile-v1" || canonicalProfileBytes)
```

Epoch numbering is audit metadata; `fromHeight` selects execution. The target
profile's own absolute component/workflow intervals must be valid at that exact
activation height.

The proposal hash is domain- and chain-bound over the complete sealed intent:

```text
SHA-256(
  "yano-composite-profile-proposal-v1" ||
  canonicalChainId || proposalId || baseProfileDigest || membershipEpochDigest ||
  targetProfileDigest || activationHeight || expiryHeight
)
```

The frozen codec also commits total length/chunk count so a different staging
shape cannot alias the same proposal. Exact length encodings are fixed with the
CDDL/golden vectors in phase 15.0.

### 5.2 State keys

The state machine commits:

```text
~composite/profile/v1
    -> active canonicalProfileBytes

~composite/profile-epoch/v1/<epoch-number>
    -> canonical ProfileEpochV1

~composite/profile-governance/v1/active
    -> bounded non-terminal proposal state, or absent
```

The current marker key is deliberately unchanged. Existing MPF proof tooling
can still prove the effective profile at a state root. Epoch records are
append-only and retain the full bytes so snapshot, audit, and historical trust
logic do not depend on an external artifact registry.

Governance staging keys are authenticated because their contents decide future
consensus behavior. Terminal proposal detail may be compacted to a bounded
receipt after its epoch record commits; the finalized governance messages and
epoch record remain the audit history.

### 5.3 Epoch zero

New governed chains write epoch 0 and the active marker at height 1. Epoch 0 is
the genesis-configured profile; it has `fromHeight = 1`, a zero previous digest,
and a zero proposal hash.

Fixed-mode chains keep the existing single marker and do not interpret profile
governance commands.

Yano is still pre-release and prototype app-chain histories are disposable.
The first implementation therefore does not need an unsafe automatic conversion
of retained fixed-mode databases. A retained database whose identity says
`fixed` cannot be reopened as `governed`; operators create a fresh chain or use
a future, separately tested migration tool. This is a deliberate pre-release
simplification, not permission to change mode after a chain is released.

## 6. Executable profile catalog

The selected composite provider constructs one immutable
`CompositeProfileCatalog` before participating in consensus. Each catalog entry
contains:

- the exact canonical profile bytes and digest;
- the `CompositeProfile` model;
- the exact ordered component products;
- the exact sorted workflow products; and
- a deterministic capability summary used only for diagnostics/readiness.

V1 packages between 1 and 64 distinct canonical profiles. The separate
`max-epochs` setting bounds authenticated activations (including reuse of a
previously packaged profile); it is not a promise that one bundle can carry that
many distinct executable profiles. Every profile digest present in retained
epoch history remains in the bundle catalog. Catalog-code pruning at a replay
floor is deferred because v1 does not authenticate such a floor.

Construction runs all existing ADR-013.2 validations and additionally rejects:

- duplicate profile digests with unequal products;
- one component generation represented by unequal descriptors/products across
  catalog entries;
- an entry whose canonical bytes do not round-trip exactly;
- unsupported profile schema versions;
- a profile whose reserved effect quota exceeds the framework's typed cap; and
- missing retained generations required by an active or scheduled epoch.

`CompositeProfileCodec` gains a strict decoder and canonical re-encoder check;
governance never accepts bytes that only a bundle-specific parser understands.
Every unique component/workflow product needed by an active, scheduled, or
locally attestable catalog entry is initialized/dry-checked once at node start.
Activation itself does not run a new lifecycle callback.

The catalog can be a dormant superset. Adding a JAR/catalog entry changes what
the node can attest as ready, but not what it executes.

V1 continues the ADR-013.2 bundle boundary: a custom provider assembles and
exports its catalog in Java. This preserves consensus-reviewable ordering and
native-image packaging. A future host-owned component-contribution SPI would
need its own selection, lifecycle, dependency, native-image, and governance
design.

## 7. Governance protocol

### 7.1 Reserved topic and ingress

Commands use the protected topic:

```text
~governance/composite-profile
```

The ordinary public submission route continues to reject reserved topics. A
dedicated privileged API/CLI constructs and submits member-signed commands.
Every node verifies the normal app-message signature before the command can be
sequenced.

### 7.2 Bounded proposal lifecycle

Only one non-terminal proposal is allowed per chain in v1:

```text
STAGING -> SEALED -> AUTHORIZED_AND_READY -> SCHEDULED -> ACTIVATED
     \          \               \              \
      +----------+---------------+---------------> VOID/EXPIRED
```

The protocol uses bounded commands:

1. `BEGIN` commits proposal metadata: proposal id, base profile digest,
   membership-epoch digest, target digest, total byte count, chunk count,
   activation height, and expiry height.
2. `CHUNK` stores one indexed profile-byte chunk. Only the `BEGIN` author may
   stage chunks; duplicate identical chunks are no-ops and conflicts void the
   proposal.
3. `SEAL` assembles the bounded chunks, verifies length/digest/canonical decode,
   runs profile compatibility validation, and freezes the proposal hash.
4. `APPROVE` echoes the exact proposal hash. Distinct eligible member signatures
   count toward the bound membership epoch's threshold.
5. `READY` echoes the exact proposal hash and target digest after the local node
   has matched and dry-validated its catalog entry.
6. `CANCEL` requires the same threshold as approval; unilateral cancellation is
   not allowed after members have relied on a proposal.

The frozen command CDDL and golden vectors are an implementation-phase gate,
not an invitation to use generic CBOR maps. Commands use fixed arrays, canonical
integers/bytes, iterative nesting/item preflight, and reject trailing data.

Initial bounds are consensus constants recorded in the chain identity:

- at most one active proposal;
- at most 64 KiB of target profile bytes;
- at most 8 chunks, each at most 16 KiB;
- at most one approval and one readiness declaration per member;
- a finite proposal lifetime in app blocks; and
- a finite v1 epoch-history bound (initially 1,024 epochs); and
- an activation height at least the configured minimum lag after `SEAL`.

Chunking avoids making the protocol depend on a deployment's ordinary
64-KiB message limit while keeping authenticated staging bounded. The epoch
bound and one-active-proposal rule also bound authenticated-state growth; a
future epoch-record compaction/version must be governed before a chain reaches
that limit.

### 7.3 Authorization is not readiness

`APPROVE` means:

> This member authorizes this exact semantic profile change.

`READY` means:

> This member's node has the exact catalog entry and passed local construction,
> compatibility, and dry-start checks for this proposal.

The proposal schedules only after:

- the base digest still equals the active profile digest;
- approvals from at least the bound membership threshold are committed;
- readiness from every member in the bound membership epoch is committed;
- the activation height and proposal lifetime are still valid; and
- all deterministic target/profile/quota checks pass.

All-member readiness intentionally favors safe rolling deployment over upgrade
availability. If one member is permanently unavailable, members remove it using
ADR-008.3 before proposing the profile. A lower readiness quorum would knowingly
activate a state machine on members that cannot execute it.

A readiness declaration is attributable, not remotely attested. If a malicious
member lies, its node can halt at activation. The other members may still
finalize if the remaining finality threshold is available; operations surface
the false attestation.

### 7.4 Membership changes do not race profile changes

`BEGIN` binds the current canonical membership-epoch digest. If a membership
change is scheduled to become effective on or before the target profile height,
or the bound epoch otherwise changes before activation, the proposal becomes
void.

Operators therefore perform membership and profile evolution sequentially:

1. finalize and activate a membership change;
2. deploy the target composite catalog to that membership;
3. propose and approve the profile against the new membership epoch.

This removes ambiguous cross-governance ordering and prevents a newly added,
unready member from appearing at the activation boundary.

## 8. Activation semantics

For a scheduled proposal at height `H`:

```text
blocks 1 .. H-1  -> previous profile epoch
block H onward   -> target profile epoch
```

The proposal must be `SCHEDULED` in committed state before block `H` begins.
Reaching the approval/readiness threshold inside block `H` is too late and the
proposal expires/voids; activation never depends on within-block message order.

The composite resolves `profileAt(block.height())` once at block start. Because
the current `FxKernel` invokes result callbacks before ordinary `apply()`, both
entry points call one idempotent internal `ensureProfileForHeight(...)` method.
At the first transition entry for height `H`, it:

1. verifies the scheduled proposal and epoch chain;
2. updates `~composite/profile/v1` to the target bytes;
3. appends the epoch record if absent; and
4. selects the catalog entry used by callbacks, workflows, and normal messages.

Those writes share the block's existing atomic state/effect batch. A block with
no effect callbacks reaches the same path from `apply()`. A retry sees identical
staged/committed state and produces identical writes.

Governance commands in block `h` are processed with the profile active at the
start of `h`; they can schedule only a future epoch. They never change routing
halfway through their own block.

## 9. Allowed evolution and compatibility

### 9.1 Changes supported by this ADR

Subject to existing validation, a governed target may:

- add a new component id with an empty isolated namespace;
- add a compatible generation of an existing component id;
- retire a component or workflow route at a future height;
- add, remove, or rearrange workflows and query aliases;
- close the stock direct evidence route in a stricter profile;
- change configuration identities whose semantics are defined as compatible;
- redistribute component/workflow effect quota within the framework cap; and
- add new versioned topics/query paths without reinterpreting old wire bytes.

Existing generation descriptors are immutable. Reusing a component id across
generations still requires the same `stateAndResultCompatibilityId`. Reusing a
topic across generations still requires identical admission semantics; changed
wire or authorization rules use a new versioned topic.

### 9.2 Changes requiring explicit migration design

This ADR does not invent a generic migration language. A component with an
incompatible state or result-callback schema must use one of:

- a new component id/namespace, starting empty;
- a bounded, application-specific deterministic migration workflow that has
  completed before activation; or
- a new chain with an externally audited migration.

A proposal may commit a migration-precondition key and expected value hash, but
profile activation only checks that deterministic commitment. It does not run
unbounded migration code. The concrete precondition encoding is frozen only
when a real first migration consumer exists.

## 10. Effects and quota across profile epochs

Effect ownership remains the exact ADR-013.2 record:

```text
~composite/effect-owner/v1/<effect-id-hash>
    -> exact component-generation identity
```

The catalog retains every generation that an ownership link can reference.
`onEffectResult()` looks up the owner generation across retained catalog entries,
not only the active profile. A result never selects a component by current topic
or component id.

The fixed profile permanently reserves every started generation's quota. In
governed mode, a generation removed at height `H` remains callback-retained and
quota-reserved through:

```text
H + effects.result-window-blocks
```

This is conservative and deterministic: ADR-010 requires every CHAIN effect to
expire within that window, so an effect emitted before `H` cannot validly invoke
the generation later. After the drain height, the current quota calculation
releases the reservation automatically. Historical replay still reconstructs
the reservation at historical heights from the epoch records.

V1 retains executable products for every profile in authenticated epoch history,
even after effect quota drains. A future replay-floor protocol may permit safe
catalog-code pruning, but a node-local or snapshot-local floor is insufficient.

The implementation MUST stop reparsing `effects.max-per-block` with a duplicate
composite default. The framework supplies a typed, immutable consensus limit to
the catalog/profile validator so the runtime and composite cannot drift.

## 11. Queries, proofs, clients, and snapshots

### 11.1 Queries and operations

The composite query surface adds bounded reads for:

- active epoch number, height, bytes, and digest;
- scheduled proposal/activation summary;
- per-member approval and readiness state;
- retained generation/drain heights; and
- one current or exact-number historical epoch record per bounded query.

The status/UI surface displays the same data from cached/committed snapshots; it
does not call plugin code during a metrics scrape.

### 11.2 Proof trust modes

Existing strict clients may continue to require one explicitly allowed profile
digest for the queried root. Governance-aware clients may instead pin:

- the genesis epoch/profile digest;
- the governance and membership policy; and
- a finalized, contiguous epoch chain ending at the proven active marker.

An active-profile MPF proof alone proves which bytes were committed at a root.
It does not tell a client whether its own trust policy accepts a governance
change. Client APIs must make this distinction explicit rather than silently
trusting any new digest.

The dependency-free `CompositeProfileEpochChainVerifier` only checks the
structure of caller-supplied, already-proven bytes. A governance-aware client
must first verify finality and MPF inclusion of the current epoch pointer, all
epoch records, and the active marker at one state root, then independently
verify the applicable membership/approval policy from finalized block history.
The structural verifier does not prove authorization.

Historical queries/proofs resolve the profile epoch active at their committed
height. Query aliases and physical component keys are interpreted using that
epoch, not the node's latest profile.

### 11.3 Snapshot and restart

A snapshot/restored node verifies:

- the active marker and epoch record agree;
- the epoch chain is contiguous and digest-linked;
- any scheduled target exists in its catalog;
- every callback-retained generation exists; and
- its chain identity has the same fixed/governed mode and governance limits.

The node fails before network participation if any check fails. V1 snapshots
retain authenticated epoch records and require executable catalog entries for
every historical profile, plus every callback-retained product.

## 12. Failure behavior and recovery

| Condition | Deterministic behavior | Recovery |
|---|---|---|
| Target profile missing locally | Node cannot submit `READY`; startup/status names the missing digest | Deploy correct bundle and restart |
| Malformed/non-canonical proposal | Command is rejected/no-op; never staged | Submit corrected proposal |
| Stale base digest | Proposal becomes void | Rebase on current epoch |
| Membership epoch changes before activation | Proposal becomes void | Wait for membership activation and repropose |
| Approval threshold not reached | Proposal expires without scheduling | Repropose if still wanted |
| One member not ready | Proposal does not schedule | Fix/remove member, then repropose |
| Member lies about readiness | That node fails closed at activation; attestation is attributable | Restore correct bundle and catch up |
| Route/quota/compatibility invalid | `SEAL` fails deterministically | Build a valid target profile |
| Node misses deployment but never claimed ready | No activation can occur | Deploy and attest |
| Local config/JAR changes active profile | Startup mismatch/fail closed | Restore chain-compatible deployment |
| Bad profile activates despite all checks | Finalized correction must be a new future epoch | No local rollback or profile break-glass |

There is no local break-glass profile override. If the membership loses enough
keys to govern, use ADR-008.3's membership recovery policy first; changing
application semantics outside finalized history would create a different chain.

## 13. Framework and module impact

Most implementation belongs in `appchain-composite` and
`appchain-composite-contracts`, but two narrow framework additions are required:

1. a deterministic, read-only membership-epoch snapshot/view keyed by app
   height, exposed to the state-machine construction/transition boundary; and
2. the typed immutable consensus-limit view defined by
   [ADR-016](016-authenticated-appchain-consensus-profile-and-typed-runtime-limits.md) so
   composite quota validation does not parse duplicate settings/defaults. Its
   values come from the runtime's single resolved chain/effect configuration
   and its canonical bytes/digest are included in a framework-owned
   authenticated chain-profile identity; a typed wrapper around node-local
   defaults is insufficient.

The membership view must return immutable snapshots derived from finalized
membership epochs. It must not expose a mutable `MemberGroup` or allow a plugin
to observe wall-clock/runtime membership changes.

Runtime/app work adds the protected governance submit API/CLI, authorization,
status projection, metrics, and UI. No new general plugin framework, app-block
wire field, effect transport, or connector change is required.

## 14. Configuration and chain identity

The mode is explicit and consensus-affecting:

```properties
yano.app-chain.chains[0].machines.composite.profile-mode=governed
yano.app-chain.chains[0].membership.mode=governed
yano.app-chain.chains[0].machines.composite.profile-governance.min-activation-lag=20
yano.app-chain.chains[0].machines.composite.profile-governance.proposal-ttl-blocks=600
yano.app-chain.chains[0].machines.composite.profile-governance.max-epochs=1024
```

`profile-mode`, governance schema version, proposal bounds, minimum activation
lag, proposal lifetime, and epoch bound are included in the chain
identity/fingerprint. Changing them locally on a retained chain fails startup.

Static/disposable chains may select `fixed`. Long-lived stock templates and the
no-code evidence demo exercise `governed`.

## 15. Implementation plan

### Phase 15.0 — Freeze contracts and fixtures

- Freeze governance-command CDDL, canonical epoch/status binary layouts,
  domain hashes, state keys, bounds, and golden vectors.
- Add strict profile decode/re-encode support without changing existing
  canonical profile bytes.
- Add iterative CBOR preflight and negative vectors.
- Freeze membership-epoch digest and proposal-hash inputs.
- Record fixed/governed mode and limits in chain identity.

### Phase 15.1 — Catalog and fixed-mode refactor

- Introduce `CompositeProfileCatalog` and profile-runtime entries.
- Refactor the existing one-profile constructor through a one-entry catalog
  without changing fixed-mode roots or behavior.
- Consume ADR-016's typed framework consensus profile and committed identity;
  add immutable membership snapshots.
- Add catalog collision, round-trip, missing generation, native packaging, and
  discovery-order tests.

### Phase 15.2 — Authenticated governance

- Implement bounded staging, sealing, approval, readiness, cancellation,
  expiry, and membership-epoch invalidation.
- Add protected REST/CLI operations and dry-readiness diagnostics.
- Persist all governance state inside the app-state atomic batch.
- Add duplicate, conflict, flood, malformed, stale-base, membership-race, and
  restart tests.

### Phase 15.3 — Epoch activation and compatibility

- Implement block-start profile resolution from both result and apply paths.
- Append epoch records and update the active marker atomically.
- Preserve exact old-generation result routing and bounded quota drain.
- Make queries/proofs epoch-aware and add strict/governance-aware client trust
  policies.
- Add replay matrices around `H-1/H/H+1`, including effects emitted before `H`
  and resolved/expired after `H`.

### Phase 15.4 — Operations and end-to-end closure

- Add status, health, metrics, UI, proposal inspection, and operator runbook.
- Exercise a real three-member rolling deployment: one member missing code,
  then all ready, activation, restart, snapshot restore, late join, and replay.
- Exercise component add, quota rebalance, compatible replacement, direct-route
  closure, and proposal cancellation/expiry.
- Run JVM/native packaging and app-chain cluster regressions.
- Obtain independent consensus/determinism, plugin/API, security, and operations
  reviews and close every Critical/High/Medium finding.

## 16. Acceptance criteria

ADR-015 may move to Accepted/Implemented only when:

1. fixed mode produces the post-ADR-016/pre-ADR-015 profile bytes, roots,
   routes, and proofs;
2. governed genesis commits epoch 0 and the existing active marker atomically;
3. governance-command CBOR has CDDL/golden vectors/iterative preflight, while
   epoch/status binary encodings have canonical fixtures, and all three reject
   malformed/trailing/oversize input;
4. fewer than threshold approvals cannot schedule a profile;
5. threshold approval without all-member readiness cannot schedule a profile;
6. readiness for another digest/proposal/member epoch cannot be replayed;
7. a membership change before activation deterministically voids the proposal;
8. every member selects the old profile at `H-1` and the target at `H`;
9. results and expiries at `H` use that same block-start profile while exact old
   effect owners still reach their retained generation;
10. quota cannot exceed the typed framework cap before, during, or after the
    retired-generation drain window;
11. component add, compatible replacement, workflow change, route closure, and
    quota rebalance produce identical roots across at least three members;
12. incompatible namespace/result changes are rejected unless represented as
    a new component id or separately accepted migration contract;
13. restart, full replay, snapshot restore, and late join reproduce every root
    around activation;
14. a node without the target catalog cannot attest readiness and fails before
    participation if it falsely claimed readiness;
15. strict clients reject an unpinned proven marker; governance-aware clients
    verify finality and MPF inclusion before checking a contiguous epoch chain
    to the active marker and applying their membership/approval policy;
16. proposal/governance state remains bounded under duplicate and adversarial
    traffic;
17. no local config/JAR/discovery-order change can activate a profile;
18. protected APIs enforce admin/member authorization and redact implementation
    or secret detail;
19. JVM and native distributions include identical catalog entries/digests; and
20. independent reviews leave no unresolved Critical, High, or Medium issue.

## 17. Consequences

### Positive

- Long-lived composite chains can evolve without discarding history.
- Every change is explicit, attributable, height-bound, and replayable.
- Operators learn about missing code before activation rather than through a
  state-root divergence.
- Profile proofs and history remain authenticated under the existing state
  commitment.
- Retired component quota is no longer reserved forever.

### Costs and limitations

- Rolling upgrades require an approval/readiness ceremony and coordinated
  bundle deployment.
- All-member readiness lets an unavailable member delay evolution until the
  membership is governed around it.
- The composite bundle/catalog remains the reviewed unit; this ADR does not
  create YAML-level component hot composition.
- Historical/retained generations increase code and snapshot-support burden.
- State-incompatible upgrades still need domain-specific migration design.
- Two small framework surfaces are required for immutable membership snapshots
  and typed effect limits.

## 18. Decisions

| ID | Decision |
|---|---|
| 015-D1 | Profile evolution is an append-only, authenticated epoch chain; local mutation is forbidden. |
| 015-D2 | Proposals authorize exact canonical profile bytes, base digest, membership epoch, and activation height. |
| 015-D3 | Threshold approval and all-member code readiness are separate mandatory gates. |
| 015-D4 | Membership and profile changes cannot overlap; an epoch change voids the profile proposal. |
| 015-D5 | The selected bundle supplies a digest-keyed executable catalog; no runtime component discovery is added. |
| 015-D6 | `~composite/profile/v1` remains the active marker; append-only epoch records preserve history. |
| 015-D7 | One block uses one block-start profile across results, expiries, workflows, and normal apply. |
| 015-D8 | Exact effect-owner generations survive profile retirement through the result window. |
| 015-D9 | Retired quota releases deterministically after the result-drain window; v1 retains every historical executable profile until a future authenticated replay-floor protocol says otherwise. |
| 015-D10 | Incompatible state migration is explicit domain work, not hidden in the governance transition. |
| 015-D11 | Pre-release fixed databases need not be converted automatically; fixed/governed mode is immutable chain identity. |
| 015-D12 | No profile break-glass override exists outside finalized chain governance. |

## 19. Implementation and verification evidence

The implementation keeps the frozen ADR-013.2 profile bytes and marker, then
adds a catalog-backed governed runtime and an authenticated append-only epoch
chain. The main delivered surfaces are:

- strict governance command, status, epoch, and structural epoch-chain
  contracts in `appchain-composite-contracts`, including published CDDL,
  canonical vectors, size bounds, and iterative CBOR preflight;
- `CompositeProfileCatalog` and `CompositeProfileGovernanceRuntime`, with
  exact product/generation binding, threshold approval, all-member local-code
  readiness, membership-epoch binding, future-height activation, cancellation,
  expiry, and bounded proposal state;
- one block-start profile across normal application, results, and expiries,
  with exact callbacks to retired effect owners and deterministic quota release
  after the result-drain window;
- retained-state initialization that verifies the marker, contiguous epoch
  chain, historical/scheduled catalog entries, mode, and authenticated
  consensus profile before participation;
- protected dry-run/submit REST operations, packaged CLI, status/health,
  metrics, dashboard projection, and the operator runbook in
  `docs/APP_CHAIN_PROFILE_GOVERNANCE.md`; and
- fixed-profile proof helpers plus the dedicated
  `yano-appchain-composite-client` governance-aware verifier. The latter checks
  portable finality against caller-pinned membership, binds the epoch pointer,
  every epoch record, and the active marker to one finalized root through MPF
  proofs, validates the contiguous epoch chain, and requires an independent
  authorization policy. Its built-in pinned-proposal policy requires the exact
  reviewed proposal hash for every non-genesis epoch.

Acceptance verification completed on 2026-07-17:

| Acceptance area | Evidence |
|---|---|
| Contracts and malformed input (criteria 1-3, 16-17) | Canonical profile/commitment regression, governance/status/epoch vectors, CDDL cross-validation, catalog collision/discovery-order checks, definite/indefinite/trailing/oversize rejection, and adversarial duplicate/conflict/flood no-op tests. |
| Authorization and activation (criteria 4-8, 12, 14) | Threshold, outsider, wrong-digest, wrong-proposal, all-member readiness, missing-code refusal, membership-race invalidation, incompatible namespace/result rejection, and exact `H-1`/`H` activation tests. |
| Effects, routes, workflows, and quota (criteria 9-11) | A pre-activation effect is resolved at `H` through the real `FxKernel`/MPF commit path and reaches its exact retired-generation callback. Three replays, restart, and snapshot reproduce that callback state. Result/expiry block-start selection, bounded drain/reclamation, component add, compatible replacement, workflow add, route closure, and quota rebalance also produce identical roots across three live members. |
| Recovery and clients (criteria 13, 15) | Three-run full replay, restart-at-height, snapshot-at-activation, retained restart, a signed live three-member snapshot restore, and an empty-ledger post-activation member catch-up all reproduce the epoch, marker, and state root. The complete governance-aware verifier rejects wrong finality trust, wrong-root proofs, missing/false authorization, and missing/wrong/extra pinned proposal hashes. |
| Operations, authorization, and distribution (criteria 18-19) | API-key/admin route tests, redacted status/health/metrics/UI tests, no-redirect CLI contract tests, packaged JVM catalog smoke, and Linux ARM64 GraalVM native catalog smoke use the real governed `evidence-v1-gated` stock composite. JVM and native reported the same exact profile digest as well as matching plugin provenance and catalog fingerprints. The exact preview digest is intentionally maintained in `app/appchain-effects-demo/config/composite-profile-digests.properties`; ADR-018 completed the unreleased v1 republish lifecycle and replaced the digest used by this historical acceptance run. |

Independent consensus/API/operations review initially identified six material
closure gaps: catalog iteration order, the complete client trust boundary,
redirect handling in the privileged CLI, a live multi-member snapshot restore,
the real effect-runtime callback path, and exact JVM/native profile parity.
Each was corrected and its affected suite rerun. No Critical, High, or Medium
ADR-015 implementation finding remains. `APP-009` below is deliberately a
separate external CI/release-evidence gate, not an ADR-015 design defect.

The broader corrected tree also passed the runtime/application module suites,
release contracts, connector fault matrix, real Kafka TLS/SASL integration,
effect failover, and full Compose/host deployment parity including immediate
replay, retained restart, external artifact/receipt checks, three-node root
agreement, and anchoring. `APP-009` deliberately remains separate: GitHub must
still retain a manual `scope=all` run on the committed tree and a final run on
the retargeted PR before release/process certification is complete.
