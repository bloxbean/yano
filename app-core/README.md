# Yano Application Core

The Yano Quarkus REST application as a publishable library: REST resources, CDI producers, configuration
defaults, native-image configuration and the embedded console assets. The unpublished [`app`](../app/README.md)
project packages it as Yano's executable (uber-jar, native binary, distributions); downstream Quarkus
applications depend on it the same way and inherit its configuration and API prefix.

## Artifact

`org.yanoproject:yano-app-core`

## Key contents

| Package | What it holds |
|---|---|
| `org.yanoproject.app` | `YanoProducer` (maps application properties to a role-specific `Yano` from `YanoAssembly`), `YanoResource`, `YanoHealthCheck`, `PluginClassLoaderProducer`, `ApiPrefixContract`, the secret-safe `PluginStartupException` and `PluginConfigurationException`. |
| `org.yanoproject.app.api.*` | JAX-RS resources and DTOs: accounts, addresses, blocks, devnet, epochs, events, genesis, governance, history, network, nonce, plugin, scripts, status, tx, utxos, validation, wallet. |
| `org.yanoproject.app.spi` | Extension points for downstream applications: `RuntimeGlobalsContributor` (adds runtime globals), `RequiresCardano` (marks routes that need a Cardano runtime), `ApiKeys` (API-key parsing shared by REST filters). |
| `org.yanoproject.app.archive`, `.bootstrap` | History-archive services and bootstrap configuration parsing. |

Resources: `application.yml` (defaults plus the `devnet`, `devnet-slotleader`, `mainnet`, `preview`,
`sanchonet`, `native` and `test` profiles), `banner.txt`, native-image configuration under
`META-INF/native-image/org.yanoproject/yano-app`, a Jandex index, the console built by
[`console-ui`](../console-ui/README.md) under `META-INF/resources/ui`, and the generated API-prefix contract
(`META-INF/yano-api-prefix-v1` plus the matching Quarkus properties and console prefix documents).

## Dependencies and boundaries

- `api`: the Quarkus platform BOM (`platform`, not `enforcedPlatform`, so it can be published), Quarkus
  RESTEasy/Jackson, ArC, SmallRye Health/OpenAPI, Micrometer Prometheus, and
  [`cardano-api`](../cardano-api/README.md) and [`runtime`](../runtime/README.md).
- Implementation: `devnet-toolkit`, [`bootstrap-providers`](../bootstrap-providers/README.md),
  [`tx-services`](../tx-services/README.md), `archive-api`, `archive-core` (`archive-store-ducklake` at
  runtime). The console assets are copied into the jar; the publication has no dependency on `console-ui`.
- The downstream application-ledger framework may use it for its integrated Quarkus application; Yano never
  depends on that framework ([ADR-064](../adr/) D8).

## Build and test

```bash
./gradlew :app-core:build -PskipSigning=true
./gradlew :app-core:test
./gradlew :app-core:build -PyanoApiPrefix=/my/api -PskipSigning=true   # bake another API prefix (default /api/v1)
# downstream-consumer gate for org.yanoproject.app.spi (builds app/consumer-check on staged publications):
./gradlew :app:appCoreConsumerCheck -PstagingRepository=file:///abs/staging -PskipSigning=true
```

## Notes

- The REST path is fixed at build time. The build fails if `yano.api-prefix`, `YANO_API_PREFIX`,
  `quarkus.resteasy.path` or `quarkus.http.root-path` is set as a system property or environment variable;
  use `-PyanoApiPrefix` instead. At runtime a different `yano.api-prefix` is rejected before node startup.
- Most REST behaviour is tested in the `app` project (`./gradlew :app:test`).
