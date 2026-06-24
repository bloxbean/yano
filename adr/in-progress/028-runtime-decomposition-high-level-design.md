# ADR-028 High-Level Runtime Design

This document summarizes the post-ADR-028 runtime shape after the pre-release API
cleanup. Public construction goes through `YanoAssembly`; consumers use
`YanoNode` and narrow role interfaces. The old `Yano` and `NodeAPI` public
surfaces are removed.

## C4 Context

```mermaid
flowchart LR
    Host["Host application\nQuarkus, Spring, Micronaut, plain Java"]
    Yano["Yano runtime library"]
    Peers["Cardano peers\nN2N chain-sync and tx-submission"]
    Clients["REST/N2N clients"]
    Storage["Chain and ledger storage\nRocksDB or in-memory"]
    TxServices["Optional tx-services\nScalus, Aiken, JULC"]

    Host -->|maps config and lifecycle| Yano
    Clients -->|REST or N2N| Yano
    Yano -->|syncs from / serves to| Peers
    Yano -->|persists and queries| Storage
    Yano -->|optional validation/evaluation bootstrap| TxServices
```

## Container View

```mermaid
flowchart TB
    Adapter["Application adapter\nYanoProducer or future starter"]
    Assembly["YanoAssembly\ncomposition root"]
    Node["YanoNode\nrole handle"]
    Runtime["runtime.internal.RuntimeNode\ninternal runtime implementation"]
    Kernel["NodeKernel\nlifecycle and health"]
    Roles["Role interfaces\nNodeLifecycle, ChainQuery, LedgerQuery,\nTxGateway, TxEvaluationGateway,\nProducerControl, DevnetControl"]

    Adapter -->|builds from YanoConfig + RuntimeOptions| Assembly
    Assembly --> Runtime
    Assembly --> Node
    Node --> Kernel
    Node --> Roles
    Roles --> Runtime
```

## Runtime Component View

```mermaid
flowchart TB
    Runtime["runtime.internal.RuntimeNode"]
    Storage["ChainStorageSubsystem\nChainState, snapshots, recovery, pruning"]
    Sync["SyncSubsystem\npeer sessions, header/body sync, rollback classification"]
    Serve["ServeSubsystem\nN2N server and tx-submission"]
    Utxo["UtxoSubsystem\nUTXO store, filters, prune, reconcile"]
    Ledger["LedgerStateSubsystem\naccount history, epoch params,\nrewards, governance, AdaPot"]
    Tx["TxSubsystem\nmempool, validation, evaluation,\nadmission, block selector"]
    Producer["ProducerSubsystem\nstrategy holder and producer controls"]
    Devnet["DevnetToolkit\nDevnetControl role"]
    Maintenance["RuntimeMaintenanceGate\nread/write maintenance coordination"]

    Runtime --> Storage
    Runtime --> Sync
    Runtime --> Serve
    Runtime --> Utxo
    Runtime --> Ledger
    Runtime --> Tx
    Runtime --> Producer
    Runtime --> Devnet
    Storage --> Maintenance
    Sync --> Storage
    Serve --> Tx
    Utxo --> Storage
    Ledger -->|ChainBlockReader + RocksDbAccess| Storage
    Tx --> Utxo
    Producer --> Tx
    Devnet --> Maintenance
```

## End-To-End Flow

```mermaid
sequenceDiagram
    participant Host as Host adapter
    participant Assembly as YanoAssembly
    participant Node as YanoNode
    participant Runtime as runtime.internal.RuntimeNode
    participant Sync as SyncSubsystem
    participant Storage as ChainStorageSubsystem
    participant Ledger as LedgerStateSubsystem
    participant Tx as TxSubsystem
    participant Producer as ProducerSubsystem

    Host->>Assembly: fromConfig(config).runtimeOptions(options).build()
    Assembly->>Runtime: construct internal runtime and install optional services
    Assembly-->>Host: YanoNode role handle
    Host->>Node: lifecycle().start()
    Node->>Runtime: start()
    Runtime->>Storage: start lifecycle-owned pruning/recovery checks
    Runtime->>Tx: start admission
    Runtime->>Sync: start peer sync if client enabled
    Runtime->>Producer: start immediate producer plan if configured
    Sync->>Storage: apply blocks and rollbacks
    Storage->>Ledger: publish chain events
    Ledger->>Storage: persist derived state
    Producer->>Tx: select block transactions
```

## Devnet Control Flow

```mermaid
sequenceDiagram
    participant REST as DevnetResource
    participant Role as DevnetControl
    participant Toolkit as DevnetToolkit
    participant Runtime as runtime.internal.RuntimeNode internals
    participant Gate as RuntimeMaintenanceGate
    participant Service as Devnet service
    participant Storage as ChainState/RocksDB

    REST->>Role: restore, rollback, fund, advance, shift, catch-up
    Role->>Toolkit: role method
    Toolkit->>Runtime: operation callback
    Runtime->>Gate: enterMaintenance(reason)
    Runtime->>Service: execute operation
    Service->>Storage: mutate or query state
    Service-->>Runtime: result
    Runtime->>Gate: close lease
    Runtime-->>Toolkit: result
    Toolkit-->>REST: typed result
```

## API Boundary Rules

- Embedders construct with `YanoAssembly` and hold `YanoNode`.
- App adapters expose only the role beans they need.
- `NodeAPI`, direct public `Yano` construction, and raw mempool access are gone.
- `ChainQuery` exposes tip/block reads and does not leak the raw `ChainState`
  object to adapters or REST resources.
- Account-state providers receive `ChainBlockReader` for replay/reconciliation
  and explicit `RocksDbAccess` only for RocksDB-backed stores; they do not depend
  on `DirectRocksDBChainState` or mutable `ChainState`.
- `DevnetControl` is backed by `devnet-toolkit` through ADR-029. Runtime
  devnet recipes expose devnet-safe SPI ports but not the optional control
  adapter; plain slot-leader recipes expose producer controls only.
- Runtime writes that move chain, producer, or devnet state go through
  `RuntimeMaintenanceGate`; normal reads use role interfaces.
