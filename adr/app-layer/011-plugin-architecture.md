# ADR-011: Yano App-Layer Plugin Architecture — Bundles, Lifecycle, and Domain APIs

## Status

Proposed — requirements exploration and roadmap

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

Yano already lets applications contribute state machines, effect executors,
stream sinks, sequencer modes, L1 observers and signers. Those extension
points are useful, but Yano discovers each one independently. It cannot yet
answer basic platform questions such as: Which product plugin is installed?
Which compatible version is active? Which contributions belong to it? In what
order should dependencies start? Which configuration and health status belong
to it? How may it expose a safe domain query or API?

This ADR adds that missing control plane. A **plugin bundle** has one manifest
and may contain one or more existing typed contributions. A catalog validates
identity, compatibility, dependencies and policy before any contribution is
initialized. Typed SPIs remain the data plane; the bundle does not replace
them with a generic service locator.

The first version is restart-based and trusted in-process code. It does not
promise sandboxing, hot reload, or arbitrary runtime JAX-RS discovery.

## 1. Context and current baseline

The app-chain implementation already discovers six `ServiceLoader` SPIs:

| Typed contribution | Role | Trust / determinism |
|---|---|---|
| `AppStateMachineProvider` | creates a chain's deterministic application transition | consensus-critical |
| `SequencerModeProvider` | supplies message ordering/proposer behavior | consensus/liveness-critical |
| `L1ObserverProvider` | verifies externally-derived L1 facts | consensus-critical |
| `SignerProviderFactory` | supplies signing capability and key custody | privileged local |
| `AppEffectExecutorFactory` | performs effects outside deterministic `apply()` | privileged local, side-effecting |
| `FinalizedStreamSinkFactory` | exports finalized blocks | auxiliary local integration |

The generic node runtime also has `NodePlugin` with discovery, lifecycle,
event access, storage filters and dependency declarations. This is a valuable
starting point, but it is not a coherent app-layer bundle system.

Verified baseline gaps at the time of this vision ADR (the first three runtime
lifecycle items are addressed by ADR-011.1; the manifested bundle/catalog gaps
remain):

1. The six typed SPIs are scanned independently. There is no shared bundle
   identity, artifact version, compatibility range, contribution inventory or
   dependency graph.
2. `PluginManager` calls `NodePlugin.init()` during discovery and only sorts
   dependencies afterwards. Missing dependencies and cycles are logged rather
   than rejected, so declared ordering does not govern initialization.
3. Each current `PluginContextImpl` owns a private service registry. A service
   registered through one context cannot be found through another, despite
   the API describing inter-plugin communication.
4. There is no app-layer enable/allow/deny policy enforced before typed SPI
   activation, and configuration is not consistently isolated by plugin id.
5. Duplicate ids, contribution conflicts and Yano API incompatibility have no
   uniform fail-fast rule.
6. `AppStateMachine.query(path, params)` exists in `core-api` but is not wired
   to the REST surface. A plugin cannot expose a product-passport or other
   domain view through a stable app-layer contract.
7. The Quarkus REST module has no plugin API contribution contract. Dynamic
   dropped-JAR REST discovery also conflicts with native-image closed-world
   assumptions and makes authentication/routing ownership unclear.
8. Plugins have no uniform inventory, health, metrics or CLI contribution
   model.

ADR-011.1 retains the existing `NodePlugin` SPI and closes its immediate
validation, dependency ordering, shared-registry, policy and lifecycle gaps. It
does not implement this ADR's manifest, bundle identity, typed-SPI correlation,
compatibility, inventory, domain API or isolation roadmap.

## 2. Decision summary

Yano will introduce a **manifested plugin bundle and catalog** above its typed
SPIs.

- A bundle has a stable id, version, Yano API compatibility range,
  dependencies, configuration namespace and declared contribution kinds.
- Existing typed SPIs remain the only way to contribute consensus and runtime
  behavior. The manifest describes and governs them; it does not instantiate
  arbitrary classes by name.
- Discovery, policy filtering, compatibility checks and dependency sorting
  finish **before** any plugin initialization.
- Contributions are assigned an explicit trust tier and failure policy.
- Lifecycle is restart-based in v1. Runtime hot install/uninstall/reload is not
  supported.
