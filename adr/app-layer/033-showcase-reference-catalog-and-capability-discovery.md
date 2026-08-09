# ADR app-layer/033: Showcase reference catalog and capability discovery

**Status:** Accepted — implementation pending
**Date:** 2026-08-09
**Scope:** App-chain showcase catalog, reusable-capability visibility, console presentation, and
optional finalized-message indexing
**Related:** ADR app-layer/006, 019, 022, 025.2, 028, 031, and 032
**Depends on:** ADR-031 composable state-machine and proof baseline
**Preview reset policy:** The app-chain feature is unreleased. Showcase identities and state are
recreated when this ADR changes a chain's committed application profile; no catalog, state, or
anchor migration compatibility is required.

---

## 1. Context

ADR-031 established one deployable application contract, `AppStateMachine`, and made stock,
custom, and composite applications reuse the same deterministic transition capabilities. The
showcase now needs to explain that architecture instead of presenting a flat list of unrelated
state-machine IDs.

The current light YAML starts eleven chains:

| Chain | Current purpose |
|---|---|
| `orders-chain` | standalone `ordered-log` |
| `registry-chain` | standalone `kv-registry` |
| `approvals-chain` | standalone basic sender-quorum approvals |
| `balances-chain` | standalone balances |
| `documents-chain` | standalone document trail |
| `workflow-chain` | orders + basic approval + audit + outbox effect composition |
| `roles-chain` | standalone ADR-019 actor/role approvals |
| `payments-chain` | virtual, no-real-funds EUTxO ledger |
| `authenticated-map-chain` | MPF authenticated map with direct role and approval authorization |
| `authenticated-map-jmt-chain` | classic-JMT authenticated-map comparison |
| `payment-chain-settlement` | EUTxO L1 observer, custody, effect, settlement, and indexer path |

This already contains useful material, but four problems remain:

1. The catalog does not have a light-profile document workflow showing document trail + domain
   actors/roles + actor approval, even though ADR-031's role-evidence implementation proved that
   reuse path.
2. Membership governance, executable-profile governance, business authorization, action approval,
   effects, L1 observation, and proof backends are visible in different status fields. The console
   guesses some capabilities from state-machine IDs and installed plugin bundles instead of
   displaying an application-declared manifest.
3. `FinalizedMessageIndex` exists as a reusable transition capability, but there is no generic
   showcase configuration that adds it to arbitrary standalone and composite applications.
4. Documentation counts and descriptions predate the current eleven-chain light profile and do not
   consistently explain which chains are foundations versus composed reference applications.

`membership.mode=governed` on a chain is cluster membership governance. It does not mean the
application has business-role authorization or an approval-gated action. The showcase and UI must
not conflate these concepts.

## 2. Decision summary

The default `light` profile will start **all foundation chains, all three cross-cutting reference
applications, both authenticated-state comparison chains, and `payment-chain-settlement`**. A
presenter will not need to start the settlement chain separately.

The target light catalog contains twelve chains:

* seven standalone foundations;
* three cross-cutting reference applications, with the MPF/JMT authenticated-map pair counted as
  one business application demonstrated over two proof backends;
* one additional backend-comparison chain; and
* the L1 settlement chain.

The showcase will add an explicit, machine-readable application capability manifest to the
existing chain status response. The console will render this manifest rather than guessing
application composition from names.

The showcase launcher will gain an opt-in finalized-message-index switch. It is disabled by
default for applications that do not intrinsically use it. `ordered-log` remains intrinsically
indexed because that capability is its stock authenticated-state behavior.

## 3. Target default light catalog

### 3.1 Foundation chains

These chains demonstrate one stock application behavior without adding business cross-cutting
concerns merely to create permutations:

| Chain | Foundation demonstrated |
|---|---|
| `orders-chain` | ordered message log and intrinsic finalized-message state record |
| `registry-chain` | conditional key/value mutation |
| `approvals-chain` | basic sender-quorum approval lifecycle |
| `balances-chain` | deterministic account/balance state |
| `documents-chain` | document history/head transitions |
| `roles-chain` | ADR-019 actor registry, role policy, and actor approval lifecycle |
| `payments-chain` | virtual EUTxO ledger with no L1 funds |

These are teaching baselines and reusable building blocks. The showcase will not create approval,
role, effect, and observer variants of every foundation. Such a product matrix would obscure reuse
and create scripts and tests for combinations that add no new architectural evidence.

### 3.2 Cross-cutting reference applications

Only the following three business compositions are included:

1. **Orders + approval + effects — `workflow-chain`.** Retain
   `order-approval-outbox-v1`. It demonstrates reuse of registry/order state, basic approvals,
   document-style audit history, deterministic effect intent, finality gating, external execution,
   and consensus-incorporated effect results.
2. **Documents + roles + approval — `document-review-chain`.** Add a lean
   `document-role-approval-v1` preset assembled from the existing ADR-019 domain actor registry,
   role-aware approval component, `RoleAuthorizationCapability`, document-trail transitions, and
   one application-owned approval-consumption receipt. It must reuse these implementations; it
   must not copy the role-evidence lifecycle or introduce another actor/approval model. It does not
   require Kafka, S3, IPFS, or the evidence product profile.
3. **Authenticated map + direct actor/role authorization + approval —
   `authenticated-map-chain`.** Retain the governed MPF scenario: externally signed direct-role
   evidence and a multi-organization approval-gated collection, with application-owned coverage
   and consumption receipts.

`authenticated-map-jmt-chain` runs the same business collections, policies, actors, commands, and
expected receipts as `authenticated-map-chain`; only the authenticated-state commitment backend
and proof verification target differ. MPF demonstrates off-chain and on-chain verification. JMT
demonstrates off-chain verification. This controlled parity prevents backend comparison from being
confounded by a different authorization profile.

### 3.3 Settlement remains in light

`payment-chain-settlement` remains in the default light profile and is started with the other
chains. It demonstrates a different boundary from the foundation and business-composition chains:

* stability-gated L1 deposit and withdrawal-confirmation observers;
* virtual EUTxO state derived from accepted L1 facts;
* deterministic settlement effect intent;
* federation co-signing and L1 execution;
* an independently rebuildable lifecycle index; and
* MPF proof and SCRIPT-anchor identity.

Starting the chain is automatic. Operations that deploy public-network scripts, use owner-supplied
keys, or move funds remain explicit and confirmation-gated. Inclusion in `light` never makes a
preprod/mainnet deposit or withdrawal automatic.

## 4. Explicit capability discovery

### 4.1 Current behavior

The chain status response exposes `stateMachine`, `stateMachineStatus`, effects, observers, anchor,
and state-commitment data. Composite operational status exposes governance and non-empty child
status, but not a stable description of every component and workflow. The console's
`discoverChainCapabilities` consequently infers capabilities from values such as
`stateMachine=authenticated-map`, `effects.enabled`, and installed plugin bundle IDs.

That is insufficient for ADR-031. A custom `AppStateMachine` can reuse approval or authorization
without using a known state-machine ID, and a component can be enabled while having no non-empty
operational status.

### 4.2 Application capability manifest

`AppStateMachine` will expose one immutable declarative capability description, separate from
mutable `operationalStatus()`. The final Java names may differ, but the contract is:

```text
AppCapabilityManifest v1
  application-id
  application-version
  manifest-digest
  components[]
    id, version, configuration-id/digest, state-namespace, topics, query-subjects
  workflows[]
    id, version, participant-component-ids, topic, effect-types
  cross-cutting[]
    capability-id, version, enabled, configuration-digest, attributes
  proof-subjects[]
```

The initial cross-cutting IDs include:

```text
authorization:direct-role-v1
approval:basic-quorum-v1
approval:actor-role-v1
effects:outbox-v1
l1-observer:eutxo-vault-deposit-v1
l1-observer:eutxo-withdrawal-confirmation-v1
state-index:finalized-message-v1
state-commitment:mpf-blake2b256-v1
state-commitment:jmt-blake2b256-v1
```

The manifest is exposed as `capabilityManifest` in the existing chain status resource. No separate
REST endpoint is needed. It is descriptive discovery data, not a new trust root. Consensus-critical
configuration digests must be derived from the committed application/composite profile, and proof
verification continues to pin the chain, genesis, commitment profile, root, and typed subject.

Stock machines declare their intrinsic capability. Composite construction derives components and
workflows from its committed profile. Generic wrappers, including finalized-message indexing,
append their declared capability. Custom machines can declare additional stable IDs without the
runtime learning their business semantics.

## 5. Console decision

The console needs changes to demonstrate ADR-031, but it does not need a separate page for every
state-machine combination.

### 5.1 Capability and composition panel

The app-chain overview will render `capabilityManifest` as:

* foundation/state components;
* cross-cutting authorization, approval, effects, L1 observer, and message-index badges;
* workflow connections and their participating components;
* state backend and verification target; and
* whether each item is intrinsic, composed, or launcher-enabled.

