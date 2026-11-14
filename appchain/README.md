# Yano app-chain host modules

Yano hosts the application-ledger framework through its stable Host API and
preserves the integrated Cardano deployment. The framework, `ordered-log`,
optional Cardano integration, and stock extensions are owned by
[Yano X](https://github.com/bloxbean/yano-x/tree/main/framework).

Existing public Java packages remain unchanged across the repository split,
including `org.yanoproject.api.appchain`, `org.yanoproject.runtime.appchain`,
and `org.yanoproject.appchain`.

## Retained modules

| Gradle project | Path | Purpose |
|---|---|---|
| `appchain-config` | `appchain-config/` | Compatibility coordinate for the configuration contracts owned by Yano-X. |
| `appchain-testkit` | `appchain-testkit/` | Embedded multi-node JUnit test support and shared conformance contracts. |
| `appchain-proof-verifier` | `appchain-proof-verifier/` | Release-matched portable proof verification. |
| `appchain-anchor-onchain` | `onchain/appchain-anchor-onchain/` | Compatibility coordinate for the checked-in validators and artifacts owned by Yano-X. |
| `appchain-plugin-conformance` | `fixtures/appchain-plugin-conformance/` | Host/plugin compatibility fixtures used across the repository boundary. |

The framework runtime, application-ledger SPIs, and `ordered-log` live in
Yano-X `framework/core` and `framework/api`; Cardano integration lives in its
optional `framework/cardano` and `framework/cardano-api` modules. Yano retains
the Host API in `host-api`, node assembly in `runtime`, and REST/native assembly
in `app`.

## Build

```bash
./gradlew :appchain-testkit:test :appchain-proof-verifier:test
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