- App-layer queries first gain a generic, authenticated route to
  `AppStateMachine.query()`. Domain API contributions use a constrained route
  registry, not arbitrary runtime JAX-RS resource discovery.
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

No plugin code receives a context until catalog construction, policy
filtering, compatibility checks, duplicate detection and dependency sorting
have succeeded.

### P5 — Restart is the lifecycle boundary

Install, remove, enable, disable and upgrade take effect at process restart in
v1. This makes class loading, thread cleanup, native packaging and consensus
coordination tractable.

## 5. Bundle descriptor and discovery

A JVM bundle contains `META-INF/yano-plugin.json`. Conceptual schema:

```json
{
  "schemaVersion": 1,
  "id": "com.example.product-passport",
  "version": "1.2.0",
  "yanoApiRange": ">=0.8 <0.10",
  "requires": [
    {"id": "com.example.shared-domain", "versionRange": "^2.0"}
  ],
  "contributions": [
    "app-state-machine",
    "effect-executor",
    "domain-api",
    "health"
  ],
  "configPrefix": "yano.plugins.com.example.product-passport",
  "nativeMode": "build-time"
}
```

The descriptor is declarative. Standard `META-INF/services/*` entries continue
to name implementations of the typed SPIs. The catalog correlates discovered
providers with their containing bundle and verifies that actual and declared
contribution kinds agree.

Catalog construction order:

1. Scan descriptors and legacy typed providers without invoking plugin code.
2. Apply configured enablement plus allow/deny policy.
3. Reject duplicate ids, malformed descriptors and unsupported API ranges.
4. Resolve required dependencies and version ranges.
5. Topologically sort bundles; a missing dependency or cycle is fatal for any
   selected required/consensus plugin.
6. Discover and validate typed contributions.
7. Construct scoped contexts, initialize in dependency order, then start only
   after all required initialization succeeds.

An artifact digest is calculated by the runtime/build rather than trusted from
the manifest. Inventory surfaces report the digest with id and version for
deployment comparison.

## 6. Trust tiers and failure policy

| Tier | Contributions | Startup / runtime policy |
|---|---|---|
| **CONSENSUS** | state machines, sequencer modes, L1 observers and validation customizers | selected contribution missing, duplicate, incompatible or failed → chain/node role does not start; runtime failure is fatal to that chain role |
| **PRIVILEGED_LOCAL** | signers, effect executors, domain/admin APIs | if explicitly selected for the role, configuration/init failure is startup-fatal; runtime failure parks/degrades affected work and alerts; APIs require authorization |
| **AUXILIARY_LOCAL** | finalized sinks, event consumers, health/metrics exporters | optional failure is isolated; plugin becomes DEGRADED/FAILED and retry policy is contribution-specific |

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

The detailed lifecycle API and whether `NodePlugin` is adapted or superseded
are deferred to ADR-011.1. The current bug where initialization precedes
dependency ordering must be removed regardless of that API choice.

## 8. Configuration and secrets

The canonical bundle namespace is:

```
yano.plugins.<plugin-id>.*
```

Each context receives an immutable, prefix-stripped view for its bundle, a
plugin-specific logger and explicit secret references. It does not receive the
entire global configuration map by default.

Existing stable contribution paths remain valid through adapters, including:

- `yano.app-chain.effects.executors.<scheme>.*`
- app-chain machine, sink, sequencer, L1 observer and signer selections

Secrets are referenced from configuration/KMS providers and never copied into
the manifest, catalog status, effect records or health details. Configuration
validation errors name the key but redact the value when it may be secret.

## 9. Query and domain API model

### 9.1 Generic state-machine query

Yano will first wire the already-declared `AppStateMachine.query(path, params)`
through a chain-scoped route, conceptually:

```
POST /api/v1/app-chain/chains/{chainId}/query/{path}
```

The route supplies canonical request bytes/parameters, enforces size and time
limits, authenticates according to app-chain API policy and returns the
machine's encoded response. Query execution is read-only and never part of
consensus; implementations must document whether they read only committed
state and how responses can be verified.

### 9.2 Domain API contributions

A later `DomainApiProvider` registers handlers and schemas through a stable
route registry under:

```
/api/v1/plugins/{pluginId}/...
```

