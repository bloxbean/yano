# ADR-022: Out-of-Box App-Chain Capabilities and an Extensible Product Catalog

## Status

Accepted and implemented — version 3

Version 1 recorded a repository-wide assessment of the app-chain
runtime, standard library, composite and role frameworks, evidence product,
effect connectors, clients, developer tooling, Studio, distribution packaging,
demos, scaffolds, and documentation.

Version 2 (2026-07-22) is a scope amendment: all post-deployment
runtime-console UI concerns are carved out to root
[ADR-028](../028-unified-console-ui-module.md) (the `console-ui` module).
This ADR keeps the capability, reusability, catalog, and product-model
decisions — including Studio's pre-deployment wizard behavior (§11) — while
the runtime console's design, delivery, and capability-aware panels are
ADR-028's. The catalog remains the single source of truth for what a
distribution contains, including recording the console's availability. The
review questions in §20 were left open for the implementation round.

Version 3 (2026-07-22) records completion of M1 through M6. The generic
composite and role boundaries, generic `role-approvals` product, expanded
release catalog and Studio selection, portable stock contracts, bootstrap
plans, signed custom component catalogs, and release-candidate gates now ship
in this branch. The schemas remain `v1alpha1` pending independently maintained
third-party catalog usage. Section 1 is retained as the verified
pre-implementation baseline; §15 records the delivered milestones and §20
records the implementation resolutions.

The number is local to the `adr/app-layer` series. Root-level ADR-022, if one
exists, is unrelated.

## Date

2026-07-21 (version 1), 2026-07-22 (version 2 scope amendment),
2026-07-22 (version 3 implementation record)

## Parent and related decisions

- [ADR-005](005-yano-app-chain-framework.md) owns app-chain consensus,
  deterministic state-machine execution, authenticated state, finality,
  storage, proofs, and Cardano anchoring.
- [ADR-006](006-appchain-enterprise-extensions-and-zk.md) introduced the stock
  state machines, clients, sinks, operations, and experimental ZK direction.
- [ADR-008.2](008.2-rotating-sequencer.md),
  [ADR-008.3](008.3-chain-governed-membership.md), and
  [ADR-008.4](008.4-script-anchors-l1view.md) own sequencing, membership, L1
  observers, and script-anchor choices.
- [ADR-010](010-deterministic-effect-system.md) and
  [ADR-010.1](010.1-emission-versioning.md) own deterministic effect intent,
  activation, execution, result incorporation, expiry, and proofs.
- [ADR-011](011-plugin-architecture.md) through
  [ADR-011.4](011.4-plugin-operations-and-observability.md) own plugin
  manifests, catalog identity, domain APIs, and operations surfaces.
- [ADR-013](013-first-party-integration-connectors-and-effect-demo.md) owns the
  Kafka, S3-compatible object-store, and IPFS connectors and evidence demo.
- [ADR-013.2](013.2-deterministic-composite-state-machine.md) owns explicit,
  deterministic component composition.
- [ADR-015](015-governed-composite-profile-evolution.md) owns governed profile
  evolution.
- [ADR-016](016-authenticated-appchain-consensus-profile-and-typed-runtime-limits.md)
  owns the authenticated consensus-profile boundary.
- [ADR-019](019-reusable-domain-actor-registry-and-role-aware-approvals.md)
  owns organizations, domain actors, keys, roles, policies, and the first
  role-aware evidence profile.
- [ADR-021](021-generic-on-approved-effect.md) owns the standard approvals
  machine's one generic post-approval effect.
- [ADR-DX-0001](dx/0001-unified-appchain-onboarding-configuration-and-lifecycle.md)
  owns configuration metadata, validation, blueprints, locks, rendering,
  Studio, lifecycle commands, drift, GitOps output, and release-pinned DX.
- Root [ADR-028](../028-unified-console-ui-module.md) owns the runtime
  console UI: the `console-ui` module, embedded and standalone serving, node
  identity display, and the generic capability-aware panels (effects,
  approvals via committed queries, evidence bundles, proofs). All
  runtime-console UI decisions live there; Studio's pre-deployment wizard
  behavior stays with this ADR and ADR-DX-0001, and this ADR records console
  availability in the catalog.

---

## 0. In plain words

Yano already contains more reusable app-chain functionality than users can
discover or select through `./yano.sh appchain`:

- five stock deterministic state machines;
- a generic composite engine;
- a generic organization, actor, role, and policy model;
- an effect runtime and built-in webhook support;
- optional Kafka, object-store, IPFS, Cardano, and ZK bundles;
- Cardano anchors and L1 observers;
- generic Java, Spring, test, proof, query, and plugin APIs; and
- a complete evidence-oriented reference product and demo.

The problem is no longer only missing implementation. The product surface does
not describe or expose the implementation consistently:

- generic composite and role primitives are packaged with evidence-specific
  providers and contracts;
- modules under `examples` are included in the default application;
- documentation uses “out of the box” for both bundled and separately built
  plugins;
- the release catalog advertises only a small subset of the available
  machines, anchors, observers, membership choices, effects, and sinks;
- Studio displays capabilities resolved from a recipe but does not let the
  user select compatible capabilities;
- third-party plugins can extend configuration validation but cannot become a
  first-class capability or recipe in project generation; and
- some recipes create a valid runtime configuration without creating the
  complete outcome their name suggests.

This ADR turns the existing framework into a coherent product model. Recipes
remain safe starting points. Capabilities become release-pinned, composable
choices with explicit requirements and conflicts. Generic composite and role
code is separated from evidence-specific profiles. Custom plugins may
contribute bounded, signed, data-only product metadata without executing code
inside the CLI or browser.

This does **not** create a YAML workflow language. Consensus-visible component
order, routes, state layouts, transitions, quotas, and effect emission remain
implemented and reviewed in a stock profile or plugin JAR.

## 1. Context and verified pre-implementation state

