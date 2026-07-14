# ADR-011: Yano App-Layer Plugin Architecture — Bundles, Lifecycle, and Domain APIs

## Status

Accepted — implemented and verified

The number is local to the `adr/app-layer` series; root-level ADR-011 is an
unrelated node-capability roadmap.

## Date

2026-07-13

## Authors

BloxBean Team

## Parent / References

- ADR-005 (app-chain framework and developer SPI)
- ADR-006 (enterprise extensions, query index and ZK roadmap)
- ADR-010 (effect executor SPI and deterministic/execution trust split)
- ADR-013 at `adr/013-modular-library-plugin-and-event-gaps.md` (node-level
  plugin, event and observability gaps)

---

## 0. In plain words

At proposal time, Yano already let applications contribute state machines,
effect executors, stream sinks, sequencer modes, L1 observers and signers, but
it discovered each extension point independently. It could not answer basic
platform questions such as: Which product plugin is installed? Which
compatible version is active? Which contributions belong to it? In what order
should dependencies start? Which configuration and health status belong to it?
How may it expose a safe domain query or API?

This ADR defines that missing control plane, which its sub-ADRs implement. A
**plugin bundle** has one manifest and may contain one or more typed
contributions. A catalog validates identity, compatibility, dependencies and
policy before any contribution is initialized. Typed SPIs remain the data
plane; the bundle does not replace them with a generic service locator.

The first version is restart-based and trusted in-process code. It does not
promise sandboxing, hot reload, or arbitrary runtime JAX-RS discovery.

## 1. Context and proposal-time baseline

When this ADR was proposed, the app-chain implementation discovered six
`ServiceLoader` SPIs:

| Typed contribution | Role | Trust / determinism |
|---|---|---|
| `AppStateMachineProvider` | creates a chain's deterministic application transition | consensus-critical |
| `SequencerModeProvider` | supplies message ordering/proposer behavior | consensus/liveness-critical |
| `L1ObserverProvider` | verifies externally-derived L1 facts | consensus-critical |
| `SignerProviderFactory` | supplies signing capability and key custody | privileged local |
| `AppEffectExecutorFactory` | performs effects outside deterministic `apply()` | privileged local, side-effecting |
| `FinalizedStreamSinkFactory` | exports finalized blocks | auxiliary local integration |

The generic node runtime also had `NodePlugin` with discovery, lifecycle,
event access, storage filters and dependency declarations. This was a valuable
starting point, but not a coherent app-layer bundle system.

The following were verified gaps at proposal time. They record the starting
point, not the current implementation state:

1. The six typed SPIs were scanned independently. There was no shared bundle
   identity, artifact version, compatibility range, contribution inventory or
   dependency graph.
2. `PluginManager` called `NodePlugin.init()` during discovery and only sorted
   dependencies afterwards. Missing dependencies and cycles were logged rather
   than rejected, so declared ordering did not govern initialization.
3. Each `PluginContextImpl` owned a private service registry. A service
   registered through one context could not be found through another, despite
   the API describing inter-plugin communication.
4. There was no app-layer enable/allow/deny policy enforced before typed SPI
   activation, and configuration was not consistently isolated by plugin id.
5. Duplicate ids, contribution conflicts and Yano API incompatibility had no
   uniform fail-fast rule.
6. `AppStateMachine.query(path, params)` existed in `core-api` but was not
   wired to the REST surface. A plugin could not expose a product-passport or
   other domain view through a stable app-layer contract.
7. The Quarkus REST module had no plugin API contribution contract. Dynamic
   dropped-JAR REST discovery also conflicted with native-image closed-world
   assumptions and made authentication/routing ownership unclear.
8. Plugins had no uniform inventory, health, metrics or CLI contribution
   model.

ADR-011.1 retained `NodePlugin` and closed its validation, dependency-order,
shared-registry, policy and lifecycle gaps. ADR-011.2 added the manifested
catalog and correlated all catalog contribution kinds. ADR-011.3 added
committed queries and constrained domain APIs. ADR-011.4 added the separate
operations, health, metrics, inspection and dashboard surfaces. The complete
platform has passed its two-node functional closure and final JVM, native, and
API-prefix artifact revalidation; ADR-011.4 records the exact evidence.

