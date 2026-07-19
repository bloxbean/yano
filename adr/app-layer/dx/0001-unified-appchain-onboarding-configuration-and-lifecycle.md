# ADR-DX-0001 v8: Unified App-Chain Onboarding, Configuration, and Lifecycle

## Status

Proposed — implementation review draft v8

This consolidated review draft incorporates the findings from the completed
review rounds. It is the single proposal to use for subsequent review and
implementation planning.

This ADR changes developer tooling, generated project contracts, configuration
metadata, and validation. It does not by itself change app-chain consensus,
wire formats, state roots, plugin activation, retained history, or governed
profile-evolution rules.

## Date

2026-07-19

## Review history

- **v8:** Records the M1 implementation boundary: versioned data-only
  blueprint, lock, capability, and recipe contracts; a reusable
  implication/conflict/artifact resolver; guided and non-interactive
  initialization; deterministic host/Compose rendering; explicit
  consensus-default materialization; bootstrap acknowledgements; secret
  references; and digest-protected generated-file ownership.
- **v7:** Records the M0b implementation boundary: runtime and tooling share
  the framework/effects parsers and side-effect-free startup rules; resolved
  sources use the runtime-selected SmallRye Config engine with profile,
  ordinal, expression, typed-conversion, provenance, redaction, and explicit
  ambient-source controls; `config effective` and resolved validation are
  available without putting Quarkus or runtime storage on the CLI classpath.
- **v6:** Records the M0a implementation boundary: a lean standalone CLI,
  deterministic schema/catalog exports, a versioned data-only component
  metadata descriptor, explicit custom-plugin metadata loading, and trust
  rules that prevent external descriptors from claiming runtime-verified
  constraints or `FULL` coverage.
- **v5:** Defines `yano.sh` as the distribution command dispatcher, reuses the
  existing N-node app-chain cluster launcher, separates node and DX
  executables, and fixes the `appchain-config`/`appchain-devtools` module and
  distribution boundaries.
- **v4:** Removes the temporary review drafts and their references so this is
  the sole app-chain onboarding ADR.
- **v3:** Corrects the M0b validator-boundary dependency, defines safe M0a
  constraint provenance and warning behavior, and adds launcher/template
  contract parity to CI.
- **v2:** Splits M0 into fast template tooling and runtime-parity resolved
  validation, prefers reuse of SmallRye Config for source resolution, defines
  how template completeness is declared, and establishes the unified plan.
- **v1:** First unified proposal.

Future review rounds should update this section and the internal document
version. They should not create competing implementation plans unless a
materially different architecture is being proposed.

## Related decisions and implementation surfaces

- [ADR-005](../005-yano-app-chain-framework.md) defines the app-chain runtime,
  configuration, finality, storage, and anchoring foundation.
- [ADR-008](../008-appchain-next-iteration-plan.md) identifies developer
  experience and enterprise polish as an app-chain delivery track.
- [ADR-008.2](../008.2-rotating-sequencer.md),
  [ADR-008.3](../008.3-chain-governed-membership.md), and
  [ADR-008.4](../008.4-script-anchors-l1view.md) define sequencing,
  membership, and settlement choices that onboarding must model safely.
- [ADR-010](../010-deterministic-effect-system.md) defines deterministic
  effect intent, execution roles, gates, outcomes, and retention.
- [ADR-011.2](../011.2-manifested-bundle-catalog.md) defines the strict runtime
  plugin manifest and catalog contract.
- [ADR-013](../013-first-party-integration-connectors-and-effect-demo.md)
  defines first-party connector bundles and the complete evidence demo.
- [ADR-013.2](../013.2-deterministic-composite-state-machine.md) and
  [ADR-015](../015-governed-composite-profile-evolution.md) define committed
  composite profiles and governed evolution.
- [ADR-016](../016-authenticated-appchain-consensus-profile-and-typed-runtime-limits.md)
  defines authenticated consensus-profile values and bounded runtime limits.
- [ADR-019](../019-reusable-domain-actor-registry-and-role-aware-approvals.md)
  defines business-actor identities and role-aware evidence workflows.
- `YanoPropertyKeys.AppChain`, `AppChainConfig`, `YanoProducer`,
  `EffectsSettings`, first-party plugin parsers, the plugin catalog, and the
  current launchers are the configuration implementation surfaces.
- `app/bin/yano.sh` is the current JVM/native node launcher and becomes the
  archive-level command dispatcher without breaking existing start commands.
- `app/appchain-cluster/cluster.sh` is the existing local N-node app-chain
  demo/evaluation launcher and is reused rather than duplicated.
- `plugin-catalog` provides the existing separate-CLI packaging pattern.
- [`docs/appchain/README.md`](../../../docs/appchain/README.md) is the
  task-oriented app-chain tutorial hub.
- [`docs/APP_CHAIN_USER_GUIDE.md`](../../../docs/APP_CHAIN_USER_GUIDE.md) is
  the current comprehensive configuration guide.

---

## 1. Context

Yano app chains expose many useful capabilities:

- deterministic applications and composite profiles;
- fixed or rotating sequencing;
- static or governed membership;
- finality policies and member thresholds;
- metadata or script-based Cardano settlement;
- deterministic effects, gates, executors, and outcome commitments;
- query, SSE, webhook, Kafka, S3, IPFS, Cardano, and ZK integrations;
- JVM, native, container, and embedded packaging;
- plugin bundles and build-time catalogs;
- snapshots, proofs, evidence bundles, metrics, health, backup, and recovery;
  and
- domain actors and role-aware approval workflows.

The current runtime configuration remains flexible, but onboarding requires a
new user to understand a large number of low-level properties and their hidden
relationships. The user must also know which values:

- must be identical on every member;
- may differ by member or deployment role;
- are secrets and must never enter tracked configuration;
- require an additional plugin artifact;
- are unavailable in a selected native build;
- depend on another capability;
- cannot be changed safely for retained history; or
- require governance rather than a local YAML edit.

The existing tutorials, demo launchers, plugin catalog, validators, and example
configurations contain much of the required knowledge, but they do not form one
versioned contract. Copying a demo is useful for exploration, not sufficient
for a long-lived project that must be reviewed, regenerated, upgraded, and
operated by several organizations.

The onboarding question should be:

> Given this Yano release, distribution, deployment target, trust model, and
> desired application outcomes, generate and explain a supported project.

It should not be:

> Which of these dozens of `yano.app-chain.*` properties do you know how to
> combine?

## 2. Problem statement

### 2.1 Users currently choose mechanisms before outcomes

A new user commonly starts with an outcome such as:

- an auditable ordered log;
- an owner-controlled registry;
- a multi-party approval workflow;
- evidence publication to external storage;
- a role-aware consortium application; or
- a custom deterministic plugin.

The current configuration path quickly asks the user to choose mechanisms such
as gates, anchors, effect targets, proposer settings, retention horizons, and
plugin namespaces. These remain valuable expert controls, but they are poor
first questions.

### 2.2 Syntax validation is not semantic validation

A YAML parser can verify that a document is well formed. It cannot, without
additional metadata and rules, prove that:

- a selected property is owned by an installed capability;
- a dynamic-prefix property is spelled correctly;
- rotating sequencing has a usable L1 slot source;
- an L1-anchored effect gate has compatible settlement;
- native packaging contains a selected contribution at build time;
- a threshold is safe for the requested topology; or
- an apparent config edit actually requires a new chain.

### 2.3 Capability selection changes artifacts and roles

Selecting Kafka, S3, IPFS, Cardano, ZK, or another extension may change:

- the required JARs or manifested bundles;
- the native build-time catalog;
- node and external-executor roles;
- secrets and infrastructure prerequisites;
- health and readiness checks; and
- generated client examples.

A form that only emits properties will fail late when the selected runtime
cannot provide the capability.

### 2.4 Dynamic property namespaces can hide mistakes

Broad runtime prefixes such as `sinks.*`, `zk.*`, `machines.*`,
`sequencer.*`, `membership.*`, `observers.*`, `transport.*`, and `effects.*`
are forwarded to lower-level consumers. The exact accepted fields are spread
across framework and plugin parsers. A typo may therefore pass initial config
loading, be ignored, or fail only after a capability is activated.