This section records the repository state that motivated the decision. The
version 3 implementation outcome is recorded in §15; it intentionally does
not rewrite the historical baseline as though the gaps never existed.

### 1.1 Runtime and extension surface

The generic runtime already provides:

- fixed and L1-window rotating sequencing;
- static and governed membership;
- threshold finality and authenticated membership epochs;
- multi-chain hosting;
- deterministic state-machine and typed-state-machine SPIs;
- MPF state proofs, portable finality evidence, snapshots, and evidence
  bundles;
- metadata and threshold-script Cardano anchors;
- built-in address-deposit and metadata-label L1 observers;
- deterministic effects, gates, retries, external claim/report, result
  incorporation, retention, and composed effect proofs;
- built-in finalized webhooks and the `webhook.post` executor;
- committed queries, SSE, bundle-owned domain APIs, REST authentication,
  health, metrics, and admin operations; and
- typed plugin SPIs for state machines, sequencers, signers, observers,
  effect executors, finalized sinks, domain APIs, health, and metrics.

The implementation is broader than the current app-chain capability catalog.

### 1.2 Stock state machines

The current default runtime and standard library contain:

| State machine | Current location | General purpose |
|---|---|---|
| `ordered-log` | `runtime` | Opaque append-only ordered records |
| `kv-registry` | `appchain-stdlib` | First-writer-owned mutable values |
| `approvals` | `appchain-stdlib` | Validator-member threshold decisions and one optional generic effect |
| `balances` | `appchain-stdlib` | Simple member-authorized mint and transfer accounts |
| `doc-trail` | `appchain-stdlib` | Append-only per-entity document/event hash trails |

All five are reusable. Only `ordered-log`, `kv-registry`, and `approvals` are
first-class state capabilities in the current DX catalog.

### 1.3 Composite framework versus stock evidence composite

`CompositeStateMachine`, `CompositeComponent`, `CompositeWorkflow`, component
descriptors, workflow descriptors, namespaced state, aggregate queries,
effect quotas, profile catalogs, and governed profile evolution are generic.

The current `appchain-composite` artifact is not a clean generic boundary:

- its stock provider exposes only `evidence-v1` and `evidence-v1-gated`;
- its implementation depends on the evidence registry and evidence contracts;
- its contracts artifact has an API dependency on evidence contracts; and
- the stock workflows are evidence release and notification workflows.

A custom application can reuse the framework, but it must write a custom
composite plugin. Arbitrary node-local YAML composition is intentionally not
supported because component order and behavior affect consensus.

### 1.4 Generic roles versus the stock role-evidence profile

The role-workflow contracts are domain-neutral:

- organization and actor identifiers are application-defined;
- roles are normalized strings, not a fixed enum;
- actor statements bind an arbitrary payload domain and 32-byte payload hash;
- policies support proposer roles, bounded AND clauses, minimum counts,
  distinctness by actor or organization, rejection behavior, and lifetimes;
- actor key epochs support proof of possession, rotation, retirement, and
  revocation; and
- organization, actor, and policy changes are threshold governed.

`manufacturer`, `auditor`, and `regulator` are values selected by the evidence
profile, not framework concepts.

The executable `appchain-role-workflow` artifact mixes generic and
evidence-specific responsibilities:

- `DomainActorRegistryComponent`, `RoleAwareApprovalsComponent`, and
  `RoleApprovalWorkflow` are reusable;
- the only manifested state-machine provider is `role-evidence`;
- release and notification workflows are evidence-specific;
- the generic approval component contains an `evidence-approval` query/index;
  and
- the bundle-owned domain API contains an evidence-version approval route.

There is no selectable generic role-approval chain. A non-evidence application
must currently assemble a custom composite plugin.

### 1.5 Evidence product placement

The evidence contracts, registry, client, and demo runner deliberately live
under `appchain/examples`. However, the default application directly or
transitively packages the evidence registry because the composite and
role-workflow providers depend on it. The generated plugin index therefore
advertises evidence as part of the default runtime.

This is neither a clean example boundary nor a clearly owned product boundary.
The evidence state machine is reusable for its exact inspection/compliance
workflow, but it is not a generic workflow engine or complete Digital Product
Passport product.

### 1.6 First-party connectors

The repository contains reusable, provider-neutral contracts and first-party
implementations for:

| Capability | Contribution | Packaging posture |
|---|---|---|
| Kafka | Finalized sink and `kafka.publish` executor | Optional first-party plugin |
| S3-compatible object store | Immutable `object.put` executor | Optional first-party plugin |
| IPFS/Kubo | Reconciled `ipfs.pin` executor | Optional first-party plugin |
| Cardano | `cardano.payment` executor | Optional privileged preview plugin |
| ZeroJ | `zk-gate`, `zk-membership`, `credential-registry` | Optional experimental plugin |

The stock JVM application omits these T3 bundles by default. The evidence demo
builds and stages Kafka, object-store, and IPFS bundles. Native executables must
include selected providers before image generation.

Some documentation currently places these actions in an “out of the box” list
without distinguishing bundled code from a separately built or installed
first-party bundle.

### 1.7 DX catalog and Studio coverage

The current release catalog contains ten capabilities and six recipes:

```text
state: ordered-log, kv-registry, approval-workflow, role-evidence, custom-plugin
sequencer: fixed, rotating
l1: slot-feed
effects: on-approved, publication

recipes: audit-log, owned-registry, evidence-publication,
         approval-workflow, role-evidence, custom-plugin
```

It omits implemented stock machines, anchor modes, governed membership,
observers, sinks, connector executors, standalone evidence, and experimental
ZK machines.

Studio is currently a release-pinned recipe/topology form. It displays the
recipe's resolved capabilities but cannot add compatible optional
capabilities. The blueprint resolver itself already has `requires`, `implies`,
`conflicts`, artifact, runtime, deployment, maturity, and answer concepts that
can support a controlled capability-selection step.