## 2. Decision summary

Yano introduces a **manifested plugin bundle and catalog** above its typed
SPIs.

- A bundle has a stable id, artifact SemVer, inclusive plugin API-major range,
  required minimum global API level, dependencies, configuration namespace
  and declared contribution kinds.
- Existing typed SPIs remain the only way to contribute consensus and runtime
  behavior. The manifest describes and governs them; it does not instantiate
  arbitrary classes by name.
- Discovery, policy filtering, compatibility checks and dependency sorting
  finish **before** any plugin initialization.
- Contributions are assigned an explicit trust tier and failure policy.
- Lifecycle is restart-based in v1. Runtime hot install/uninstall/reload is not
  supported.
- App-layer queries first gain a generic, authenticated route to a
  committed-context `AppStateMachine.query()` overload. The legacy
  two-argument method remains linkable but is never presented as a
  root-attested runtime query. Domain API contributions use a constrained
  route registry, not arbitrary runtime JAX-RS resource discovery.
- JVM deployments may load configured plugin artifacts; native deployments
  include plugins at build time and generate the same catalog metadata.
- Existing unmanifested SPI providers remain supported for a migration period
  with warnings and synthetic legacy descriptors.

## 3. Goals and non-goals

### Goals

1. Give a domain plugin one identity across all of its typed contributions.
2. Validate duplicates, dependencies, compatibility and operator policy before
   activation.
3. Preserve the deterministic boundary between consensus contributions and
   node-local integrations.
4. Define predictable lifecycle, scope, failure and shutdown behavior.
5. Isolate configuration and secrets by plugin id while retaining existing
   contribution-specific config paths during migration.
6. Expose plugin inventory, activation state, health and version/digest
   diagnostics.
7. Wire application queries and permit safe, namespaced domain APIs.
8. Support both JVM and native-image packaging without claiming dynamic native
   loading.

### Non-goals

- Sandboxing untrusted Java code. In-process plugins are fully trusted code.
- Runtime hot reload or zero-restart upgrades.
- Replacing typed SPIs with a generic `Map<String,Object>` service locator.
- Making an installed executor catalog consensus-visible. Unknown effect types
  remain valid deterministic intent and wait for a capable executor.
- Allowing plugins to add unreviewed unauthenticated admin endpoints.
- Implementing every proposed SPI in this vision ADR.

## 4. Architectural principles

### P1 — Typed SPIs are the contract; the bundle is the control plane

`AppStateMachineProvider`, `AppEffectExecutorFactory` and the other typed
interfaces carry behavior. The bundle manifest supplies identity, metadata,
policy and lifecycle. A plugin does not register an arbitrary object and ask
consumers to downcast it.

### P2 — Consensus and node-local contributions never share failure semantics

A selected state machine or L1 verifier cannot quietly fail while a node
continues with different consensus behavior. An optional telemetry sink may
fail without stopping consensus, but the failure must be visible as degraded
plugin health.

### P3 — Installed local capabilities do not change deterministic transitions

The state machine may emit an effect type for which no executor is installed.
That effect remains pending. `FxKernel` and `apply()` never consult the plugin
catalog, local allowlist or executor availability, so member installations
cannot fork roots.

### P4 — Validate first, initialize second

No plugin code receives a context until catalog construction, structural and
compatibility validation, policy filtering, duplicate detection and dependency
sorting have succeeded. Manifested provider constructors are likewise deferred;
legacy metadata discovery remains the documented migration exception.

### P5 — Restart is the lifecycle boundary

Install, remove, enable, disable and upgrade take effect at process restart in
v1. This makes class loading, thread cleanup, native packaging and consensus
coordination tractable.

## 5. Bundle descriptor and discovery

A JVM bundle contains a bundle-qualified
`META-INF/yano/plugins/<bundle-id>.json` manifest. ADR-011.2 freezes schema v1;
this is a schema-valid example rather than a conceptual alternate format:

