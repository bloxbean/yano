# App-chain state-machine plugin template

A standalone Gradle project for a **custom app-chain state machine** loaded by a
default Yano distribution as a plugin jar — no fork, no rebuild of the node.

## What's here

```
plugin-template/
├── build.gradle                       # compileOnly against yano-core-api
├── settings.gradle
├── src/main/java/com/example/appchain/
    ├── CounterStateMachine.java       # your deterministic apply() logic
    └── CounterStateMachineProvider.java
└── src/main/resources/META-INF/
    ├── services/
    │   └── com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider
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
through Yano's production directory catalog and constructs the `counter`
provider. The plugin project version is this bundle's independently released
SemVer and must match the manifest `version`; it is not the Yano dependency
version selected by `-PyanoVersion`.

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
code is activated.

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

## Testing

- **Unit**: call `apply()` with an in-memory `AppStateWriter`
  (`CounterStateMachineTest`) — fastest inner loop.
- **End-to-end**: use `appchain-testkit`'s `@AppChainCluster` to run your
  machine across a real embedded multi-node cluster.

See `docs/APP_CHAIN_TUTORIAL.md` for the full walkthrough.