The registry owns collision checks, request limits, serialization, OpenAPI
metadata, authentication class (`READ`, `PRIVILEGED`, or internal-only),
metrics and shutdown. Plugins do not receive the Quarkus router directly.

Arbitrary JAX-RS/CDI discovery from a dropped JAR is rejected for v1 because
it makes route ownership and security difficult and does not translate to a
native image. A build-time adapter may allow approved native API
contributions to compile into the same route registry.

Cross-machine aggregation and materialized read models (for example a product
passport view spanning registry, document and approval state) are useful but
deferred until the basic query contract and consistency semantics are proven.

## 10. Observability, health and CLI

The platform exposes one inventory entry per bundle and contribution:

- id, version, artifact digest and source
- compatibility decision, dependencies and contribution kinds
- enabled/disabled reason and trust tier
- lifecycle state, last failure and health summary
- per-chain instances and relevant configuration namespace (never values of
  secrets)

Health and metric contributions receive bounded typed registries, so they
cannot replace the node's server or publish unmanaged endpoints. Standard
plugin lifecycle and failure counters are supplied by the platform.

CLI extension is useful for domain operations but is not a v1 requirement.
Initial CLI commands inspect, validate and diagnose the catalog; arbitrary
plugin-defined commands follow only after naming, authorization and native
packaging rules are settled.

## 11. Packaging and compatibility

### JVM

A configured plugin directory/classpath is scanned at startup. The exact
class-loader model (shared, child-first with API parent, or per-bundle layers)
is deferred to ADR-011.1 because dependency isolation, logging libraries and
ServiceLoader behavior require focused testing. Plugins compile against a
published plugin/API surface and must not package Yano runtime internals.

### Native image

Native deployments include selected bundles at build time. A build step
validates manifests, registers reflection/resources where needed and emits the
same runtime catalog. Dropping a new JAR next to an existing native binary is
not supported.

### Compatibility

The descriptor's `yanoApiRange` governs the stable plugin API, not Yano's full
implementation version. Consensus plugin upgrades additionally use existing
chain-coordination/version-activation procedures; manifest compatibility alone
does not make a state-transition upgrade safe.

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

### ADR-011.1 — Bundle catalog and lifecycle foundation

- freeze manifest schema and plugin API compatibility rules;
- implement descriptor scan, legacy adapters, enable/allow/deny policy,
  duplicate/conflict checks and dependency graph validation;
- order before initialization, provide one scoped shared registry and reverse
  shutdown;
- expose inventory/diagnostics and add JVM/native packaging tests;
- decide and test the class-loader model.

### ADR-011.2 — Query and domain API extensions

- wire `AppStateMachine.query()` to an authenticated, bounded chain route;
- define request/response encoding and committed-state consistency;
- specify `DomainApiProvider`, route registry, authorization classes and
  build-time native adapter;
- add product-passport-style aggregation only after query semantics settle.

### ADR-011.3 — Operations and integration extensions

- standard health and metrics contribution APIs;
- catalog validation/inspection CLI;
- evaluate CLI contributions and alternative effect transports using concrete
  demand rather than speculative generic interfaces.

## 17. Migration

1. Existing typed ServiceLoader providers continue to work as synthetic
   legacy bundles with a warning and restricted metadata.
2. First-party extensions add manifests and become catalog integration tests.
3. Duplicate provider/scheme/id behavior changes from discovery-order wins to
   explicit startup failure.
4. Operators adopt plugin allow/deny policy and compare catalog fingerprints
   before consensus-role upgrades.
5. Unmanifested provider support is deprecated only after at least one
   documented migration window; removal requires a separate compatibility
   decision.

## 18. Open questions for sub-ADRs

1. Which per-bundle class-loader model gives useful dependency isolation
   without duplicating Yano API classes?
2. Which version-range grammar/library is stable enough for manifests?
3. What canonical query request/response encoding and proof metadata should
   the generic route use?
4. Should artifact provenance require signed manifests, signed JARs, or an
   external deployment attestation?
5. Does `NodePlugin` become a typed auxiliary contribution, an adapter, or a
   deprecated parallel mechanism?
6. Which consensus plugin/config fields belong in a member-comparison
   fingerprint, and should startup optionally require an operator-provided
   expected fingerprint?