```json
{
  "schemaVersion": 1,
  "id": "com.example.product-passport",
  "version": "1.2.0",
  "yanoApi": {
    "min": 1,
    "max": 1,
    "minLevel": 1
  },
  "dependencies": [
    {
      "id": "com.example.shared-domain",
      "minVersion": "2.0.0",
      "maxVersionExclusive": "3.0.0"
    }
  ],
  "contributions": [
    {
      "kind": "app-state-machine",
      "name": "product-passport",
      "provider": "com.example.passport.PassportStateMachineProvider"
    },
    {
      "kind": "domain-api",
      "name": "com.example.product-passport",
      "provider": "com.example.passport.PassportDomainApiProvider"
    }
  ]
}
```

The descriptor is declarative. Standard `META-INF/services/*` entries continue
to name implementations of the typed SPIs. The catalog correlates discovered
providers with their containing bundle and verifies that actual and declared
contribution kinds agree. Configuration namespace and JVM/native packaging
mode are host-derived policy, not schema-v1 manifest fields.

Catalog construction order:

1. Scan manifested descriptors and ServiceLoader provider types without
   invoking manifested provider constructors. During the migration window,
   construct unmanifested legacy providers through a bounded, diagnostic-safe
   adapter because their selector and `NodePlugin` dependency metadata exist
   only in callbacks.
2. Reject malformed descriptors/indexes, unsupported schema or plugin API
   ranges, duplicate bundle identities and manifest/provider correlation drift.
3. Apply configured enablement plus deny/allow policy. Manifested bundles reach
   this step without provider construction; the legacy exception is visible in
   inventory and warnings.
4. Reject selected contribution conflicts and resolve required dependency
   identities and version ranges.
5. Topologically sort selected bundles; a missing dependency or cycle is fatal
   for any selected required/consensus plugin.
6. Publish the immutable catalog/provider registry.
7. Construct scoped contexts, initialize in dependency order, then start only
   after all required initialization succeeds.

An artifact digest is calculated by the runtime/build rather than trusted from
the manifest. Inventory surfaces report the digest with id and version for
deployment comparison.

## 6. Trust tiers and failure policy

Schema v1 recognizes exactly ten catalog contribution kinds: `node-plugin`
plus nine typed SPIs.

| Tier | Manifest contribution kinds | Startup / runtime policy |
|---|---|---|
| **REQUIRED** | `node-plugin` | catalog or lifecycle validation failure is startup-fatal; runtime lifecycle failure is fatal to the owning node role |
| **CONSENSUS** | `app-state-machine`, `sequencer-mode`, `l1-observer` | selected contribution missing, duplicate, incompatible or failed → chain/node role does not start; runtime failure is fatal to that chain role |
| **PRIVILEGED_LOCAL** | `signer-provider`, `effect-executor`, `domain-api` | if explicitly selected for the role, configuration/init failure is startup-fatal; runtime failure parks/degrades affected work and alerts; APIs require authorization |
| **AUXILIARY_LOCAL** | `finalized-sink`, `health`, `metrics` | optional failure is isolated; plugin becomes degraded/failed and retry policy is contribution-specific |

The tier is determined by the typed contribution, not self-declared to obtain a
weaker failure policy. A bundle containing several tiers is reported per
contribution and has the strongest required startup constraint among its
selected contributions.

## 7. Lifecycle and scope

Bundle lifecycle:

```
DISCOVERED → VALIDATED → INITIALIZED → STARTED → STOPPED → CLOSED
                   └─────────────────────▶ FAILED
```

- Bundle/node scope owns metadata and shared immutable services.
- Factory contributions create **per-chain** state machines, executors, sinks,
  observers and signer bindings where the existing SPI requires it.
- A mutable executor or sink instance is never implicitly shared across
  chains.
- All contexts see one deliberate typed shared-service registry. Registrations
  declare owner, interface and lifecycle; duplicate keys are rejected.
- `stop` and `close` run in reverse dependency order. Cleanup is idempotent.
- Threads and scheduled work use managed handles so health and shutdown can
  attribute them to the owning plugin.

ADR-011.1 defines lifecycle correctness for the existing `NodePlugin` SPI.
ADR-011.2 represents that SPI as a typed `node-plugin` contribution in a
manifested bundle and retains an explicit synthetic legacy adapter during the
migration window.

## 8. Configuration and secrets

The canonical bundle namespace is:

