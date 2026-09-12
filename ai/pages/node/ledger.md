# Ledger state and storage

Understand local state, rollback, filtering, pruning, and partial bootstrap.

Canonical URL: https://getyano.dev/node/ledger/

Yano uses **RocksDB** for authoritative node state. Depending on configuration, it tracks UTxOs, accounts, stakes, delegations, rewards, epoch snapshots, protocol parameters, and Conway governance state.

## Rollback is part of the model

A chain can reorganize. State and derived indexes need to follow the canonical chain backward as well as forward. Keep consumers aware of rollback; a previously seen block or transaction is not automatically a permanent application fact.

Read [account state and rollback](/reference/account-state/) for the state lifecycle and epoch-boundary recovery behavior.

## Storage controls solve different problems

| Control | Effect | Consequence |
| --- | --- | --- |
| `yano.storage.path` | Node RocksDB directory | One node process per database |
| `yano.app-chain.storage.path` | Separate app-chain RocksDB root | Back up app-chain identity and state together |
| `yano.filters.utxo.*` | Persist selected UTxOs | Queries describe the selected subset, not complete network coverage |
| `yano.chain.block-body-prune-depth` | Remove older block bodies | Old body-dependent queries and scans may be unavailable |
| `yano.rollback-retention-epochs` | Bound retained rollback material | Must match your recovery requirements |

Pruning bodies and retaining rollback data are separate policies. Do not assume preserved headers mean all historical bodies are still available to downstream clients.

## Partial bootstrap

The optional bootstrap mode can obtain selected initial UTxOs through providers such as Blockfrost or Koios instead of replaying all history. This is a **partial-state** workflow.

The runtime disables derived state that requires full history in partial bootstrap mode: account state, stake balance indexes, epoch parameter tracking, rewards, Ada pots, governance, and epoch snapshots. UTxO bootstrap remains available. It is not a shortcut to a complete historical node.

See the [configuration catalog](/reference/configuration-catalog/) for `yano.bootstrap.*` settings. Use fresh storage when changing the network or completeness model.
