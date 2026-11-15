# ADR-064: Yano-X depends on Yano, never the reverse

- Status: Proposed
- Date: 2026-11-16
- Tracking: [Yano #249](https://github.com/bloxbean/yano/issues/249) (requirement: [#247](https://github.com/bloxbean/yano/issues/247))
- Baselines inspected: Yano `ebd3eeea4` (merge of #246), Yano-X `1ce50558` (merge of X #102)
- Amends: [ADR-063](063-standalone-application-ledger-and-host-api.md) D4 ("Cardano node implementation:
  `yano-cardano-api`, plus concrete X API artifacts where used") and its integrated-distribution rows.
  Everything else in ADR-063 (Host API, V101 sessions, standalone chain identity, finalized commitment)
  stays in force.
- Risk: **high**. It touches plugin activation, consensus-relevant plugin facades, one persisted
  ledger-state encoding, the public embedding façade and both distributions. It does not change any wire
  format, consensus rule, persisted byte layout or `yano.app-chain.*` key.

## Context

ADR-063 moved the application-ledger engine to Yano-X (X) but kept Yano as the place where the
integrated product is assembled. As a result, Yano depends on X at compile time, test time, packaging
time and CI bootstrap time:

| Area (Yano `ebd3eeea4`) | Edge to X |
| --- | --- |
| `runtime/build.gradle:5,8,29` | `api yano-x-appchain-cardano`, `compileOnly yano-x-appchain-host-adapter`, X core test fixtures |
| `runtime/.../internal/RuntimeNode.java:3-6,43-44,102-106,458-601,1709` | Required root `AppLedgerHostPlugin.BUNDLE_ID`, X service keys, X record `CardanoRuntimeServices`, `assemble()` and `instanceof AppChainManager`, `wireL1EpochState`, `awaitStopped` restart preflight |
| `runtime/.../assembly/{Yano,RuntimeYano,YanoAssembly,AppLedgerAssembly}.java` | Public `appChain()/appChains()/domainApis()/localReadModels()` return X types; standalone mode validates `yano.app-chain.*` with the X parser |
| `runtime/.../appchain/hostbridge/*` | Yano implements the X SPIs `L1EpochStateProvider` and `L1TransactionBuilderService` |
| `runtime/.../plugins/*` | `PluginSpiFacades` (2,519 lines, ~75% facades for X SPIs), `DomainApiRegistry` (1,429), `LocalReadModel*Registry`, X cases in `CatalogPluginProviderRegistry`/`PluginCatalogBuilder` selector switches, `DomainHttpMethod` in `PluginOperationsRegistry` |
| `plugin-catalog` | `ContributionKind` enum hard-codes 11 X SPI interfaces; `api yano-x-appchain-cardano-api`; X API package prefixes and X service-file patterns in the scanner and native resource config |
| `ledger-state` | Persists the X enums `GovernanceActionType/ProposalStatus/ProposalStatusReason` **by ordinal** (`GovernanceCborCodec:97-99,114-116`) on every L1 node |
| `app` | `AppChainResource` (2,597), `PluginDomainResource`, `AppChainApiKeyFilter`, `AppChainHealthGroupCheck`, `AppChainMetrics`, `YanoProducer` app-chain properties and producers; `buildTimePluginBundles` pins `org.yanoproject.x.app-ledger`; native `reflect-config.json` names X classes |
| `appchain/*` modules | Re-export X artifacts (`appchain-config`, `appchain-anchor-onchain`), or depend on X (`appchain-proof-verifier`, `appchain-testkit`, `appchain-plugin-conformance`) |
| `node-api` | `YanoPropertyKeys.AppChain`, `YanoConfig.appChainStoragePath` (X-domain settings owned by Yano) |
| `console-ui`, `qa/app-ledger`, `qa/harness/appchain-*`, app-chain skills, docker app-chain mounts | X product surfaces and tests |
| CI | `prepare-framework-inputs`, `.github/ci-input-pins.json`, four-step `COORDINATED_INPUTS.md` bootstrap |

The bootstrap row shows the problem in practice. A clean checkout of Yano `main` does not build:
`./gradlew :app:quarkusBuild` fails with `Could not find org.yanoproject.x:yano-x-appchain-api:0.1.0-pre6-a3ef67b36`.
That coordinate exists only as a 90-day GitHub Actions artifact. X, in turn, builds against Yano
artifacts staged from Yano CI. Neither repository can be built, tested or released on its own.

Already clean, and reused by this ADR: `node-api`, `host-api` and `cardano-api` import no X types.
`HostApi` (version 1.1), `N2NExtensionRegistry`, `HostLifecycleRegistry`, `PluginContext` services,
`PluginProviderRegistry` (keyed by `Class`), required embedded bundles in `PluginRuntimeEnvironment`, and
`ServeSubsystem.appOnly` are all X-free.

## Decisions

### D1. Ownership rule

Yano never depends on Yano-X. This covers source imports, class or package name strings, resources
(`META-INF/services`, native-image config, catalog descriptors), plugin bundle ids, Gradle
configurations of every kind (production, test, build tooling), published POMs/BOM, distributions, CI
and QA harnesses. Yano builds, tests, packages and publishes from a clean checkout with no X artifact
reachable.

Yano-X may depend on Yano's published artifacts: `node-api`, `host-api`, `cardano-api`,
`plugin-catalog`, and, for the integrated distribution and integration tests only, `runtime` and the
`app` library (D8). X owns everything that has app-ledger semantics. That includes engine, adapter,
facades, registries, REST, console pages, configuration keys, tests, QA harnesses and the integrated
product.

The namespace rule in `AGENTS.md` already reserves `org.yanoproject.x.*` for X. This ADR adds the
dependency direction to `AGENTS.md` and to a build gate (D11).

### D2. Generic activation through the existing Host API (level 1.2)

Yano keeps the ADR-063 activation order. Only the parts of it that name X are replaced, each with the
smallest generic seam:

| Today (X-named) | Replacement (Yano-owned, `host-api` unless stated) |
| --- | --- |
| Required root `AppLedgerHostPlugin.BUNDLE_ID` when `yano.app-chain.enabled` | New property `yano.plugins.required` (comma-separated bundle ids), fed into the existing `requiredEmbeddedBundles`. The integrated distribution sets it. The X root decides from its own configuration whether it does anything. |
| `NODE_CONFIG_SERVICE` / `RUNTIME_OPTIONS_SERVICE` keys owned by X | `HostApi.nodeConfig()` (copied `YanoConfig`) and `HostApi.runtimeOptions()` (existing `RuntimeOptions` record), as default methods |
| `getService(ASSEMBLY_SERVICE, AppLedgerHostPlugin.class).assemble()` | `HostLifecycleRegistry.registerAssembly(Runnable)`. The host runs accepted callbacks once, in registration order, after every `NodePlugin.init` succeeds and before it seals the N2N and lifecycle registries. A failure aborts startup with the existing reverse cleanup. |
| `instanceof AppChainManager`, `appChainManager.awaitStopped(3s)` | `HostLifecycleRegistry.registerRestartReadiness(BooleanSupplier)`, which the kernel consults in the existing restart preflight (same `false` = retryable semantics). |
| `CardanoRuntimeServices` (X record, X key) | The same seven fields as a Yano-owned record `CardanoHostServices` in `cardano-api`, registered under `CardanoHostServices.SERVICE_KEY`. All field types are already Yano or CCL types. X's adapter reads this record; X's own record is removed or derived. |
| `wireL1EpochState(new DefaultL1EpochStateProvider(...))` after ledger-state assembly | An eighth field on `CardanoHostServices`, `Supplier<Optional<EpochSnapshotReader>> epochSnapshots`. It returns empty until ledger-state is assembled, and also when the account-state store is disabled. `EpochSnapshotReader` is a new read-only `cardano-api` interface (see D5). |

`HostApiVersion.CURRENT` becomes `(1, 2)`. All additions are default methods or new types, so binaries
compiled against 1.1 still link. Hosts that do not support 1.2 are rejected by the adapter's existing
`requireCompatible` check.

The X plugin (adapter roots `CardanoAppLedgerPlugin`/`StandaloneAppLedgerPlugin`) does its own
assembly inside the registered callback:

- parses chains;
- registers 100/103 agents through `n2n()`;
- transfers ownership through `registerBeforePublication`;
- registers its restart readiness;
- publishes its gateways as plugin services (D6).

No new plugin loader, kernel stage, scheduler or service registry is introduced.

### D3. Open contribution kinds; X owns its SPI kinds and facades

**Catalog (strings only, no code loading).** `ContributionKind` changes from an enum to a value type
with these fields:

- `manifestKey`
- `serviceInterface` (binary name)
- `manifestRequired`
- `PluginTrustTier`
- rules: `maxPerBundle`, `nameIsBundleId`, `forbiddenNameChars`, `reservedSystemNames`

Only the four Yano kinds stay built in: `node-plugin`, `utxo-index-contributor`, `health`, `metrics`.
Other kinds come from descriptor resources `META-INF/yano/contribution-kinds/<owner>.json`. These are
found on the host class path at runtime, and on the scanned artifacts at index or CLI time. A descriptor
also lists its API package prefixes, which replaces the hard-coded X prefixes in
`PluginArtifactScanner:54-57`.

The manifest and index formats are unchanged. Both already store kind keys only, never interface names
(`PluginIndexCodec:159-202`). The catalog fingerprint keeps hashing kind key plus trust tier, so X
descriptors must declare the existing keys and tiers exactly (I3). An unknown kind still fails closed.

**Runtime binding.** A `ContributionKindBinding<P>` SPI is added in `node-api`. It is discovered with
`ServiceLoader` on the host loader before the catalog is built, and it supplies:

- the `Class<P>`;
- `selector(P)`, which replaces the two per-kind selector switches;
- `facade(P, PluginCallbackScope)`.

`PluginCallbackScope` is the public face of the existing generic plumbing: TCCL scope, callback
admission, cleanup-after-seal, activation provenance, and product reservation/snapshot. That plumbing is
`CallbackTracker`, `ActivationContext` and `ProductReservations` in `PluginSpiFacades`.

The X facades move to X **verbatim except for the scope calls**. Their consensus-critical copies and
bounds remain one implementation, now X-owned. The Yano facades (utxo-index, health, metrics) stay. A
kind without a binding fails catalog construction; Yano never falls back to raw, un-faced providers for
manifested kinds.

**What moves to X.** `DomainApiRegistry`, `LocalReadModelRegistry` and
`LocalReadModelContributionRegistry` move to X unchanged. X registers them through
`registerBeforePublication` after its manager. `PluginOperationsRegistry` drops its `DomainHttpMethod`
overload and exposes a small public `PluginOperationsRecorder` (`HostApi.operations()`) that carries the
existing package-private domain hooks with kind and operation as strings and enums owned by `node-api`.
`PluginOperation.DOMAIN_GET/POST` keep their names because they are persisted in metrics and inventory.

### D4. Bundle configuration accessor

The moved registries use `PluginRuntimeEnvironment.bundleConfig(bundleId)` and the always-empty
`domainApiConfig`. These are exposed as `HostApi.bundleConfig(String bundleId)`. `domainApiConfig` is
deleted (dead).

### D5. L1 contracts that are Cardano concepts move to Yano

**Governance enums.** `ledger-state` gets Yano-owned enums in `cardano-api` (`org.yanoproject.api.governance`):
`GovernanceActionType`, `GovernanceProposalStatus` and `GovernanceProposalStatusReason`. They keep the
**identical constant order**, because `GovernanceCborCodec` persists ordinals; I2 makes this a test
gate. The codec, prefixes and stored bytes do not change.

X keeps its own `l1view` enums. Its generic API (`framework/api`) depends only on `node-api`, so it
cannot import `cardano-api`. The X epoch bridge maps by `name()`, as `EpochGovernanceObserver` already
does, and an exhaustive mapping test covers every constant.

This is one mapping at a trust boundary, not duplicated logic: the Yano ledger and the X public
observation API are versioned independently.

**Epoch snapshots.** `EpochSnapshotReader` (`cardano-api`) exposes the reads that
`DefaultL1EpochStateProvider` uses today:

- `persistent()` and `snapshotRetentionEpochs()`;
- the last completed boundary epoch;
- `protocolParameters(epoch)`;
- `openHistoricalView()`, a closeable view with the four existing `has*/forEach*` operations, typed with
  the Yano enums.

`DefaultAccountStateStore`/`HistoricalEpochStateView` implement it. Boundary block lookup uses the
existing `ChainBlockReader`/`EpochParamProvider`.

**Bridges.** `DefaultL1EpochStateProvider` and `DefaultL1TransactionBuilderService` move to X's
`framework/cardano` and are rebuilt on these contracts. Both need only `CardanoHostServices` and
`EpochSnapshotReader`. The tip slot comes from `ChainBlockReader.getLocalTip()`, which is verified equal
to the current `chainState.getTip()` source in M1. QuickTx leaves `runtime`'s dependency list.

### D6. Public façade: generic plugin services instead of X types

`Yano` loses `appChain()`, `appChains()`, `domainApis()` and `localReadModels()`. `RuntimeYano`,
`YanoDevnetAssembly` and `AppLedgerAssembly` lose their implementations. Yano adds one generic accessor,
`<T> Optional<T> service(String key, Class<T> type)`, which reads services registered through the
existing `PluginContext.registerService`.

X publishes `AppChainGateways`, `DomainApiGateway` and `LocalReadModelHost` under X-owned keys. It also
provides a static helper, `org.yanoproject.x.framework.host.AppLedgers.of(Yano)`, for embedders.

This is an intentional preview source/binary break for embedders, documented in the migration notes
together with the one-line replacement. The X host-matrix fixture is updated to use it.

### D7. Standalone (`yano.mode=app-ledger`) becomes a generic no-L1 host

The mode value and `RuntimeMode.APP_LEDGER` are kept for configuration compatibility. Their meaning in
Yano becomes: a host with no Cardano chainstate, which runs the plugin environment, Host API, app-only
N2N server (Yaci V101, ADR-063 D2), kernel and REST.

`AppLedgerAssembly` keeps that composition. Its app-chain validation (`validateHost:157-201`) moves to
the X plugin, which already re-validates transport.

The network magic for `ServeSubsystem.appOnly` comes from the plugin: an
`N2NExtensionRegistry.requireAppOnlyNetwork(long magic)` call made during the assembly callback. The host
fails startup in `app-ledger` mode if no plugin supplied one, or if two plugins supplied different values.
The host keeps rejecting known Cardano network magics.

`CardanoAvailabilityFilter` (503 for `@RequiresCardano` routes) stays generic.

`YanoPropertyKeys.AppChain`, `YanoConfig.appChainStoragePath` and the app-chain `@ConfigProperty` fields
in `YanoProducer` move to X; key strings and defaults are unchanged. Plugins need these values, so
`YanoProducer` forwards the effective `yano.*` configuration snapshot into `RuntimeOptions.globals()`.
Today it does this only for a fixed list of keys plus the X parser's dynamic prefixes (`:793-845`,
`:1018-1028`).

### D8. The integrated Yano+X product is composed downstream, in Yano-X

Quarkus registers JAX-RS resources, CDI beans and native metadata at build time. A plugin directory
therefore cannot add the app-chain REST surface, which uses SSE, sub-resource locators, per-route access
levels and large proof bodies. Re-expressing 4,300 lines of REST on a new dynamic route SPI would be a
rewrite. Moving the code is cheaper and preserves behaviour exactly. So:

- **Yano publishes `yano-app`**, its Quarkus application module, as an ordinary library with a Jandex
  index. It is removed from `nonLibraryModules`. Yano's own executable (`yano.jar`, ZIP, Docker, native)
  is built from it exactly as today and contains no X.
- **X adds `framework/rest`**, a Quarkus library holding the moved REST code: `AppChainResource`,
  `PluginDomainResource`, `AppChainAccess`, `AppChainHealthGroupCheck`, `AppChainMetrics`, the
  app-chain producers and `UnavailableAppChainGateway`, and `appchain-proof-verifier` (D9). Package
  names move under `org.yanoproject.x.*`; REST paths, JSON shapes, status codes and OpenAPI group stay
  identical.
- **X's `distribution/jvm` becomes a Quarkus application.** It builds the integrated `yano.jar` from
  `yano-app` + `yano-runtime` + X framework + `framework/rest`, sets `yano.plugins.required`, and
  generates the packaged plugin index with Yano's published `PluginIndexGenerator`. It then assembles
  the existing X ZIP layout (scripts, bundles, Studio, docs) with this jar in place of Yano's. The
  user-facing integrated deployment (paths, scripts, config, plugin directory, ports) is unchanged.
- The plugin index generated for Yano's own executable is a build output of Yano's executable task, not
  part of the `yano-app` library, so a downstream application never carries two indexes.

**Auth split.** `PluginOperationsResource` is a generic Yano surface that today is protected only by the
app-chain key realm. The generic credential realm therefore stays in Yano under a Yano-owned access
annotation, as `ApiKeyRealm` plus the `ApiAccess` levels READ, SUBMIT, SNAPSHOT_ADMIN, PRIVILEGED and
INTERNAL. It covers key parsing, full versus scoped keys, snapshot-admin keys, the fail-closed PRIVILEGED
rule and canonical path and body bounds. It applies to resources annotated `@ApiKeyProtected`, and it
keeps reading the existing `yano.app-chain.api.*` keys, because renaming them is out of scope (Q4).

X's resources use the same realm. X adds only the app-chain-specific checks after authentication:
topic-scoped SUBMIT and hiding INTERNAL domain routes. No endpoint loses authentication (I6).

**Native.** Yano's native image no longer contains app-chain code. Yano-X is JVM-only today (ADR-063:
"X remains JVM-only"), so native app-chain support ends with this ADR, unless X later adds a downstream
native build of its application (out of scope, Q1).

### D9. Delete the Yano copies and compatibility modules

| Yano item | Disposition |
| --- | --- |
| `appchain-config`, `appchain-anchor-onchain` (pure re-exports) | Delete. Consumers use `yano-x-appchain-config` / `yano-x-appchain-anchor-onchain`. Their tests go to X. |
| `appchain-proof-verifier` (3 classes) | Move to X as `yano-x-appchain-proof-verifier`. |
| `appchain-testkit` (`AppChainCluster`, depends on runtime) | Move to X (test-scope dependency on `yano-runtime`). |
| `appchain-plugin-conformance` and the app native/packaged catalog smokes that use it | X fixtures move to X. Yano keeps a small X-independent conformance fixture (`node-plugin`, `health`, `metrics`, `utxo-index-contributor`, plus one test-only descriptor kind) so its generic catalog, native and packaged smokes still run. |
| ~45 runtime app-chain tests, 6 integration tests, catalog tests using X fixtures | App-chain behaviour tests move to X (mostly `framework/cardano` integration tests, which already use `yano-runtime` test-scope). Generic catalog tests switch to the Yano fixture kinds. |
| `console-ui` `routes/app-chain/**`, `lib/{appchain,eutxo,authmap}` | Move to X. The generic plugins, status and observability pages stay. |
| `qa/app-ledger`, `qa/harness/appchain-*.sh`, release-qa `appchain` category, `test-app-chain-*` skills | Move to X's QA, which runs them against the downstream distribution. |
| Docker/compose app-chain mounts and env, `gradle/runtime-plugin-bundle.gradle` (unused) | Delete from Yano; X documents its own. |
| CI `prepare-framework-inputs`, `ci-input-pins.json`, framework part of `COORDINATED_INPUTS.md`, `appLedgerFramework*` properties | Delete. Yano keeps `stage-consumer-inputs.yml` (foundation/host scopes) only to stage Yano artifacts for X branch CI. |

### D10. X builds against Yano artifacts only

X keeps its `yanoVersion`/`nodeApiVersion`/`hostApiVersion` pins and its existing repositories
(Central, `repo.bloxbean.org` snapshots, explicit staging repository, opt-in Maven Local). The
framework-substitution and plugin-bundle host-class stripping rules are updated for the new `yano.jar`
composition.

No X module is consumed by Yano, so `framework-stage` and every other X-to-Yano staging scope disappear.

### D11. Guardrail

Yano adds `verifyNoYanoXDependency` to `check` (and CI). It fails on any of the following:

- a resolved dependency with group `org.yanoproject.x`, or a module name starting `yano-x`, in any
  configuration of any project, including test, annotation-processor and build-tool configurations;
- the same in generated POMs, Gradle module metadata and the BOM;
- `org.yanoproject.x` or `yano-x` (as a token) in tracked files outside `adr/`, `docs/`, `www/` and
  `README.md`. The excluded locations hold history and cross-product links;
- an X class in the built `yano.jar` or the native resource/reflect configs.

A negative probe, which injects an X coordinate and an X string, must make the task fail (I1).

## Invariants

- **I1** (D1, D11): Yano's `./gradlew build` and `:app:quarkusBuild` succeed from a clean checkout with
  X repositories unreachable. `verifyNoYanoXDependency` passes and fails its negative probe.
- **I2** (D5): `GovernanceCborCodec` output for every `(actionType, status, reason)` triple is
  byte-identical before and after the enum move. A golden-vector test uses bytes captured from the
  baseline commit, and a test pins the constant order.
- **I3** (D3): For the same plugin set, catalog fingerprints, inventory kind/tier strings and index files
  are byte-identical between the baseline integrated build and the downstream integrated build.
- **I4** (D3): X facades keep every copy, bound, sanitisation and admission rule. The moved
  `PluginTcclBoundaryTest`/`PluginContributionCleanupBarrierTest` cases pass unchanged in X; the generic
  cases pass in Yano against the fixture kinds.
- **I5** (D2): Activation order is preserved: plugin init, assembly callbacks, seal, kernel
  pre-publication, then publication. Late registration, duplicate registration, failure inside a callback
  and restart-readiness `false` behave as they do for the X-named path today.
- **I6** (D8): Every route of the baseline integrated `yano.jar` exists in the downstream integrated jar
  with the same method, path, access level, status codes and authentication outcome. A route inventory is
  diffed against the baseline OpenAPI document, and the existing filter tests move with their code.
- **I7** (D7): Standalone app-ledger nodes still finalize, catch up and restart over V101 with no Cardano
  chainstate, and existing `yano.app-chain.*` configurations parse identically.
- **I8** (D8, D10): One set of Yano artifacts, built once, is used unchanged (same SHA-256) by two
  independently built X versions to produce working integrated distributions.

## Milestones

Two PRs, one per repository, each in reviewable commits. No release or tag is part of this work.

- **M1 — Yano generic contracts (additive).** Host API 1.2 (D2, D4), `CardanoHostServices`,
  `EpochSnapshotReader`, Yano governance enums (D5), `Yano.service` (D6), open contribution kinds plus
  `ContributionKindBinding`/`PluginCallbackScope` (D3), `ApiKeyRealm` (D8), `yano-app` library
  publication, Yano conformance fixture kinds.
  - Entry: this ADR approved.
  - Exit: I2, I5; generic catalog tests on fixture kinds; full Yano unit and integration tests green; the
    artifacts published to a file staging repository.
- **M2 — Yano-X takes ownership.** Adapter on Host API 1.2 and `CardanoHostServices`; moved bridges,
  facades, registries, REST, proof verifier, testkit, console pages, tests and QA harnesses; kind
  descriptors and bindings; downstream Quarkus distribution.
  - Entry: M1 artifacts staged.
  - Exit: X `./gradlew check` and `distributionCheck` green against the staged Yano; I3, I4, I6, I7;
    app-chain QA harnesses pass on the downstream distribution.
- **M3 — Yano removes every X edge.** Delete the runtime, app, catalog, ledger-state, devnet-toolkit,
  appchain modules, QA, console, docker and CI items (D6–D9); add the guardrail (D11); update `AGENTS.md`
  and docs.
  - Entry: M2 exit.
  - Exit: I1; full Yano build and tests; release-qa `l1,e2e,compat,docker` (JVM and native) green
    without X.
- **M4 — Independence proof and docs.**
  - Entry: M3 exit.
  - Exit: I8. M3's Yano artifacts are staged once; X is built twice (PR head, and PR head with a distinct
    version and one functional no-op change in an X module) against those bytes; the standalone
    three-node cluster and the integrated Cardano + app-chain QA run on both. Migration notes cover
    embedders, plugin authors (`ContributionKind` constants), operators (integrated ZIP now from X; native
    app-chain dropped) and the Host API matrix.