```
yano.plugins.bundle."<plugin-id>".*
```

The quoted SmallRye map key is an exact owner even when one reverse-DNS id
prefixes another or a bundle is removed. The former bracketed spelling remains
a migration alias. Environment variables use the reversible UTF-8-hex owner
form `YANO_PLUGINS_BUNDLE_HEX__<UTF8_HEX_ID>__<KEY>`; the former punctuation-
to-underscore owner form is rejected because it cannot distinguish removed or
colliding dot/hyphen ids. A manifested `NodePlugin` context receives an
owner-stripped, deeply immutable, bounded JSON-like view for its bundle and a
plugin-specific logger. The shared global map remains only as a legacy
compatibility view during migration.

Existing stable contribution paths remain valid through adapters, including:

- `yano.app-chain.effects.executors.<scheme>.*`
- app-chain machine, sink, sequencer, L1 observer and signer selections

Contribution-specific adapters continue to own credentials and KMS references.
The generic v1 domain, health and metrics contexts deliberately receive no raw
bundle configuration because no opaque secret-reference/capability contract is
yet defined for them. Secrets are never copied into manifests, catalog or
operations snapshots, effect records, health details or diagnostics.
Configuration validation errors redact both keys and values because either may
identify secret material.

## 9. Query and domain API model

### 9.1 Generic state-machine query

Yano wires a committed-context overload of the already-declared
`AppStateMachine.query(path, params)` through a chain-scoped route,
conceptually below the artifact API prefix (shown with the default):

```
POST /api/v1/app-chain/chains/{chainId}/query/{path}
```

The legacy two-argument hook remains available for source/binary linkage but is
not invoked or root-attested by the runtime. A runtime query supplies a
root-fixed committed reader and returns its captured height/root, enforces raw
transport and decoded size limits, uses one shared normalized path grammar,
counts queue time toward its deadline, and removes timed-out queued work before
it can invoke a plugin. Query execution is read-only and never part of
consensus; the returned payload is opaque and is not automatically a proof.

### 9.2 Domain API contributions

A manifest-only `DomainApiProvider` registers handlers and constrained route
metadata through a stable route registry under the artifact's API prefix
(shown below with the default):

```
/api/v1/plugins/{pluginId}/...
```

The prefix is an artifact build contract selected only with strict
`-PyanoApiPrefix=<path>` (default `/api/v1`), not launch-time plugin
configuration. The registry owns unique route identity, structural collision checks, raw
canonical-path validation with percent aliases rejected, request limits,
response media validation, authentication class (`READ`, `PRIVILEGED`, or
internal-only), metrics and shutdown. Privileged public routes fail closed
unless authentication is enabled and an unscoped full key exists. Per-bundle
admission plus a host-wide bound prevents one domain API from exhausting all
handler capacity. Plugins do not receive the Quarkus router directly.
Schema/OpenAPI contribution is deferred until a bounded, native-compatible
contract exists; opaque bytes are never advertised as JSON unless the host
validates them first.

Arbitrary JAX-RS/CDI discovery from a dropped JAR is rejected for v1 because
it makes route ownership and security difficult and does not translate to a
native image. A build-time adapter may allow approved native API
contributions to compile into the same route registry.

Cross-machine aggregation and materialized read models (for example a product
passport view spanning registry, document and approval state) are useful but
deferred until the basic query contract and consistency semantics are proven.

## 10. Observability, health and CLI

The immutable catalog exposes one inventory entry per bundle and contribution:

- id, version, artifact digest and source
- compatibility decision, dependencies and contribution kinds
- enabled/disabled reason and trust tier

A separate node-local operations snapshot joins to that catalog by fingerprint
and exposes bounded lifecycle, health, failure and custom metric state. Dynamic
state is never included in the catalog fingerprint. Runtime lifecycle is
currently observed for `node-plugin`, `domain-api`, `health` and `metrics`.
The other six app-chain factory contributions remain catalog-visible with
`lifecycleObserved=false`, `VALIDATED` and `UNKNOWN`; selection alone is not
reported as an active instance.

Health and metric contributions receive bounded typed registries, so they
cannot replace the node's server or publish unmanaged endpoints. Standard
plugin lifecycle and failure counters are supplied by the platform, and HTTP,
health, Micrometer and dashboard adapters read only host-cached snapshots.

