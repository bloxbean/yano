# Yano Host API

Versioned infrastructure contracts for extensions that are released independently of Yano. A plugin obtains
these capabilities through the existing `PluginContext.getService` mechanism; the module adds no extension
loader and exposes no node implementation. Lifecycle, configuration, events, scheduling, health and metrics
keep their existing SPIs in [`node-api`](../node-api/README.md).

## Artifact

`org.yanoproject:yano-host-api`

Published at the Yano project version, like every Yano module. Compatibility is the contract version
`HostApiVersion.CURRENT` (currently 1.2), which plugins check with `requireCompatible`, not the artifact
version.

## Key contents

All types are in `org.yanoproject.api.host`:

| Type | Purpose |
|---|---|
| `HostApi` | Host capability: node configuration, runtime options, network label and per-bundle configuration (Host API 1.2). |
| `HostApiVersion` | Major/level of the contract; `requireCompatible(major, minimumLevel)`. |
| `HostLifecycleRegistry` | Registers host-owned services and an owner's assembly step in the kernel's pre-publication stage. |
| `N2NExtensionRegistry` | Registers node-to-node session extensions (inbound IDs, outbound factory decoration) and supplies the app-only network magic of a host without Cardano chainstate. |
| `PluginServiceLookup` | Read access to services registered by the currently initialized plugin generation. |

The implementations live in `runtime` (`org.yanoproject.runtime.host`).

## Dependencies and boundaries

- Depends only on [`node-api`](../node-api/README.md).
- `runtime` implements it; `bom` aligns its version.
- It is one of the Yano artifacts the downstream application-ledger framework may depend on; Yano never
  depends on that framework ([ADR-064](../adr/)).
- Design: [ADR-063](../adr/063-standalone-application-ledger-and-host-api.md).

## Build and test

```bash
./gradlew :host-api:build -PskipSigning=true
./gradlew :host-api:test
```