## Verification

Independent evidence, never values produced by the code under test:

- I2 uses bytes captured from `ebd3eeea4`.
- I3 and I6 diff against artifacts built from `ebd3eeea4` with the pinned inputs.
- I8 compares SHA-256 values of the staged Yano artifacts.
- Behavioural parity comes from the existing tests moving with their code unchanged.
- Release QA runs the Haskell sync, past time travel, sparse backfill, epoch crossing, e2e, SDK
  compatibility and Docker suites on the X-free Yano, compared against a baseline run on `ebd3eeea4`.

## Alternatives considered

- **Generic dynamic HTTP route SPI in Yano, with X re-expressing its REST on it.** This would keep a
  bare-Yano-plus-plugin install path. Rejected: it rewrites 4,300 lines including SSE, locators and the
  key realm, invents an HTTP framework in `node-api`, and gives no stronger independence than D8.
- **Opaque extension kinds that X discovers itself from bundle loaders.** Rejected: it would duplicate
  catalog provenance, digest, allow/deny and cleanup-barrier logic in X, or bypass it.
- **A generic `java.lang.reflect.Proxy` facade instead of X-owned facades.** Rejected: it cannot
  reproduce the per-SPI copying and bounds that consensus relies on, and it adds proxy metadata and hot
  path reflection.