The host-owned CLI inspects, validates and diagnoses catalogs without loading
provider code. Arbitrary plugin-defined commands remain deferred until naming,
authorization and native packaging rules are settled.

## 11. Packaging and compatibility

### JVM

A configured plugin directory/classpath is scanned at startup through one
lifecycle-owned parent-first loader. Directory artifacts are captured into
immutable runtime snapshots before catalog validation and activation. Plugins
compile against the published plugin/API surface and must not package Yano
runtime internals.

### Native image

Native deployments include selected bundles at build time. A build step
validates manifests, registers reflection/resources where needed and emits the
same runtime catalog. Dropping a new JAR next to an existing native binary is
not supported.

### Compatibility

The descriptor's inclusive `yanoApi.min`/`yanoApi.max` range governs breaking
plugin API-major compatibility, while required `yanoApi.minLevel` identifies
the minimum additive API surface. A host is compatible only when its current
major is within the range **and** its current global level is at least
`minLevel`. The level is globally monotonic and never resets on a major bump;
a high level cannot compensate for an unsupported major. Bundle artifact
SemVer and the Yano product release version are independent of both API
dimensions.

Yano has not shipped a stable plugin release yet and its preview databases and
catalog history are disposable. The complete current public plugin surface is
therefore the unreleased baseline **plugin API major 1, global level 1**; the
health and metrics kinds are part of that baseline, not a level migration.
Once this baseline is released, every additive public plugin API change must
advance the global level. Before level 2 is introduced, the project must add an
append-only level ledger that records each level, release, and newly introduced
public symbol or contribution kind. Published ledger entries are never edited
or renumbered.

Consensus plugin upgrades additionally use existing chain-coordination and
height/version-activation procedures; manifest compatibility alone does not
make a state-transition upgrade safe.

## 12. Effect-system integration

ADR-010's `AppEffectExecutorFactory` is the reference shape for a typed,
per-chain, configuration-scoped contribution:

- a bundle may contribute one or more executor schemes;
- duplicate schemes selected for one chain are rejected unless a future
  explicit composition policy exists;
- lack of an executor never invalidates or changes an emitted effect;
- executors close with their chain scope;
- alternate Kafka/gRPC/WS delivery, if needed, is a dispatcher/transport
  contribution around the source-agnostic executor SPI, not consensus logic.

Plugin inventory must not claim that a signed `~fx/result` proves external
execution. ADR-010's attestation trust model remains unchanged.

## 13. Security model

1. In-process plugins are fully trusted code with the node process's
   permissions. Manifest validation is not a sandbox.
2. Operators use allowlists/denylists and artifact provenance/digests to
   control installed code. Signed artifacts/manifests are a future hardening
   option.
3. Privileged APIs inherit centralized authentication and authorization;
   plugins cannot opt out.
4. Signer and executor plugins receive only the credentials/configuration
   required for their declared chain/type scope.
5. Consensus inventory exposes a comparable fingerprint (plugin id, version,
   digest and consensus config hash) for diagnosis. Automated cross-member
   negotiation is not assumed by this ADR.
6. A plugin failure or exception is sanitized before reaching public health
   and API responses.

## 14. Alternatives considered

- **Keep independent ServiceLoader scans only.** Simple, but cannot provide
  product identity, compatibility, dependencies, policy or coherent health.
- **Make every extension a `NodePlugin`.** Rejected: a broad lifecycle object
  does not express the contracts and determinism of state machines, signers or
  effect executors. `NodePlugin` may be adapted into a bundle, not become the
  only behavior interface.
- **Use a generic service registry for all behavior.** Rejected: string keys
  and runtime casts hide compatibility, lifecycle and trust boundaries.
- **Discover arbitrary CDI/JAX-RS resources from plugin JARs.** Rejected for
  v1 due to security, route conflicts and native-image constraints.
- **Support hot reload immediately.** Rejected: unloading Java code safely is
  incompatible with unmanaged threads, static state, class-loader leaks and
  consensus upgrade coordination.
