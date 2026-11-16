# ADR-064: Yano-X depends on Yano, never the reverse

- Status: Proposed (r4)
- Date: 2026-11-16
- Tracking: [Yano #249](https://github.com/bloxbean/yano/issues/249) (requirement: [#247](https://github.com/bloxbean/yano/issues/247))
- Baselines inspected: Yano `ebd3eeea4` (merge of #246), Yano-X `1ce50558` (merge of X #102)
- Amends: [ADR-063](063-standalone-application-ledger-and-host-api.md) D4 ("Cardano node implementation:
  `yano-cardano-api`, plus concrete X API artifacts where used") and its integrated-distribution rows.
  Everything else in ADR-063 stays in force: Host API, V101 sessions, standalone chain identity,
  finalized commitment.
- Risk: **high**. This touches plugin activation, consensus-relevant plugin facades, one persisted
  ledger-state encoding, the public embedding façade and both distributions. It changes no wire format,
  consensus rule, persisted byte layout or `yano.app-chain.*` key.

## Maintainer decisions (2026-11-16)

- **MD1 — delivery.** The integrated product is composed downstream by Yano-X (D8). The alternative was
  a generic plugin HTTP route SPI in Yano, onto which Yano-X would re-implement its REST so that X could
  be dropped into a bare Yano `plugins/` directory. It was rejected: it rewrites the JAX-RS surface and
  adds an HTTP framework that Yano must keep stable. #247 explicitly allows build-time composition
  downstream. Wording in #249 that implied plugin-directory installation is superseded.
- **MD2 — native.** Yano-X stays JVM-only. Yano's native binary has no application-ledger support after
  this change. A downstream native build of the X application is a possible later follow-up.
- **MD3 — embedding.** Yano-X must remain embeddable as a library, together with Yano, in any JVM
  application (for example Spring Boot), without Quarkus (D12).

## Context

ADR-063 moved the application-ledger engine to Yano-X (X), but kept Yano as the place where the
integrated product is assembled. Yano therefore depends on X at compile, test, packaging and CI-bootstrap
time. Inventory at `ebd3eeea4`:

| Area | Edge to X |
| --- | --- |
| `runtime/build.gradle:5,8,29` | `api yano-x-appchain-cardano`, `compileOnly yano-x-appchain-host-adapter`, X core test fixtures |
| `runtime/.../internal/RuntimeNode.java:3-6,43-44,102-106,458-601,900-939,1571-1607,1709,3852-3972` | Required root `AppLedgerHostPlugin.BUNDLE_ID`; X service keys; X record `CardanoRuntimeServices`; `assemble()` and `instanceof AppChainManager`; `wireL1EpochState`; `awaitStopped` restart preflight; X-typed read-model/domain registries. The test-only `appChainPluginSettings()` (`:1014-1022`) has one test caller. |
| `runtime/.../assembly/{Yano,RuntimeYano,YanoAssembly,AppLedgerAssembly}.java` | Public `appChain()/appChains()/domainApis()/localReadModels()` return X types. Standalone mode validates `yano.app-chain.*` with the X parser. |
| `runtime/.../appchain/hostbridge/*` | Yano implements the X SPIs `L1EpochStateProvider` and `L1TransactionBuilderService`. |
| `runtime/.../plugins/*` | `PluginSpiFacades` (2,519 lines, mostly X SPI facades); `DomainApiRegistry` (1,429); `LocalReadModel*Registry`; X cases in the selector switches of `CatalogPluginProviderRegistry:832-890` and `PluginCatalogBuilder:1369-1395`; X reserved names and trust table (`PluginCatalogBuilder:1071-1126`, `PluginCatalogInspector:216-273`); `DomainHttpMethod` and `"domain-api"` handling in `PluginOperationsRegistry`. |
| `plugin-catalog` | The `ContributionKind` enum hard-codes 11 X SPI interfaces. It also carries `api yano-x-appchain-cardano-api`, X API prefixes in `PluginArtifactScanner:54-57,565-568`, kind rules in `BundleManifest:94-116`/`CatalogValidation:30-50`, and 11 X service patterns in the native `resource-config.json`. |
| `ledger-state` | Persists the X enums `GovernanceActionType/ProposalStatus/ProposalStatusReason` **by ordinal** (`GovernanceCborCodec:97-99,114-116`) on every L1 node. |
| `app` | `api/appchain/*` (6 files, ≈4,270 lines: `AppChainResource` 2,597, `AppChainMetrics` 886, `AppChainApiKeyFilter` 504, `PluginDomainResource` 153, health, access annotation); app-chain properties and producers in `YanoProducer` (`:190-354,793-845,941-958,978-1028,1649-1795`); `buildTimePluginBundles` pins `org.yanoproject.x.app-ledger` (`:60-67`); native and packaged catalog smokes on X fixtures (`:842-1090,2267-2349`); X names in native `reflect-config.json`; `config/application-appchain.yml`; app-chain OpenAPI group in `application.yml:70-96`; `observability/` app-chain dashboards. |
| `appchain/*`, `appchain/test-vectors` | Re-exports of X (`appchain-config`, `appchain-anchor-onchain`), or modules that depend on X (`appchain-proof-verifier`, `appchain-testkit`, `appchain-plugin-conformance`). |
| `node-api` | `YanoPropertyKeys.AppChain`, `YanoConfig.appChainStoragePath`, `RuntimeMode.APP_LEDGER`. |
| `devnet-toolkit` | `YanoDevnetAssembly:3-6,243-260` façade delegations. |
| `console-ui` | `routes/app-chain/**`, `lib/{appchain,eutxo,authmap}`, X bundle ids in `capabilities.ts`, `client.ts` and the models. |
| Build | `build.gradle:54-64` (X substitution and framework repository), `:242`, `:716-728`; `bom/build.gradle:34-37`; `settings.gradle:35-40`; `gradle.properties` `appLedgerFramework*`; unused `gradle/runtime-plugin-bundle.gradle`. |
| QA, skills, scripts | `qa/app-ledger/*`, `qa/harness/appchain-*.sh`, the release-qa `appchain` category, `test-app-chain-*` and `deploy-showcase-*` skills, `scripts/sparse-backfill/lib/yano.sh:17`. |
| Docker | App-chain mounts and environment in `docker/config/application.yml`, `docker/compose/*`, `docker/yano.sh`/`.bat`, `compose/.env`, `README.md`. |
| CI | `prepare-framework-inputs` action and script, `.github/ci-input-pins.json`, the four-step `COORDINATED_INPUTS.md` bootstrap, and the framework inputs of `stage-consumer-inputs.yml`. |
| Docs | `docs/appchain/**` and the app-chain pages under `www/` (product documentation that moves to X). |

On the X side, about twelve build files consume `yano-appchain-*` coordinates. The frozen host-matrix
fixtures (`first-host`, `supported-host`, `namespace-host`) model hosts that embed X.

**Evidence of the problem.** A clean checkout of Yano `main` fails `:app:quarkusBuild` with
`Could not find org.yanoproject.x:yano-x-appchain-api:0.1.0-pre6-a3ef67b36`. The build only succeeds
after the pinned 90-day Actions artifact is downloaded. The baseline QA for this ADR had to do exactly
that, and then passed: 14 tests, plus the 2 known #184 e2e smoke failures.

**Already clean and reused by this ADR.** `node-api`, `host-api` and `cardano-api` import no X types.
The same holds for `HostApi` (1.1), `N2NExtensionRegistry`, `HostLifecycleRegistry`,
`PluginContext` services, `PluginProviderRegistry` (keyed by `Class`), required embedded bundles in
`PluginRuntimeEnvironment`, and `ServeSubsystem.appOnly`. The index format stores kind keys only
(`PluginIndexCodec:159-202`).

## Decisions

### D1. Ownership rule

Yano never depends on Yano-X. This covers source imports; class, package, bundle-id and artifact strings;
resources (`META-INF/services`, native config, catalog descriptors); every Gradle configuration
(production, test, annotation processing, build tooling and buildscript); published POMs, Gradle module
metadata and the BOM; distributions and the Docker image; CI; and QA.

Yano-X may depend on Yano's published artifacts:

- `node-api`, `host-api`, `cardano-api` and `plugin-catalog`;
- for the integrated application and for integration tests, also `runtime` and `app-core` (D8).

X owns every artifact with application-ledger semantics: engine, adapter, facades, registries, REST,
console, configuration projection, tests, QA, docs and the integrated product.

**Explicit exceptions** (C9), kept for configuration compatibility. None of them is an X dependency:

- the mode value `yano.mode=app-ledger` and `RuntimeMode.APP_LEDGER`, which now mean a generic no-L1
  host (D7);
- the three `yano.app-chain.api.*` key names read by Yano's own plugin-operations filter (D8);
- the plain token `app-chain` in docs or paths.

The namespace rule in `AGENTS.md` already reserves `org.yanoproject.x.*`. This ADR adds the dependency
direction there and enforces it (D11).

### D2. Generic activation through Host API 1.2

Yano keeps the ADR-063 activation order. It replaces only the X-named parts:

| Today (X-named) | Replacement (Yano-owned) |
| --- | --- |
| Required root `AppLedgerHostPlugin.BUNDLE_ID` when `yano.app-chain.enabled` | Property `yano.plugins.required` (comma-separated bundle ids) |
| `NODE_CONFIG_SERVICE` / `RUNTIME_OPTIONS_SERVICE` | `HostApi.nodeConfig()` / `HostApi.runtimeOptions()` |
| `getService(ASSEMBLY_SERVICE, AppLedgerHostPlugin.class).assemble()` | `HostLifecycleRegistry.registerAssembly(String ownerBundleId, Runnable)` |
| `appChainManager.awaitStopped(3s)` in the restart preflight | `HostLifecycleRegistry.registerRestartReadiness(String ownerBundleId, BooleanSupplier)` |
| `CardanoRuntimeServices` (X record and key) | `CardanoHostServices` (Yano record in `cardano-api`) |
| `wireL1EpochState(new DefaultL1EpochStateProvider(...))` | `CardanoHostServices.epochSnapshots()` (D5) |
| `runtimeNetwork()` (runtime-internal `GenesisFileResolver`) | `HostApi.networkName()` |

Each replacement in detail:

- **`yano.plugins.required`.** The ids feed the existing `requiredEmbeddedBundles`, unchanged (A8):
  - embedded only, meaning bundles on the host class path;
  - the bundles activate despite `yano.plugins.enabled=false` and the allow/deny lists, as the X root
    does today;
  - a listed bundle that is missing, or whose plugin fails `init`, aborts startup with the existing
    error.

  The `RuntimeNode` gate `plugins.enabled || appChainEnabled` becomes `plugins.enabled || !required.isEmpty()`.
  The integrated distribution sets the property.
- **`nodeConfig()` / `runtimeOptions()`.** Both are default methods.
  - `nodeConfig()` returns a fresh copy of `YanoConfig` on every call, so one plugin cannot mutate
    another's view.
  - `runtimeOptions()` returns a new `RuntimeOptions` on every call. Its globals are the same bounded
    deep snapshot that `RuntimeNode:502-504` builds today with `PluginConfigValues`, never the host's
    own record. A test mutates the original map and the nested chain configuration after construction
    (X16).
  - Exposure is unchanged: X receives the same values today through its keys (A12).
- **`registerAssembly`.** It may only be called during `NodePlugin.init`.
  - The host runs the callbacks once, in registration order, at today's exact `assemble()` position
    (`RuntimeNode:514`). That is after every `init` succeeds, after `CardanoHostServices` is
    registered, and before startup migrations, UTxO and ledger-state construction, and the N2N and
    lifecycle seal.
  - Each callback runs under its owner's plugin class loader as TCCL.
  - Its exception aborts startup with the owner id as provenance and with the existing reverse cleanup.
  - Re-entrant registration, and registration after `init`, are rejected (A7).
- **`registerRestartReadiness`.** The kernel's existing restart preflight consults every registered
  supplier, with the same semantics as today's `awaitStopped` preflight (A7):
  - `false` is retryable, and the kernel stays `STOPPED`;
  - an exception marks the kernel `FAILED` and keeps resources for an explicit close;
  - an interrupt keeps `STOPPED` and the interrupt flag.

  `registerRestartReadiness` and `registerPluginProducts` (D3) may be called only during `init` or an
  assembly callback.
- **Owner ids.** These are trusted host-class-path plugin ids. A directory bundle may call these
  methods, but it cannot impersonate an embedded owner, because the host checks the id against the
  calling plugin's context (A7).
- **`CardanoHostServices`.**
  - Its fields are today's seven `CardanoRuntimeServices` fields with the same types; CCL
    `ProtocolParams` becomes an `api` dependency of `cardano-api` (A13).
  - It gains `Supplier<Optional<EpochSnapshotReader>> epochSnapshots` (D5).
  - It is registered under `CardanoHostServices.SERVICE_KEY` before plugin `init` only in Cardano mode.
  - X's adapter reads it, and X's `CardanoRuntimeServices` record is deleted.
- **`HostApi.networkName()`.** It replaces the label that the read-model registry obtains today through
  the runtime-internal `GenesisFileResolver` (A10).
  - In Cardano mode it returns today's value.
  - In app-ledger mode it returns today's `"app-" + magic` once a plugin has called
    `requireAppOnlyNetwork` (D7). Before that it throws `IllegalStateException`. X builds its read-model
    registry after that call, as `AppLedgerAssembly:112-120` does today.

`HostApiVersion.CURRENT` becomes `(1, 2)`. All additions are new types or default methods.
`PluginApiVersion.CURRENT_LEVEL` rises because public plugin API symbols are added (D3).

**Publication of assembly products (A1, X1).** `PluginContext.registerService` is closed after `init`
(`PluginContextImpl:94-96,146-153`). The rule stays. During `init`, the X root registers one stable,
plugin-owned holder object under an X key. Its assembly callback populates the holder.

- The holder reports "not assembled" before the callback runs and after the owner's close.
- The holder lives as long as the plugin instance.
- A kernel restart keeps the same plugin instance, so it keeps the same holder.
- Consumers never cache the holder's contents across a restart.

D6 adds the read side.

### D3. Open contribution kinds; X owns its kinds and facades

**Kind registry.** `ContributionKind` becomes an immutable value type, with equality on `manifestKey`.
Its fields:

- `manifestKey`;
- `serviceInterface`, as a binary name;
- `manifestRequired` and `PluginTrustTier`;
- rules: `maxPerBundle`, `nameIsBundleId`, `forbiddenNameChars` and `reservedSystemNames`;
- `operationsTracked`, which replaces the `"domain-api"` literal in `PluginOperationsRegistry` (A9).

A `ContributionKindRegistry` is built once per catalog build or inspection from two origins:

1. the four built-in Yano kinds: `node-plugin`, `utxo-index-contributor`, `health`, `metrics`;
2. descriptor resources from **trusted origins only** (A5, X8): one fixed resource
   `META-INF/yano/contribution-kinds.json` per artifact, with the owner declared inside the JSON, found
   by resource name at runtime and by entry name at index or CLI time, so discovery does not depend on
   JAR directory entries.

Trust is decided by **origin**, not by artifact content (A5 P3). The uber-jar and `framework/cardano`
legitimately mix host code with a bundle manifest. The trusted origins are:

- at runtime, resources loaded by the host (parent) class loader;
- at index or CLI time, the artifacts passed as the host class path. That is the application runtime
  class path in the `app` and X builds, and an explicit `--host-artifact <jar>` option in the
  `yano-plugins` CLI.

A descriptor that is visible only through a directory-plugin loader, or only inside a scanned plugin
artifact that is not on the host class path, is rejected.

The registry rejects:

- duplicate keys;
- one interface registered under two keys;
- redefining a built-in kind;
- conflicting rules.

Descriptors also declare the API package prefixes they protect, replacing `PluginArtifactScanner:54-57`.
All static `ContributionKind.values()/fromManifestKey` call sites take the registry instance instead.
Manifest and index formats are unchanged, and an unknown kind still fails closed.

**Runtime binding.** `node-api` gains `ContributionKindBinding<P>`.

- Bindings are discovered with `ServiceLoader` on the host loader, before the catalog is built.
- Each binding supplies the `Class<P>`, `selector(P)` (which replaces the two selector switches) and
  `facade(P, PluginCallbackScope)`.
- Every descriptor kind must have exactly one binding with the same key, and with an interface equal to
  `serviceType().getName()`. A built-in kind keeps its Yano binding. Any mismatch fails catalog
  construction.
- Bindings do not receive an operations recorder. The recorder is handed to the moved registry
  through `registerPluginProducts` (see "Registry phases" below) (A9, X15).

**`PluginCallbackScope` (A6).** This is the public face of the existing `CallbackTracker`,
`ActivationContext` and `ProductReservations`. Admission and reservations stay shared across all
bindings. Its methods:

- `bundleId()`, `selector()` and `loader()`;
- `<T> T call(Supplier<T>)` and `void run(Runnable)`, which apply admission and TCCL;
- `void runCleanup(Runnable)`;
- `<T> T activation(String action, Supplier<T>)`;
- `<T> T product(T value, UnaryOperator<T> facade)` and
  `<T> List<T> products(List<T> values, UnaryOperator<T> facade)`, which apply reservation and snapshot.

The pure helpers that both the Yano and X facades use move once into `node-api` as
`org.yanoproject.api.plugin.spi.FacadeSupport`: bounded list and map snapshots, `StatusBudget`,
product close and failure propagation. They are not copied.

**What moves to X.**

- The X facades move to X **verbatim except for these scope and helper calls**. The Yano facades
  (`utxo-index`, `health`, `metrics`) stay.
- `DomainApiRegistry`, `LocalReadModelRegistry` and `LocalReadModelContributionRegistry` move to X
  unchanged. The X root activates whenever it is listed in `yano.plugins.required`. With
  `yano.app-chain.enabled=false` it still builds these registries with `AppChainGateways.empty()`, as
  the host does today, so domain APIs keep working without app chains (A4).
- `PluginOperationsRegistry` loses its `DomainHttpMethod` overload. The public `PluginOperation` enum
  keeps `DOMAIN_GET/POST`, because those names appear in metrics and inventory.

**Registry phases (A3, X11).** The two registries keep their distinct positions as they are today.

*Read models.* Today they start in a pre-publication subsystem placed directly after the host lifecycle
subsystems: `RuntimeNode:900-928`, and the `local-read-models` stage in `AppLedgerAssembly:139-143`.
X registers that same subsystem with the existing `registerBeforePublication`, right after its
manager, which yields the same position in both hosts.

*Domain products.* These have their own host position, which is neither provider admission nor
pre-publication:

- **Resume.** In Cardano mode, after `pluginManager.startAll()` and before the UTxO filter chain,
  index contributors and `activateTelemetry()` (`RuntimeNode:1562-1576`). Provider admission is
  reopened earlier, at `:1562`. In app-ledger mode, at the start of the `app-operations` stage, before
  `activateTelemetry()` (`AppLedgerAssembly:144-146`).
- **Seal.** In both stop paths: `stopDomainAndNodePlugins` (startup rollback) and
  `stopPluginsAfterRuntimeDrain`. In the drain path the seal happens **before** the unsafe-drain early
  return (`RuntimeNode:3852-3859`), and provider admission is sealed later, at `:3868`. In app-ledger
  mode, in the `app-operations` stop, after operations telemetry.

These positions are exposed through one hook,
`PluginOperationsRecorder HostLifecycleRegistry.registerPluginProducts(String ownerBundleId, PluginProducts)`,
where `PluginProducts` has `void resume()` and `void sealAndAwait()`.

- The host calls `resume()` in registration order and `sealAndAwait()` in reverse order, at exactly
  the positions above, on every path including rollback and unsafe drain. X's `sealAndAwait()` seals
  domain products and then read models, which is today's order in `stopDomainAndNodePlugins`. Both
  seals are idempotent.
- Only embedded owners may register. The call returns a `PluginOperationsRecorder` bound to this host
  instance; there is no static state, so several embedded Yano instances stay independent.
- The recorder accepts events only for bundles that own an `operationsTracked` contribution. Its
  methods are today's package-private domain hooks with the kind made explicit:
  `activating`, `active`, `activationFailed`, `stopped(succeeded)`, `admitted`, `started`,
  `queueFinished`, `callbackFinished` and `outcome(bundleId, PluginOperation, PluginOperationOutcome)`.
- The moved `DomainApiRegistry` keeps its `OperationsObserver` seam (`:251-303`) and adapts it to the
  recorder. Admission, queue cancellation and timeout events (`:764-765,1156-1165`) are therefore
  recorded exactly as today, and moved tests cover them.

**Permanent close (X18, A14).** `registerPluginProducts` does not transfer close ownership. The X root
owns the registries it creates (`DomainApiRegistry`, `LocalReadModelContributionRegistry`,
`LocalReadModelRegistry`) and closes them in reverse creation order from its existing
`NodePlugin.close()`. The host already invokes that through `closePluginManager()`, at the point where
`RuntimeNode:3956-3961` closes them today.

- If assembly fails part-way, the callback closes whatever it created, in reverse, before rethrowing.
- Close-before-start and repeated close are no-ops after the first close.
- A rejected registration leaves ownership with the caller.
- Close keeps aggregating failures, and the moved tests prove executor termination
  (`DomainApiRegistry:572-601`).
- With app chains disabled, the X root still owns and closes the registries it builds for domain APIs.

Registries registered through `registerBeforePublication` keep that method's existing ownership-transfer
rule (`HostLifecycleRegistry:12-18`).

I5 pins both positions for Cardano and app-ledger hosts, including the rollback and unsafe-drain paths.

**Native (A11).** Descriptor kinds are JVM-only, because X is JVM-only (MD2). The native image keeps the
built-in kinds and their static service patterns. The native and packaged smokes use only built-in kinds,
plus a JVM-only test descriptor kind (D9).

### D4. Bundle configuration

The moved registries use `PluginRuntimeEnvironment.bundleConfig(bundleId)` today. It becomes
`HostApi.bundleConfig(String bundleId)`.

The deliberate empty `domainApiConfig` projection is a security boundary, because bundle configuration
can contain credentials (`PluginRuntimeEnvironment:576-588`). It moves with `DomainApiRegistry` to X, and
it still returns an empty map. The moved regression test uses a secret-bearing bundle configuration (X3).
`HostApi.bundleConfig` is documented as an unrestricted read for trusted host code only.

### D5. L1 contracts that are Cardano concepts move to Yano

**Governance enums.**

- `ledger-state` reuses yaci `GovActionType` for the action type (C10). It has the same seven
  constants with the same ordinals as X's `GovernanceActionType`, and it is already persisted by ordinal
  in `GovActionRecord`. Its names differ for three constants (`*_ACTION` suffixes).
- Two new Yano-owned enums in `cardano-api` (`org.yanoproject.api.governance`) replace the X ones:
  `GovernanceProposalStatus` and `GovernanceProposalStatusReason`. They keep the **identical names and
  constant order**.
- The codec, prefix `0x71`, field layout and stored bytes do not change.
- Both new enums are **append-only**, and this is documented in their javadoc (C3).

X keeps its own `l1view` enums, because X's generic API depends only on `node-api`. The mapping happens
at the Yano-to-X boundary in X's epoch bridge (C12, X12):

- **Action type.** The exhaustive `switch` that exists today at `GovernanceEpochProcessor:958-975`
  (yaci `GovActionType` to X `GovernanceActionType`) moves into the bridge unchanged. It does not map by
  name.
- **Status and reason.** These map by `name()`, because the names are identical.

An exhaustive test covers every constant of all three enums. A Yano constant that X cannot map makes the
bridge fail closed for that epoch's governance view, with an explicit diagnostic; it is never silently
dropped. This is one mapping moved to the trust boundary, not duplicated logic.

**Epoch snapshots (C2, X2, A10).**

- `cardano-api` gains `EpochSnapshotReader`. It is the Yano-typed counterpart of X's
  `L1EpochStateProvider`, with the same six operations: `persistent`, `snapshotRetentionEpochs`,
  `epochAtSlot`, `firstObservableEpoch`, `completedBoundaries(after, limit)` and `open(boundary)`.
- `open` returns a closeable `EpochSnapshot` with Yano-typed params and the three `has/forEach` pairs
  of `HistoricalEpochStateView:39-110`.
- The current `DefaultL1EpochStateProvider` logic moves, **unchanged**, into a Yano runtime class
  `DefaultEpochSnapshotReader` that implements this interface. That logic covers completed-boundary
  gating, the `findNextBlock`/`getBlockNumberBySlot` boundary lookup, `EpochSlotCalc` and close-scoped
  views. The adapter therefore needs no chain or epoch internals.
- X's bridge maps types and applies its `ProtocolParamsCanonicalCodec`.

**Readiness of the epoch supplier.** It returns empty until today's `wireL1EpochState` position
(`RuntimeNode:583-592`), and the same reader instance afterwards. Today the provider is only used after
kernel start, so X resolves the supplier in its pre-publication `Subsystem.init`. If the host has no
account-state store, or no `EpochParamProvider`, the supplier stays empty and X reports epoch observation
unavailable, exactly as an unwired manager does today. The store-disabled case keeps today's
`persistent() == false` behaviour.

**L1 transaction builder.** `DefaultL1TransactionBuilderService` moves to X's `framework/cardano`. It is
built from `CardanoHostServices`, using `ChainBlockReader.getLocalTip().getSlot()`, which is
`chainState.getTip()` (`RuntimeNode:4201-4202`). QuickTx leaves `runtime`'s dependencies.

### D6. Public façade: generic plugin services

`Yano` loses `appChain()`, `appChains()`, `domainApis()` and `localReadModels()`. `RuntimeYano`,
`YanoDevnetAssembly` and `AppLedgerAssembly` lose their implementations.

`host-api` gains `PluginServiceLookup` with `<T> Optional<T> service(String key, Class<T> type)`, and
`Yano` extends it.

- It reads services registered through `PluginContext.registerService` by the **currently
  initialized** plugin generation.
- The result is re-resolved on every call, and it returns empty before init and after close (B5).

X publishes its holder (D2). For embedders it provides
`org.yanoproject.x.framework.host.AppLedgers.of(PluginServiceLookup)` in `framework/host-adapter`, which
keeps its API-only compile dependency on `host-api` (B12, X13). The no-Quarkus library test (D12)
exercises this helper. X's REST producers resolve through `Yano.service` on every request; they never capture once.
This is a documented preview break with a one-line migration, and there is no shim. The X host-matrix
fixture changes to use the helper.

### D7. `yano.mode=app-ledger` becomes a generic no-L1 host

The value and `RuntimeMode.APP_LEDGER` are kept (D1 exception). In Yano the mode means a host without
Cardano chainstate that runs the plugin environment, Host API, app-only N2N server (Yaci V101), kernel
and REST.

- `AppLedgerAssembly` keeps that composition.
- Its app-chain validation (`validateHost:157-201`) moves to the X plugin. It runs inside the assembly
  callback, which still comes before any database or listener is opened (B9).
- The network magic for `ServeSubsystem.appOnly` comes from the plugin, via
  `N2NExtensionRegistry.requireAppOnlyNetwork(long)` during assembly.
- The host keeps rejecting known Cardano magics, and it fails startup when no plugin supplied a magic or
  plugins supplied conflicting ones. On a Yano-only distribution, the diagnostic is "app-ledger mode
  requires a plugin that provides an app-only network" (B9).
- `CardanoAvailabilityFilter` (`@RequiresCardano`, 503) stays.

**Configuration projection (X7, B3, C6).** Yano does **not** forward a raw `yano.*` snapshot.

- The app-chain `@ConfigProperty` fields and the normalization logic of `YanoProducer` move to an
  X-owned CDI bean in X's `framework/rest`. That logic is `:190-354`, `:793-845` and `:978-1028`: the
  effective-enable rule, the structured `chains` list, indexed-to-map conversion, dynamic prefixes and
  storage defaults.
- That bean implements a new Yano interface, `org.yanoproject.app.spi.RuntimeGlobalsContributor`, with
  `Map<String,Object> globals()`. Before it builds `RuntimeOptions`, `YanoProducer` merges every
  contributor. Startup fails if two contributors set the same key, or if a contributor sets a key the
  host itself sets (B14).
- Quarkus typed configuration therefore keeps its environment-variable mapping for dashed keys, such as
  `YANO_APP_CHAIN_*`.
- The projection keeps the same keys and values, so what is exposed to plugins is unchanged.
- `YanoPropertyKeys.AppChain` and `YanoConfig.appChainStoragePath` move to X (`AppChainStoragePaths`).
  Key strings and defaults are unchanged.
- Embedders without Quarkus pass globals through `RuntimeOptions`, as today (D12).

### D8. The integrated Yano+X product is composed downstream, in Yano-X (MD1)

**Yano module shape (B2, X5).**

- The sources and resources of `app` move to a new publishable library module **`app-core`** (artifact
  `yano-app-core`).
  - It uses `platform(quarkus-bom)`, not `enforcedPlatform`, so Gradle can publish it.
  - It carries a Jandex index.
  - It contains `application.yml`, the native-image config, the built console assets under
    `META-INF/resources/ui`, and the generated API-prefix contract (`app/build.gradle:185-198,421-423`).
    Every downstream application therefore inherits Yano's prefix and defaults.
  - It is a normal library publication, so it receives the existing `--no-fonts` javadoc option
    (`build.gradle:567-571`).
  - Estimated size, based on today's 0.6 MB plain jar and the console assets: 2–3 MB per release,
    including sources and javadoc.
- `app` stays the unpublished Quarkus application. It depends on `app-core` and keeps the executable
  concerns:
  - `enforcedPlatform`, and the Jackson 2.21.4 and SLF4J forces and exclusions;
  - plugin-index generation;
  - packaged and native smokes;
  - ZIP and native distribution tasks;
  - `@QuarkusTest` suites.

  Output paths (`app/build/yano.jar`, ZIPs) are unchanged for scripts and QA.
- `app-core` gets a public CDI producer for the single `Yano` instance, which replaces today's
  package-private `ensureYano()`.
- The small surface X compiles against lives in `org.yanoproject.app.spi`: the `Yano` producer,
  `RuntimeGlobalsContributor`, the shared `ApiKeys` helper, and `RequiresCardano`. Yano has no japicmp
  or revapi gate today (B13), so the gate for this surface is the M1 downstream-consumer check. That
  check is a source-compilation and boot check, not full binary-compatibility coverage. It compiles and
  boots a fixture against exactly these symbols from staged publications. It runs in CI, and locally
  only when its inputs change (C14).
- `app.spi` changes are additive only. Removing or changing a symbol needs an ADR, and must not be done
  by editing the fixture in the same PR (B13 P3).
- Embedder migration notes include the removal of `YanoConfig.appChainStoragePath` (B14).
- Yano's console has no link to X's console. The X console is reached directly at `/ui/app-chain/` and
  links back to `/ui/` (B14).

**Yano-X.**

- A new module, `framework/rest`, holds the moved code:
  - `AppChainResource`, `PluginDomainResource`, `AppChainApiKeyFilter`, `AppChainAccess`,
    `AppChainHealthGroupCheck` and `AppChainMetrics`;
  - the app-chain producers, `UnavailableAppChainGateway` and the configuration projection (D7);
  - a startup check that fails when `yano.app-chain.enabled` is true but the X root is missing from
    `yano.plugins.required`, for example because an operator overrode it (A8 P3).

  `appchain-proof-verifier` becomes its own plain X library, `framework/proof-verifier`
  (`yano-x-appchain-proof-verifier`), with no Quarkus. `framework/rest`, `sdk/client` and
  `tooling/devtools` consume it. Its dependency closure is part of the no-Quarkus checks (B11, X14).

  Java packages move under `org.yanoproject.x.*`. Paths, JSON, status codes and the OpenAPI group stay
  identical.
- `distribution/jvm` gains a small Quarkus application.
  - It mirrors Yano's `app` executable settings: enforced platform, forces, and index generation
    through Yano's published `PluginIndexGenerator`.
  - It depends on `yano-app-core`, `yano-runtime`, the X framework and `framework/rest`, and it sets
    `yano.plugins.required`.
  - It builds the integrated `yano.jar`, then assembles the existing X ZIP layout around it.
- The user-facing integrated deployment is unchanged: paths, scripts, configuration, plugin directory
  and ports.
- The `verifyYanoInputs` manifest pin `builtInStateMachines: ['ordered-log']` and
  `extractYanoHostEntries` follow the new jar (B8, B10).

X takes over these bounded duplicates, listed explicitly (B8):

- about 150 lines of closure and index Gradle logic. X keeps one implementation, a shared Gradle
  script used by both `distribution/jvm` and `host-matrix`, replacing the partial copy in
  `host-matrix/run.py`. The Yano JVM ZIP stays an input for scripts, configuration and docs; only its
  `yano.jar` is replaced;
- the packaged smokes that use X fixtures;
- the app-chain parts of `qa/harness/common.sh`.

**Auth split (X4, B4).** The existing filter stays whole and moves verbatim to X, so classification and
rejection order cannot change. That order is: canonical path, body bound, dynamic domain access, INTERNAL
as 404, privileged 404 or 503, then the key check.

X's filter stops matching `PluginOperationsResource`. Yano keeps a small `PluginOperationsAuthFilter`
for that resource. It runs exactly the code path that the current filter runs for that resource, and
gives the same outcomes (B14):

- a non-canonical path is rejected;
- 503 `AUTH_UNAVAILABLE` when no full key is configured in the existing `yano.app-chain.api.keys`;
- 401 for a missing or invalid key;
- 403 for a topic-scoped key.

That resource has no request body, so no body-bound outcome applies.

The key parsing and the canonical-path and entity-bound helpers are shared through
`org.yanoproject.app.spi.ApiKeys`, so they are not copied. `AppChainAccess` stays X's annotation.
Yano's resource needs no annotation, because its filter matches by class.

**Native.** Yano's native image contains no application-ledger code (MD2).

### D9. Delete or move the Yano copies

| Yano item | Disposition |
| --- | --- |
| `appchain-config`, `appchain-anchor-onchain` | Delete. Consumers use `yano-x-appchain-config` and `yano-x-appchain-anchor-onchain`. Their tests and `appchain/test-vectors` move to X. |
| `appchain-proof-verifier` | Moves to X as the plain library `framework/proof-verifier` (`yano-x-appchain-proof-verifier`). |
| `appchain-testkit` | Moves to X (test-scope `yano-runtime`). |
| `appchain-plugin-conformance` and smokes | The X fixtures move to X. Yano adds an X-independent fixture with built-in kinds, an inbound N2N agent factory and a JVM-only test descriptor kind. The packaged and catalog smokes then still cover N2N registration and app-ledger mode generically (B9). |
| Runtime and app app-chain tests, catalog tests on X fixtures | App-chain behaviour tests move to X with their code. Generic catalog, TCCL and barrier tests switch to the fixture kinds. |
| `console-ui` app-chain pages | Move to X as a separate SvelteKit application served at `/ui/app-chain/` from `framework/rest` resources (B7). Yano's console keeps the generic pages and builds nothing under `/ui/app-chain/`, so the uber-jar has one owner per path. The few shared UI components the moved pages import are moved with them and owned by X from then on. Yano's console keeps its own copy. This is accepted because the two consoles are separate products with no shared npm package. |
| `qa/app-ledger`, `qa/harness/appchain-*`, release-qa `appchain`, `test-app-chain-*` and `deploy-showcase-*` skills, `docs/appchain`, app-chain `www` pages, `app/config/application-appchain.yml`, app-chain observability dashboards | Move to X. X's QA runs them against the downstream distribution. |
| Docker/compose app-chain mounts and env, `gradle/runtime-plugin-bundle.gradle`, `scripts/sparse-backfill` app-chain property | Delete from Yano. The integrated Docker image, if any, is an X deliverable, and the migration notes cover Docker users (B9). |
| `build.gradle:54-64,242,716-728`, BOM entries, `settings.gradle` modules, `appLedgerFramework*`, `prepare-framework-inputs`, `ci-input-pins.json`, framework parts of `COORDINATED_INPUTS.md` | Delete. `stage-consumer-inputs.yml` stays to stage Yano artifacts for X branch CI. |
| X host-matrix frozen fixtures for hosts that embedded X | Retired in X. A new frozen Host API 1.2 baseline fixture is recorded from M1 artifacts. |

### D10. X builds against Yano artifacts only

X keeps its `yanoVersion`/`nodeApiVersion`/`hostApiVersion` pins and its repositories: Central,
`repo.bloxbean.org` snapshots, an explicit staging repository, and opt-in Maven Local.
`gradle/framework-project-resolution.gradle` and the plugin-bundle host-class stripping follow the new
integrated jar. No X module is consumed by Yano, so `framework-stage` and the X-to-Yano staging scopes
disappear.

### D11. Guardrail (C4, X10)

`gradle/no-yano-x.gradle`, applied from the root, adds `verifyNoYanoXDependency` to `check` and CI. It
contains five rules:

1. A `resolutionStrategy` rule on every configuration of every project, including buildscript and the
   `settings.gradle` `pluginManagement` classpath. It fails
   any request for group `org.yanoproject.x` or module `yano-x-*`. It also checks declared
   dependencies, so an unresolved declaration cannot hide.
2. A scan of generated POMs, Gradle module metadata and the BOM.
3. A token scan for `org.yanoproject.x` and `yano-x` in tracked files.
4. An inspection of the built `yano.jar`, the JVM ZIP, the Docker build context, the generated plugin
   index, and the native resource and reflect configs.
5. A negative probe: a temporary build injects one X coordinate and one X token, and the task must fail.

The token scan is case-insensitive and excludes only (C13):

- the top-level `adr/**`, `docs/**` and `www/**` trees, which are history and cross-product links;
- the top-level `README.md`;
- the `AGENTS.md` ownership section;
- the guard script and its probe fixture.

The token `app-chain` is intentionally allowed (D1).

**I1 mechanism.** The build runs with a fresh `GRADLE_USER_HOME` and Maven Local disabled. Rule 1 makes
any X request fail even if an artifact happens to be cached.

### D12. Embedding (MD3)

The X framework (`api`, `core`, `config`, `cardano`, `host-adapter`) stays plain Maven libraries that
depend only on Yano's `node-api`, `host-api` and `cardano-api`. `yano-runtime` is needed only at runtime.

An embedder:

1. starts Yano with `YanoAssembly` and `yano.plugins.required` set, plus `RuntimeOptions` globals;
2. reaches the chains through `AppLedgers.of(yano)`;
3. exposes them however it likes.

`framework/rest` and `yano-app-core` are only for Quarkus distributions. X adds a library-mode
integration test with no Quarkus on the class path.

## Invariants

- **I1** (D1, D11). From a clean checkout, `./gradlew build` and `:app:quarkusBuild` succeed under the
  D11 mechanism. `verifyNoYanoXDependency` passes, and it fails its negative probe.
- **I2** (D5).
  - Golden bytes captured from `ebd3eeea4` for every status/reason/action-type triple encode
    byte-identically and decode to the same values. That is 245 vectors, already captured in M0.
  - The existing `GovernanceHistoryRollbackTest` and `HistoricalEpochStateViewTest` pass. Their only
    change is the type substitution (X enums replaced by yaci `GovActionType` and the Yano enums).
  - A baseline-written ledger-state fixture containing `0x71` lifecycle rows and `GovActionRecord` rows
    opens and reads identically under the new build.
  - Tests pin the constant order of both new enums and the yaci `GovActionType` ordinal correspondence
    (C12, X12).
- **I3** (D3, revised per A2, C5, X9).
  - **Old versus new catalog code.** M0 captures the old catalog's index files and fingerprints for
    frozen fixture artifacts, using the baseline jar. M1 then needs no X-dependent executable. In M2,
    where X's descriptors exist, the new catalog runs on the same frozen artifacts.
    - Index files must be byte-identical.
    - Fingerprints are compared on their canonical input. The new input must equal the old one except
      for the intentional `PluginApiVersion` level, which is asserted separately.
    - Each hash is recomputed independently from its input.
  - **Baseline versus migrated distribution.** The semantic inventories must match on the preserved
    fields: bundle ids, kind keys, contribution names, trust tiers, and provider classes mapped through
    the documented package moves. Bundle versions and artifact digests change intentionally, and the
    new values are validated independently.
  - Provenance hashing is not weakened.
- **I4** (D3). The moved X facade tests (`PluginTcclBoundaryTest` and `PluginContributionCleanupBarrierTest`
  cases) pass unchanged in X. The generic cases pass in Yano on the fixture kinds. The X3
  secret-config regression passes in X.
- **I5** (D2, D3).
  - A Yano fixture test records the order: init, then assembly callbacks (owner TCCL), then seal, then
    kernel pre-publication, then publication. It records `PluginProducts.resume/sealAndAwait` at the D3
    positions for Cardano and app-ledger hosts, including startup rollback and the unsafe-drain path. It
    also covers recorder queue, cancellation and timeout events.
  - It rejects late, re-entrant, duplicate or impersonated registration.
  - It covers restart readiness returning `false`.
  - X's moved adapter tests pass.
- **I6** (D8).
  - A route inventory of the baseline integrated `yano.jar`, captured at M0, equals that of the
    downstream jar in methods, paths and OpenAPI group.
  - Real HTTP tests in X's downstream application cover dynamic domain routes, INTERNAL routes, absent,
    full, scoped and snapshot keys, auth disabled, malformed paths and oversized bodies, with the
    baseline status codes.
  - Yano's plugin-operations filter tests cover the same outcomes on the bare Yano.
- **I7** (D7).
  - Flat and indexed configurations, omitted and false enablement, environment-variable overrides and
    storage defaults project identically through the packaged application.
  - Standalone nodes finalize, catch up and restart over V101 with no Cardano chainstate.
- **I8** (D8, D10, revised per B6).
  - Yano artifacts are staged once, and their SHA-256 is recorded.
  - Two different X builds are composed against those exact bytes, and both pass the standalone and
    integrated smokes. The two builds are X_a (PR head) and X_b (X_a plus a real behavioural change in a
    fixture state machine, with its own version).
  - An X build that requires Host API level 1.3 is rejected by that host at startup. Rejection happens
    in the assembly callback, before any X-owned database is opened and before any listener starts.
    Host chain storage is already open by then (`RuntimeNode:416`); it is closed by the existing
    reverse cleanup (X17). This is the case that can fail.
  - The X host-matrix runs X_a against the frozen 1.2 baseline and the current host.

## Milestones (revised per C1, X6)

The old activation paths hard-link X, so the Yano cutover cannot be additive. The order is therefore:
cut Yano over first, then make X own the product.

- **M0 — Baselines.**
  - Entry: none.
  - Exit: from `ebd3eeea4` plus the pinned inputs, capture before the Actions artifacts expire:
    - governance golden bytes;
    - a baseline ledger-state store;
    - the OpenAPI and route inventory;
    - the integrated catalog's semantic inventory;
    - integrated `yano.jar` and ZIP hashes;
    - the baseline release-qa report (done: 14 passed, plus 2 known);
    - the old catalog's index files and fingerprints on frozen fixture artifacts (I3);
    - an auth outcome matrix over routes, key states and malformed or oversized requests (I6);
    - app-chain status and chain projections for flat and indexed configurations, as the effective
      globals baseline (I7);
    - a baseline-written ledger-state fixture with `0x71` and `GovActionRecord` rows, produced with the
      baseline classes (I2).

    Small vectors are committed as test resources. Large artifacts go to the QA evidence directory. The
    baseline jar and ZIP, and the capture script, are retained with their SHA-256 values, so any further
    baseline evidence can be regenerated after the Actions artifacts expire (C8).
- **M1 — Yano cutover (one Yano PR).**
  - Entry: this ADR approved.
  - Scope: D2–D9 Yano side and D11. Host API 1.2, `CardanoHostServices`, `EpochSnapshotReader`, the
    enum move, the open kind registry with bindings, scope, `FacadeSupport` and the fixture, `Yano.service`,
    the `app-core`/`app` split, the plugin-operations filter, and deleting every X edge.
  - Exit:
    - I1, I2 and I5, plus generic-fixture catalog tests (the descriptor-dependent I3 checks run in
      M2) (X9);
    - full Yano unit and integration tests;
    - an X-free downstream-consumer check: a tiny Quarkus fixture application in Yano composes
      `app-core` from staged publications with a fixture plugin. It verifies one `Yano` instance,
      unambiguous producers, one plugin index and an effective-config dump equal to Yano's own `app`.
    - release-qa `l1,e2e,compat,docker` (JVM and native) compared against M0;
    - artifacts staged to a file repository.
- **M2 — Yano-X ownership (one X PR).**
  - Entry: M1 artifacts staged.
  - Scope: X side of D2–D10 and D12.
  - Exit:
    - X `./gradlew check` and `distributionCheck` against the staged Yano;
    - I3 (both the old-versus-new catalog comparison and the inventory comparison), I4, I6 and I7;
    - the library-mode test;
    - the moved app-chain QA on the downstream distribution: cluster with L1 rollbacks, extensions,
      rotation and governance, script anchor.
- **M3 — Independence proof and docs.**
  - Entry: M2 exit.
  - Exit: I8, plus migration notes for embedders, plugin authors (`ContributionKind` constants, CLI
    `--host-artifact`), operators (integrated ZIP and Docker from X, native without app chains, bare
    Yano with X bundles failing closed) and the Host API matrix.

**PRs and merge order.** One Yano PR (M1) and one X PR (M2/M3), each built from reviewable commits.
X branch CI uses the staged Yano artifacts. After both are approved, Yano merges first, its snapshot is
published, and the X PR's Yano pin moves from staged to published before X merges. There is no release
or tag.

Between the two merges, Yano `main` contains no integrated app-chain product. Users of the integrated
product keep the last released X distribution. The window is kept short by merging X right after the
snapshot publishes (C13).

## Verification

Evidence is independent of the code under test:

- M0 baseline bytes, inventories and reports;
- SHA-256 of staged artifacts;
- existing tests moving unchanged with their code;
- release QA compared with the M0 run: Haskell sync and past time travel (JVM and native), sparse
  backfill, epoch crossing, e2e, SDK compatibility and Docker on X-free Yano; the app-chain harnesses on
  X's distribution.

## Alternatives considered

- **A generic plugin HTTP route SPI, with X re-implementing REST on it.** Rejected by MD1.
- **Opaque extension kinds discovered by X from bundle loaders.** Rejected. It would duplicate or bypass
  catalog provenance, digest, policy and barrier logic.
- **A generic `java.lang.reflect.Proxy` facade.** Rejected. It cannot reproduce the per-SPI copies and
  bounds, and it adds reflection metadata.
- **Forwarding a raw `yano.*` snapshot to plugins.** Rejected (B3, C6). Environment-only dashed keys
  are lost, derived values are lost, and the exposure is wider than today.
- **One shared authentication filter with extension hooks.** Rejected (X4, B4). It cannot preserve
  rejection order. Moving the filter whole plus a tiny Yano filter is simpler.
- **Moving the governance enums to `node-api`.** Rejected. It puts Cardano governance into the generic
  foundation for the sake of one mapping.
- **Keeping the `yano-appchain-*` compatibility coordinates.** Rejected. They are X dependencies by
  construction.
- **Quarkus mutable-jar re-augmentation to add X to an installed Yano.** Not pursued. It is unverified
  for adding application jars, and MD1 settles delivery.

## Consequences

- Yano builds, releases and runs as a Cardano node without X. CI loses the four-step bootstrap.
- X releases against a supported Host API level without a Yano release. The integrated application pins
  the Yano artifact version it composes.
- The integrated JVM ZIP, any integrated Docker image and the app-chain console are X deliverables.
  Yano's ZIP, Docker image and native binary serve no app chains.
- A bare Yano with X bundles in `plugins/` fails closed with "contribution kind is not supported".
- Embedders replace four façade methods with `AppLedgers.of(yano)`. Plugin authors use X's kind
  constants and pass `--host-artifact` to the CLI.
- Yano stops publishing `yano-appchain-config`, `yano-appchain-anchor-onchain`,
  `yano-appchain-proof-verifier` and `yano-appchain-core-testkit`. It starts publishing `yano-app-core`.

## Open questions

None blocking. MD1–MD3 settle the earlier Q1–Q3. Q4 (renaming the `yano.app-chain.api.*` key names that
Yano's plugin-operations filter reads) is deferred to a later ADR; this ADR keeps the names (D1
exception).

## Related findings (out of scope)

- Contracts in implementation-sounding `org.yanoproject.runtime.*` packages inside `node-api` keep their
  binary names.
- `p2p` `enableAppChainSync` is a Yaci peer-client method name, not an X dependency; it is unchanged.

## Revision history

- r1 (2026-11-16): initial proposal.
- r2 (2026-11-16), responding to the reviews of `6185c025d`:
  - Maintainer decisions MD1–MD3 recorded (B1).
  - A1/X1 → D2 init-time holder and D6 lifecycle.
  - A2/C5/X9 → I3 revised.
  - A3 → D3 contribution-phase listener at the existing points.
  - A4 → D3 note that domain APIs stay available with app chains disabled (the X root still
    builds the registries with empty gateways).
  - A5/X8 → trusted-origin kind registry and CLI `--host-artifact`.
  - A6 → `PluginCallbackScope` methods and `FacadeSupport`.
  - A7 → owner ids, TCCL, position, re-entrancy.
  - A8 → `yano.plugins.required` semantics.
  - A9 → `operationsTracked` and the recorder passed only to bindings.
  - A10 → `networkName()` and the epoch supplier readiness.
  - A11 → native keeps built-in kinds.
  - A12 → `nodeConfig()` copies.
  - A13 → `api` scope, equality, test caller.
  - B2/X5 → `app-core`/`app` split, `Yano` producer, SPI package, consumer check.
  - B3/X7/C6 → `RuntimeGlobalsContributor` projection.
  - B4/X4 → whole filter moves, plus a small Yano filter.
  - B5 → `Yano.service` lifecycle.
  - B6 → I8 revised.
  - B7 → separate X console at `/ui/app-chain/`.
  - B8/B10 → X duplicates listed.
  - B9 → regressions recorded and the generic fixture.
  - C1/X6 → milestones re-ordered to cutover-first.
  - C2/X2 → `EpochSnapshotReader` mirrors the X provider with the moved logic.
  - C3 → I2 extended, append-only, unmapped constants fail closed.
  - C4/X10 → guardrail rules and exceptions.
  - C7 → inventory completed.
  - C8 → M0 baselines.
  - C9 → D1 exceptions.
  - C10 → yaci `GovActionType` reused.
  - C11 → numbers corrected.
  - X3 → `domainApiConfig` boundary kept in X.
  - Also added: `--no-fonts` and the size estimate for `yano-app-core`.
- r3 (2026-11-16), responding to the round-2 reviews of `1cb37a698`:
  - A3/X11 → read models through `registerBeforePublication`; domain products through
    `registerPluginProducts` at their exact positions, including rollback and unsafe drain.
  - A9/X15 → per-host recorder returned by `registerPluginProducts` to the moved registry; methods
    listed; `OperationsObserver` seam kept.
  - C12/X12 → the action-type switch moves to X's bridge; name mapping only for status and reason;
    I2 pins the yaci ordinals.
  - X9 → I3 compares canonical inputs, uses M0 old-catalog outputs, and separates preserved fields
    from intentional changes.
  - C8 → M0 list completed.
  - B12/X13 → `PluginServiceLookup` in `host-api`; `AppLedgers.of(PluginServiceLookup)`.
  - B11/X14 → `framework/proof-verifier` as a plain library.
  - B13 → the downstream-consumer check is the `app.spi` gate.
  - X16 → `runtimeOptions()` deep snapshot.
  - X17 → the I8 rejection point stated precisely.
  - P3s → A5 trust by origin; A7 readiness exceptions and call phases; A8 X-side required check; A10
    `networkName()` in app-ledger mode; B8 single closure script and ZIP input; B14 plugin-operations
    outcomes; C13 guardrail scope and merge window.
- r4 (2026-11-16), responding to the round-3 reviews of `f881fcd85` (A, B and C approved; X requested
  changes):
  - X18/A14 → permanent-close ownership: the X root closes its registries from `NodePlugin.close()`,
    including partial assembly, close-before-start and rejected registration.
  - X9 → M1 exit keeps only the generic catalog tests; both I3 comparisons move to M2.
  - B13/C14/X → `app.spi` gate described as a source-compilation and boot check, run in CI or when its
    inputs change; additive-only rule.
  - B14 → contributor keys that collide with host keys fail; `appChainStoragePath` migration note;
    console reachability.
