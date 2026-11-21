# Yano Node API

Generic node and plugin foundation contracts: node configuration, the plugin SPI, plugin operations
(health, metrics, lifecycle) and the small kernel contracts that subsystems and hosts share. It carries no
Cardano SDK types and no host implementation, so plugins and frameworks that do not need Cardano chain or
ledger access can compile against it alone.

## Artifact

`org.yanoproject:yano-node-api`

## Key contents

| Package | What it holds |
|---|---|
| `org.yanoproject.api.config` | `YanoConfig`, `NodeConfig`, `RuntimeOptions`, `RuntimeMode` (`CARDANO`, `APP_LEDGER`), `PluginsOptions`, the `Upstream*Config` records and `YanoPropertyKeys`, the framework-neutral property names every adapter should use. |
| `org.yanoproject.api.plugin` | The plugin SPI: `NodePlugin`, `PluginContext`, `PluginCapability`, `NodePolicy`, `Notifier`, `StorageAdapter`, `StorageFilter`, catalog views (`PluginCatalogView`, `PluginBundleInfo`) and `PluginApiVersion` (`CURRENT_MAJOR`, `CURRENT_LEVEL`). |
| `org.yanoproject.api.plugin.spi` | Contribution-kind plumbing shared by the host and downstream facades: `ContributionKindBinding`, `PluginCallbackScope`, `PluginProducts`, `FacadeSupport`. |
| `org.yanoproject.api.plugin.operations` | Plugin health, metrics and operation-recording contracts (`PluginHealthProvider`, `PluginMetricsProvider`, `PluginOperationsView`, ...). |
| `org.yanoproject.runtime.kernel` | `Subsystem`, `SubsystemContext`, `SubsystemHealth`, `Schedulers`, `ServiceRegistry`. |
| `org.yanoproject.runtime.plugins`, `.chain`, `.util` | `PluginProviderRegistry`, `BlockBodyRetentionRegistry`, `LifecycleFailures`. |
| `org.yanoproject.p2p.peer` | `PeerClientFactory`, `PeerEndpoint`. |
| `org.yanoproject.api.util` | `StrictEd25519` (Ed25519 verification with cardano-node semantics). |

Some contracts live in implementation-sounding `org.yanoproject.runtime.*` and `org.yanoproject.p2p.*`
packages; they keep those binary names (ADR-064) and are still part of this API artifact.

## Dependencies and boundaries

- Depends on Yaci `core`, `helper` (both with `cardano-client-core` excluded) and `events-core`, plus
  `cardano-client-crypto`. No sibling Yano module.
- Most Yano libraries build on it, directly (`cardano-api`, `host-api`, `runtime`) or through `cardano-api`.
- It is one of the Yano artifacts the downstream application-ledger framework may depend on; Yano itself
  never depends on that framework
  ([ADR-064](../adr/)).
- Cardano chain, ledger and query contracts belong in [`cardano-api`](../cardano-api/README.md), not here.

See also the [events and plugins guide](../runtime/docs/events-and-plugins-guide.md) and
[plugin operations](../docs/PLUGIN_OPERATIONS.md).

## Build and test

```bash
./gradlew :node-api:build -PskipSigning=true
./gradlew :node-api:test
```
