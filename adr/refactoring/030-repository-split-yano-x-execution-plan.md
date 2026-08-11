# ADR-030: Split Yano core from `yano-x`

**Status:** Accepted. Phases A-D are merged. Phase E history-preserving extraction is implemented
on feature branches and awaits maintainer commit/merge approval. Phase F release rehearsal has not
started.
**Date:** 2026-08-11
**Decision owners:** Yano maintainers
**Reviewed baseline:** `feat/037-generic-appchain-proof` at `6d364000`
**Related:** [ADR-029](../029-repository-split-analysis.md),
[ADR-031](../app-layer/031-composable-state-machine-foundation-and-portable-proofs.md), and
[ADR-011](../app-layer/011-plugin-architecture.md)

---

## 0. Independent review outcome

The split is recommended, but not in the exact form or sequence of the previous draft.

The repository boundary is useful for ownership, release cadence, cognitive load, and for keeping
the ordinary data-node build free of product and example work. It is not, by itself, a build
optimization: the costly application code must first stop being compiled, tested, indexed, and
packaged by the core host. Moving files while retaining compile-time product dependencies would
create two repositories without creating a lean Yano.

The review found seven decision-level corrections:

1. The current tree has **71 included Gradle child projects**, so the previous `~25`/`~43` allocation
   and some line-number evidence are already stale.
2. `app` still compiles against product code. In particular, `EutxoLifecycleIndexers`,
   `EutxoBridgeResource`, and `EutxoBridgeSettingsLoader` are host-owned eUTxO implementations;
   generic app-chain REST/auth code also imports composite contracts.
3. A manifest is necessary but does not make an artifact a deployable plugin. Every downstream JVM
   extension needs an isolated, dependency-complete bundle and a launch test through the real plugin
   directory. Several stock modules currently work because their dependencies are on `app`'s parent
   class path.
4. GraalVM native images cannot load plugin-directory JARs. The supported `yano` deployment will
   therefore be a core-only native image, while extension deployment belongs to a separate JVM
   distribution from `yano-x`.
5. Compatibility is not expressed by `yanoApi.minLevel` alone. The implemented contract is the
   inclusive API-major range (`min`/`max`) **and** the monotonic additive `minLevel`, plus each
   bundle's own dependency ranges.
6. Generic composition is not required to boot or operate the ordered-log core. It is an optional
   application capability and should move downstream with its contracts and clients.
7. The UI is explicitly excluded from this extraction for now. The earlier requirement to split
   `console-ui` is removed and recorded as a temporary exception, not a blocker.

Accordingly, this ADR makes plugin compliance a prerequisite to extraction and uses a single
repository cutover only after the core/application seam is green inside the current monorepo. It
does not require native compatibility from extension artifacts in the first release line.

The app-chain capability has not had a public release. The split therefore is not a compatibility
exercise for accidental prerelease internals: obsolete adapters, aliases, duplicate activation
paths, product-specific host hooks, and other known app-chain technical debt are removed while the
seam is prepared. A debt item may be deferred only when removal is blocked by a demonstrated
dependency; the blocker, owner, and removal phase must be recorded in this ADR before the phase can
complete. Supported Cardano data-node behavior and the accepted app-chain invariants remain subject
to regression testing throughout the work.

---

## 1. Decision

Split the current repository into two repositories with a one-way dependency:

```text
yano-x (JVM plugins, capabilities, products, examples, tools)
                         |
                         v
yano (GraalVM Cardano data node + app-chain host framework + OrderedLog)
```

- **`bloxbean/yano`** remains the Cardano L1 data node and the minimal app-chain host. Its supported
  deployment target is a GraalVM native image. It owns the public host SPI, deterministic engine,
  consensus/finality, authenticated state, L1 host adapters,
  generic anchoring and proof-serving mechanisms, plugin catalog/loader, generic REST/operations
  surface, and `OrderedLogStateMachine` as the only selectable built-in application.
- **`bloxbean/yano-x`** is JVM-only for the first release line. It owns every optional
  application implementation: stock state machines and their reusable capabilities, composition,
  role/approval workflows, connectors,
  product profiles, eUTxO and ZK, clients, Spring integration, examples, devtools, studio, and the
  JVM batteries-included distribution.
- **`console-ui` stays in `yano` temporarily**, including its current app-chain pages. It must use
  stable generic or plugin domain APIs and must not justify Java compile-time product dependencies.
- Dependency direction is strictly `yano-x -> yano`. `yano` must build, test, and publish
  with no checkout, artifact, source path, task reference, or generated metadata from the downstream
  repository.
- `yano` continues to publish ordinary Java host/runtime/API artifacts and a supported JVM assembly
  input for downstream use. GraalVM is its deployment target, not a reason to make the core Java
  libraries unusable from the JVM or to duplicate the host application downstream.

### 1.1 Core necessity rule

An implementation stays in `yano` only when at least one of these is true:

1. it is required for the Cardano data node;
2. it is required to boot, replicate, query, prove, operate, or anchor the minimal ordered-log app
   chain; or
3. it defines or verifies the host/plugin compatibility boundary.