### 2.5 One-shot generation has no lifecycle

Generating a large YAML file once and asking the user to own and edit it gives
good day-zero convenience but no reliable regeneration, upgrade, explanation,
or semantic diff. App chains are long-lived systems, so the intent and the
resolved release-specific result must both be retained.

### 2.6 Shared templates are not complete node configurations

The checked-in app-chain YAML is intentionally a shared launcher template.
Member identities, peers, thresholds, proposers, secrets, and other values are
injected by node-specific or private overlays. Validation must distinguish an
incomplete but valid template from a fully resolved node configuration.

## 3. Decision drivers

The selected design should:

1. reduce the common path to a small number of outcome, trust, and deployment
   decisions;
2. preserve access to expert configuration;
3. keep the existing runtime configuration path authoritative at startup;
4. produce deterministic, reviewable, regenerable output;
5. validate both configuration semantics and actual artifact availability;
6. treat consensus defaults, secrets, and retained history conservatively;
7. work offline with an official distribution;
8. support hand-authored projects, not only generated ones;
9. provide one contract for CLI, web, IDE, documentation, tests, and AI;
10. support incremental delivery without creating a throwaway generator; and
11. fail conservatively when metadata or change safety is unknown.

## 4. Options considered

### 4.1 More documentation and sample YAML files

This is inexpensive and remains useful, but examples grow combinatorially,
drift from runtime behavior, and cannot provide artifact resolution, a lock,
semantic diffs, or cross-member diagnostics.

**Decision:** retain documentation and examples as outputs and learning aids,
not as the primary onboarding architecture.

### 4.2 Runtime presets or Quarkus profiles

Profiles provide fast common paths but do not naturally own topology, plugin
artifacts, secret placement, generated client code, or lifecycle policy.
Version-dependent runtime expansion could also hide consensus changes.

**Decision:** do not introduce generalized runtime app-chain presets in v1.
Use versioned generation-time recipes that resolve to explicit configuration.

### 4.3 One-shot CLI templates

A CLI that expands a preset into user-owned YAML is straightforward and gives
immediate value. It lacks a durable high-level source, lock, regeneration, and
upgrade path.

**Decision:** use a CLI, but generate from a durable blueprint plus lock rather
than treating expanded output as the source of truth.

### 4.4 Capability-driven offline initializer

An offline CLI can inspect the actual extracted release, work in automation,
reuse secure launcher behavior, and perform semantic resolution.

**Decision:** this is the first official interface.

### 4.5 Static web initializer

A browser experience offers excellent discovery, progressive disclosure, and
shareable non-secret intent. A second browser resolver would risk divergence
from the Java resolver, and browser code must still be treated as capable of
leaking inputs.

**Decision:** add a static discovery frontend after the CLI contract is stable.
In its first version it generates a release-pinned blueprint and previews safe
non-secret output. The local CLI remains authoritative for producing the lock
and runnable project. Full in-browser project generation is allowed only if it
uses a portable shared resolver or passes the same conformance vectors and
lock-parity gate as the CLI.

### 4.6 In-node configuration editor

A node must already be configured to start, and a single running member should
not mutate shared consortium intent.

**Decision:** do not build an in-node editor as the onboarding path. A runtime
UI may later display redacted effective identities and drift read-only.

### 4.7 Kubernetes operator first

An operator is useful for later GitOps reconciliation but excludes host and
Compose users and would stabilize deployment reconciliation before the intent
model is proven.

**Decision:** defer. A future CRD may consume the stable blueprint.

### 4.8 AI-only configuration

Conversational assistance can explain tradeoffs and gather intent, but an LLM
must not invent low-level properties, defaults, or safety classifications.

**Decision:** AI may be a frontend over the deterministic blueprint,
validation, resolution, and rendering contracts. It is never authoritative.

## 5. Decision

Yano will build a **schema-backed, capability-driven App-Chain Initializer and
configuration lifecycle**.

The architecture has three metadata layers and one deterministic resolver:

```text
Layer 1: authoritative property registry
  paths/patterns, types, defaults, bounds, ownership, scopes, change policy
                              |
                              v
Layer 2: release capability catalog
  outcomes, questions, requirements, conflicts, artifacts, roles, checks
                              |
                              v
Layer 3: reviewed recipe catalog
  supported capability sets, defaults, tutorials, acceptance scenarios
                              |
                              v
                 deterministic resolver/generator
                 /        |          |          \
          offline CLI  static web  IDE/schema  AI frontend
                 \        |          |          /
                              v
      blueprint + lock + explicit config + overlays + deployment + docs
```

The concise product rule is:

> Users select application outcomes, trust assumptions, and deployment roles.
> Yano derives, validates, pins, explains, and tests the exact configuration
> and artifacts.

The normative decisions are:

1. `appchain.yaml` is the durable user-authored intent.
2. `appchain.lock` is the release-specific resolved result.
3. Runtime YAML/properties, overlays, plugin selections, and deployment files
   are regenerable outputs.
4. The existing `yano.app-chain.*` runtime properties remain the startup path;
   the blueprint is not a second runtime config parser.
5. Every consensus-shared value, including a current default, is explicitly
   materialized and locked.
6. A capability must resolve both configuration and artifacts for the selected
   distribution.
7. The official offline CLI is authoritative for resolution and rendering.
8. Hand-authored runtime configuration remains supported with clearly stated
   validation coverage.
9. Unknown properties and unknown change safety fail conservatively.
10. Initialization and rendering never mutate a running chain or retained
    app-chain database.
11. `./yano.sh` is the common entry point in an extracted distribution;
    existing `start` and `start:<profiles>` commands remain compatible.
12. App-chain DX commands execute in a separate `appchain-devtools` process,
    not inside the Quarkus node JAR or native node process.
13. Local N-node demos reuse `appchain-cluster/cluster.sh`; Yano does not add a
    competing `yano-cluster.sh`.
14. Shared configuration definitions live in `appchain-config`; initializer,
    resolver, and lifecycle commands live in `appchain-devtools`.

## 6. Goals and non-goals

### 6.1 Goals

- Generate a supported app chain without requiring knowledge of every runtime
  property.
- Start with outcome-oriented recipes and progressively disclose expert
  controls.
- Establish authoritative machine-readable property metadata.
- Detect unknown, misspelled, unowned, misplaced, or inactive configuration
  before startup.
- Generate complete projects rather than isolated YAML fragments.
- Resolve JVM/native/container artifact compatibility before launch.
- Separate consensus-shared, cluster-shared, node-local, secret,
  infrastructure, and client settings.
- Pin versions, schemas, catalogs, artifacts, defaults, profiles, and generated
  output.
- Explain every implication and source.
- Classify changes conservatively.
- Work offline and in non-interactive CI.
- Connect each advertised recipe to documentation and an executable packaged
  smoke test.

### 6.2 Non-goals

- A visual language for arbitrary deterministic state transitions.
- Runtime hot mutation of consensus configuration.
- Automatic retained-chain migration.
- Bypassing governed profile or membership activation.
- Provisioning production wallets, funds, KMS/HSM policy, external accounts,
  or organization PKI.
- Uploading production secrets to a web service or AI provider.
- Supporting every cross-product of features in the first release.
- Replacing custom plugin development or conformance tests.
- Making Kubernetes mandatory.
- Treating generated output as proof that a deployment is production-ready.

## 7. Authoritative configuration model

### 7.1 Shared module and developer-tools boundaries

Yano introduces two Gradle modules:

```text
:appchain-config       path: appchain/appchain-config
:appchain-devtools     path: appchain/appchain-devtools
```

`appchain-config` is a small public, side-effect-free library consumed by
runtime code and developer tooling. It owns or exposes:

- typed property definitions;
- value parsing and normalization;
- bounds and cross-field validation interfaces;
- redaction rules;
- config-source provenance types;
- scope and change-policy enums; and
- a public validation facade for framework-level config.

This resolves a current module-boundary problem: tooling linked only to
`core-api` cannot directly invoke package-private runtime helpers such as
`EffectsSettings`. The implementation must either move side-effect-free
definitions into the shared module or expose them through a public facade.
The CLI must not duplicate runtime validation logic.

