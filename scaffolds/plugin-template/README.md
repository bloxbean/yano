# App-chain state-machine, domain API, and operations plugin template

A standalone Gradle project for a **custom app-chain state machine, committed
query, bundle-owned domain API, health source, and bounded custom metrics**
loaded by a default Yano distribution as a plugin jar — no fork, no rebuild of
the node.

## What's here

```
plugin-template/
├── build.gradle                       # compileOnly against yano-core-api
├── settings.gradle
├── src/main/java/com/example/appchain/
    ├── CounterStateMachine.java       # deterministic apply + committed query
    ├── CounterStateMachineProvider.java
    ├── CounterDomainApiProvider.java  # constrained routes + query facade
    ├── CounterHealthProvider.java     # fixed health schema + cached samples
    ├── CounterMetricsProvider.java    # fixed, label-free metric schema
    └── JsonSupport.java               # safe JSON string encoding
└── src/main/resources/META-INF/
    ├── services/
    │   ├── com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider
    │   ├── com.bloxbean.cardano.yano.api.plugin.domain.DomainApiProvider
    │   ├── com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthProvider
    │   └── com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricsProvider
    └── yano/plugins/
        └── com.example.appchain.counter.json
```

## Build

```bash
# publish yano artifacts locally first (from the yano repo root), if needed:
#   ./gradlew publishToMavenLocal
cd scaffolds/plugin-template
gradle build          # -> build/libs/appchain-plugin-0.1.0.jar
```

The template defaults to Yano `0.1.0-pre9`. To test another published or
locally published version, use `gradle build -PyanoVersion=<version>`. The
plugin compiles against `yano-core-api`; `yano-runtime` is test-only because
it supplies `StateMachineConformance`. The `check` lifecycle also runs
`verifyPluginJar`, which rejects missing discovery metadata or bundled Yano
API/runtime classes, and `verifyPluginCatalogLaunch`, which loads the built JAR
through Yano's production directory catalog and constructs all four providers.
The plugin project version is this bundle's independently released SemVer and
must match the manifest `version`; it is not the Yano dependency version
selected by `-PyanoVersion`.

The schema-v1 manifest's `yanoApi` object is a separate host-compatibility
contract. `min`/`max` are inclusive plugin API majors and the required positive
`minLevel` is the oldest global additive API level whose public symbols this
plugin uses. The complete, unreleased preview surface is the major 1 / level 1
baseline. After that baseline ships, Yano increments the global level for each
additive public plugin API symbol or contribution kind and never resets it on a
major bump. Before level 2, the repository will add the append-only level
ledger that maps each level to its first release and exact additions. Keep
`minLevel` at the oldest level that actually supplies the APIs you need; do not
copy the plugin's artifact version into it.

Yano repository contributors can verify the scaffold directly against the
current checkout, without publishing it first:

```bash
./gradlew -p scaffolds/plugin-template clean build -PyanoSource="$PWD"
```

## Deploy

1. Copy the jar into the node's plugins directory (e.g. `./plugins/`).
2. Point the node at it, allow the manifest bundle id when an allow-list is
   configured, and select the machine:

```yaml
yaci:
  plugins:
    directory: ./plugins
    # allow-list: com.example.appchain.counter
yano:
  app-chain:
    enabled: true
    chain-id: my-chain
    state-machine: counter          # == CounterStateMachine.ID
    signing-key: <32-byte hex seed>
    proposer: <proposer pubkey hex>
    members: <pubkey hex>,<pubkey hex>,...
    threshold: 2
    peers: host-b:13337,host-c:13337
```

The qualified JSON manifest and the `META-INF/services` entry are both
required. The manifest declares the auditable bundle identity and provider;
ServiceLoader remains the behavior-instantiation contract. Their provider
kind/class declarations must match exactly or the node fails before plugin
code is activated. For schema v1, each `domain-api`, `health`, and `metrics`
contribution name and provider `id()` must equal the containing bundle id
`com.example.appchain.counter`; a bundle may declare at most one of each.
The node also checks that its API major is within the manifest range and its
global API level is at least `minLevel`, rejecting an incompatible bundle
before provider construction.