If OrderedLog can pass the core acceptance suite without an implementation, that implementation
moves downstream. This places composite, stdlib, role workflow, connector contracts, product proof
clients, and product-specific host services downstream even when their code is generic or reusable.

The OrderedLog transition primitives and its generic finalized-message proof subject may remain
core because they implement the one reference machine. This exception must not become a route for
unrelated stock capabilities to accumulate in `core-api`.

### 1.2 No prerelease-debt preservation rule

Because no public app-chain compatibility promise exists yet, implementation convenience is not a
reason to retain a deprecated or transitional app-chain surface. In each phase:

1. characterize the supported Yano data-node behavior and app-chain invariants before changing the
   seam;
2. remove superseded app-chain APIs, configuration keys, code paths, bridges, and publications once
   their replacement is exercised by the same phase's tests;
3. do not introduce forwarding wrappers, duplicate artifacts, or long-lived aliases solely to
   preserve unreleased app-chain internals; and
4. record any unavoidable deferral with an evidence-backed blocker, named owner, target phase, and
   a test that prevents the debt from becoming an implicit dependency.

This rule does not authorize regressions in the Cardano node, OrderedLog, wire/storage formats that
the retained core depends on, or already documented external Yano behavior. Before/after golden,
rollback/replay, distribution, and native-image checks remain release gates.

---

## 2. What “everything is a plugin” means

The requirement applies to code that extends a **running Yano node**. It does not mean that a DTO,
client SDK, CLI, on-chain validator, test fixture, or pure deterministic helper must itself be a
`ServiceLoader` provider.

### 2.1 Runtime plugin compliance

Every downstream runtime extension must satisfy all of the following:

1. It declares a schema-v1 bundle manifest and a supported `ContributionKind`.
2. The runtime selects it only through `PluginProviderRegistry`; no raw `ServiceLoader`, direct
   constructor, product `switch`, CDI bean, or product-specific REST resource in the host may
   activate it.
3. Its JVM distribution is one dependency-complete drop-in bundle (or a deliberately specified
   multi-bundle dependency set) that does not duplicate host-provided SPI classes.
4. Its lifecycle is owned by the host and uses bounded host contexts. Unloading/shutdown closes all
   threads, stores, callbacks, and metrics registrations it created.
5. It is represented identically in the catalog whether preinstalled by the Yano X distribution or
   installed later by an operator; both use the JVM plugin directory.
6. It passes catalog validation, API compatibility checks, class-origin/isolation checks, and a
   real process-level activation smoke test.
7. The lean `app` has no compile or runtime dependency on the extension artifact.

Existing contribution kinds cover node lifecycle plugins, state machines, authenticated-map
validators, sequencer modes, L1 observers, epoch observers, signer providers, effect executors,
finalized sinks, local read models, domain APIs, health, and metrics.

### 2.2 Capabilities and libraries

- A deterministic capability used **inside one or more state-machine plugins** is a downstream
  library packaged in those bundles. It does not need a new runtime contribution kind merely to be
  reusable.
- A capability selected, configured, started, queried, or stopped independently by the host needs a
  typed contribution kind and conformance suite before extraction.
- Contracts, codecs, client SDKs, proof verifiers, on-chain artifacts, CLIs, examples, and testkits
  remain normal downstream artifacts. They must not appear in the lean host dependency graph unless
  a small core-only part is explicitly carved out.

This distinction avoids turning every Java class into a plugin while still guaranteeing that every
optional behavior of a running node crosses the catalog boundary.

### 2.3 Deployment targets

| Distribution | Supported model |
|---|---|
| `yano` GraalVM native | Core data node and app-chain host with OrderedLog only. It does not load external extension JARs. |
| `yano-x` JVM | Validated extension bundles are staged in or installed into `yano.plugins.directory`; no native-image build is offered initially. |
| Development/test JVM host | Yano's existing `:app:yanoDistZip` output is the base used to test the core host and assemble the downstream JVM distribution; it is not Yano's primary deployment. |

This deliberately avoids a lowest-common-denominator plugin API. Extension authors target Java 25
and the JVM plugin contract without providing GraalVM reachability metadata. If native extension
support is wanted later, it requires a separate ADR and a build-time composition/conformance model.

Yano X reuses Yano's existing JVM ZIP production mechanism and does not introduce another base
distribution format or Quarkus host application. It unpacks the lean `yano-<version>.zip`, stages
validated bundles under its `plugins/` directory, and repackages the result as
`yano-x-jvm-<version>.zip`. It must not copy or fork the Yano `app` sources, because two
independently evolving host applications would recreate the coupling this split is intended to
remove.

---

## 3. Target ownership

The allocation is expressed by responsibility rather than a fragile module count. Before cutover,
it must be checked in as a machine-readable module/path manifest used by settings, CI, extraction,
and dependency tests.

### 3.1 Stays in `yano`

- Existing Cardano node modules: `core-api`, `plugin-catalog`, `consensus`, `p2p`, `runtime`,
  `ledger-rules`, `ledger-state`, `ccl-ledger-rules`, `scalus-bridge`, `tx-services`,
  `bootstrap-providers`, `epoch-export`, `devnet-toolkit`, `testkit`, `testkit-ccl`, and lean `app`.