A compact capability matrix across all running chains will make the showcase story visible without
selecting chains one by one. Membership governance and executable-profile governance appear in a
separate governance group so they are not labeled as business approval.

### 5.2 Finalized-message proof lab

The existing finalized-message list and generic proof controls will be connected into one proof
lab. Selecting a finalized message will show:

1. its compact path to the block `messagesRoot`, available for every chain;
2. the independently verifiable signed block/finality identity;
3. its authenticated `finalized-message-v1` state proof when that capability is enabled;
4. the state root and latest applicable L1 anchor;
5. MPF versus JMT verification target; and
6. the explicit statement that message-body availability is not proven by either root.

The current message-inclusion and generic state-proof endpoints are sufficient. The UI derives the
published typed subject key using the manifest and ADR-031 client contract; this ADR does not add a
duplicate proof endpoint.

### 5.3 Reference-workflow trace

For the three reference applications the console will show a small receipt-based transition trace:

```text
command -> authorization/approval -> application mutation -> effect/consumption receipt
```

The trace reads typed committed receipts and effect records. It must not infer success from logs or
node-local index rows. The existing authenticated-map console and effect lifecycle panels remain;
they gain manifest-driven headings and links. `document-review-chain` adds document head,
role-policy/proposal, and approval-consumption views using its declared query subjects.

## 6. Optional finalized-message index

### 6.1 Current implementation and gap

`FinalizedMessageIndex` already provides canonical configuration, inclusion policy, bounded write
cost, record encoding, and typed proof decoding. `OrderedLogStateMachine` always applies it, and a
small number of other applications call it directly. There is no general YAML option or
application wrapper that safely attaches it to every `AppStateMachine`.

The launcher must therefore not implement this as a text-only YAML switch. First, Yano will add a
generic `AppStateMachine` decorator/composition that delegates admission/execution and commits the
message-index transition in the same block/state transaction.

The generic form uses a reserved, versioned state namespace so message IDs and the tip marker
cannot collide with application-owned keys. Typed `ProofSubjects.finalizedMessage` and the console
derive this physical key from the published namespace. Because app-chain is unreleased, the
ordered-log key layout may be normalized to the same supported namespace and old roots discarded.

### 6.2 Showcase CLI

The canonical option is correctly spelled `--enable-finalized-message-index`; no misspelled
`--enable-finialized-message` compatibility alias is introduced.

```bash
# Enable the optional application-only index on every configured chain.
./showcase.sh quickstart --profile light --instance indexed \
  --enable-finalized-message-index

# Enable it only on selected chains.
./showcase.sh quickstart --profile light --instance indexed-selected \
  --enable-finalized-message-index=documents-chain,workflow-chain
```

The option applies to `prepare`, `up`, and `quickstart`. Rules:

1. Absence means disabled for non-intrinsic applications. `orders-chain` still reports the index as
   intrinsic because ordered-log is defined by that state record.
2. The no-value form means all configured chains, including `payment-chain-settlement`.
3. The `=` form accepts canonical comma-separated chain IDs. Empty elements, duplicates, unknown
   IDs, whitespace variants, and a mixture of `all` and IDs fail before any node starts.
4. The showcase-added index defaults to `APPLICATION_ONLY`; reserved system topics are not indexed.
   Ordered-log retains its explicitly declared policy.
5. `maxMessagesPerBlock` is committed and must be compatible with the chain's committed block
   limit. The enabled configuration and digest appear in the capability manifest.
6. Selection changes application state roots and state-genesis identity. The selection and its
   digest are retained in `showcase-identity.json`. Restart reuses it; a conflicting setup option
   fails closed and instructs the user to create/reset an instance.
7. The option affects all nodes identically. It is never a node-local indexer toggle and must not be
   confused with the rebuildable `appchain-indexers` storage root.

For an indexed message the demo proves an authenticated record containing at least height,
original message index, topic, and sender. It does not duplicate the body into authenticated state
and does not prove continued body availability.

## 7. Showcase source of truth

The implementation will add one versioned, machine-readable showcase catalog manifest. It records
chain IDs, scenario IDs, foundation/reference classification, expected capability IDs, profile
membership, proof backend, anchor eligibility, and smoke-test handler.

The following must consume or validate against this source rather than maintain independent chain
lists:

* light YAML generation/validation;
* `showcase_identity.py` and retained identity markers;
* `showcase.sh describe`, run dispatch, setup-option validation, and anchor scope;
* cluster validators and deployment skills;
* distribution/contract tests; and
* the documentation capability table.