The first template-only tooling slice does not depend on completing this
module refactor. It may consume an initial read-only property registry for
structural, ownership, type, and documented-bounds checks. Complete resolved
validation is not shipped until the public validator boundary can call the
same semantic rules as the runtime.

`appchain-devtools` is a standalone application module. It owns:

- CLI parsing and machine-readable diagnostics;
- template, resolved, and project validation orchestration;
- effective config and explanation;
- capability/recipe resolution;
- blueprint, lock, and project rendering;
- `doctor`, `diff`, and migration commands; and
- schema/catalog/codestart loading.

Its principal dependencies are `appchain-config`, `plugin-catalog`, SmallRye
Config, Jackson/YAML, and a CLI framework. It does not boot or depend on the
Quarkus `app` as its execution model.

M0a keeps this dependency surface deliberately smaller: it uses
`appchain-config` plus Jackson/YAML and a small internal command parser.
`plugin-catalog`, SmallRye Config, and any dedicated CLI framework are added
only by a milestone that needs them. This keeps template validation small and
does not pre-commit resolved-mode behavior before M0b.

The `app` module assembles the final distributions and may depend on the
`appchain-devtools` install/package task. It does not add the dev-tools
implementation to the running node's application dependency graph. This
follows the existing separate-application pattern used by `plugin-catalog`.

### 7.2 Property definitions

Each framework or first-party property definition includes:

```text
stable property id
canonical flat path and/or bounded path pattern
flat and chains[i] applicability
owning framework area or capability id
type and normalized representation
default or explicit no-default
allowed values and numeric/string/collection bounds
constraint source/provenance and verification status
required / requiredWhen / forbiddenWhen
safety scope
change policy
secret classification and redaction behavior
description and stable documentation link
deprecation and replacement metadata
schema version introduced/removed
validation coverage level
```

Safety scope is one of:

| Scope | Meaning |
|---|---|
| `CONSENSUS_SHARED` | Must resolve identically for every member and replay |
| `CLUSTER_SHARED` | Shared topology/deployment intent, but not deterministic apply state |
| `NODE_LOCAL` | May differ by role/member without changing consensus semantics |
| `SECRET` | Private value; generated tracked output contains only a reference |
| `INFRASTRUCTURE` | External service or resource declaration |
| `CLIENT` | Consumer application setting, not node runtime configuration |

Change policy is one of:

| Policy | Meaning |
|---|---|
| `LIVE_SAFE` | Supported without restart or consensus change |
| `RESTART_REQUIRED` | Same retained history/profile; process restart required |
| `ROLLING_DEPLOY_FIRST` | Code/artifact must reach relevant members first |
| `GOVERNED_ACTIVATION` | Requires finalized future activation |
| `NEW_CHAIN_REQUIRED` | Cannot reinterpret retained history safely |
| `SECRET_ROTATION` | Requires an identity/credential-specific runbook |
| `UNSUPPORTED` | No safe automated transition is defined |

Unknown or unclassified transitions default to `UNSUPPORTED`, never
`LIVE_SAFE`.

Validation coverage is one of:

| Coverage | Meaning |
|---|---|
| `FULL` | All accepted keys/patterns and semantic rules are represented and parity-tested |
| `PARTIAL` | Known keys are validated, but the namespace has documented uncovered behavior |
| `UNSUPPORTED_METADATA` | Runtime use is possible, but strict metadata-based validation is unavailable |

The CLI reports coverage for each selected capability and must not claim
strict typo detection for a `PARTIAL` or `UNSUPPORTED_METADATA` namespace.

### 7.3 Source of metadata

"Generated from code" means shared typed definitions and build-validated
sidecars, not source scraping alone.

Constants such as `YanoPropertyKeys.AppChain` remain useful canonical names,
but Java constants and exception messages cannot reliably express
descriptions, conditional requirements, scopes, or change policy. The
implementation proceeds incrementally:

1. Introduce typed definitions for framework-owned properties.
2. Reuse those definitions progressively in runtime parsing.
3. Derive fixed `chains[i]` suffixes from the registry rather than maintaining
   a second list.
4. Export JSON Schema and catalogs from the definitions.
5. Use strict sidecars for plugin-owned metadata where parser centralization
   is not practical.
6. Fail builds when the registry, sidecar, parser parity tests, or capability
   assignments disagree.

During M0a, defaults and bounds are sourced directly from public runtime
definitions such as `AppChainConfig` constants wherever they exist. A
constraint entered only from documentation or manual inventory is marked
unverified and is advisory until a runtime-backed definition or parser parity
test confirms it.

This reduces drift and detects inventory mismatch. It does not claim that
semantic drift is impossible.

The M0a sidecar resource is versioned as:

```text
META-INF/yano/appchain-config-metadata-v1.json
```

It contains data only and is read without loading plugin classes. An explicit
`--metadata <descriptor|plugin.jar>` option allows hand-authored and custom
plugin metadata to extend exact properties beneath runtime-forwarded
namespaces. Exact ownership collisions fail closed, and the most-specific
declared namespace owns a nested match. Because explicit external descriptors
are not release-trusted parity evidence, they cannot claim
`PUBLIC_RUNTIME_DEFINITION`, `RUNTIME_PARSER_TEST`, or `FULL` coverage; their
hand-authored constraints remain `DOCUMENTED_UNVERIFIED` warnings.

### 7.4 Ownership of dynamic namespaces

Broad prefixes do not imply that every suffix is valid. The registry supports
exact keys and bounded patterns for named targets, aliases, topics, circuits,
and observer instances.

Illustrative ownership:

```text
machines.kv-registry.*                  owner: appchain-stdlib/kv-registry
machines.composite.*                    owner: appchain-composite
effects.executors.kafka.*               owner: appchain-kafka
sinks.kafka.*                           owner: appchain-kafka
zk.circuits[*].*                        owner: appchain-zk
observers.<instance>.*                  owner: selected observer provider
```

First-party parsers should reject unknown owned fields once full descriptor
coverage exists. Third-party plugins may continue through the advanced JVM
workflow, but incomplete metadata is visibly labeled.

## 8. Capability, recipe, and release catalogs

### 8.1 Capability definition

A capability is a user-selectable outcome or mechanism. Its descriptor
contains:

```text
id, name, category, keywords, maturity, description
provides, requires, implies, conflicts
Yano version and API compatibility
JVM/native/container availability
runtime bundle and contribution selectors
property assignments and questions
deployment roles and placement constraints
secret references and external prerequisites
safe codestart/template fragments
health, readiness, and smoke checks
tutorial and reference links
```

Examples include:

```text
state:ordered-log
state:kv-registry
state:role-evidence
sequencer:rotating
membership:governed
settlement:script
sink:kafka
effect:object.put
client:spring-boot
```

Capability assignments reference stable property IDs. The build rejects an
unknown property, conflicting ownership, secret value embedded in metadata,
or assignment to an incompatible scope.

### 8.2 Runtime plugin manifest versus dev-tools descriptor

The strict runtime plugin manifest remains focused on safe runtime discovery,
bundle identity, compatibility, dependencies, and contributions. It is not
expanded into a user-experience schema containing prose, forms, codestarts, or
deployment instructions.

The capability descriptor is a companion developer-tools resource. Build and
release checks connect the two through bundle/contribution identity and prove
that an advertised capability is present in the selected distribution.

### 8.3 Recipe definition

A recipe is a reviewed set of capabilities and recommended non-secret inputs,
not a second implementation or a runtime preset. It contains:

```text
recipe id, version, maturity, and trust statement
selected capabilities and recommended answers
supported runtime/deployment matrix
required placeholders and secret-reference forms
post-generation/bootstrap steps
tutorial link and acceptance scenario
```

Initial target recipes are:

- `audit-log`;
- `owned-registry`;
- `approval-workflow`;
- `evidence-publication`;
- `role-evidence`; and
- `custom-plugin`.

The first initializer milestone may ship only a smaller supported subset, but
it must use the same descriptor and resolver architecture.

### 8.4 Recipe, tutorial, and smoke-test contract

The following relationship is normative:

- every advertised recipe has a task-oriented tutorial;
- every capability-bearing tutorial points to a recipe or explicitly states
  why it is an advanced/manual path;
- the packaged smoke scenario is the executable form of the tutorial's core
  success path;
- CLI help, docs, and future UI obtain recipe lists from the release catalog;
  and
- tutorial deep links encode only release-pinned non-secret intent.

A recipe is therefore a tested onboarding promise, not merely an example.

### 8.5 Release capability index

Each official distribution publishes a signed or release-integrity-protected
index that records:

- Yano version and distribution flavor;
- property, capability, recipe, and schema digests;
- available runtime bundles and contributions;
- JVM/native/container availability;
- supported recipes and deployment targets;
- maturity and support labels; and
- codestart/template digests.

The CLI inspects the actual local release and rejects a selection that is not
present even if a remote catalog advertises it.

### 8.6 Published build outputs

Each official release publishes versioned artifacts such as:

```text
appchain-blueprint-schema.json
appchain-runtime-config-schema.json
appchain-property-catalog.json
appchain-capability-catalog.json
appchain-recipe-catalog.json
appchain-release-capability-index.json
appchain-codestarts.zip
yano-devtools-<version>.zip
```

The runtime-config schema and blueprint schema are separate contracts. The
former describes hand-authored/generated runtime keys; the latter describes
user intent.

## 9. Blueprint contract

### 9.1 Source of truth

`appchain.yaml` is concise, user-authored, and durable. It records intent, not
release-specific expanded defaults or private values.

The initial API uses a Kubernetes-style versioned envelope because it is
readable, migratable, and can later be consumed by GitOps tooling. The API
group below is provisional until schema stabilization.

```yaml
apiVersion: yano.bloxbean.com/v1alpha1
kind: AppChainProject

metadata:
  name: product-evidence

spec:
  yanoVersion: 0.1.0
  network: preprod

  runtime:
    type: jvm

  deployment:
    target: docker-compose

  chains:
    - chainId: product-evidence
      recipe: role-evidence

      topology:
        members: 3
        finality: two-thirds
        sequencing: rotating
        membership: governed

      settlement:
        mode: script
        cadenceBlocks: 30

      integrations:
        - objectstore-s3
        - kafka

      client:
        type: spring-boot
```

`spec.chains[]` is reserved in `v1alpha1`, while the first supported release
may restrict a project to one chain. This avoids a later schema shape change
without forcing multi-chain orchestration into the initial milestone.

The blueprint never contains member private keys, actor seeds, connector
credentials, API secrets, or anchor mnemonics.

### 9.2 Advanced overrides

Users customize the blueprint or an explicit validated overlay, not generated
files in place. An override:

- must resolve to an owned property or bounded property pattern;
- is shown in effective config with its source;
- is assigned a safety scope and change policy;
- is included in the lock when relevant; and
- fails resolution when incompatible with the selected capability set.

An explicit `unsupportedRawProperties` escape hatch may preserve expert
experimentation. Selecting it:

- disables supported-recipe claims;
- marks validation coverage as degraded;
- prevents strict semantic diff guarantees; and
- requires a visible acknowledgement in the lock.

## 10. Resolver behavior

The deterministic resolver:

1. loads the blueprint and its schema version;
2. selects the exact local release property/capability/recipe catalogs;
3. validates blueprint structure and supported combinations;
4. expands the recipe at generation time;
5. resolves implications and rejects conflicts;
6. verifies actual runtime, bundle, contribution, and native availability;
7. derives numeric values such as member threshold and displays them;
8. assigns properties/resources to shared, node, executor, client, secret, and
   infrastructure outputs;
9. materializes every consensus-shared value, including defaults;
10. validates all resolved framework and first-party settings through the
    shared validator facade;
11. creates bootstrap, trust, and verification instructions;
12. emits a normalized redacted plan;
13. writes the lock and generated outputs deterministically; and
14. reports coverage, maturity, and unsupported acknowledgements.

Required implication examples include:

- rotating sequencing requires a usable L1 slot feed and forbids a fixed
  proposer;
- `l1-anchored` effect gates require compatible anchoring and L1 state;
- Kafka sink and Kafka effect executor remain independently selectable;
- S3, IPFS, Cardano, and ZK selections require their exact bundles and roles;
- role-evidence requires its composite/profile and dependency set;
- a native runtime requires selected contributions in its build-time catalog;
- retention requires a compatible finality horizon and archival decision;
- enabled executors require factories, role placement, and identity;
- result-signer restrictions must match executor/member policy; and
- governed membership/profile choices cannot be applied to retained history
  through an ungoverned local edit.

Conflict and implication rules are declarative where practical, but rules that
require runtime domain logic call the shared validator. Frontends do not
reimplement either class of rule independently.

## 11. Lock contract and identity model

### 11.1 Lock contents

`appchain.lock` contains release-specific, normalized, non-secret resolution:

```text
lock format version
blueprint schema id and digest
property, capability, recipe, and release-index ids/digests
Yano version and distribution flavor
network identity
resolved recipe and implied capabilities
all resolved consensus-shared values, including defaults
relevant cluster-shared values
plugin ids, versions, artifacts, and digests
actual plugin catalog fingerprint
expected consensus-profile commitment when derivable
composite profile digest when applicable
anchor script/thread-policy identities when applicable
generated non-secret file digests
resolvedConfigDigest
validation coverage and maturity labels
unsupported/experimental acknowledgements
```

Secrets are never stored in the lock. Public identities or fingerprints may be
stored only when their runtime contracts already treat them as public.

### 11.2 Distinct identities

The following values have different meanings and must remain separately named
in schemas, CLI output, status, and documentation:

| Identity | Purpose |
|---|---|
| `resolvedConfigDigest` | Operational identity of normalized generated intent |
| Plugin catalog fingerprint | Identity of installed/selected runtime code |
| Consensus-profile commitment | Authenticated runtime consensus identity |
| Composite profile digest | Deterministic application identity |
| Generated file digest | Regeneration/tamper comparison for one output file |

The operational config digest is not a consensus proof and does not replace
ADR-016 or composite-profile commitments.

### 11.3 Normalization

Before `resolvedConfigDigest` is calculated:

- fields are ordered canonically;
- values use normalized representations;
- secrets and host-specific transient values are omitted or represented by a
  stable reference class, as specified by the property definition;
- generated timestamps and absolute paths are excluded;
- schema/catalog identities are included; and
- the encoding and digest algorithm are versioned in the lock format.

The precise canonical encoding is an implementation specification that must be
approved before the lock format is stabilized.

## 12. Configuration validation and explanation

### 12.1 Three validation modes

The CLI must distinguish three valid user intents.

#### Template mode

```bash
./yano.sh appchain config validate --mode template application-appchain.yml
```

Validates one intentionally incomplete shared or role template. It performs
schema, ownership, type, bounds, placement, and rules that can be evaluated
from the available fields. Missing values known to be injected by declared
overlays are reported as unresolved requirements, not necessarily errors.

Template validation does not claim the file can start a node by itself.

The tool determines expected overlay-provided fields from one of these
versioned contracts:

- the generated project's blueprint and lock;
- an explicit `--template-contract <file>` supplied with a hand-authored
  project; or
- a built-in launcher contract shipped with the matching Yano distribution.

A template contract declares property IDs or bounded patterns, the role/scope
that supplies them, and whether they are required before node startup. It
contains no secret values. Without a contract, the tool still performs all
checks possible on the file, but complete-config requirements are reported as
`UNRESOLVED_NO_TEMPLATE_CONTRACT`; the result cannot be described as startable
or complete.

#### Resolved mode

```bash
./yano.sh appchain config validate --mode resolved \
  --config application-appchain.yml \
  --config node0.properties \
  --config private.properties
```

Merges the declared configuration sources using the same precedence and
normalization contract as the node, then invokes complete framework and
selected-capability validation. It verifies a configuration that is intended
to start a specific node/role.

#### Project mode

```bash
./yano.sh appchain config validate --mode project appchain.yaml
```

Validates the blueprint, resolution, lock, generated outputs, selected local
distribution, and project invariants. It detects stale or manually edited
generated files without silently overwriting them.

### 12.2 Config-source precedence