- App-chain host contracts under `core-api/.../api/appchain` and generic plugin contracts.
- The deterministic engine under `runtime/.../appchain` and generic host bridges.
- `appchain-config` as a separate Yano module, because the host and OrderedLog require app-chain
  configuration. Folding it into another module is not part of this split.
- `OrderedLogStateMachine`, `FinalizedMessageIndex`, its proof subject, and only those cross-cutting
  primitives required by the minimal core profile.
- Plugin catalog/loader/operations and a small core plugin-conformance fixture.
- Generic app-chain REST, health, metrics, snapshot, proof-serving, and operations endpoints.
- The default anchor implementation and `appchain-anchor-onchain`; anchoring is part of the minimum
  host profile for this split. No anchor-provider SPI is introduced now.
- A small implementation-neutral app-chain proof-verifier module used by the host, published as
  `com.bloxbean.cardano:yano-appchain-proof-verifier`, with no stdlib, composite, product, or HTTP
  client dependency.
- A split core app-chain testkit sufficient for OrderedLog, host SPI, and plugin conformance tests.
- `console-ui`, as the explicit temporary exception in this ADR.

### 3.2 Moves downstream

- `appchain-stdlib` and `appchain-stdlib-contracts`, including authenticated-map, approvals,
  balances, document trail, epoch params/governance/stake, and KV registry.
- Authenticated-map validators, role-workflow and its contracts.
- `appchain-composite`, composite contracts/client, and all composite product assembly.
- Evidence, Cardano-history, eUTxO, eUTxO-ZK, ZK, connector, effects, and indexer modules.
- Connector/integration contracts, application proof/on-chain contracts and clients. Only the
  implementation-neutral `yano-appchain-proof-verifier` described in §3.1 remains in Yano.
- Showcase, demo runners, project examples, Spring Boot starter, studio, devtools, scaffolds, and
  application-oriented testkits.
- The JVM batteries-included distribution and plugin packs.

### 3.3 Must be split before allocation

- **`appchain-client`:** move the external REST/SSE SDK, stdlib clients, composite clients, portable
  proof utilities, and authoring helpers downstream. Carve the generic host-side native proof math
  into the retained `yano-appchain-proof-verifier` module described in §3.1.
- **`appchain-testkit`:** retain only core engine/OrderedLog/plugin conformance support. Move effect,
  connector, product, and batteries-included cluster harnesses downstream.
- **`app` REST/application code:** generic endpoints stay; composite governance, eUTxO transaction
  building, eUTxO index lifecycle, and any future product endpoint move behind plugin SPIs.
- **Documentation and ADRs:** core architecture decisions stay in `yano`; product/extension ADRs
  move. Do not move all of `adr/app-layer/**` indiscriminately.

---

## 4. Current seam violations and required fixes