- **Run all plugins out of process.** Deferred. External effect executors
  already cover the strongest secrets/side-effect isolation need; state
  machines and verifier SPIs require a different deterministic protocol.

## 15. Decisions

| ID | Decision |
|---|---|
| D1 | Add a manifested bundle/catalog layer; retain typed SPIs. |
| D2 | Validate policy, compatibility and dependency order before initialization. |
| D3 | Assign failure semantics by contribution trust tier. |
| D4 | Use restart-based lifecycle in v1; no hot reload. |
| D5 | Isolate bundle configuration and expose one intentional shared typed registry. |
| D6 | Wire generic machine queries before introducing constrained domain APIs. |
| D7 | Do not allow arbitrary runtime JAX-RS discovery. |
| D8 | Treat JVM runtime loading and native build-time inclusion as distinct packaging modes with one descriptor/catalog model. |
| D9 | Preserve effect consensus independence from installed executors and transports. |

## 16. Delivery roadmap

### ADR-011.1 — NodePlugin lifecycle correctness kernel (accepted and implemented)

- validate metadata, operator policy and the complete dependency graph before
  lifecycle callbacks;
- initialize/start transactionally and stop/close in deterministic reverse
  dependency order;
- provide the shared owner-aware service/filter registry and restart-safe
  contribution scopes;
- integrate plugin activation with runtime startup, shutdown and construction
  cleanup.

### ADR-011.2 — Manifested bundle catalog and compatibility (accepted and implemented)

- freeze manifest schema and plugin API compatibility rules;
- implement descriptor scan and synthetic legacy adapters across `NodePlugin`
  and the existing typed app-layer SPIs;
- enforce bundle-wide enable/allow/deny policy, API compatibility,
  duplicate/contribution-conflict checks and dependency validation before
  typed contribution activation;
- expose inventory/diagnostics and add JVM/native packaging tests;
- decide and test the class-loader model and deterministic catalog fingerprint.

### ADR-011.3 — Query and domain API extensions (accepted and implemented)

- wire the contextual `AppStateMachine.query()` overload to an authenticated,
  bounded chain route without invoking the legacy two-argument hook;
- define request/response encoding and committed-state consistency;
- specify `DomainApiProvider`, route registry, authorization classes and
  build-time native adapter;
- keep product-passport-style aggregation as a plugin/application concern;
  materialized cross-machine views remain deferred until concrete demand.

### ADR-011.4 — Operations and integration extensions (accepted and implemented)

Implementation and acceptance evidence are specified by
`011.4-plugin-operations-and-observability.md`:

- a node-local operations view separate from the catalog fingerprint;
- manifest-only, auxiliary health and metric snapshot providers;
- bounded cached REST, health, Micrometer, and static dashboard adapters;
- a host-owned catalog validation/inspection CLI that never loads providers;
- plugin-defined CLI contributions and alternative effect transports remain
  deferred until concrete demand.

## 17. Migration

1. Existing unmanifested typed ServiceLoader providers continue temporarily as
   synthetic legacy bundles, with a warning and restricted metadata, only for
   self-contained JVM directory JARs and explicit library-compatibility mode.
   Packaged JVM/native build-time inclusion requires a manifest.
2. First-party extensions add manifests and become catalog integration tests.
3. Duplicate provider/scheme/id behavior changes from discovery-order wins to
   explicit startup failure.
4. Operators adopt plugin allow/deny policy and compare catalog fingerprints
   before consensus-role upgrades.
5. Unmanifested provider support is deprecated only after at least one
   documented migration window; removal requires a separate compatibility
   decision.

## 18. Remaining questions for sub-ADRs

ADR-011.2 resolved the v1 loader, version-range, `NodePlugin` catalog role and
catalog-fingerprint questions: v1 uses one lifecycle-owned parent-first loader,
structured inclusive/exclusive SemVer bounds, a typed manifested `node-plugin`
contribution plus a migration adapter, and a canonical selected-catalog
fingerprint. ADR-011.3 resolves the root-fixed query envelope and constrained
domain-route boundary; proof-carrying payload conventions and bounded
schema/OpenAPI metadata remain deferred. The remaining cross-cutting question
is whether artifact provenance should require signed manifests, signed JARs or
an external deployment attestation.