The v1alpha1 blueprint intentionally supports one chain and static membership.
This ADR does not require immediate multi-chain project generation; one
generated project per production chain remains the simplest safe default.
Governed membership, however, is an implemented chain capability and should be
modelled by the catalog when its operator runbook and bootstrap flow are
available.

### 1.8 Custom-plugin metadata gap

ADR-DX-0001 permits a component JAR to carry:

```text
META-INF/yano/appchain-config-metadata-v1.json
```

The CLI reads that descriptor as data and extends validation without loading
plugin classes. An optional trust envelope binds it to the runtime manifest.

That descriptor cannot currently contribute:

- a selectable capability or recipe;
- required and conflicting capabilities;
- non-secret recipe questions;
- runtime/native/deployment availability;
- artifact and bootstrap requirements;
- maturity and support posture; or
- a documentation reference.

`appchain init --capability` accepts only capabilities embedded in the Yano
release. Studio likewise consumes only release assets.

### 1.9 Recipe completeness gap

The existing `evidence-publication` recipe selects `ordered-log` and enables
the effect runtime. `ordered-log` records messages but does not emit effect
intents. The generated project therefore cannot itself perform evidence
publication. The recipe is a preparatory configuration, not the complete
outcome suggested by its name.

The `role-evidence` recipe selects the correct state machine but a useful
environment still requires organizations, actors, keys, policies, connector
plugins, executor-local targets, credentials, and bootstrap commands. The
maintained evidence demo runner owns those steps, while the generated project
does not yet describe them.

A recipe must distinguish:

```text
GENERATED       files and lock are valid
STARTABLE       node prerequisites are satisfied
BOOTSTRAPPED    required application genesis/governance records exist
OUTCOME_READY   required executors/external services are ready
```

### 1.10 Client and documentation gaps

`appchain-client`, the Spring Boot starter, and the testkit are generic and
well separated. Stock command helpers still live with state-machine
implementations, so a client may depend on `appchain-stdlib` merely to encode a
command. Evidence and role workflows have explicit no-SPI contract artifacts,
but the standard library does not.

Documentation is comprehensive but distributed. Notable gaps are:

- no one generated component catalog stating support tier and packaging;
- no standalone generic role-approval tutorial because no such provider
  exists;
- no runnable custom composite/role-plugin scaffold;
- no dedicated `balances` state-machine reference; and
- inconsistent use of “ships” and “out of the box” for optional connectors.

## 2. Goals and non-goals

### 2.1 Goals

- Make every supported, implemented app-chain capability discoverable through
  one release-pinned catalog.
- Define unambiguous bundled, optional, reference, and experimental support
  tiers.
- Keep generic composite and role framework artifacts free of evidence-domain
  dependencies.
- Provide a stock generic role-approval state machine for applications that
  authorize payload hashes without using the evidence product.
- Preserve the complete evidence scenario as an explicitly owned first-party
  domain profile and demo.
- Let recipes provide safe defaults while users add only compatible,
  implemented capabilities.
- Allow custom plugins to contribute bounded product metadata without loading
  plugin code in the CLI or Studio.
- Make generated projects state whether they are generated, startable,
  bootstrapped, and outcome-ready.
- Give stock machines lightweight client-side contracts independent of server
  implementations.
- Keep the solution maintainable, release-pinned, deterministic, and simple to
  extend with new state machines, effects, sinks, observers, signers, and
  domain profiles.

### 2.2 Non-goals

- A general YAML workflow, rules, orchestration, or smart-contract language.
- Dynamically assembling consensus components from arbitrary classpath
  discovery or user-selected ordering.
- Automatically trusting or installing a third-party plugin from the network.
- Accepting secrets in Studio, blueprints, locks, capability descriptors, or
  generated shared configuration.
- Hiding connector endpoints or credentials inside effect payloads.
- Declaring every repository experiment supported merely because it compiles.
- Making the evidence demo runner a generic application SDK or production
  controller.
- Designing, building, or packaging any runtime console UI. Root ADR-028
  owns the `console-ui` module, its embedded/standalone delivery, and its
  capability-aware panels; this ADR only classifies console availability in
  the catalog.
- Supporting arbitrary multi-chain projects in the first delivery slice.
- Stabilizing v1alpha1 DX schemas before field usage and compatibility review.
- Changing retained consensus semantics without a new profile/state identity
  and an explicit migration or fresh-chain decision.

## 3. Decision summary

1. Adopt four normative availability tiers: `BUNDLED`,
   `FIRST_PARTY_OPTIONAL`, `REFERENCE`, and `EXPERIMENTAL`.
2. Make the release capability catalog the product source of truth and verify
   it against runtime plugin manifests, build packaging, configuration
   metadata, docs, and acceptance tests.
3. Keep `appchain-composite` generic. Move evidence presets and evidence
   workflow contracts into an explicitly owned evidence profile artifact.
4. Keep `appchain-role-workflow-contracts` and the reusable role components
   generic. Move `role-evidence`, evidence indexes, evidence routes, and
   evidence terminal workflows into the evidence profile artifact.
5. Add a stock `role-approvals` state-machine provider and a `role-approval`
   recipe containing only generic organizations, actors, policies, proposals,
   decisions, queries, and governance.
6. Promote the evidence workflow from an ambiguous `examples` dependency to
   an explicitly supported first-party preview domain profile. Keep the
   evidence demo runner and report UI as reference/demo artifacts. Generic
   console panels that supersede the report UI's reusable ideas (effect
   lifecycle, approvals, evidence bundles, proofs, verified payloads) are
   owned by root ADR-028, not by this ADR.
7. Expand the built-in catalog to cover every supported stock machine and the
   implemented sequencing, membership, anchor, observer, sink, and effect
   choices appropriate for project generation.
8. Treat recipes as reviewed bases. Studio and the CLI may add compatible
   capabilities using declared requirements, implications, conflicts, and
   artifact availability.
9. Replace or remove recipes that do not implement their promised outcome.
   `evidence-publication` must select the actual evidence profile and declare
   its connector/bootstrap prerequisites; enabling effects on `ordered-log`
   is insufficient.
