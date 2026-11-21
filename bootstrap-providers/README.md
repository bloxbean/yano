# Yano Bootstrap Providers

The default `BootstrapDataProvider` implementations used when Yano bootstraps partial chain state from an
external source (lightweight relay mode) instead of replaying the full chain: block metadata and UTXOs are
fetched from Blockfrost or Koios.

## Artifact

`org.yanoproject:yano-bootstrap-providers`

## Key contents

Package `org.yanoproject.bootstrap.providers`:

| Type | Purpose |
|---|---|
| `DefaultBootstrapDataProviderFactory` | `create(YanoConfig)` picks the provider from the bootstrap configuration (`koios`, otherwise Blockfrost) and the network; returns empty when it cannot be configured. |
| `BlockfrostBootstrapProvider` | Blockfrost API client. Requires a project API key. |
| `KoiosBootstrapProvider` | Koios API client. No API key; rate-limited. `forNetwork(network)` or a custom base URL. |

## Dependencies and boundaries

- Implements `org.yanoproject.api.bootstrap.BootstrapDataProvider` from
  [`cardano-api`](../cardano-api/README.md) (its only Yano dependency); uses Jackson for JSON.
- Used by `app-core`; `runtime` consumes the contract only and does not depend on this module.

Design: [ADR-025 bootstrap partial state](../adr/025-bootstrap-partial-state-ledger-and-nonce-boundaries.md).

## Build and test

```bash
./gradlew :bootstrap-providers:build -PskipSigning=true
./gradlew :bootstrap-providers:test
# live-API tests (src/integrationTest): Koios preprod, and Blockfrost when BF_PROJECT_ID is set
BF_PROJECT_ID=<project id> ./gradlew :bootstrap-providers:integrationTest
```