The CLI must not invent a simplified merge order that disagrees with the
running Quarkus application. The shared config-resolution layer must model the
same relevant MicroProfile/Quarkus inputs used by Yano, including:

- base and profile-aware application configuration;
- additional config locations;
- config ordinals;
- node and private overlays;
- environment variables; and
- system properties.

The default implementation reuses the same SmallRye Config engine and relevant
configuration factories/interceptors used by the Yano runtime. It must not
independently recreate profile, ordinal, expression, environment-name, or
source-precedence behavior. If a runtime component cannot be reused directly,
the replacement requires a parity suite that executes identical source stacks
through the CLI and a bootstrapped Yano configuration environment.

For reproducibility, commands state which ambient sources they include.
Non-interactive validation should support excluding ambient environment/system
properties, while runtime-parity diagnostics should include them and show
redacted provenance.

Every effective field records its winning source. Ambiguous duplicate forms,
such as equivalent YAML and flattened paths in one effective stack, are
reported according to an explicit compatibility policy.

### 12.3 Unknown-key and ownership validation

Offline validation performs:

- exact and bounded-pattern key recognition;
- one-character typo suggestions;
- malformed indexed-path detection;
- unselected-capability namespace detection;
- cross-bundle namespace ownership checks;
- unknown fields in plugin descriptors and catalogs;
- type, enum, length, and numeric bounds;
- required/forbidden cross-field rules;
- safety-scope placement checks; and
- secret-in-tracked-output checks.

M0b may provide `FULL` coverage only for framework and first-party namespaces
whose property metadata and parser parity are complete. It must report partial
coverage rather than accepting an unknown suffix as valid merely because it
matches a broad dynamic prefix.

M0a cannot label a namespace `FULL`, because the complete runtime parity suite
arrives in M0b. In M0a, a bound may reject input only when it is derived from a
public runtime definition or confirmed by a targeted runtime parser test. A
registry-only documented bound produces a warning with its provenance rather
than a false validation failure.

### 12.4 `config effective`

```bash
./yano.sh appchain config effective --mode resolved --format yaml
./yano.sh appchain config effective --mode resolved --format json --show-sources
```

The redacted effective view shows, for every field:

```text
effective value or redacted marker
source: runtime default / recipe / blueprint / overlay / environment / system
owner capability or framework area
safety scope and change policy
explicit versus defaulted
generated destination and role
validation coverage
```

### 12.5 `config explain`

```bash
./yano.sh appchain config explain effects.default-gate
./yano.sh appchain explain capability:objectstore-s3
./yano.sh appchain explain recipe:role-evidence
```

Explanation includes ownership, purpose, type/default/bounds, implications,
conflicts, safety scope, change path, providing artifact, distribution
availability, source precedence, and documentation.

### 12.6 `doctor`

`doctor` checks environment facts that schema validation cannot prove:

- actual binary/distribution and plugin catalog identity;
- missing, duplicate, or incompatible plugin bundles;
- native build-time capability presence;
- secret-reference existence, ownership, mode, type, and expected public
  derivation where safe;
- unsafe symlinks and known demo identities;
- ports, directories, retained-state identity, and launcher prerequisites;
- optional external endpoint, TLS, authentication, and profile checks;
- generated file and lock integrity; and
- optional live cross-member parity.

### 12.7 Live comparison

Initial live comparison may use identities already exposed by protected status
surfaces, including consensus-profile and plugin-catalog identities. A
`resolvedConfigDigest` comparison requires explicit runtime integration: the
node must receive or derive the lock identity at startup and expose it through
a protected, redacted status endpoint.

Live comparison may report:

- chain/member topology;
- authenticated consensus-profile commitment;
- composite profile digest;
- plugin catalog fingerprint;
- generated resolved-config digest, when runtime integration exists;
- node role; and
- safe categories of disagreement.

It never reveals secret values or causes one node to overwrite another.

### 12.8 `diff`

```bash
./yano.sh appchain diff old/appchain.lock new/appchain.lock
```

Each change receives one of the policies from section 7.2. The report explains
the required action and owning decision. Unknown fields or transitions are
`UNSUPPORTED`.

`diff` is advisory and suitable as a CI gate. It never performs a governed
activation, rotates credentials, edits retained state, or starts a new chain.

## 13. Generated project

### 13.1 Example layout

```text
product-evidence/
  appchain.yaml
  appchain.lock

  schema/
    appchain-blueprint-schema.json
    appchain-runtime.schema.json

  config/
    application-appchain.yml
    shared-consensus.properties
    nodes/
      node0.properties
      node1.properties
      node2.properties
    executors/
      executor0.properties

  secrets/
    README.md
    .gitignore

  plugins/
    plugin-lock.json

  compose.yaml
  scripts/
    start
    stop
    status
    verify-roots
    smoke-test

  client/
    java-spring/                  # optional codestart

  README.md
```

Targets omit irrelevant files. A host-process target may render launcher
overlays rather than Compose. Client codestarts are optional and may be
delivered after the first initializer milestone.

### 13.2 Shared configuration policy

The generated shared configuration contains every resolved
`CONSENSUS_SHARED` property as an explicit value, even when equal to the
current runtime default. Comments document origin and rationale but are never
the only record of a consensus value.

Cluster and node files are generated according to property scope. Private
values are replaced with reference forms or placeholders that cannot be
mistaken for working production credentials.

Generated YAML declares its exact editor schema using a relative vendored path
or a release-versioned URL:

```yaml
# yaml-language-server: $schema=../schema/appchain-runtime.schema.json
```

### 13.3 Ownership and regeneration

Generated files carry a header stating that they are derived outputs. `render`
compares their recorded digest before replacing them:

- unchanged generated files may be regenerated;
- edits that can be expressed in the blueprint or overlay produce a migration
  suggestion;
- unknown manual edits stop rendering and require an explicit reconciliation
  choice; and
- no command silently discards user modifications.

The normal customization path is blueprint plus validated overlay.

### 13.4 Determinism

For the same blueprint, lock inputs, and release catalogs, rendering produces
byte-identical non-secret output across runs. Determinism excludes local secret
material and explicitly declared host-specific runtime state.

Catalog iteration order, map ordering, timestamps, temporary paths, locale,
and host name must not change locked output.

## 14. CLI packaging and contract

### 14.1 Distribution entry point

In an extracted Unix-like distribution, `./yano.sh` is the public dispatcher.
It preserves current behavior and routes commands as follows:

| Command | Target |
|---|---|
| `./yano.sh start` or `start:<profiles>` | Existing `yano.jar` or native `yano` node |
| `./yano.sh appchain cluster ...` | Existing `appchain-cluster/cluster.sh` |
| `./yano.sh appchain config ...` | Separate `appchain-devtools` executable |
| `./yano.sh appchain init/render/doctor/diff ...` | Separate `appchain-devtools` executable |

The raw native node executable remains named `yano` for compatibility. Because
that name is already occupied, ADR examples use `./yano.sh appchain ...` for
extracted archives. A future package-manager installation may expose bare
`yano appchain ...` only after it provides a front-controller executable and
moves the raw node binary behind an internal name such as `yano-node`.

The dispatcher never loads developer-tool classes into the node process. It
uses `exec` to replace itself with the selected node, CLI, or cluster launcher.
Existing `./yano.sh start`, profile aliases, `JAVA_OPTS`, and
`YANO_EXTRA_ARGS` remain backward compatible.

### 14.2 Local N-node quick demo

Yano does not add a `yano-cluster.sh`. The distribution already contains an
N-node app-chain launcher, so the public convenience commands delegate to it:

```bash
./yano.sh appchain cluster start 3
./yano.sh appchain cluster status
./yano.sh appchain cluster submit orders-chain demo "hello"
./yano.sh appchain cluster stop
./yano.sh appchain cluster clean
```

Direct invocation remains supported:

```bash
./appchain-cluster/cluster.sh start 3
```

This is a single-machine demo/evaluation path. Its deterministic demo
identities, local ports, temporary directories, and default devnet are not a
production consortium deployment contract. Generated host/Compose projects
are the supported transition from demo to a reviewable project.

