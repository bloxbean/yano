# Yano Runtime

The Yano Cardano node implementation: runtime assembly, the subsystem kernel, header/body sync, RocksDB
chain state, UTXO store, mempool, block production (devnet and slot leader), ledger-state integration,
the plugin manager and the Host API implementation. Embedders use it to run a node in their own JVM; the
Quarkus application (`app-core`, `app`) is a host built on top of it.

## Artifact

`org.yanoproject:yano-runtime`

The root `org.yanoproject:yano` starter POM depends on this artifact only, and it brings in `plugin-catalog`,
`consensus`, `p2p`, `ledger-rules` and `ledger-state` transitively.

## Key contents

| Package (`org.yanoproject.runtime.*`) | What it holds |
|---|---|
| `assembly` | Entry point: `YanoAssembly` (`relay`, `devnet`, `slotLeader`, `devnetTimeTravel`, `fromConfig`) builds a `Yano` handle exposing the `cardano-api` roles (`lifecycle()`, `chain()`, `ledger()`, `txGateway()`, ...). |
| `kernel` | `NodeKernel`, the ordered subsystem lifecycle owner. |
| `sync`, `sync.multipeer`, `sync.validation` | Upstream sync, multi-peer candidate tracking (uses `consensus`), header and body validation pipelines. |
| `chain` | Chain state (`DirectRocksDBChainState`, `InMemoryChainState`), rollback, recovery and snapshots. |
| `utxo`, `utxo.index` | UTXO processing and store, pruning, UTXO index contributors. |
| `mempool`, `tx`, `validation` | Ledger mempool, `TxSubsystem` and transaction admission, the `TransactionServicesFactory` contract that `tx-services` fills, validation-engine admission and shadow sync. |
| `blockproducer`, `producer`, `devnet` | Devnet and slot-leader block production, nonce state, faucet, snapshots and time travel. |
| `ledger`, `account`, `genesis`, `wallet` | Ledger-state subsystem, genesis parsing, optional wallet indexes. |
| `plugins`, `host` | `PluginManager`, catalog building, plugin context, and `DefaultHostApi` and the other `host-api` registries. |

Resources: bundled genesis files for `mainnet`, `preprod`, `preview`, `sanchonet` and `devnet` under
`genesis/`, the built-in `LoggingPlugin` (`META-INF/services` plus its plugin manifest) and native-image
configuration.

## Dependencies and boundaries

- `api` dependencies: `node-api`, `cardano-api`, `host-api`, `plugin-catalog`, `consensus`, `p2p`,
  `ledger-rules`, `ledger-state`, Yaci `core`/`helper` and Reactor. RocksDB is an implementation
  dependency.
- Optional services stay outside it: `tx-services` (Scalus/Aiken/julc validation and evaluation),
  `bootstrap-providers`, `devnet-toolkit` and the archive modules.
- The downstream application-ledger framework may use it for its integrated application and integration
  tests; Yano never depends on that framework
  ([ADR-064](../adr/)).

Guides: [Yano as a library](../www/src/content/docs/develop/library.md),
[Embed Yano in Java](../www/src/content/docs/develop/embed.md),
[events and plugins](docs/events-and-plugins-guide.md),
[block producer testing](docs/block-producer-testing.md).

## Build and test

```bash
./gradlew :runtime:build -PskipSigning=true
./gradlew :runtime:test                         # core tests (with a generated test plugin index)
./gradlew :runtime:pluginCatalogSemanticsTest   # catalog tests that bring their own inputs (part of check)
./gradlew :runtime:test --tests '*LedgerMempoolBudgetTest' -PmempoolBenchmark=true
```

`benchmarkWalletIndexes`, `benchmarkWalletHistory` and `verifyWalletHistory` are explicit-only benchmark
and verification tasks; see `build.gradle` for their properties.

## Notes

- Logging: the runtime carries `slf4j-reload4j` and `reload4j` as implementation dependencies.