10. Add a data-only component product descriptor for first-party and custom
    plugins. It may extend project generation and Studio only after explicit
    operator selection and validation.
11. Bind custom product metadata, configuration metadata, and the runtime
    plugin manifest in a versioned trust envelope. Trust authenticates bytes
    and publisher identity; it does not grant `BUNDLED`, `FULL`, or `STABLE`
    status.
12. Add a no-SPI `appchain-stdlib-contracts` artifact for stock command,
    state, and query codecs. Server implementations remain in
    `appchain-stdlib`.
13. Generate prerequisite and bootstrap plans for recipes that need roles,
    policies, plugin bundles, targets, anchors, or external services. Never
    generate or copy secret values into shared artifacts.
14. Extend `doctor` to report readiness by stage and identify the exact absent
    artifact, public identity, application bootstrap record, executor, target,
    or external prerequisite.
15. Continue to require an explicit stock/custom provider JAR for new
    component ordering, conditional transitions, payload transformations,
    multiple effects, or domain logic.

## 4. Product availability model

### 4.1 Normative tiers

| Tier | Meaning | May be selected without extra artifact work? |
|---|---|---:|
| `BUNDLED` | Included, indexed, tested, and supported by the named distribution | Yes |
| `FIRST_PARTY_OPTIONAL` | Built and tested by Yano but separately installed or included at image build time | No |
| `REFERENCE` | Demonstrates a pattern or domain; no general product support claim | No |
| `EXPERIMENTAL` | API/semantics/maturity may change; controlled experiments only | Explicit opt-in |

Maturity (`stable`, `preview`, `experimental`) remains separate from
availability. A bundled preview component is possible, as is a stable optional
connector.

### 4.2 Required catalog fields

Every capability and recipe records at least:

```text
id
display name and description
category
availability tier
maturity
scope (chain | node | distribution)
selectable
trust statement
provided contracts
requirements, implications, and conflicts
runtime types and deployment targets
artifact or bundle ids
native inclusion posture
external prerequisites
bootstrap requirements
non-secret answers
configuration assignments
documentation reference
acceptance scenario id
```

The release index records which tier is true for each distribution flavor.
“Present in this repository” is not an availability tier.

`scope` and `selectable` close a gap in the current model, where every
catalog capability is implicitly project-selectable and the resolver adds
every selected capability to a chain. The two fields are independent axes:

- `scope` states where a capability applies and where its configuration is
  rendered: `chain` (consensus-shared), `node` (node-local enablement,
  executors, sinks), or `distribution` (present by virtue of the packaged
  artifact).
- `selectable` states whether a blueprint may explicitly request the
  capability.

Defaults preserve current behavior: `scope: chain`, `selectable: true`.
`scope: node, selectable: true` is valid and expected for node-local
executors, sinks, and integrations (§6.3): selecting one pins it in the
project lock and renders node-local configuration, but it contributes
nothing to consensus-shared chain configuration. `scope: node,
selectable: false` fits the embedded console: it is derived from the
selected distribution, never named in a blueprint, and never added to the
selected app-chain capabilities. The resolver rejects a blueprint that
names any non-selectable capability — fail closed, not silently ignored.

### 4.3 Initial classification

The first catalog expansion should classify:

- `ordered-log`, stdlib machines, generic composite runtime, generic
  `role-approvals`, built-in webhook support, metadata/script anchors, fixed
  sequencing, and supported membership choices as `BUNDLED` where the actual
  distribution contains them;
- Kafka, S3-compatible storage, IPFS, and Cardano executors as
  `FIRST_PARTY_OPTIONAL` unless a named distribution includes them;
- the evidence product profile as a first-party `preview` product with its
  actual distribution availability recorded explicitly;
- the embedded node console UI as `BUNDLED` with `scope: node`,
  `selectable: false` — derived from the selected distribution, never named
  in a blueprint, and never added to the selected app-chain capabilities;
  implementation and delivery owned by root ADR-028;
- the evidence runner, Compose environment, report UI, and plugin conformance
  fixture as `REFERENCE`; and
- ZeroJ state machines as `EXPERIMENTAL`.

## 5. Module and ownership boundaries

### 5.1 Generic composite boundary

`appchain-composite` retains only generic composition machinery:

- component and workflow contracts;
- deterministic routing and namespacing;
- exact query dispatch and aggregate queries;
- effect ownership and quotas;
- canonical profiles and catalogs; and
- governed profile evolution.

`appchain-composite-contracts` must not have an API dependency on evidence
contracts. Evidence release commands, evidence workflow capacity, and other
domain contracts move to an evidence-owned contracts/profile artifact.

The stock provider name `composite` may remain only if it has a clearly defined
generic selector contract. An evidence preset must not be the implicit default
of a generic provider. Prefer an explicit evidence provider/profile identity
when that removes ambiguity.

### 5.2 Generic role boundary

`appchain-role-workflow-contracts` remains a no-SPI portable artifact.

The generic executable role artifact contains:

- `DomainActorRegistryComponent`;
- `RoleAwareApprovalsComponent` without evidence-specific indexes;
- `RoleApprovalWorkflow`;
- generic organization, actor, policy, proposal, decision, statistics, and
  proof queries;
- generic domain API projections; and
- the stock `role-approvals` provider.

Evidence-owned code contains:

- `RoleEvidencePreset`;
- evidence release/notify workflows;
- evidence-to-proposal convenience indexes and routes; and
- any fixed manufacturer/auditor/regulator sample policy.

The generic proposal identity is its proposal ID plus payload domain/hash. A
future general secondary index may map `(payloadDomain, payloadHash)` to a
proposal only if its uniqueness and retention semantics are separately
specified. It must not retain the name `evidence-approval` in generic state.

### 5.3 Stock generic role-approval provider

The `role-approvals` provider commits:

```text
domain-actors component
role-approvals component
role-approval workflow
generic profile marker and configuration identity
```