| ID | Current evidence | Required outcome before extraction |
|---|---|---|
| P1 | `app/build.gradle` directly references stdlib, composite, role, evidence, eUTxO, ZK, connectors, devtools, studio, and conformance projects. | Lean `app` has no downstream project/artifact dependency and no downstream task/path reference. |
| P2 | `EutxoLifecycleIndexers` directly constructs the SQLite provider and owns product metrics/lifecycle. | Add a typed, lifecycle-owned local-read-model/indexer contribution using bounded host services; move the implementation downstream. |
| P3 | `EutxoBridgeResource` and settings loader are product-specific host REST code. | Add a typed, bounded host service for reviewed L1 transaction building and expose the product routes through a manifested domain API; remove them from the core host. |
| P4 | Generic REST/auth imports `CompositeProfileGovernanceV1`. | Move composite governance behind a manifested domain API supplied with a generic bounded privileged-system-message host service. |
| P5 | `runtime` has a test dependency on stdlib. | Replace it with a core fake for host conformance and move the stdlib scenario downstream. |
| P6 | `appchain-client` exposes core proof code and stdlib/composite contracts from one artifact. | Complete the client split in §3.3. |
| P7 | Stock manifests exist, but not every stock/plugin module has a verified isolated drop-in bundle. | Publish and process-smoke every runtime extension bundle from the Yano JVM host assembly used by `yano-x`. |
| P8 | Host catalog/distribution generation uses Gradle project paths in `app/build.gradle`. | Move extension catalog and JVM distribution assembly downstream; consume published Yano host artifacts and extension Maven coordinates without a sibling checkout. |
| P9 | `app` and devtools/showcase reference each other's tasks, runtime classpaths, config paths, and distribution files. | Publish core distribution/config/test artifacts; downstream assembly consumes only published outputs. |
| P10 | `runtime/.../DefaultL1EpochStateProvider` imports `ledger-state` from the engine package. | Keep the adapter in a clearly named host-bridge package and preserve an app-chain SPI free of live ledger-store types. |
| P11 | Compatibility prose calls `minLevel` the single contract. | Enforce `yanoApi.min`, `max`, and `minLevel`; validate bundle dependency ranges and BOM alignment separately. |
| P12 | UI and app-chain docs/config are treated as one movable block in the old plan. | Keep UI for now; split only product Java/build ownership and classify docs/config by core versus product. |
| P13 | Yano owns the plugin loader but its settings use the legacy `yaci.plugins.*` namespace. | Make `yano.plugins.*` canonical, including `yano.plugins.directory`; retain bounded legacy aliases only for migration. |
| P14 | First-party plugin manifests, fixture code, and DX release metadata duplicate a prerelease Yano version literal and break when `gradle.properties` advances. | Generate publication-coupled versions from the owning Gradle project and reject new hard-coded first-party manifest versions. |
| P15 | The packaged JVM/native plugin smoke fixture omits mandatory deterministic state identity and asserts obsolete plugin API coordinates. | Generate a complete conformance identity, assert the current API contract, and keep the same process smoke for JVM and native distributions. |
| P16 | Real connector harnesses select integration-test classes through the unit-test Gradle task, so the advertised CI job fails before exercising a connector. | Run real connector cases through each module's `integrationTest` source set and fail when its JUnit evidence is missing, skipped, or dirty. |
| P17 | The Milestone 1 workflow contract checks a contiguous YAML substring, so adding valid release gates between existing dependencies breaks the gate. | Parse the acceptance job's dependency list and require the complete release-gate set independent of ordering. |
| P18 | The effects-demo Compose and host templates rely on a default state commitment profile but omit its required fingerprint and genesis id, leaving every generated E2E node unhealthy. | Pin the complete deterministic state identity in both templates and verify all generated members agree. |
| P19 | Failover/parity E2Es assert obsolete plugin API coordinates and pre-expansion bundle/contribution totals, so an exact healthy current catalog is rejected. | Keep exact bundle-set and contribution-total assertions aligned with the current plugin API and selected demo catalog. |
| P20 | Deployment parity applies the initial publish check profile to the safe guided `run` command's subsequent read-only verification report. | Validate strict publish and read-only report profiles separately while retaining their shared state, proof, connector, and anchor checks. |
| P21 | Deployment parity requires a subsequent safe read-only `run` to advance the consensus tip even though it intentionally submits no message. | Require byte-identical three-member cluster state, including tip, after immediate and post-restart read-only verification. |
| P22 | The optional historical state-proof `height` query relies on RESTEasy's reflective `Long` conversion, which fails when the chain-scoped subresource is created in the native image. | Bind the wire value as text, parse it explicitly with a stable 400 response, and exercise the route through the native process smoke. |
| P23 | Chain status returns the internal `AppCapabilityManifest` record directly and relies on Jackson reflection that is absent from the native image. | Publish an explicit deterministic REST view of the manifest and cover it with unit and native status serialization tests. |

P1-P10 and P13-P23 are cutover blockers. P11 and P12 are resolved by the compatibility and UI
decisions in this ADR and remain acceptance checks. P14-P23 are removed in Phase A because a
reliable baseline cannot be captured from version-incoherent metadata, a stale packaged-process
smoke, connector jobs that never select their integration tests, or an order-sensitive workflow
contract; generated demo nodes must be startable with a complete state identity, and live gates
must recognize the current exact plugin and report contracts. Native HTTP binding and serialization
must also be explicit rather than relying on reflection that is only present in the JVM build.

---

## 5. Execution plan

### Phase A — freeze and describe the boundary

1. Initialize the existing Yano X Git workspace at `/Users/satya/work/bloxbean/yano-x`; connect the
   remote repository when it is ready. The absolute path is a local development convention, not a
   build or publication contract.
2. Check in a machine-readable allocation manifest listing every current module, source path,
   distribution asset, CI workflow, ADR/doc group, and owner. CI fails when a new path/module has no
   owner.
3. Record clean baseline timings for `clean build`, unit tests, packaging, and the six heavy
   app-chain jobs. A post-split claim of improvement must compare like-for-like tasks and hardware.
4. For the seam-preparation window, require review for changes to public app-chain/plugin SPIs and
   avoid unrelated wire/storage changes.
5. Inventory known prerelease app-chain compatibility shims and seam debt. Classify every item as
   remove in a named phase or retain for a demonstrated core/external requirement; unowned or
   reason-free deferrals fail the phase gate.
6. Capture the regression suites and golden artifacts that protect supported Cardano-node behavior,
   OrderedLog replication/rollback/proofs, distribution contents, and the native core image.

Phase A's measured baseline, executed gate results, removed prerelease debts, and comparison rules
are recorded in [`baselines/030-phase-a-baseline.md`](baselines/030-phase-a-baseline.md). The
allocation manifest is enforced by the root `check` lifecycle and CI; the Phase-A baseline classified
71 included projects, Phase B classified 74, and Phase C adds the retained `yano-bom` for a current
total of 75 full-build projects and 23 lean-build projects. The manifest continues to classify all
projects and P1-P23. Phase B started from the reviewed Phase-A merge on the integration branch.

### Phase B — make extensions real plugins inside the monorepo