If the plugin uses third-party runtime dependencies, publish a reproducible
shaded JAR (without `com/bloxbean/cardano/yano/api/**`) so the artifact is a
self-contained deployment unit and its catalog digest covers all executable
plugin code. Thin plugin JARs are intentionally rejected when their provider
class cannot be correlated to the same artifact.

## The state-machine contract

- `validate(message)` — fast, side-effect-free admission.
- `apply(block, writer)` — **deterministic** transition over a finalized block.
  Same block + same prior state must yield byte-identical new state on every
  member (followers re-execute and compare state roots). No wall-clock, no
  randomness, no external I/O — use `block.timestamp()` for time.
- Every key you write becomes individually provable (MPF); clients verify
  proofs offline with the `appchain-client` SDK.

## Committed queries and domain routes

`CounterStateMachine.query(path, params, context)` runs off-consensus against a
root-fixed committed snapshot. It is read-only, may overlap later block apply,
must not retain `context`, and reports only `UNSUPPORTED` or `INVALID_REQUEST`
as plugin-authored `AppQueryException` codes. The host owns timeout, overload,
availability, result-size, and unexpected-failure codes.

`CounterDomainApiProvider` publishes an immutable route set validated with
`DomainApiRouteSet.validateAndOrder`:

- `GET status` and `GET counters/{key}` are `READ`;
- `GET operator` is `PRIVILEGED` and is never anonymously enabled;
- `GET internal` is reserved inventory and cannot be dispatched in v1.

The host owns `/api/v1/plugins/{bundleId}/...`, request limits, authentication,
timeouts, and error redaction. The plugin receives no JAX-RS router or request
identity. In production ADR-011.3 v1, `DomainApiContext.bundleConfig()` is
intentionally empty; do not expect credentials there. The example closes its
product idempotently, safely JSON-encodes caller-controlled strings, and maps
`AppQueryException.code()` to `DomainApiException.code()` without copying the
source message.

Example requests after the node starts:

```bash
# Generic root-attested query (paramsHex is UTF-8 "visits")
curl -X POST \
  http://localhost:8080/api/v1/app-chain/chains/my-chain/query/counter/read \
  -H 'Content-Type: application/json' \
  -d '{"paramsHex":"766973697473"}'

# Bundle-owned READ route; response carries the same committed root envelope
curl 'http://localhost:8080/api/v1/plugins/com.example.appchain.counter/counters/visits?chain=my-chain'
```

Enable API-key authentication and configure at least one unscoped full key
before using a `PRIVILEGED` route. Send it as `X-API-Key`. If authentication is
disabled, missing, or has only topic-scoped keys, privileged plugin routes are
hidden as 404 and are never invoked.

## Health and metrics

`CounterHealthProvider` and `CounterMetricsProvider` demonstrate the bounded
operations contracts. Each publishes its complete descriptor schema once;
samples must contain exactly those identities. Health reports carry only a
check id and status. Metrics are label-free in v1, use finite values, and keep
counters/timers generation-monotonic. Both construction contexts intentionally
contain an empty configuration map.

Yano samples these sources behind callback deadlines and serves HTTP health,
Prometheus metrics, and the plugin dashboard from its host-owned cache. An HTTP
or metrics scrape never invokes plugin code directly, and plugin health does
not alter node liveness, readiness, consensus, or the catalog fingerprint.

## Testing

- **Unit**: call `apply()` and `query()` with an in-memory state view
  (`CounterStateMachineTest`), then exercise routes with a fake bounded
  `DomainQueryService` (`CounterDomainApiTest`) — fastest inner loop.
- **Catalog/deployment**: `check` verifies all four ServiceLoader descriptors,
  all four manifest contributions, API major/level compatibility, API-class
  isolation, and resolves every provider from the built JAR through the
  production directory catalog.
- **End-to-end**: use `appchain-testkit`'s `@AppChainCluster` to run your
  machine across a real embedded multi-node cluster.

See `docs/APP_CHAIN_PLUGIN_QUERY_AND_DOMAIN_API.md` for the full query/domain
contract and `docs/APP_CHAIN_TUTORIAL.md` for the app-chain walkthrough.
