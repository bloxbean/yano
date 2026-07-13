# App-chain state-machine plugin template

A standalone Gradle project for a **custom app-chain state machine** loaded by a
default Yano distribution as a plugin jar — no fork, no rebuild of the node.

## What's here

```
plugin-template/
├── build.gradle                       # compileOnly against yano-node-api
├── settings.gradle
└── src/main/java/com/example/appchain/
    ├── CounterStateMachine.java       # your deterministic apply() logic
    └── CounterStateMachineProvider.java
└── src/main/resources/META-INF/services/
    └── com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider
```

## Build

```bash
# publish yano artifacts locally first (from the yano repo root), if needed:
#   ./gradlew publishToMavenLocal
cd scaffolds/plugin-template
gradle build          # -> build/libs/appchain-plugin-0.1.0.jar
```

## Deploy

1. Copy the jar into the node's plugins directory (e.g. `./plugins/`).
2. Point the node at it and select the machine:

```yaml
yano:
  plugins:
    directory: ./plugins
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