It supports arbitrary application role names and payload domains. It proves
that the active key for a governed actor signed the exact payload hash and
that the configured policy revision was satisfied.

It does not perform a domain transition or emit an external effect. The v1
contract stores only the authorized payload hash, not trusted executable
payload bytes. An automatic generic role-approved effect therefore requires a
separate explicit contract/profile decision rather than an unsafe assumption
that a hash is an executable command.

Applications may:

- query the terminal proposal and act in their own off-chain process;
- submit a separately validated domain command bound to that proposal; or
- install a reviewed composite profile that atomically consumes the approval.

### 5.4 Evidence ownership

Evidence code that is part of a supported release moves out of an ambiguous
`examples` ownership path into an explicitly named first-party domain product
area. Reference launchers and report applications may remain under examples or
demo directories.

The evidence product continues to state its narrow claim: it is an inspection
or compliance-evidence workflow, not a generic workflow engine, legal identity
system, proof of real-world truth, or complete DPP solution.

## 6. Capability and recipe resolution

### 6.1 Recipe-first, capability-second

The safe user flow is:

1. choose one maintained recipe;
2. resolve its required and implied capabilities;
3. optionally add compatible capabilities;
4. answer bounded, non-secret questions;
5. review artifacts, support tiers, prerequisites, and trust statements; and
6. let the CLI resolve and render the authoritative project.

Recipes remain useful because a blank capability graph is not friendly to a
new user. They are defaults, not closed products.

### 6.2 Capability categories

The catalog should support at least:

```text
state
sequencer
membership
finality
anchor
l1-observer
effects-runtime
effect-emission
effect-executor
finalized-sink
api/security
retention
deployment
custom-plugin
```

Only categories with an implemented, validated mapping are exposed. A catalog
entry cannot create runtime behavior merely by naming it.

### 6.3 Consensus assignments and node-local prerequisites

A capability separates:

- consensus-shared property assignments;
- cluster-shared deployment identity;
- node-local enablement and executor ownership;
- secret references;
- artifact requirements; and
- external prerequisites.

The blueprint and lock contain no private keys, mnemonics, passwords, API
tokens, or connector credentials. Secret-reference examples remain unusable
until explicitly provisioned.

### 6.4 One generated project per chain

The first stable product model continues to generate one app chain per
project. This keeps the lock, shared consensus, membership lifecycle,
bootstrap records, artifacts, and GitOps ownership unambiguous.

The runtime may host several separately generated chains on one node. A future
multi-chain project format must define cross-chain port, artifact, secret,
upgrade, and lifecycle ownership before raising `chains.maxItems`.

## 7. Custom component product metadata

### 7.1 Descriptor boundary

Add a versioned, data-only resource, provisionally:

```text
META-INF/yano/appchain-component-catalog-v1.json
```

It contains only bounded declarative metadata. The CLI and Studio must not
load provider classes, initialize the plugin, inspect arbitrary class files,
execute scripts, fetch URLs, or interpolate environment values while reading
it.

The existing runtime manifest remains the executable contribution contract.
The existing configuration metadata remains the property-validation contract.
The component catalog is the product/DX contract.

A future descriptor revision may additionally carry bounded, data-only UI
hints (display name, key committed-query paths, field labels) so the ADR-028
console can render a custom component's queries by name. The same data-only
rules apply; executable or templated UI contributions remain out of scope in
both ADRs. Ownership is split explicitly: this ADR owns the UI-hint
descriptor contract, bounds, and validation; ADR-028 owns whether and how
the console renders the hints.

### 7.2 Trust envelope

A versioned trust envelope binds the hashes of:

```text
runtime plugin manifest
configuration metadata descriptor, when present
component product catalog
bundle identity and version
publisher key id
```

An operator-pinned public key authenticates the binding. Successful
verification does not:

- execute or approve plugin code;
- install the JAR;
- grant namespace ownership;
- make a third-party recipe first-party;
- label validation coverage `FULL`; or
- change its maturity/support tier.

### 7.3 Selection and pinning

The CLI accepts explicitly supplied component catalogs or plugin JARs for
project initialization and rendering. Every selected external descriptor and
runtime artifact digest is pinned in `appchain.lock`.

Catalog ID, capability ID, recipe ID, property ownership, provided-contract,
and artifact collisions fail closed. A plugin may extend the graph but cannot
override a release capability.

For native output, the descriptor must identify a version-matched build-time
artifact already present in the native release index. Supplying a JVM JAR does
not make an existing native executable load it.

### 7.4 Studio import

Studio may import a user-selected local descriptor/JAR metadata snapshot in
the browser. It remains static and offline:

- no remote registry lookup;
- no telemetry or persistence;
- no secrets;
- no code execution;
- strict size, schema, ID, and collision checks; and
- downloaded blueprint only.

The version-matched CLI revalidates everything and remains authoritative.

## 8. Recipe completeness and bootstrap

### 8.1 Recipe contract

Every recipe declares:

- the primary user outcome;
- the capability graph that implements it;
- required runtime/plugin artifacts;
- application bootstrap records;
- node-local executors and external services;
- secret references;
- the first useful command/query; and
- an automated acceptance scenario proving that outcome.

A recipe that merely enables an unused namespace is invalid.

### 8.2 Evidence publication correction

The current `ordered-log + effects.enabled` evidence-publication recipe is
removed or replaced. The replacement selects an evidence state/profile that
actually emits `object.put`, `ipfs.pin`, and/or `kafka.publish` intents and
declares the selected connector bundles and target prerequisites.

If no connector is selected, the recipe is named and documented as an
evidence-ledger foundation rather than external publication.

### 8.3 Role bootstrap

The generic role and role-evidence recipes generate a non-secret bootstrap
plan containing:

- organization records;
- actor IDs, roles, public keys, and revision expectations;
- key proof-of-possession requirements;
- policy records and clause semantics;
- governance proposal/approval/activation steps; and
- exact verification queries/proof keys.

