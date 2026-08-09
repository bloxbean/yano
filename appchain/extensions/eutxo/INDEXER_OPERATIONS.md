# EUTxO lifecycle indexer

The EUTxO lifecycle indexer is a node-local, rebuildable read model for the
unified console and bounded read APIs. It joins finalized L2 transaction
summaries, current accounts, Cardano bridge deposits and withdrawals, and an
optional validity-proof lifecycle. It is never used for consensus, state
roots, finality, bridge authorization, proof verification, or settlement.

## Enable and configure

The JVM distribution includes the SQLite provider. Indexing is enabled by
default and starts only when the node hosts an `eutxo-ledger` chain:

```yaml
yano:
  app-chain:
    indexer:
      storage:
        path: appchain-indexers
    eutxo-indexer:
      enabled: true
      store:
        type: jdbc
        # Omit this URL for the safe per-chain default.
        # jdbc:
        #   url: jdbc:sqlite:/absolute/path/eutxo-lifecycle.db
      # ZK demo projects set this to their provider-neutral lifecycle source.
      # validity:
      #   path: /absolute/project/path/runtime/validity
```

The default database is:

```text
<yano.app-chain.indexer.storage.path>/<chain-id>/eutxo-lifecycle.db
```

This rebuildable root must remain separate from both `yano.storage.path` and
`yano.app-chain.storage.path`. Every owned directory includes
`.yano-eutxo-index`, which binds cleanup and rebuild operations to the network,
chain, state-machine profile, and authenticated-state genesis identity. An explicit JDBC URL is
supported only for a node with one EUTxO chain. SQLite files must be on a local
filesystem, not NFS or another network filesystem.

Set `yano.app-chain.eutxo-indexer.enabled=false` to disable the derived view.
The chain continues to validate and finalize normally.

## Read API

The manifested plugin base is:

```text
/api/v1/plugins/com.bloxbean.cardano.yano.appchain.eutxo.indexer/index/v1
```

Examples for a chain named `payments-eutxo`:

```bash
BASE=http://127.0.0.1:7070
INDEX="$BASE/api/v1/plugins/com.bloxbean.cardano.yano.appchain.eutxo.indexer/index/v1"

curl "$INDEX/status?chain=payments-eutxo"
curl "$INDEX/transactions?chain=payments-eutxo&limit=25"
curl "$INDEX/transactions/<transaction-id>?chain=payments-eutxo"
curl "$INDEX/messages/<message-id>?chain=payments-eutxo"
curl "$INDEX/accounts/<address>?chain=payments-eutxo"
curl "$INDEX/accounts/<address>/utxos?chain=payments-eutxo"
curl "$INDEX/accounts/<address>/activity?chain=payments-eutxo"
curl "$INDEX/bridge/deposits?chain=payments-eutxo"
curl "$INDEX/bridge/deposits/<l1-tx-id>/<output-index>?chain=payments-eutxo"
curl "$INDEX/bridge/withdrawals?chain=payments-eutxo"
curl "$INDEX/bridge/withdrawals/<claim-id>?chain=payments-eutxo"
curl "$INDEX/lineage/outpoints/<tx-id>/<output-index>?chain=payments-eutxo"
curl "$INDEX/validity/batches?chain=payments-eutxo"
```

List endpoints use opaque, route-bound cursors returned by the prior page and
accept limits from 1 through 100. Hashes must be canonical lowercase
64-character hexadecimal values, output indexes must fit 0 through 65535,
and addresses must be normalized Cardano Bech32 text. Unknown parameters,
duplicate singleton parameters, malformed cursors, SQL text, and unbounded
requests fail closed.

Every response identifies its projection as `DERIVED` and reports indexed
height, finalized height, lag, history origin, and coverage. The status
payload also reports a normalized logical digest. Independent nodes at the
same finalized source point should converge to the same digest.

## Console and demos

Open the chain-specific page printed by the demo:

```text
http://127.0.0.1:7070/ui/app-chain/eutxo/?chain=payments-eutxo
```