The Bash launcher is packaged only where Bash is supported. Windows users use
the generated Compose path until a Windows dispatcher and local-process
launcher are explicitly supported.

### 14.3 Tool distribution layout

The JVM release contains the installed `appchain-devtools` application. A
representative layout is:

```text
yano-<version>/
  yano.sh
  yano.jar
  config/
    schema/
      appchain-runtime.schema.json
      appchain-property-catalog.json
  appchain-cluster/
  tools/
    yano-appchain/
      bin/yano-appchain
      lib/...
  codestarts/
```

The `appchain-devtools` CLI consumes `appchain-config`, the release catalogs,
and the actual distribution/plugin inventory without booting Quarkus. If
complete runtime validation requires a public facade or small additional
module dependency, that boundary is established before M0b or any
resolved-mode validation ships. The tool must not rely on package-private
runtime types.

The same JVM CLI is also published as
`yano-devtools-<version>.zip`. It can validate or generate for an extracted
native distribution when given that distribution's release index/catalog.
This is the initial native-node tooling path and requires Java only for the
developer tool, never to run the native node.

Native node archives do not silently acquire a Java runtime dependency. A
later platform-native `yano-appchain` executable may be compiled from the same
`appchain-devtools` sources and packaged per platform. It must pass the same
conformance vectors and produce the same normalized plan/lock as the JVM CLI;
it is not an independent implementation.

If a native archive does not contain a compatible dev-tools executable,
`./yano.sh appchain config/init/...` fails with an actionable instruction to
install the exactly matching `yano-devtools` package. Cluster commands remain
available on supported Unix-like native archives because they route to the
packaged Bash launcher.

Native, JVM, Windows, and standalone-tool support boundaries are declared in
the release capability index and verified from final release artifacts.

### 14.4 Command shape

```bash
./yano.sh appchain init
./yano.sh appchain init --recipe owned-registry --network devnet --members 3
./yano.sh appchain render

./yano.sh appchain config validate --mode template <file>
./yano.sh appchain config validate --mode resolved --config <file>...
./yano.sh appchain config validate --mode project <appchain.yaml>
./yano.sh appchain config effective --mode resolved --show-sources
./yano.sh appchain config explain <property>

./yano.sh appchain doctor
./yano.sh appchain explain capability:<id>
./yano.sh appchain explain recipe:<id>
./yano.sh appchain diff <old.lock> <new.lock>
./yano.sh appchain migrate <project>
```

All non-interactive commands provide machine-readable diagnostics, stable
error codes, and a JSON output mode.

### 14.5 Command responsibilities

- `init`: gather intent, resolve capabilities, and create a project.
- `render`: deterministically regenerate outputs from blueprint and lock.
- `config validate`: perform file, resolved-node, or project validation.
- `config effective`: show redacted values and provenance.
- `config explain`: explain a runtime property.
- `doctor`: inspect distribution, environment, identities, and optional peers.
- `explain`: explain capability/recipe resolution.
- `diff`: classify a proposed change without applying it.
- `migrate`: upgrade blueprint/lock formats without changing retained-chain
  semantics.
- `cluster`: delegate local demo lifecycle commands to the maintained cluster
  launcher; it is not a production deployment controller.

## 15. Frontends

### 15.1 Guided CLI

The default guided flow asks no more than eight groups of questions before
review:

1. application outcome or recipe;
2. network/environment;
3. organizations, members, and availability target;
4. sequencing and governance, with recommended defaults first;
5. settlement requirement;
6. external actions, storage, and streams;
7. runtime, deployment target, and optional client; and
8. review of implications, roles, artifacts, secrets, immutable values, and
   trust assumptions.

Low-level cadence, pool, retry, timeout, quota, and retention settings remain
available in advanced mode.

### 15.2 Static App-Chain Studio

The first web release:

- loads release-pinned schemas and catalogs;
- starts from use cases and progressively reveals advanced choices;
- displays requirements, conflicts, artifacts, roles, maturity, and warnings;
- previews normalized non-secret intent;
- encodes only non-secret selections in a deep link; and
- downloads `appchain.yaml` plus instructions to validate and render locally.

The web UI never requests production secret values. It uses strict Content
Security Policy, does not place configuration in telemetry, does not persist
secret-like inputs, escapes third-party text, and treats downloaded templates
as data.

A later web release may download a complete lock/project only if it shares the
resolver implementation or passes release conformance vectors and produces the
same normalized plan and lock as the CLI.

### 15.3 IDE/editor tooling

Published schemas provide completion, descriptions, enums, bounds,
deprecations, and basic conditional checks for both blueprints and runtime
YAML. Semantic implications that JSON Schema cannot express remain enforced by
the resolver and shared validators.

Shipped examples and generated files include schema headers. Offline projects
vendor the matching schemas so editor support does not require network access.

### 15.4 AI-assisted configuration

An optional `configure-appchain` skill may:

1. interview the user at the outcome/trust/deployment level;
2. select a release-pinned recipe and capabilities;
3. create or modify `appchain.yaml`;
4. run deterministic validation and resolution;
5. present warnings and unresolved decisions; and
6. render only after validation succeeds.

The AI path may not invent raw runtime keys, bypass unsupported combinations,
receive production secrets, or treat prose reasoning as validation.

### 15.5 Client codestarts

The initializer may generate a Java/Spring client that demonstrates:

- authenticated command submission;
- SSE subscription;
- state query;
- finality/proof retrieval; and
- proof verification.

Client generation consumes the same chain ID, API-auth policy, topics, and
proof policy as the node project. It is optional and may ship after the first
CLI release. Additional languages are separate codestarts, not resolver forks.

## 16. Secrets and trust boundary

The following rules are mandatory:

1. Blueprints, locks, shareable URLs, tracked output, logs, and diagnostics do
   not contain secret values.
2. Generated configuration contains secret references, not resolved secrets.
3. Local disposable devnet identities are generated only into ignored,
   owner-only, non-symlinked paths and are labeled non-production.
4. Production workflows request file, KMS, HSM, Vault, or orchestrator
   reference forms.
5. Member, business actor, API, anchor, connector, and TLS identities remain
   distinct.
6. Private values remain out of process arguments and effective-config output.
7. Web and AI frontends never ask for or transmit production secrets.
8. Third-party descriptors and display text are untrusted and safely escaped.
9. Third-party codestarts/templates are declarative data and cannot execute
   arbitrary code during preview or generation.
10. Secret scanning is a release gate for every generated golden project.

The system must not claim that a static website is inherently unable to
receive secrets. The protection comes from the schema, UX, content policy,
telemetry policy, implementation, and tests.

## 17. Change and upgrade lifecycle

### 17.1 Regeneration

`render` resolves a pinned blueprint against the lock and local release. An
upgrade is explicit:

1. copy or branch the project;
2. select a new Yano release/catalog;
3. resolve a candidate lock;
4. inspect the semantic diff and support changes;
5. follow any artifact-first, governed, secret-rotation, or new-chain plan;
6. regenerate outputs; and
7. run the packaged validation/smoke path.

### 17.2 Schema and lock evolution

The initial blueprint is `v1alpha1`. Publishing it makes it an external alpha
contract even before stabilization. Therefore:

- its schema identifier and stability are explicit;
- incompatible alpha changes receive a `migrate` path where practical;
- the lock format is versioned independently;
- old release catalogs remain addressable for reproducibility; and
- stabilization occurs only after real CLI and packaged-recipe usage.

### 17.3 Retained history

The initializer does not infer that a syntactically valid change is safe for
retained history. Changes to committed profiles, consensus limits, membership,
sequencing, settlement identities, or deterministic effect semantics defer to
their owning ADRs and use `GOVERNED_ACTIVATION`, `NEW_CHAIN_REQUIRED`, or
`UNSUPPORTED` when safety cannot be proven.

## 18. Backward compatibility and support levels

### 18.1 Existing hand-authored configuration

Existing YAML/properties workflows remain supported. Users can adopt
`config validate`, `config effective`, `config explain`, and `doctor` without
creating a blueprint.

Template mode supports current shared launcher files. Resolved mode supports
the complete overlay stack. Migration into a generated project is optional.

### 18.2 Third-party plugins