Private actor keys remain in the actor application, KMS, HSM, Vault, or an
owner-only local file. Nodes and Studio never receive them.

Generated bootstrap commands must be idempotent or detect already-applied
state. They must not silently replace existing governed records.

### 8.4 Readiness reporting

`doctor` reports independent stages:

```text
CONFIG_VALID
ARTIFACTS_READY
IDENTITIES_READY
RUNTIME_STARTABLE
APPLICATION_BOOTSTRAPPED
EXECUTORS_READY
EXTERNAL_TARGETS_READY
OUTCOME_READY
```

Not every recipe requires every stage. A plain audit log can be outcome-ready
without an executor. Evidence publication cannot be outcome-ready while its
declared connector is absent.

## 9. Standard-library client contracts

Create a no-SPI `appchain-stdlib-contracts` artifact containing bounded,
versioned client contracts for:

- `kv-registry` put/delete commands and value records;
- `approvals` propose/approve/reject commands, decision records, and generic
  effect-state records;
- `balances` mint/transfer commands and balance records; and
- `doc-trail` append commands, entries, and head records.

The artifact must not contribute plugin manifests, `ServiceLoader` entries,
runtime implementations, storage, or networking. `appchain-client` and the
Spring starter may offer typed adapters using these contracts without
depending on `appchain-stdlib`.

Wire/state compatibility remains owned by the state-machine version. Moving a
helper class into a contracts artifact must not silently change canonical
bytes.

## 10. Distribution and CLI positioning

### 10.1 Public command boundary

`./yano.sh appchain` remains the sole documented user entry point. Internal
`yano-appchain` and `yano-plugins` executables remain packaging and CI
boundaries.

The public command groups should converge on:

```text
appchain recipes
appchain capabilities
appchain init/render/doctor/diff/drift/migrate/gitops
appchain config ...
appchain plugin inspect|validate|scaffold
appchain bootstrap ...
appchain cluster ...
```

This ADR does not require a network marketplace or automatic installation.
`plugin inspect` and `validate` are read-only. Artifact copying remains an
explicit operator action until a separately reviewed install command defines
ownership, recovery, native behavior, and trust policy.

### 10.2 Distribution truth gate

For every release flavor, CI compares:

- the generated runtime plugin index;
- capability and recipe artifacts;
- optional-bundle indexes;
- JVM/native availability claims;
- packaged schemas, Studio assets, and CLI catalogs; and
- documentation support labels.

A capability labelled `BUNDLED` fails the build if the corresponding runtime
contribution or built-in implementation is absent. An optional capability
fails if its separately published bundle cannot pass offline catalog and
packaged-runtime acceptance.

## 11. Studio behavior

Studio adds a capability-selection step after recipe selection:

```text
1. outcome recipe
2. compatible state/profile options
3. topology, sequencing, membership, and finality
4. anchors and observers
5. effects and sinks
6. runtime and deployment
7. artifacts, support tiers, prerequisites, and trust review
8. blueprint download
```

The UI shows why a capability is implied, unavailable, conflicting, optional,
or experimental. It does not show an unsupported checkbox merely because a
property namespace exists.

Studio never renders final runtime configuration. The CLI resolves catalogs,
validates answers, materializes defaults, writes locks, and enforces
no-silent-overwrite.

Studio remains a pre-deployment configuration wizard and is distinct from
the ADR-028 runtime console. Migrating Studio onto the `console-ui`
toolchain and component library is an accepted later follow-up recorded in
ADR-028; it does not change the behavior required here.

## 12. Documentation product

Generate one version-matched component catalog from the same release metadata.
Each capability page or catalog entry links to documentation covering:

- when to use and not use it;
- availability and maturity;
- configuration and bootstrap;
- exact command and state contracts;
- REST, Java, and Spring usage where applicable;
- proofs and trust boundaries;
- effects/external prerequisites;
- operations and recovery; and
- extension guidance.

Add at least:

- a dedicated `balances` reference;
- a generic `role-approvals` reference and tutorial;
- a custom composite/role-plugin scaffold tutorial;
- a first-party connector installation guide separated from the evidence
  scenario; and
- one table clearly distinguishing bundled, optional, reference, and
  experimental capabilities.

Documentation examples and Studio deep links are validated against the exact
release catalog. Historical ADRs remain design history and are not the user
capability catalog.

## 13. Security and trust boundaries

- Consensus execution remains deterministic and performs no external I/O.
- Product metadata is data-only and bounded; reading it never activates
  plugin code.
- Third-party catalogs are explicitly selected and digest-pinned.
- External metadata cannot claim first-party availability, stable maturity, or
  full validation coverage.
- Studio accepts no secrets and performs no remote lookup.
- Shared blueprints, locks, catalogs, and generated consensus configuration
  contain no secret values.
- Effect payloads contain stable action contracts and target aliases, never
  executor endpoints or credentials.
- Connector credentials and actor private keys remain node/actor local.
- A recipe cannot imply that an external effect proves a real-world fact; it
  proves finalized authorization and the executor's bounded outcome report.
- `cardano.payment` remains subject to the existing material-funds readiness
  gates.
- Experimental ZK capabilities remain explicitly labelled and cannot be
  selected accidentally by a stable recipe.

## 14. Compatibility and retained state

App chains, the DX schemas, the generic approval effect, and the role/evidence
profiles have not reached a stable public release in this branch. Module and
catalog boundaries may therefore be corrected without preserving accidental
artifact layout compatibility.

Nevertheless:

- packaging-only moves should preserve canonical command/state/profile bytes
  when practical;
- any changed deterministic semantics, component descriptor, route,
  configuration identity, workflow quota, profile byte, or state layout must
  use a new identity and a fresh chain or governed migration;
- a renamed provider must never attach silently to retained state created by a
  different profile; and
- generated project schema/catalog migrations remain explicit through
  `appchain migrate`.

