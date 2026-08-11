# Yano app-chain core modules

Yano owns the generic, GraalVM-compatible app-chain host framework and ships
`ordered-log` as its one out-of-box state machine. All other stock state
machines, capabilities, connectors, products, examples, and JVM-only tools are
built in [Yano X](https://github.com/bloxbean/yano-x).

The Java namespace remains stable across both repositories:
`com.bloxbean.cardano.yano.appchain.*`.

## Retained modules

| Gradle project | Path | Purpose |
|---|---|---|
| `appchain-config` | `appchain-config/` | Framework-neutral app-chain configuration contracts. |
| `appchain-testkit` | `appchain-testkit/` | Embedded multi-node JUnit test support and shared conformance contracts. |
| `appchain-proof-verifier` | `appchain-proof-verifier/` | Release-matched portable proof verification. |
| `appchain-anchor-onchain` | `onchain/appchain-anchor-onchain/` | Default Plutus V3 script-anchor validators and checked-in artifacts. |
| `appchain-plugin-conformance` | `fixtures/appchain-plugin-conformance/` | Host/plugin compatibility fixtures used across the repository boundary. |

The runtime implementation, app-chain SPIs, `ordered-log`, REST host, and
GraalVM integration live in the top-level `runtime`, `core-api`, and `app`
modules.

## Build

```bash
./gradlew :appchain-config:test :appchain-testkit:test
./gradlew :appchain-proof-verifier:test :appchain-anchor-onchain:test
./gradlew :app:quarkusBuild
```

See the [core app-chain documentation](../docs/appchain/README.md),
[consensus guide](../docs/APP_CHAIN_CONSENSUS_GUIDE.md), and
[plugin contract](../docs/APP_CHAIN_PLUGIN_QUERY_AND_DOMAIN_API.md).

## Extension development

Yano publishes its libraries and its normal JVM distribution ZIP to the local
Maven repository/build directory during split development. Yano X consumes
those same outputs; no special lean ZIP format is introduced.

Custom extensions must use the plugin SPI and `yano.plugins.directory`.
Do not add extension-specific dependencies or source paths to the Yano host.