Third-party runtime bundles remain loadable under the existing manifest and
compatibility rules. If no dev-tools descriptor exists:

- the CLI reports `UNSUPPORTED_METADATA` coverage;
- it validates the known framework portion;
- it does not claim strict unknown-key or semantic coverage for that plugin;
- a generated project cannot carry a fully supported-recipe label; and
- raw plugin config is preserved only through the explicit advanced path.

### 18.3 Runtime strict mode

Runtime rejection of every unowned dynamic key is not enabled globally until
first-party descriptor coverage and the third-party compatibility policy are
complete. Offline strict validation can ship earlier and report coverage.

## 19. Delivery plan

### M0 — existing-config tooling in two releasable slices

M0 is deliberately split so that the quick validation win does not wait for a
configuration-module refactor or full Quarkus-parity source resolution.

#### M0a — template validation and explanation

- create the `appchain-config` and `appchain-devtools` module skeletons;
- inventory framework and selected first-party properties in an initial
  read-only registry;
- source defaults and bounds from public runtime definitions where available,
  and record provenance for every constraint;
- introduce coverage labels for dynamic namespaces;
- export the first versioned runtime schema and property catalog;
- ship `config validate --mode template` and `config explain`;
- extend `yano.sh` to dispatch app-chain config commands to the separate tool
  and cluster commands to the existing launcher;
- package `appchain-devtools` in the JVM distribution and the standalone
  version-matched `yano-devtools` archive;
- support built-in and explicit template contracts, while reporting
  `UNRESOLVED_NO_TEMPLATE_CONTRACT` when none is available;
- validate maintained shared examples and demo templates in CI; and
- add schema headers to maintained examples.

M0a performs structural, ownership, type, verified-bounds, placement, and
locally evaluable cross-field checks. Unverified registry-only bounds are
warnings. M0a does not claim `FULL` namespace coverage, complete node validity,
runtime-default parity, or full semantic coverage.

#### M0b — resolved validation and effective configuration

- create the shared configuration-definition/public-validator boundary;
- embed or directly reuse the runtime's SmallRye Config resolution semantics;
- implement resolved source loading, profile and ordinal handling, redacted
  provenance, and ambient-source controls;
- ship `config validate --mode resolved` and `config effective`;
- invoke complete framework and selected first-party semantic validators; and
- add CLI/runtime source-resolution and validator parity suites.

M0b completion requires real runtime-validator parity for every namespace
labeled `FULL`. Neither M0 slice requires complete first-party catalog
coverage, but both must report their actual coverage.

The implemented M0b boundary keeps resolution in `appchain-devtools` and the
side-effect-free parser/validator contracts in `appchain-config`. The runtime
depends on the latter and invokes the same `AppChainConfigParser`,
`AppChainConfigSemantics`, and `AppChainEffectsConfig` used by resolved-mode
validation. Trusted hosts can explicitly register an `AppChainSemanticValidator`;
untrusted component archives remain data-only metadata and are never
class-loaded by validation. A build gate requires the CLI and Quarkus runtime
to resolve the same SmallRye Config engine version. Only the effects core
properties whose complete parser is shared are labeled `FULL`; open executor,
sink, state-machine, observer, and custom-plugin namespaces remain `PARTIAL`.

### M1 — thin initializer using the final architecture

- publish blueprint `v1alpha1` and lock alpha formats;
- implement a minimal but real capability/recipe descriptor model;
- implement implication/conflict and artifact resolution;
- ship interactive and non-interactive `init` plus deterministic `render`;
- support three initial recipes: `audit-log`, `owned-registry`, and
  `evidence-publication`;
- render host-process and Compose projects using proven launcher behavior;
- materialize every consensus-shared default; and
- enforce secret and generated-file ownership policies.

M1 must not use a hard-coded recipe renderer that is replaced in M2.

The implemented M1 catalogs are release-pinned data resources consumed by one
resolver and renderer. The three initial recipes select capabilities through
the same descriptor closure used for additive capability choices. Capability
assignments reference property-registry suffixes; unknown properties,
exclusive-provider collisions, implication gaps, conflicts, and unsupported
runtime/deployment combinations fail before files are written. The renderer
emits `v1alpha1` blueprint and lock contracts, host or Compose node overlays,
secret-reference examples, and vendored schemas/catalogs. It records a
bootstrap acknowledgement when public member keys are not yet known and never
substitutes deterministic demo identities for production membership. Every
generated non-secret file except the lock itself is digest-pinned, and
regeneration stops on an unexpected edit. M2 extends these catalogs and
lifecycle commands without replacing the M1 resolver/render architecture.

### M2 — full first-party contract and lifecycle tooling

- complete first-party property and capability descriptors;
- publish the release capability index;
- add `approval-workflow`, `role-evidence`, and `custom-plugin` recipes;
- complete JVM/native artifact checks;
- validate the standalone JVM dev-tools package against every supported native
  distribution flavor;
- ship `doctor`, `diff`, and project validation;
- generate trust/bootstrap/verification documentation; and
- define the migration path for alpha schemas and locks.

### M3 — packaged acceptance matrix and stabilization gate

For every advertised recipe/runtime/deployment combination:

- generate from the final distribution artifact and embedded catalog;
- start the final packaged runtime;
- submit a typed command;
- verify finality, root parity, query, and proof;
- exercise selected sinks/effects;
- restart a member and verify catch-up;
- scan tracked output, logs, and process arguments for secrets; and
- stop and clean all processes and generated runtime data.

After two milestones of usage and successful packaged acceptance, decide
whether blueprint, capability, recipe, and lock schemas are ready to move from
alpha to stable v1. Stabilization is a review gate, not automatic.

### M4 — App-Chain Studio and tutorial deep links

- publish static release-pinned schema/catalog assets;
- implement guided selection and safe blueprint download;
- add tutorial "Open in initializer" links;
- validate every deep link in CI; and
- evaluate full browser generation only under the shared-engine/parity rule.

Optional Java/Spring client codestarts may land in this milestone.

### M5 — live drift and runtime strictness

- add protected redacted runtime identity exposure where missing;
- compare consensus, composite, catalog, and resolved-config identities;
- add safe category-level mismatch diagnostics; and
- enable runtime strict unknown-key behavior only for fully covered ownership
  domains under a compatible rollout policy.

### M6+ — AI, GitOps, and third-party ecosystem

- add the schema-pinned AI skill;
- generate CI, Helm, and Kustomize outputs;
- evaluate an operator after blueprint stabilization;
- define third-party descriptor signing, trust, maturity, and compatibility;
- validate third-party capability metadata against runtime manifests; and
- expand client codestarts and deployment targets based on adoption.

## 20. Verification and build gates

### 20.1 Property-registry parity

- The build fails if a fixed framework property or multi-chain suffix is
  missing from the registry.
- First-party parser tests assert documented defaults, bounds, enums,
  conditional requirements, and unknown-key behavior.
- Capability assignments reference existing owned properties.
- Runtime examples pass the appropriate template or resolved validators.
- Public runtime constants or parser parity tests back every registry
  constraint that is enforced as an error; unverified constraints remain
  warnings.
- Built-in template contracts are parity-tested against the keys and roles
  injected or required by their maintained launcher scripts. Contract drift
  fails the build.
- Runtime and blueprint schema outputs have reviewed golden snapshots.

### 20.2 Unknown-key adversarial tests

Test at least:

- one-character typos under every fully covered dynamic prefix;
- unselected plugin namespaces;
- malformed indexed paths;
- duplicate or ambiguous YAML/properties forms;
- overlong aliases and instance names;
- third-party patterns attempting to claim another namespace;
- descriptor/catalog duplicate IDs and unknown fields;
- excessive nesting, collection size, and metadata size; and
- hostile URLs or display text.

### 20.3 Deterministic generation

- Identical blueprint, lock inputs, and catalogs produce byte-identical
  non-secret output.
- Tests randomize input map and catalog order.
- Every consensus-shared default is explicit and locked.
- Timestamps, host paths, locale, and host name do not affect locked output.
- CLI and any complete web resolver produce the same normalized plan and lock.
- Generated-file drift is detected before overwrite.

### 20.4 Security

- Golden tracked projects contain no seeds, mnemonics, private keys, API keys,
  passwords, or known demo credentials.