1. Remove P2-P4 by adding only the host SPIs actually required by at least one existing product.
2. Produce a dependency-complete JVM bundle for every runtime extension, including stdlib and
   role/composite-based products; validate class origin and forbidden host-contract duplication.
3. Route all extension activation through `PluginProviderRegistry`. Remove product constructors,
   raw service loading, hard-coded state-machine/capability lists, and product CDI resources from
   `app`/`runtime`.
4. Split `appchain-client` and `appchain-testkit`; eliminate the runtime-to-stdlib test edge.
5. Add a generated conformance matrix mapping every downstream runtime artifact to manifest,
   contributions, bundle task, and JVM activation test.
6. Rename the host plugin configuration to `yano.plugins.*`, add bounded legacy-alias tests, and
   update every first-party config, scaffold, test, script, and guide.
7. Move reusable host E2E support behind a published Gradle test-fixtures variant; downstream E2E
   modules must not splice another project's test output onto a Quarkus classpath.

This phase is complete only when deleting application projects from `settings.gradle` still leaves
a buildable and testable lean host.

Phase B implementation evidence, regression commands, removed prerelease seams, and remaining
phase boundaries are recorded in
[`baselines/030-phase-b-plugin-seam.md`](baselines/030-phase-b-plugin-seam.md).

### Phase C — establish published contracts

1. During local seam development, run Yano's existing publication and distribution tasks, for
   example `./gradlew publishToMavenLocal :app:yanoDistZip`. Yano X resolves Yano Maven dependencies
   from `mavenLocal()` only when an explicit development property is enabled and receives the
   existing ZIP through an explicit `yanoJvmDist` file property. It must not invoke a task in the
   sibling Yano checkout or assume a fixed sibling path.
2. The Yano X assembly verifies that the base ZIP version/build identity matches its resolved Yano
   BOM/runtime artifacts and fails on a mismatch.
3. Before a public Yano X release, publish Yano prereleases to an internal/staging Maven repository;
   a Central release is not required during the refactoring campaign.
4. Publish `yano-bom` for host/API alignment and a version catalog if Gradle consumers need it.
   Downstream publishes `yano-x-bom` for plugin/product alignment.
5. Publish config/schema artifacts, plugin CLI, core testkit, and the existing JVM distribution ZIP
   when release infrastructure is ready. Do not make downstream builds depend on `:app` tasks or
   copy `app` sources.
6. Use real `-SNAPSHOT` versions on moving mainlines. A downstream release must never depend on a
   mutable or locally substituted release version.
7. Test the local-Maven development mode and, once available, a clean repository-only build against
   staged artifacts. CI release gates use the latter so local Maven state cannot hide missing POM
   metadata or accidental project dependencies.

Phase C's Yano BOM, explicit full/lean settings groups, initial independent Yano X build, Maven/ZIP
identity gates, regression results, and staging boundary are recorded in
[`baselines/030-phase-c-published-contracts.md`](baselines/030-phase-c-published-contracts.md).

### Phase D — separate the native core and JVM extension distributions

1. Reuse Yano's existing `:app:yanoDistZip` and native distribution tasks and formats; this split
   changes their contents and ownership boundaries, not their base packaging mechanism.
2. `yano` produces the supported GraalVM native distribution containing no downstream bundle and
   exposing only OrderedLog as a built-in state-machine id.
3. `yano` publishes the Java libraries and its existing JVM ZIP needed downstream, while keeping the
   native image as its primary end-user deployment.
4. `yano-x` produces a versioned plugin pack and batteries-included JVM distribution from
   published Yano artifacts plus validated bundles.
5. `yano-x` has no native-image tasks, reachability-metadata promise, or native release
   gate in this phase.
6. Docker image names and tags are distinct: `bloxbean/yano` is the native core image and
   `bloxbean/yano-x` is the JVM extension image.

Phase D's core JVM/native distribution contracts, independently versioned plugin bundles, Yano X
plugin pack/JVM assembly, reproducibility evidence, regression results, and removed packaging debt
are recorded in
[`baselines/030-phase-d-distributions.md`](baselines/030-phase-d-distributions.md).

### Phase E — history-preserving extraction

1. Tag the agreed source commit and create recovery branches in the original repository.
2. Generate the downstream history in a temporary clone with `git filter-repo`, driven by the
   allocation manifest. Do not run destructive history rewriting in the working Yano repository.
3. Import the filtered result into the empty downstream directory/repository, add its root build,
   license/notices, ownership, CI, release, dependency updates, and links back to core ADRs.
4. Remove moved paths from `yano` in one reviewed cutover change. Mixed files must already have been
   split in Phases B-D; extraction must not decide architecture.
5. Preserve tags or an explicit source-commit mapping so a downstream line can be traced to its
   pre-split commit.

Phase E's source tag/recovery refs, filtered-history mapping, 52-module cutover, repository-owned
documentation and CI, JVM-only downstream cleanup, and regression evidence are recorded in
[`baselines/030-phase-e-extraction.md`](baselines/030-phase-e-extraction.md).

### Phase F — independent release rehearsal

1. Publish a Yano prerelease to staging.
2. From a fresh checkout with no sibling `yano` directory, build and test downstream against that
   repository.