The page supports address, app-message, L2 transaction, deposit outpoint, and
withdrawal-claim search. It renders L1 deposit to L2 activity to L1 payout,
and adds the proof/root-settlement stage when a validity provider is present.
L1 transaction enrichment is lazy and gracefully degrades when the node no
longer retains the requested L1 body or UTxO data.

Both direct and ZK disposable workspaces configure one index per node.
`demo start`, `round-trip`, `status`, and `verify` wait for index catch-up and
compare normalized digests across members.

## Health and recovery

Status values are:

- `READY`: checkpoint equals the finalized app-chain height;
- `CATCHING_UP`: canonical history is being replayed;
- `REBUILDING`: a replacement projection is being prepared; and
- `FAILED`: the derived view stopped and requires operator attention.

A committed bridge-halt code, including a deep stable-L1 rollback alarm, is
reported separately. Previously accepted history remains visible; the
diagnostic does not silently rewrite bridge history.

SQLite startup validates Flyway checksums and `PRAGMA quick_check`. A partial
relational projection is reconstructed from the atomic local event journal.
Malformed journal data, a changed chain/profile identity, a migration
checksum mismatch, an unknown database, or missing retained canonical history
fails the index without stopping consensus.

For a full rebuild:

1. Stop the node.
2. Confirm the directory contains `.yano-eutxo-index` and that its identity
   matches the intended chain.
3. Move the entire `indexes` directory to a chain-specific backup location.
   Include the database, WAL, and shared-memory files; do not copy a live
   SQLite main file alone.
4. Restart the node. It creates a new Flyway-managed database and catches up
   from retained finalized app blocks and committed EUTxO records.
5. Wait for `READY`, verify zero lag and the expected logical digest, then
   retain or dispose of the old derived backup according to operator policy.

If required finalized app blocks are no longer retained, the index reports
failure or partial coverage. Restore a suitable canonical app-chain snapshot
or use a retained external index source; never fabricate missing rows.

## Metrics

Prometheus names are emitted in Micrometer form and include fixed
`chain` and `provider=jdbc-sqlite` labels:

```text
yano_appchain_eutxo_indexer_indexed_height
yano_appchain_eutxo_indexer_lag_blocks
yano_appchain_eutxo_indexer_queue_depth
yano_appchain_eutxo_indexer_apply_seconds
yano_appchain_eutxo_indexer_query_seconds
yano_appchain_eutxo_indexer_rebuild_progress
yano_appchain_eutxo_indexer_database_bytes
yano_appchain_eutxo_indexer_failures_total
yano_appchain_eutxo_indexer_rollbacks_total
```

No address, transaction, claim, JDBC URL, SQL text, or error text is used as a
metric label.

## SQLite support envelope

The reproducible Java task is:

```bash
./gradlew :appchain-eutxo-indexer-jdbc:benchmarkEutxoIndexer --rerun-tasks
```

Override the representative payment count with
`-PeutxoBenchmarkOperations=<1000..1000000>`; it must be divisible by 100.
The report is written to
`appchain-eutxo-indexer-jdbc/build/reports/eutxo-indexer/benchmark.json`.

The committed Java 25 / macOS arm64 / 16-processor run at 20,000 payments is
in
[`benchmarks/sqlite-v2-macos-arm64-java25-20000.json`](benchmarks/sqlite-v2-macos-arm64-java25-20000.json).
It measured 8,194 payment events/sec, 11,562 rebuild events/sec, 2,995
database bytes/payment, query p95 below 7 ms, and a stalled-writer finalized
callback p99 of 0.625 microseconds. This is a reproducible engineering
baseline, not a universal capacity or durability guarantee.

Use SQLite for one-node-local writer, ordinary console/query workloads, and a
dataset proven by the operator's own hardware benchmark. Multi-terabyte
retention, HA database service, network filesystems, or sustained loads beyond
the measured envelope should use a future conformant PostgreSQL provider or an
external finalized-event index. The storage boundary is conformance-tested so
that replacement does not change the API or projection semantics.
