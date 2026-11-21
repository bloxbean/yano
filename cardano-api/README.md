# Yano Cardano API

Cardano node contracts: the chain, ledger, transaction and query surfaces of a Yano node, Cardano events,
and the SPIs for UTXO indexes, epoch archives, wallet scans and bootstrap data. Embedders and plugins that
need Cardano data program against these interfaces; `runtime` implements them. Generic node and plugin
contracts stay in [`node-api`](../node-api/README.md).

## Artifact

`org.yanoproject:yano-cardano-api`

## Key contents

| Package | What it holds |
|---|---|
| `org.yanoproject.api` | Role interfaces returned by the runtime: `NodeLifecycle`, `ChainQuery`, `LedgerQuery`, `TxGateway`, `TxEvaluationGateway`, `MempoolQueryGateway`, `MempoolAdminGateway`, `ProducerControl`, `DevnetControl`; plus `CardanoHostServices` (Cardano capabilities offered to plugins in Cardano mode), `EpochSnapshotReader`, `NetworkGenesisValues`. |
| `org.yanoproject.api.events` | Node events: `BlockAppliedEvent`, `RollbackEvent`, `TipChangedEvent`, `Pre/Post/EpochTransitionEvent`, `UtxoStateAppliedEvent`, ...; `events.stream.NodeEventStream`. |
| `org.yanoproject.api.utxo`, `.utxo.index` | UTXO read views and the `UtxoIndexContributor` SPI. |
| `org.yanoproject.api.archive` | Epoch-artifact and projection contributor SPIs for the history archive. |
| `org.yanoproject.api.wallet` | Bounded wallet scan contracts (`WalletScan`, `WalletScanRequest`, ...). |
| `org.yanoproject.api.bootstrap` | `BootstrapDataProvider` and its data records. |
| `org.yanoproject.api.genesis`, `.governance`, `.model`, `.rollback`, `.db`, `.era`, ... | Genesis data, governance proposal status enums, DTOs, rollback-capable store contracts, RocksDB access. |

Resources: `cost-model-opnames/PlutusV1..V3.txt` (read by `util.CostModelOpNames`).

## Dependencies and boundaries

- Depends on [`node-api`](../node-api/README.md), Yaci `core`/`helper`/`events-core` and
  `cardano-client-core` (as `api`: `CardanoHostServices` exposes CCL `ProtocolParams`).
- Used by `runtime`, `plugin-catalog`, `ledger-rules`, `ledger-state`, `p2p`, `bootstrap-providers`,
  `scalus-bridge`, `devnet-toolkit` and `app-core`.
- It is one of the Yano artifacts the downstream application-ledger framework may depend on; Yano never
  depends on that framework ([ADR-064](../adr/)).

Embedding guide: [Embed Yano in Java](../www/src/content/docs/develop/embed.md).

## Build and test

```bash
./gradlew :cardano-api:build -PskipSigning=true
./gradlew :cardano-api:test
```