3. Build and smoke the Yano GraalVM image with OrderedLog and no downstream contribution.
4. Install representative stdlib, connector, product, and eUTxO bundles into the
   `yano-x` JVM distribution and pass process-level tests.
5. Verify the downstream packaged and directory-loaded JVM catalogs contain the selected Maven
   plugin set and resolve to the same contribution identities.
6. Promote releases only after both repositories' SBOM, license, dependency convergence, docs-link,
   and distribution-content gates pass.

---

## 6. Compatibility and release policy

### 6.1 Plugin compatibility

For every bundle, the host enforces:

```text
manifest.yanoApi.min <= host API major <= manifest.yanoApi.max
host global API level >= manifest.yanoApi.minLevel
all declared bundle dependency version ranges are satisfied
```

`minLevel` is monotonic and additive; it complements rather than replaces the API-major range.
Changing a consensus-visible contract, wire format, storage format, root calculation, or lifecycle
semantic requires the corresponding version/profile change even if Java binary linkage still works.

### 6.2 Release coordination

- Yano versions the host API/runtime. Downstream versions plugins and products independently.
- A Yano BOM aligns core artifacts; a downstream BOM aligns tested plugin sets and pins one tested
  Yano line.
- Cross-repository SPI work lands as: Yano snapshot/prerelease -> downstream adaptation and tests ->
  Yano release -> downstream release. Do not merge a downstream requirement that only exists in an
  unpublished local Yano checkout.
- Support policy must state which released Yano major/API levels each downstream release line tests.
  The manifest is machine enforcement; the release notes/BOM are the supported combination.

### 6.3 State and wire compatibility

The repository split is a packaging change, not authorization to reset app-chain state or change
roots. Before/after golden replay must produce identical blocks, roots, proofs, plugin manifests,
and anchor identities for unchanged profiles. Any intentional preview reset or wire/storage change
requires its own ADR and must not be hidden in extraction commits.

---

## 7. Documentation, configuration, and UI

- Keep core app-chain configuration (`yano.app-chain.*` host/engine settings), generic app-chain REST
  documentation, plugin authoring, OrderedLog, and core architecture ADRs in `yano`.
- Move stock machine, composition, product, connector, eUTxO/ZK, studio/devtools, and showcase
  configuration/docs/ADRs downstream.
- Keep the UI source/build in `yano` for this decision. App-chain product pages may remain, but must
  tolerate an absent plugin and discover capabilities through generic/catalog APIs. No moved Java
  module may be restored to the host merely to compile the UI.
- Cross-repository documentation uses versioned links where a release must be reproducible; CI checks
  links in both directions.
- Revisit UI ownership in a separate ADR after the Java/build split has stabilized.

### 7.1 Stable source and protocol identities

The repository and Maven-coordinate change does not rename the app-chain technical domain. Keep:

- Java packages: `com.bloxbean.cardano.yano.appchain.*`;
- host/engine configuration: `yano.app-chain.*`;
- existing state-machine ids, plugin bundle ids, contribution names, consensus/profile identifiers,
  REST paths, and persisted state keys.

Do not introduce `com.bloxbean.cardano.yanox.*` or `yano.x.*`. `yano-x` is the repository/product
name; `yano.appchain` remains the Java domain namespace.

### 7.2 Plugin host configuration

Plugin discovery is owned by Yano, not Yaci. The canonical settings are:

```properties
yano.plugins.enabled=true
yano.plugins.directory=plugins
yano.plugins.allow-list=
yano.plugins.deny-list=
yano.plugins.auto-register-annotated=false
yano.plugins.logging.enabled=false
```

The `yano-x` JVM distribution uses `yano.plugins.directory`. Native Yano does not load directory
JARs and reports that limitation when a populated directory is configured.

For one transition release, accept the corresponding `yaci.plugins.*` keys as deprecated aliases.
Documentation and generated configuration emit only `yano.plugins.*`. If both namespaces provide
the same setting with different values, startup fails with a configuration error; it must not choose
one silently. `yaci.events.enabled` is outside this rename and requires a separate ownership review.

---

## 8. Definition of done

### Core `yano`

1. `./gradlew clean build` passes with only modules owned by `yano` included.
2. A generated dependency check finds no downstream coordinate in main, test, buildscript, plugin,
   distribution, or task graphs.
3. A source check finds no product-specific imports or direct constructors in `app`/`runtime`.
4. The supported Yano GraalVM distribution exposes OrderedLog and no other selectable application.
5. The existing Yano JVM ZIP is lean, contains no Yano X bundle, and is usable as the downstream
   assembly base without a sibling Gradle task dependency.
6. OrderedLog two-node replication, rollback/replay, proof, snapshot/restart, and anchor tests pass.
7. The plugin CLI and runtime reject incompatible API majors/levels, duplicate contributions,
   forbidden host-class copies, invalid dependency ranges, and malformed bundles before provider
   construction.
8. Core configuration and generated examples use `yano.plugins.*`; deprecated `yaci.plugins.*`
   aliases warn, and conflicting old/new values fail startup.