Disposable demo state may be reset. Production-style retained-state behavior
must always fail closed on identity mismatch.

## 15. Delivery plan

Version 3 implementation status:

| Milestone | Status | Delivered outcome |
|---|---|---|
| M0 | Complete | Availability vocabulary, recipe correction, catalog truth gates, and `v1alpha1` posture |
| M1 | Complete | Evidence-free generic composite/role boundaries and explicit evidence product artifacts |
| M2 | Complete | Bundled `role-approvals` provider, generic domain API, signing support, and bootstrap plan |
| M3 | Complete | Complete first-party capability graph, scoped selection, Studio parity, and staged readiness |
| M4 | Complete | No-SPI stock contracts, typed clients, bootstrap outputs, and packaged outcome acceptance |
| M5 | Complete | Signed data-only custom catalogs, four scaffold modes, digest pinning, and Studio local import |
| M6 | Complete | Static, packaged JVM, host/Compose/GitOps, and GraalVM native release-candidate gates |

M6 deliberately does not stabilize the schemas. The remaining stabilization
condition is external field usage, not unfinished implementation; see
[`docs/appchain/RELEASE_ACCEPTANCE.md`](../../docs/appchain/RELEASE_ACCEPTANCE.md).

### M0 — Product truth and correction

- Add availability/support fields to the release catalog.
- Inventory every bundled and optional contribution from actual manifests.
- Correct “out of the box” documentation.
- Remove or correct the misleading evidence-publication recipe.
- Add catalog/build gates for support-tier and artifact truth.
- Keep schemas `v1alpha1`.

### M1 — Generic module boundaries

- Remove evidence dependencies from generic composite contracts/runtime.
- Remove evidence indexes/routes/workflows from generic role artifacts.
- Create the explicit evidence profile/product artifact.
- Preserve or deliberately version canonical identities.
- Add dependency-boundary and plugin-manifest tests.

### M2 — Generic role-approval product

- Add the `role-approvals` provider and recipe.
- Add generic domain API/query/proof surfaces.
- Add role bootstrap planning and safe offline signing commands.
- Add replay, restart, snapshot, governance, rotation, revocation, proof, and
  negative-role/distinct-organization tests.

### M3 — Complete first-party capability catalog and Studio

- Add missing stdlib, membership, anchor, observer, webhook, connector, and
  applicable ZK capabilities.
- Add compatible capability selection to Studio and CLI init.
- Generate artifact/prerequisite plans and staged doctor results.
- Verify Studio/CLI/release-catalog parity.

### M4 — Standard contracts and application bootstrap

- Add `appchain-stdlib-contracts` and typed Java/Spring helpers.
- Add idempotent bootstrap plans for role/evidence recipes.
- Add component references and tutorials, including balances.
- Add final-distribution outcome acceptance for each stable/preview recipe.

### M5 — Custom component product catalogs

- Add the bounded component catalog and trust envelope.
- Support explicit custom catalog/JAR input in init, render, doctor, and
  Studio local import.
- Add state-machine, composite/role, effect-executor, and sink scaffold modes.
- Pin all external catalog and artifact digests in the project lock.
- Verify collisions, tampering, trust claims, JVM loading, and native
  incompatibility diagnostics.

### M6 — Stabilization and field review

- Exercise generated projects outside repository-authored demos.
- Run JVM/native and host/Compose/GitOps acceptance by supported tier.
- Record usability findings and remove capabilities that lack ownership or
  outcome acceptance.
- Stabilize schemas only after real third-party/plugin usage supplies
  compatibility evidence.

Each milestone is independently reviewable. M0 must not wait for the module
refactor, and custom ecosystem work must not block exposing already bundled
stock machines honestly.

## 16. Acceptance gates

### 16.1 Catalog and packaging

- Every `BUNDLED` capability maps to an actual indexed contribution or tested
  built-in implementation in every advertised distribution.
- Every `FIRST_PARTY_OPTIONAL` capability maps to a reproducible bundle and
  offline catalog validation.
- No `examples` or fixture artifact enters the default product transitively
  without an explicit supported-product decision.
- Native availability is verified at build time; directory JAR support is
  never advertised for native binaries.
- Catalog entries without `scope`/`selectable` resolve as `scope: chain`,
  `selectable: true`; existing catalogs and blueprints resolve unchanged.
- A blueprint that explicitly names a `selectable: false` capability fails
  resolution with a diagnostic naming the capability; it is never silently
  ignored.
- The embedded console's availability is derived from the distribution
  flavor and recorded in the release index without any blueprint selection.
- A selected `scope: node` capability is pinned in the project lock and
  renders only node-local configuration; it contributes no consensus-shared
  chain configuration, and multi-node root parity is unaffected by
  node-capability differences.

### 16.2 Generic dependency boundaries

- Generic composite artifacts contain no evidence API dependency or evidence
  implementation classes.
- Generic role artifacts contain no evidence workflows, routes, keys, or
  state-machine provider dependency.
- Evidence artifacts depend on generic composite/role contracts, not the
  reverse.
- Contracts artifacts contain no plugin manifest or `ServiceLoader` entry.

### 16.3 Recipe outcomes

- Every advertised recipe generates and validates through the final packaged
  CLI.
- Every stable/preview recipe has a final-distribution test exercising its
  primary outcome, not merely process startup.
- Role recipes prove governed bootstrap and at least one valid and invalid
  actor decision.
- Effect recipes prove emission, executor readiness or explicit external-worker
  ownership, result incorporation, and proof availability.
- Evidence publication proves actual object/IPFS/Kafka behavior for the
  connectors it claims.

### 16.4 Custom metadata

- Descriptor parsing is size bounded, strict, duplicate detecting, and
  resource-only.
- Plugin classes are never loaded by validation, init, render, doctor, or
  Studio.
- Collision, tampering, missing trust, and digest drift cases fail closed.
- Third-party descriptors cannot escalate availability, maturity, or
  validation coverage.
- Studio-produced custom blueprints resolve identically in the packaged CLI.