- Private local files are owner-only and non-symlinked.
- Process arguments and logs contain no generated secrets.
- Effective/explain/doctor diagnostics and web previews remain redacted.
- Third-party templates cannot execute code or access filesystem/network
  resources through the generator.

### 20.5 Packaged recipe tests

- Tests use the final distribution, not only development classpaths.
- JVM directory-bundle and native build-time-catalog behavior are tested
  separately.
- Every recipe test executes the corresponding tutorial success path.
- External integrations use controlled test dependencies or explicitly scoped
  contract tests.
- Cleanup is asserted so the test suite leaves no running processes.

### 20.6 Distribution dispatcher and tooling tests

- Existing `./yano.sh start` and profile commands launch the same JVM/native
  node behavior as before the dispatcher change.
- `./yano.sh appchain config ...` invokes `appchain-devtools` without booting
  the Quarkus node.
- `./yano.sh appchain cluster start 3`, status, stop, and clean work from the
  final JVM and supported Unix-like native archives.
- Direct `appchain-cluster/cluster.sh` invocation remains compatible.
- The final JVM archive contains exactly one version-matched installed
  dev-tools application and release catalog.
- The standalone `yano-devtools` archive produces the same normalized output
  as the JVM-bundled tool and can inspect every supported native archive.
- If a native archive has no dev-tools executable, its dispatcher emits the
  documented install instruction rather than treating `appchain` as a node
  argument.
- Windows and native support boundaries in help output match the release
  capability index.

## 21. Acceptance criteria

The first stable-v1 decision requires all applicable criteria below:

1. A user can generate and run a healthy three-member devnet starter without
   manually writing a `yano.app-chain.*` property.
2. The guided common path has no more than eight question groups before review.
3. Template validation correctly accepts maintained shared launcher templates
   without pretending they are startable node configs.
4. Resolved validation uses the same relevant config-source precedence and
   complete semantic validators as the node.
5. A misspelled fully covered framework or first-party dynamic property fails
   offline with an owner and useful suggestion.
6. Partial or missing plugin metadata is visibly labeled and never presented
   as strict validation.
7. Generated YAML provides offline editor completion and validation.
8. Every consensus-shared value, including current defaults, is explicit in
   generated runtime output and the lock.
9. `config effective` identifies each field's source, owner, scope, policy,
   and destination without revealing secrets.
10. `doctor` rejects a capability missing from the actual JVM/native
    distribution.
11. Config, plugin catalog, consensus-profile, and composite-profile identities
    are shown as distinct concepts.
12. Generated tracked output and shareable URLs contain no secret values.
13. `diff` conservatively identifies governed, new-chain, secret-rotation, and
    unsupported changes.
14. Rendering is reproducible and refuses to silently overwrite manual edits.
15. Every advertised recipe passes its packaged tutorial smoke test.
16. Every shipped example and demo config passes its declared validation mode.
17. Existing hand-authored and third-party workflows remain usable with honest
    support/coverage labels.
18. If complete project generation exists in more than one frontend, the same
    release and blueprint produce the same normalized plan and lock.
19. An optional generated client can submit, subscribe, query, and verify a
    proof against its generated chain.
20. Migration tooling can read every previously published alpha blueprint/lock
    accepted for external testing or reports a precise manual migration path.
21. Existing `./yano.sh start` and `start:<profiles>` commands remain backward
    compatible.
22. `./yano.sh appchain cluster start 3` starts a local three-node demo from a
    supported final distribution and `clean` removes its generated data.
23. App-chain DX commands execute through the separate dev-tools application
    without booting the Quarkus node or opening chain state.
24. The version-matched standalone dev-tools package can validate and generate
    for every native distribution advertised as supported.

## 22. Consequences

### 22.1 Benefits

- New users select outcomes and trust assumptions rather than low-level keys.
- Existing users gain validation, explanation, provenance, and diagnostics
  without adopting generated projects.
- Blueprint plus lock provides regeneration and upgrade workflows.
- Explicit consensus defaults reduce mixed-release and replay ambiguity.
- Artifact-aware resolution catches JVM/native incompatibilities before
  startup.
- One metadata contract feeds CLI, web, IDE, AI, docs, and tests.
- The recipe/tutorial/test contract keeps advertised onboarding paths honest.
- Support conversations can share non-secret, release-pinned intent.

### 22.2 Costs

- Property metadata must be curated and moved closer to parsing.
- Two new modules and a standalone dev-tools release artifact must be built,
  versioned, tested, and packaged.
- First-party extension authors must maintain dev-tools descriptors alongside
  runtime manifests.
- JSON Schema cannot express all semantic rules, so a resolver and validator
  remain necessary.
- CLI, browser assets, codestarts, distributions, and recipes add a release
  matrix.
- Accurate config-source parity requires deliberate integration with the
  runtime configuration model.
- Runtime strict mode cannot safely precede descriptor coverage and a
  third-party policy.

### 22.3 Risks and mitigations

| Risk | Mitigation |
|---|---|
| Property schema drifts from runtime parser | Shared typed definitions, public validator facade, parser parity tests |
| Capability catalog drifts from artifacts | Check release index and manifest contributions against the distribution |
| Dynamic prefix still hides typos | Exact/pattern ownership plus explicit coverage labels |
| Consensus changes through a default | Materialize and lock every consensus-shared default |
| M1 creates a throwaway renderer | Ship a minimal catalog through the final resolver architecture |
| CLI merge differs from Quarkus | Share config-resolution semantics and show provenance |
| Static UI diverges from CLI | Blueprint-only first release; shared engine or lock-parity gate later |
| Browser receives secret data | No secret fields, CSP, no config telemetry, tests, local secret references only |
| AI invents unsupported config | Blueprint-only changes followed by deterministic validation/rendering |
| Config digest is mistaken for consensus proof | Separate names, schemas, UI fields, and documentation |
| Third-party metadata attacks tooling | Strict resource bounds, escaping, validation, and no executable templates |
| Generated project implies production readiness | Maturity labels, trust statement, pilot checklist, explicit scope |
| Manual generated-file edits are lost | Digest check and explicit reconciliation before overwrite |
| Dispatcher breaks existing node launch | Backward-compatibility tests over final JVM/native archives |
| Demo launcher is mistaken for production orchestration | Explicit demo labeling and generated project path |
| JVM/native dev-tools outputs diverge | Same sources, conformance vectors, normalized plan/lock parity |
| Native archive silently requires Java | Separate tools package and explicit release/help support boundary |

## 23. Follow-on implementation decisions

The following details are intentionally left for implementation design or a
later ADR, but must be resolved before the named stabilization gate:

1. Which existing config parsers move into `appchain-config` in M0b and which
   migrate incrementally behind the public validator facade.
2. Stable API group replacing the provisional `yano.bloxbean.com` name.
3. Canonical encoding and digest algorithm for `resolvedConfigDigest`.
4. Exact CLI launcher packaging on Windows.
5. Published native distribution flavors, their release-index identities, and
   when a platform-native `yano-appchain` tool becomes a release gate.
6. Protected runtime endpoint fields required for live comparison.
7. Third-party descriptor signing/trust and namespace-allocation policy.
8. Whether stable v1 supports multiple chains per project or only preserves
   the array shape.
9. Whether schemas are vendored only, URL-addressed only, or both. The current
   recommendation is both.
10. Client codestart release timing and language priority.
11. Exact template-contract schema, discovery order, launcher ownership, and
    compatibility policy across Yano releases.

These are not permission to weaken the normative safety rules in this ADR.

## 24. Recommendation

Adopt this unified plan for iterative review and implementation planning.

The delivery order is:

1. fast template validation/explanation, followed by SmallRye-backed resolved
   validation and the shared validator boundary;
2. minimal blueprint/capability/recipe resolution through the final engine;
3. lock, CLI, lifecycle tooling, and full first-party metadata;
4. packaged recipe acceptance and schema stabilization;
5. static discovery UI and optional client codestarts;
6. live drift, runtime strictness, AI, GitOps, and third-party expansion.

This sequence provides early value without sacrificing the long-term lifecycle
model or overstating validation coverage.