9. No known app-chain technical-debt item is carried forward without the blocker, owner, target
   phase, and regression guard required by §1.2; superseded unreleased APIs and activation paths are
   deleted rather than maintained in parallel.

### Downstream repository

9. The local development build resolves Yano from explicitly enabled `mavenLocal()` plus an explicit
   `yanoJvmDist` ZIP, and rejects mismatched Maven/ZIP build identities.
10. A clean repository-only build passes against staged Yano artifacts without a sibling checkout or
    local Maven dependency before the first public release.
11. Every runtime extension appears in the generated conformance matrix and passes isolated JVM
   bundle activation. Pure clients/contracts/tools are explicitly classified as non-runtime
   artifacts rather than silently exempted.
12. Stock state machines, composition-based products, connectors, eUTxO, and ZK can be absent from
   core Yano and installed into the `yano-x` JVM host without changing Yano source.
13. The `yano-x` JVM distribution passes showcase/cluster smoke tests and reports the
    expected catalog fingerprint; it has no native-image release task.
14. Before/after golden replay confirms unchanged state roots, proofs, and anchor identity.
15. Measured core build/test/package time and output size improve against the Phase-A baseline; the
    ADR records the result without attributing improvements that came from separately changed tasks.
16. The Phase-A regression inventory remains green after each cutover phase, with any intentional
    behavioral change documented and approved before its golden expectation changes.

---

## 9. Risks and mitigations

- **Two repositories amplify SPI churn.** Use staged artifacts, API-level checks, and a temporary
  seam-change review rule; do not keep both repositories permanently on snapshots.
- **Plugin isolation can reveal hidden classpath assumptions.** Require dependency-complete bundles
  and real lean-host process tests, not only unit tests or manifest parsing.
- **Local Maven state can hide missing publications or version drift.** Make `mavenLocal()` opt-in,
  bind the JVM ZIP to the same Yano build identity, and require a clean staged-repository build before
  release.
- **Users may expect extensions to work in native Yano.** Name and document the images explicitly;
  native Yano is core-only and `yano-x` is JVM-only until a later ADR says otherwise.
- **Cross-repository tests can become slow and flaky.** Keep host SPI conformance in Yano, extension
  conformance downstream, and run a small versioned compatibility matrix rather than cloning both
  repositories for every unit test.
- **A big extraction is hard to review.** Make all semantic refactors before extraction; drive the
  mechanical path move from the checked-in allocation manifest.
- **UI temporarily weakens the ownership story.** Treat it as an explicit exception with no Java
  back-edge and revisit it separately.
- **Dependency or license drift can enter bundled plugins.** Generate SBOM/license reports per
  bundle and for assembled distributions; fail on duplicate host APIs and dependency convergence.

---

## 10. Rollback and recovery

Until the first downstream release is promoted:

1. retain the pre-split tag and recovery branches;
2. publish only prerelease coordinates and non-`latest` container tags;
3. keep source-commit mapping and extraction commands reproducible; and
4. if a blocking seam defect is found, fix it on the pre-split recovery branch and repeat the
   extraction rather than adding a reverse dependency from `yano`.

After stable releases, rollback means selecting the prior compatible Yano/downstream release pair;
it does not mean re-merging repositories.

---

## 11. Consequences

**Benefits:** the ordinary Yano build and release no longer absorb product velocity; the core has one
clear reference application; optional runtime behavior has an enforceable plugin contract; products
can release independently; JVM operators can install extensions without rebuilding the node.

**Costs:** published artifacts become part of local and CI workflows; extension users run the JVM
distribution rather than the native core image; seam-spanning changes need coordinated prereleases;
duplicated repository governance and release automation are unavoidable.

This split improves build time only after P1-P9 are removed. Its durable architectural value is the
enforced host/plugin boundary, not the number of Git repositories.

---

## 12. Repository naming decision

The downstream repository and product name is **Yano X**, hosted as **`bloxbean/yano-x`**.

The `X` denotes the extension/application ecosystem without claiming that the app-chain SPI, engine,
or OrderedLog moved out of Yano. It is short enough to serve as a product identity and does not bind
the repository name permanently to the current JVM-only deployment constraint.

The repository subtitle and first README sentence should be:

> Yano X is the JVM extension and application ecosystem for Yano.

The supported distribution is `yano-x-jvm-<version>.zip`, the BOM is `yano-x-bom`, and the container
is `bloxbean/yano-x:jvm-<version>`. These names do not alter the stable source/protocol identities in
§7.1.

---

## 13. Yano X module, folder, and artifact naming

### 13.1 Repository-wide conventions

- Gradle root name: `yano-x`.
- Maven group: `com.bloxbean.cardano`.
- Maven artifact prefix: `yano-x-`.
- Gradle project paths and filesystem folders are hierarchical and describe responsibility; they do
  not repeat `yano`, `x`, or `appchain` in every directory.
- Maven artifact ids are assigned explicitly by a convention plugin or checked-in module manifest.
  Do not derive them from the leaf `project.name`, because names such as `client`, `contracts`, and
  `runtime` repeat across domains.