### 16.5 Determinism and lifecycle

- New/changed stock providers pass deterministic replay, kill/reopen,
  snapshot/restore, and multi-node root-parity tests.
- Consensus defaults are explicit in locks and generated shared YAML.
- Regeneration retains no-silent-overwrite and digest ownership.
- `doctor`, diff, drift, migration, and GitOps tests cover new capability and
  artifact identities.

### 16.6 Documentation

- Generated capability tables match the release catalogs exactly.
- Every recipe and capability documentation link exists.
- Commands and Studio deep links resolve against the packaged release.
- Optional and experimental components are never described as bundled or
  production-ready.

## 17. Alternatives considered

### 17.1 Add documentation only

Rejected. Documentation cannot make omitted capabilities selectable, separate
generic APIs from evidence dependencies, or let custom plugins participate in
project generation.

### 17.2 Add more hard-coded recipes without a capability model

Rejected. Recipe combinations would grow combinatorially and every new
connector or custom component would require CLI and Studio code changes.

### 17.3 Allow arbitrary YAML composition

Rejected. Component ordering, routing, namespaces, state compatibility,
workflows, quotas, and effects are consensus-critical. A reviewed provider and
committed profile remain the deployable unit.

### 17.4 Keep generic and evidence code in the same artifacts

Rejected. It forces non-evidence users to depend on evidence contracts,
obscures ownership, makes default-distribution packaging accidental, and
prevents a truthful generic role/composite product.

### 17.5 Make the evidence demo runner the generic bootstrap engine

Rejected. It intentionally assumes one domain, fixed connector semantics,
three endpoints, sample policies, and report structure. Reusable bootstrap
contracts belong in generic tooling; the runner remains a high-value reference
and acceptance application.

### 17.6 Add a network plugin marketplace now

Rejected. Local, explicitly selected, digest-pinned descriptors provide the
required extensibility without introducing remote trust, availability,
dependency-resolution, install, rollback, or supply-chain policy.

### 17.7 Put all stock, connector, and domain code in one artifact

Rejected. It increases the privileged dependency surface, native image size,
startup/catalog scope, and support ambiguity. Availability tiers and explicit
artifacts provide a clearer boundary.

## 18. Consequences

### 18.1 Positive

- Existing implementation becomes visible and usable without memorizing YAML.
- Auditor, regulator, employee, service, or device roles become a generic
  product rather than an evidence-demo feature.
- Composite and role libraries become clean building blocks for custom
  plugins.
- Evidence remains a complete first-party scenario without contaminating
  generic APIs.
- New first-party and custom components can extend one catalog model.
- Studio, CLI, distribution, docs, and tests share one source of truth.
- Users can tell immediately whether a capability is bundled, optional,
  reference-only, or experimental.
- Recipes become verifiable user outcomes instead of configuration snippets.

### 18.2 Negative and costs

- Module separation changes build files, package ownership, manifests, native
  inclusion, tests, and documentation.
- A complete capability graph and outcome acceptance suite require ongoing
  release discipline.
- Generic role bootstrap is still operationally meaningful; tooling cannot
  establish whether a real-world actor deserves a role.
- Optional connectors still require external services, credentials, target
  policy, and operator review.
- Plugin product metadata introduces another versioned descriptor and trust
  contract.
- Keeping recipes and capabilities honest may temporarily remove advertised
  options until their end-to-end outcome is complete.

## 19. Expected final user experience

A user starts with either Studio or the public CLI:

```bash
./yano.sh appchain recipes
./yano.sh appchain init
```

The user selects an outcome such as:

```text
audit log
owned registry
member approval
role approval
document trail
balances
evidence publication
custom state machine/composite
```

The user then selects compatible sequencing, membership, anchoring, effects,
sinks, runtime, and deployment choices. The review shows support tier,
maturity, required artifacts, external prerequisites, and trust statements.

The generated project contains:

```text
appchain.yaml
appchain.lock
shared and per-node YAML
artifact/plugin plan and pinned identities
secret-reference examples
bootstrap plan
start/status/stop/validate scripts
CI verification
Helm/Kustomize derivatives when requested
project-specific trust, operations, and verification documentation
```

The operator completes public identities, secret provisioning, application
bootstrap, and external targets, then runs:

```bash
./yano.sh appchain render my-project
./yano.sh appchain config validate --mode project my-project
./yano.sh appchain doctor my-project --distribution .
./my-project/scripts/bootstrap
./my-project/scripts/start
```

The application uses REST, the generic Java client, typed stock contracts, or
the Spring starter. New deterministic business transitions still require a
small, explicit, tested plugin rather than hidden configuration behavior.

## 20. Implementation resolutions

1. The evidence registry and evidence profile are bundled preview products in
   the named JVM and native distributions. Kafka, object store, IPFS, and
   Cardano executors remain first-party optional bundles.
2. The generic provider ID is `role-approvals`; `role-workflow` remains the
   module and plugin-bundle identity.
3. The generic v1 product requires proposal-ID lookup. The
   `(payloadDomain, payloadHash)` convenience index remains evidence-owned and
   does not leak into generic state.
4. The component-catalog trust envelope binds the product catalog, runtime
   manifest, and optional configuration metadata as one explicit data-only
   product trust contract. It does not replace runtime loading policy or grant
   support-tier escalation.
5. Implemented anchors, observers, sequencing, membership, effects, sinks, and
   executors appear only where the release catalog has an exact validated
   mapping. Experimental and non-selectable distribution capabilities remain
   visibly labelled and cannot be selected accidentally.
6. One chain per generated project remains the `v1alpha1` contract. The runtime
   can host multiple independently generated chains; a fleet manifest is a
   separate future decision.
7. All bundled recipes advertise and pass JVM and native generation. The three
   stable recipes have packaged outcome acceptance; the two preview recipes
   have module outcome plus packaged-provider acceptance. The custom-plugin
   recipe is JVM-only, reference, and experimental.