- **Moving the governance enums to `node-api` so X's generic API can share them.** Rejected: it puts
  Cardano governance types into the generic foundation for the sake of one mapping.
- **Keeping `yano-appchain-*` compatibility coordinates in Yano.** Rejected: they are X dependencies by
  construction.

## Consequences

- Yano builds, releases and runs as a Cardano node without X, and CI loses the four-step bootstrap.
- X can release against an unchanged Yano host version for as long as its Host API level is supported.
- The integrated JVM ZIP and the app-chain console are X deliverables. Yano's ZIP, Docker image and
  native binary no longer serve app chains.
- Embedders replace four façade methods with `AppLedgers.of(yano)`. Plugin authors referencing
  `ContributionKind.APP_STATE_MACHINE` and similar constants use X's descriptor constants instead.
- `yano-appchain-config`, `yano-appchain-anchor-onchain`, `yano-appchain-proof-verifier` and
  `yano-appchain-core-testkit` stop being published by Yano.

## Open questions (maintainer decision)

- **Q1.** Native app-chain support ends (Yano native is X-free; X is JVM-only). Lean: accept for this
  preview and track a downstream X native build separately if wanted.
- **Q2.** An ordinary Yano ZIP cannot gain app chains by dropping X into its plugin directory, because REST
  is build-time (D8). Lean: accept. The issue allows downstream composition, and I8 proves independence at
  the artifact level.
- **Q3.** The façade break in D6 is preview-only. Lean: accept, with migration notes and no deprecation
  shim (AGENTS.md: no shims).
- **Q4.** The generic key realm keeps reading `yano.app-chain.api.*` keys for compatibility. Lean: keep the
  names now, and consider neutral aliases in a later ADR.

## Related findings (out of scope)

- `RuntimeNode.appChainPluginSettings()` has no production caller (`:1014-1022`). It is deleted with M3.
- The X framework uses Yano contracts in implementation-sounding packages (`org.yanoproject.runtime.*`
  in `node-api`). These names are kept for binary compatibility; there is no rename here.

## Revision history

- r1 (2026-11-16): initial proposal.