- The Yano X repository has its own version. `yano-x-bom` pins the tested Yano line and aligns all
  Yano X artifacts.

Because the app-chain artifacts remain prerelease, modules moving downstream adopt the `yano-x-*`
coordinates before extraction. Do not carry both `yano-appchain-*` and `yano-x-*` indefinitely.
If an artifact has known external prerelease consumers, publish a time-bounded relocation POM or
migration note rather than preserving duplicate implementations.

### 13.2 Folder and artifact allocation

| Gradle project / folder | Maven artifact id |
|---|---|
| `:sdk:client` / `sdk/client` | `yano-x-client` |
| `:sdk:proof-contracts` / `sdk/proof-contracts` | `yano-x-proof-contracts` |
| `:sdk:proof-onchain` / `sdk/proof-onchain` | `yano-x-proof-onchain` |
| `:sdk:integration-contracts` / `sdk/integration-contracts` | `yano-x-integration-contracts` |
| `:sdk:testkit` / `sdk/testkit` | `yano-x-testkit` |
| `:composition:runtime` / `composition/runtime` | `yano-x-composite` |
| `:composition:contracts` / `composition/contracts` | `yano-x-composite-contracts` |
| `:composition:client` / `composition/client` | `yano-x-composite-client` |
| `:state-machines:stdlib` / `state-machines/stdlib` | `yano-x-stdlib` |
| `:state-machines:stdlib-contracts` / `state-machines/stdlib-contracts` | `yano-x-stdlib-contracts` |
| `:state-machines:zk` / `state-machines/zk` | `yano-x-zk` |
| `:capabilities:authenticated-map-validators` / `capabilities/authenticated-map-validators` | `yano-x-authenticated-map-validators` |
| `:capabilities:role-workflow` / `capabilities/role-workflow` | `yano-x-role-workflow` |
| `:capabilities:role-workflow-contracts` / `capabilities/role-workflow-contracts` | `yano-x-role-workflow-contracts` |
| `:connectors:kafka` / `connectors/kafka` | `yano-x-kafka` |
| `:connectors:objectstore-s3` / `connectors/objectstore-s3` | `yano-x-objectstore-s3` |
| `:connectors:ipfs` / `connectors/ipfs` | `yano-x-ipfs` |
| `:connectors:effects-cardano` / `connectors/effects-cardano` | `yano-x-effects-cardano` |
| `:tooling:devtools` / `tooling/devtools` | `yano-x-devtools` |
| `:tooling:studio` / `tooling/studio` | `yano-x-studio` |
| `:tooling:spring-boot-starter` / `tooling/spring-boot-starter` | `yano-x-spring-boot-starter` |
| `:examples:showcase` / `examples/showcase` | `yano-x-showcase` |
| `:examples:showcase-client` / `examples/showcase-client` | `yano-x-showcase-client` |
| `:bom` / `bom` | `yano-x-bom` |
| `:distribution:jvm` / `distribution/jvm` | `yano-x-jvm-<version>.zip` (distribution, not a library) |

Product families use a domain folder and preserve the domain in the artifact suffix:

| Folder family | Components | Artifact pattern |
|---|---|---|
| `products/evidence/` | `contracts`, `registry`, `client`, `profile`, `demo-runner` | `yano-x-evidence-<component>` |
| `products/cardano-history/` | `runtime`, `client`, `cli`, `onchain` | runtime: `yano-x-cardano-history`; others: `yano-x-cardano-history-<component>` |
| `ledgers/eutxo/` | `contracts`, `ledger`, `client`, `testkit`, `demo`, `bridge-cardano`, `bridge-onchain`, `indexer-core`, `indexer-jdbc` | `yano-x-eutxo-<component>` |
| `ledgers/eutxo-zk/` | `contracts`, `runtime`, `zeroj`, `prover`, `client`, `onchain`, `testkit`, `lifecycle`, `indexer`, `demo` | `yano-x-eutxo-zk-<component>` |

The module allocation manifest required by §5 records the exact Gradle path, folder, Maven artifact
id, publication type, plugin bundle id, and owning repository for every module. CI rejects duplicate
artifact ids or an unclassified module.

### 13.3 Runtime bundle publications

A runtime extension may publish a thin Java artifact for compile-time use and a separate
dependency-complete deployable artifact:

```text
com.bloxbean.cardano:yano-x-stdlib
com.bloxbean.cardano:yano-x-stdlib-bundle

com.bloxbean.cardano:yano-x-kafka
com.bloxbean.cardano:yano-x-kafka-bundle
```

Use a distinct `-bundle` artifact id rather than a Maven classifier. The Yano X JVM distribution and
plugin pack consume bundle artifacts; Java applications and other Yano X modules consume thin
artifacts. Pure contracts, clients, testkits, CLIs, on-chain artifacts, and tools do not publish a
bundle unless they contain a runtime plugin contribution.

Changing a Maven artifact id does not change a plugin manifest id. Existing reverse-DNS bundle ids
under `com.bloxbean.cardano.yano.appchain.*` remain stable unless a separate compatibility ADR
changes their runtime identity.