`loadtest.sh` and `soaktest.sh` remain chain-ID driven. Only examples and typed workload aliases
need catalog updates; their submission engines do not change.

## 8. Implementation plan

### Phase 0 — characterize and freeze the current showcase

* Pin the current eleven-chain profile, current status response, demo routes, and finalized-message
  proof behavior.
* Add a test that distinguishes membership governance from application authorization/approval.

### Phase 1 — catalog manifest and twelve-chain light profile

* Add the machine-readable showcase catalog.
* Add `document-review-chain` using only reusable ADR-019/031 components.
* Keep `payment-chain-settlement` in default light and align all counts, scenarios, anchor logic,
  retained identity, scripts, and docs.
* Give MPF and JMT authenticated-map chains business-profile parity.

### Phase 2 — capability declaration and status API

* Add the immutable `AppStateMachine` capability manifest contract.
* Implement intrinsic manifests for stock machines and derived manifests for composites.
* Add `capabilityManifest` to existing status JSON and strict API/client models.
* Reject duplicate capability/component/workflow IDs and nondeterministic manifest ordering.

### Phase 3 — generic finalized-message-index composition

* Add the namespaced decorator/composition and canonical key derivation.
* Commit its configuration into application/composite identity.
* Implement the launcher flag, selection validation, retained-marker binding, and config export.
* Add standalone, composite, MPF, JMT, restart, and state-cost tests.

### Phase 4 — console capability matrix and proof lab

* Replace state-machine-name inference with manifest discovery.
* Add the cross-chain capability matrix and chain composition view.
* Connect finalized messages to message-root and optional state-root proof verification.
* Add the three reference-workflow traces using committed receipts.

### Phase 5 — scripts, docs, and live qualification

* Update demo wrappers, master demo, curl demo, anchoring, submission, load examples, and skills.
* Run three-node devnet with the index disabled, enabled for selected chains, and enabled for all.
* Run a retained-state restart and prove cross-node root/manifest identity.
* Run preprod with settlement present in light; keep all public-key/funds actions explicit.

## 9. Acceptance criteria

1. Default light starts twelve chains, including `document-review-chain` and
   `payment-chain-settlement`, with no separate settlement start command.
2. The three advertised compositions reuse the exact stock transition/authorization/approval
   implementations and have differential tests against their standalone components.
3. The MPF/JMT authenticated-map pair has identical business commands, policies, and receipts.
4. Every chain exposes a deterministic capability manifest; the console no longer infers reusable
   capabilities from state-machine names.
5. The default optional finalized-message index is off for non-intrinsic chains.
6. The all-chain and selected-chain CLI forms produce identical roots across three nodes, survive
   restart, and reject retained-identity drift.
7. A selected-chain message can be verified through both `messagesRoot` and authenticated state;
   an unselected-chain message has the block proof but an authenticated-state absence proof.
8. MPF proof verification succeeds off-chain and in the reference on-chain verifier; JMT remains
   explicitly off-chain-only.
9. The console never labels finalization or state inclusion as message-body availability.
10. Settlement remains safe: starting light does not automatically deploy public-network scripts
    or move funds.

## 10. Consequences

### Positive

* The showcase explains ADR-031 reuse directly rather than asking users to infer it from code.
* The catalog stays compact: foundations plus three meaningful compositions, not a permutation
  matrix.
* Custom applications become visible in the console without hard-coded state-machine-name checks.
* Finalized-message state proofs can be enabled consistently on any application while keeping
  authenticated-state growth opt-in.
* The settlement story remains one-command discoverable in the default profile.

### Costs and risks

* Adding a capability declaration to `AppStateMachine` is another public contract that needs stable
  IDs, ordering, and codec tests.
* A universal message index can substantially increase authenticated writes and retained MPF/JMT
  data; the UI and CLI must show the committed cost bound.
* The twelve-chain profile increases startup, anchoring, and qualification time.
* A workflow trace requires typed application receipts; node-local logs or indexes are not an
  acceptable shortcut.

## 11. Non-goals

This ADR does not:

* enable every cross-cutting concern on every stock machine;
* merge membership governance, profile governance, business roles, and action approvals;
* turn the finalized-message index into a node-local SQL index;
* claim message-body availability from `messagesRoot` or `stateRoot`;
* add a duplicate typed-proof REST endpoint; or
* make public-network settlement automatic merely because the chain starts in `light`.
