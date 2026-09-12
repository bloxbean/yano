# Capability guide

See which Yano features fit your runtime and workflow.

Canonical URL: https://getyano.dev/start/capabilities/

Yano is modular. Select capabilities for the job, and check the configuration and data prerequisites before relying on a query.

| Capability | Where it fits | What to know |
| --- | --- | --- |
| Cardano synchronization and REST queries | JVM or native node | Current local state depends on sync progress and enabled subsystems |
| Local block production | Devnet | Uses local genesis and test funds |
| Slot-leader devnet recipe | Advanced development | The bundled `devnet-slotleader` profile enables slot-leader mode; it is not a promise of production pool operation |
| Faucet, snapshots, rollback, time travel | Devnet controls | Isolate mutations to test-owned environments |
| Java / JUnit testkit | Embedded JVM | Managed lifecycle and real RocksDB storage |
| JavaScript / TypeScript testkit | Native child process | Matching platform binary required |
| CCL backend adapter | Java testkit | Selected backend services, not the whole CCL backend API |
| Wallet first-seen and scan indexes | Optional node capability | Fresh sync and continuous coverage; scans require retained bodies |
| DuckLake history projection | JVM | Fresh-sync archive; no native support |
| `ordered-log` app chain | JVM or native host | Opaque events; does not enforce application business rules |
| Multiple app chains, proofs, and L1 anchoring | App-chain host | Configure identities, membership, thresholds, and anchor requirements |
| Certified observations | Optional preview | Disabled by default; explicit committed profile and trust policy |
| Dynamic plugin JARs and Yano X extensions | JVM | Matching plugin contracts and explicit installation |
| Console, health, and metrics | Runnable application | Optional persistent metrics companion requires Docker Compose |
| Embedding and event/plugin SPI | Java libraries | Use public role and extension interfaces |

## Configuration is part of the feature

The `wallet` and `projection` profiles serve different purposes. The former enables optional discovery/scan indexes; the latter enables historical projection. A plain default node is not an archive, and a filtered UTxO database is not complete wallet history.

Native builds preserve core providers but cannot dynamically load JVM plugins. Use the [configuration guide](/reference/configuration/) and [generated catalog](/reference/configuration-catalog/) to inspect exact source/profile values.
