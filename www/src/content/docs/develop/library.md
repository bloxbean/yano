---
title: "Yano as a library"
description: "Run a Yano node inside your own Spring Boot application, listen to chain events, and keep a scoped index in your own database."
sidebar:
  order: 5
---

Yano is a Cardano node written in Java, published as ordinary Maven artifacts. Your application can run the node in its own JVM: Yano syncs from an upstream relay, stores the chain in RocksDB, and calls your code for every block, rollback and epoch boundary. Your code writes what it needs to its own database.

If you write scoped indexers with [yaci-store](https://github.com/bloxbean/yaci-store) as a library, the pattern is familiar. The difference is that the node is inside your application: there is no separate node process to run beside your indexer, Yano itself syncs from any public relay, and the same process can also answer UTXO and ledger queries, submit transactions and serve downstream peers.

This page takes you from a first run to production concerns. The complete, runnable application is in [`examples/spring-boot-indexer`](https://github.com/bloxbean/yano/tree/main/examples/spring-boot-indexer).

## What an embedded node gives your application

| Capability | API | Needs |
| --- | --- | --- |
| Sync and store the chain | `Yano.start()`, `chain()` | Always on |
| Block, rollback, tip and sync events | `chain().registerListeners(...)` | Always on |
| Epoch boundary events | `PreEpochTransitionEvent`, `EpochTransitionEvent`, `PostEpochTransitionEvent` | Account state |
| Read any stored block | `chain().getBlockByNumber(n)` | Bodies not pruned |
| UTXO by address, credential or outpoint | `ledger().getUtxoState()` | UTXO store |
| Stake, rewards, pools, DReps, governance | `ledger().getLedgerStateProvider()` | Account state and ledger switches |
| Slot, epoch and time conversion | `ledger()` | Always on |
| Protocol parameters per epoch | `ledger().getProtocolParameters(epoch)` | Account state and epoch-parameter tracking |
| Submit transactions to the network | `txGateway().submitTransaction(cbor)` | Always on |
| Validate transactions and evaluate scripts | `txEvaluationGateway()`, `transactionBootstrap(...)` | UTXO store, `yano-tx-services` |
| Mempool events and queries | `MemPoolTransactionReceivedEvent`, `mempoolQueryGateway()` | Transactions reaching this node |
| Reject transactions or blocks | `TransactionValidateEvent`, `BlockConsensusEvent` | Always on |
| Serve downstream Cardano nodes and indexers | `serverPort` in `YanoConfig` | Server enabled |
| Local devnet with faucet, snapshots and time travel | `YanoDevnetAssembly` | `yano-devnet-toolkit` |

The [feature switches](#choose-what-yano-stores) section explains each "Needs" entry.

## Run the example first

You need Java 25 and network access to a preprod relay.

```bash
git clone https://github.com/bloxbean/yano.git
cd yano/examples/spring-boot-indexer
../../gradlew bootRun
```

The application syncs preprod into `./data/chainstate` and indexes [CIP-20](https://cips.cardano.org/cip/CIP-20) transaction messages (metadata label `674`) into an H2 database at `./data/indexer.mv.db`. Preprod messages start appearing around block 220,000, a few minutes into the sync.

```bash
curl -s localhost:8080/api/status
# {"yanoTipBlock":224767,"yanoTipSlot":11637946,"indexedBlock":224766,"syncing":true}

curl -s 'localhost:8080/api/metadata?limit=1'
# [{"txHash":"11de28f8...","label":674,"body":"{\"msg\":[\"VyFi: Process orders\"]}",...}]
```

`indexedBlock` follows `yanoTipBlock` closely because the indexer runs inside Yano's block apply.

## Build it step by step

The example has five small classes. This section builds each one.

### 1. Add the dependencies

Yano requires Java 25. The example uses Spring Boot 4.1 and Gradle:

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '4.1.1'
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot's versions apply as constraints, Yano's own dependencies keep theirs.
    implementation platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
    implementation platform("org.yanoproject:yano-bom:${yanoVersion}")
    // Spring Boot manages Netty 4.2; Yano is built and tested on Netty 4.1.
    implementation enforcedPlatform('io.netty:netty-bom:4.1.135.Final')

    implementation 'org.springframework.boot:spring-boot-starter-webmvc'
    implementation 'org.springframework.boot:spring-boot-starter-jdbc'
    implementation 'org.yanoproject:yano-runtime'

    runtimeOnly 'com.h2database:h2'
    runtimeOnly 'org.postgresql:postgresql'
}

configurations.configureEach {
    // Spring Boot ships Logback; drop the reload4j binding that Yano's standalone node uses.
    exclude group: 'org.slf4j', module: 'slf4j-reload4j'
    exclude group: 'ch.qos.reload4j', module: 'reload4j'
}

// RocksDB loads a native library; JDK 25 warns unless native access is granted.
tasks.named('bootJar') {
    manifest {
        attributes 'Enable-Native-Access': 'ALL-UNNAMED'
    }
}

tasks.named('bootRun') {
    jvmArgs '--enable-native-access=ALL-UNNAMED'
}
```

Set `yanoVersion` to a published release, such as `0.1.0-pre18`, in `gradle.properties`. Keep every `org.yanoproject` artifact on the same version; the BOM does that for you.

Three details matter for any Spring Boot application:

- **Logging.** `yano-runtime` brings the reload4j SLF4J binding for the standalone node. Exclude it so Logback stays the only binding.
- **Netty.** Spring Boot's dependency management upgrades Netty to 4.2. Yano and the Yaci protocol library run on the Netty 4.1 line, so pin the Netty BOM to a 4.1 release. With Maven and `spring-boot-starter-parent`, set `<netty.version>4.1.135.Final</netty.version>` instead.
- **Native access.** RocksDB loads a JNI library. The manifest attribute and JVM flag above silence the JDK 25 warning.

Maven users add the same dependencies and exclusions:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.yanoproject</groupId>
      <artifactId>yano-bom</artifactId>
      <version>${yano.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependency>
  <groupId>org.yanoproject</groupId>
  <artifactId>yano-runtime</artifactId>
  <exclusions>
    <exclusion>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-reload4j</artifactId>
    </exclusion>
    <exclusion>
      <groupId>ch.qos.reload4j</groupId>
      <artifactId>reload4j</artifactId>
    </exclusion>
  </exclusions>
</dependency>
```

To build against an unreleased checkout, run `./gradlew publishToMavenLocal` in the Yano repository, add `mavenLocal()` and use the version it publishes.

### 2. Create the node

A `Yano` instance is built from two things:

- `YanoConfig`: the network, the upstream relay, storage and ports.
- `RuntimeOptions`: the event bus, plugins, and a `globals` map of feature switches.

`YanoAssembly.relay(config)` builds a node that follows the chain. Building the node opens its storage. `start()` begins the sync.

```java
@Configuration
public class YanoConfiguration {

    @Bean(destroyMethod = "close")
    public Yano yano(YanoProperties props) throws IOException {
        // RocksDB creates the last directory only.
        Files.createDirectories(Path.of(props.storagePath()));
        YanoConfig preset = YanoConfig.defaultForNetwork(props.network());
        long magic = preset.getProtocolMagic();
        YanoConfig config = preset.toBuilder()
                .remoteHost(props.remoteHost())
                .remotePort(props.remotePort())
                .rocksDBPath(props.storagePath())
                .enableServer(props.serverPort() > 0)
                .serverPort(props.serverPort())
                // Genesis files for the public networks ship inside yano-runtime.
                .shelleyGenesisFile(GenesisFileResolver.resolve(null, magic, "shelley-genesis.json"))
                .byronGenesisFile(GenesisFileResolver.resolve(null, magic, "byron-genesis.json"))
                .alonzoGenesisFile(GenesisFileResolver.resolve(null, magic, "alonzo-genesis.json"))
                .conwayGenesisFile(GenesisFileResolver.resolve(null, magic, "conway-genesis.json"))
                .build();
        config.validate();

        RuntimeOptions defaults = RuntimeOptions.defaults();
        RuntimeOptions options = new RuntimeOptions(defaults.events(), defaults.plugins(), props.globals());

        return YanoAssembly.relay(config).runtimeOptions(options).build();
    }
}
```

`YanoProperties` is a `@ConfigurationProperties("yano")` record that binds the settings from `application.yml`:

```yaml
yano:
  network: preprod
  remote-host: preprod-node.world.dev.cardano.org
  remote-port: 30000
  storage-path: ./data/chainstate
  # Set to 13337 to serve downstream Cardano nodes from this process.
  server-port: 0
  # Runtime feature switches. Keys are YanoPropertyKeys names; brackets keep the dots.
  globals:
    "[yano.utxo.enabled]": false
    "[yano.account-state.enabled]": false
```

Things to know about this bean:

- **Genesis files.** You must resolve them yourself. The runtime does not load bundled genesis on its own; the packaged Yano node does the same resolution before it builds the config. `GenesisFileResolver.resolve(path, magic, name)` returns `path` if that file exists. Otherwise, for mainnet, preprod, preview and sanchonet, it extracts the copy bundled in `yano-runtime`. For a custom network, pass your own file paths.
- **Validation.** `validate()` checks the combination of client, server, producer and storage settings. `build()` does not call it for you.
- **Presets.** `defaultForNetwork` fills in sync tuning for the network. It falls back to preprod for a name it does not know, so the example rejects an unknown `yano.network`. The preprod preset points at `localhost:32000`; always set the relay explicitly.
- **Startup.** Do not call `start()` in the bean method. The next steps start the node after the listeners are registered and the index has caught up.
- **One process per directory.** RocksDB locks the storage directory. A second process, or a second `Yano` in the same JVM, cannot open the same path.

### 3. Write the listener

A listener is any object with methods annotated `@DomainEventListener` (from `com.bloxbean.cardano.yaci.events.api`). Each method takes one event, or an `EventContext<E>` when you also need the metadata. This one stores metadata under one label:

```java
@Component
public class MetadataIndexer {

    private final MetadataStore store;
    private final JsonMapper json;
    private final long label;

    // constructor omitted

    @DomainEventListener
    public void onBlockApplied(BlockAppliedEvent event) {
        index(event);
    }

    @DomainEventListener
    public void onRollback(RollbackEvent event) {
        Point target = event.target();
        store.rollbackToSlot(target.getHash() == null ? -1 : target.getSlot());
    }

    void index(BlockAppliedEvent event) {
        // Byron blocks carry no metadata, and a Byron epoch-boundary block reuses the
        // number of the block before it, so the cursor starts with the Shelley era.
        if (event.era() == Era.Byron) {
            return;
        }
        List<MetadataStore.Entry> entries = extract(event);
        store.saveBlock(new MetadataStore.IndexedBlock(event.blockNumber(), event.slot(), event.blockHash()),
                entries);
    }

    private List<MetadataStore.Entry> extract(BlockAppliedEvent event) {
        Block block = event.block();
        if (block == null || block.getAuxiliaryDataMap() == null || block.getAuxiliaryDataMap().isEmpty()) {
            return List.of();
        }
        // Phase-2 invalid transactions are in the block but the ledger ignores their metadata.
        Set<Integer> invalid = block.getInvalidTransactions() == null
                ? Set.of() : new HashSet<>(block.getInvalidTransactions());
        String key = Long.toString(label);
        List<MetadataStore.Entry> entries = new ArrayList<>();
        for (int i = 0; i < block.getTransactionBodies().size(); i++) {
            AuxData aux = block.getAuxiliaryDataMap().get(i);
            if (aux == null || aux.getMetadataJson() == null || invalid.contains(i)) {
                continue;
            }
            JsonNode value = json.readTree(aux.getMetadataJson()).get(key);
            if (value != null) {
                String txHash = block.getTransactionBodies().get(i).getTxHash();
                entries.add(new MetadataStore.Entry(txHash, label, value.toString(),
                        event.blockNumber(), event.slot()));
            }
        }
        return entries;
    }
}
```

`BlockAppliedEvent.block()` is the Yaci `Block` model: transaction bodies, witnesses, auxiliary data keyed by transaction index, and the indexes of invalid transactions. Byron blocks arrive with `block() == null`; use `ByronMainBlockAppliedEvent` when you need Byron transactions.

Byron also has epoch-boundary blocks. They carry no transactions, and each one is published with the block number of the main block before it; only the slot is unique. An indexer that keys anything on the block number must skip them, which is why the example ignores the Byron era entirely. `ByronBlockProjectionEvent` identifies them if you need them.

**Keep listener classes free of Spring AOP.** Yano finds listener methods by reflection on the object you register. If the bean is proxied, for example because a method is annotated `@Transactional` or `@Async`, the proxy class carries no annotations and nothing is registered. Put transactions in a separate bean, as the example does with `MetadataStore`.

### 4. Store each block with a cursor

Write a block's data and a cursor row in **one transaction**. Then the cursor never claims a block whose data is missing, and replaying a block only replaces its own rows:

```sql
CREATE TABLE IF NOT EXISTS indexed_block (
    block_number BIGINT      PRIMARY KEY,
    slot         BIGINT      NOT NULL,
    block_hash   VARCHAR(64) NOT NULL
);

CREATE TABLE IF NOT EXISTS tx_metadata (
    tx_hash      VARCHAR(64) NOT NULL,
    label        BIGINT      NOT NULL,
    body         TEXT        NOT NULL,
    block_number BIGINT      NOT NULL,
    slot         BIGINT      NOT NULL,
    PRIMARY KEY (tx_hash, label)
);
```

```java
public void saveBlock(IndexedBlock block, List<Entry> entries) {
    tx.executeWithoutResult(status -> {
        // Replacing the block's rows keeps a replayed block idempotent.
        jdbc.update("DELETE FROM tx_metadata WHERE block_number = ?", block.blockNumber());
        jdbc.update("DELETE FROM indexed_block WHERE block_number = ?", block.blockNumber());
        for (Entry e : entries) {
            jdbc.update("INSERT INTO tx_metadata (tx_hash, label, body, block_number, slot) VALUES (?, ?, ?, ?, ?)",
                    e.txHash(), e.label(), e.body(), e.blockNumber(), e.slot());
        }
        jdbc.update("INSERT INTO indexed_block (block_number, slot, block_hash) VALUES (?, ?, ?)",
                block.blockNumber(), block.slot(), block.blockHash());
    });
}

public void rollbackToSlot(long slot) {
    tx.executeWithoutResult(status -> {
        jdbc.update("DELETE FROM tx_metadata WHERE slot > ?", slot);
        jdbc.update("DELETE FROM indexed_block WHERE slot > ?", slot);
    });
}
```

Store the slot on every row you write. A rollback is then "delete everything after slot S" for each table.

### 5. Start the node after catching up

Yano publishes `BlockAppliedEvent` right after it stores a block. If the application stops between the two, Yano holds blocks the indexer never saw. Likewise, if it stops between a Yano rollback and your rollback handler, the indexer holds blocks Yano no longer has. On startup, repair both from Yano's own block store before any new event arrives:

```java
@Component
public class YanoNodeLifecycle implements SmartLifecycle {

    @Override
    public void start() {
        // Register before start() so no event is published without a listener.
        yano.chain().registerListeners(indexer);
        catchUp(yano.chain());
        yano.start();
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        yano.stop();
    }

    void catchUp(ChainQuery chain) {
        ChainTip tip = chain.getLocalTip();
        long tipNumber = tip != null ? tip.getBlockNumber() : -1;

        // Walk back to the newest block both sides agree on and drop anything after it.
        long agreed = Math.min(tipNumber, store.lastBlock().map(MetadataStore.IndexedBlock::blockNumber).orElse(-1L));
        while (agreed >= 0 && !sameBlock(chain, agreed)) {
            agreed--;
        }
        long agreedSlot = agreed >= 0 ? store.block(agreed).orElseThrow().slot() : -1;
        store.rollbackToSlot(agreedSlot);

        for (long number = agreed + 1; number <= tipNumber; number++) {
            BlockAppliedEvent block = readBlock(chain, number);
            if (block != null) {
                indexer.index(block);
            }
        }
    }

    /** Returns the stored block as Yano published it, or null for a Byron block. */
    private static BlockAppliedEvent readBlock(ChainQuery chain, long number) {
        byte[] body = chain.getBlockByNumber(number);
        if (body == null) {
            // Byron's genesis boundary block is not stored under a block number.
            if (number == 0) {
                return null;
            }
            throw new IllegalStateException("Yano has no body for block " + number
                    + "; keep yano.chain.block-body-prune-depth at 0 to rebuild the index");
        }
        if (StoredBlockUtil.isStoredByronBlock(null, body)) {
            return null;
        }
        Block block = BlockSerializer.INSTANCE.deserialize(body);
        HeaderBody header = block.getHeader().getHeaderBody();
        return new BlockAppliedEvent(block.getEra(), header.getSlot(), number, header.getBlockHash(), block);
    }

    // sameBlock(...) compares the stored hash with chain.getCanonicalBlockReference(number).
}
```

`SmartLifecycle` puts this in the right order: Spring starts the node after every bean exists, and stops the node before it closes the `DataSource`. Spring then calls `close()` on the `Yano` bean, which releases RocksDB.

The same catch-up does two more jobs:

- **Backfill.** Add an indexer to an application whose Yano store is already synced, or delete the index tables, and the catch-up rebuilds the index from Yano's stored blocks without contacting the network. It runs before the application finishes starting: in testing, rebuilding 338,629 preprod blocks took about 2.5 minutes and produced exactly the rows the live sync had written. On mainnet expect hours, and run a long rebuild before you put the application behind a health check.
- **Recovery after a crash.** H2's file mode delays writes by up to half a second, so a `kill -9` during sync loses the last few hundred blocks of the index. In testing, the next start logged `Catching up from block 244584 to 245016` and continued. PostgreSQL does not lose committed transactions, but a crash between Yano's commit and yours still leaves at most one block to replay.

### 6. Serve the data

The rest is ordinary Spring. The example's controller reads the index and Yano's status:

```java
@GetMapping("/api/status")
public Status status() {
    ChainTip tip = yano.chain().getLocalTip();
    return new Status(tip != null ? tip.getBlockNumber() : null, tip != null ? tip.getSlot() : null,
            store.lastBlock().map(MetadataStore.IndexedBlock::blockNumber).orElse(null),
            yano.lifecycle().isSyncing());
}
```

## How events are delivered

These guarantees decide how you write a listener:

- **Synchronous, in chain order.** Listeners run on Yano's block-apply thread, one block at a time, after Yano has stored the block. A slow listener slows the sync; a fast one keeps pace with it.
- **A failing listener stops the block.** If a synchronous listener throws, Yano does not apply the block. It removes the block from its own chain state, publishes a compensating `RollbackEvent`, and fetches the block again from its peer. The index can therefore lag, but it cannot silently skip a block. In testing, a database constraint that rejected block 329,287 held both Yano's tip and the index at 329,286 with no gaps. See [known limitations](#known-limitations) for how often Yano retries.
- **Order between listeners.** `@DomainEventListener(order = n)` runs lower numbers first, across all listener objects. Yano's own UTXO store listens at order `100`, so a listener with a higher order runs after the block is visible in the UTXO store. Listening to `UtxoStateAppliedEvent` expresses the same thing more directly.
- **Async listeners.** `@DomainEventListener(async = true)` runs the method on a virtual thread with a bounded queue. Use it only for side effects you can afford to lose, such as notifications. The first exception in any async listener puts the event bus into a failed state, so later publications fail. `TransactionValidateEvent`, `UtxoStateAppliedEvent` and `UtxoStateRolledBackEvent` listeners must be synchronous.
- **Rollbacks.** `RollbackEvent.target()` is the point to keep: delete what is after its slot. `realReorg()` is `false` for the routine rollback to the intersection point when a peer session starts; handle both kinds the same way, because the delete is a no-op when nothing follows the point. A target whose hash is `null` is the chain origin.
- **No unsubscribe.** `registerListeners` keeps the listener for the life of the node. Register once, before `start()`.

`registerListener(listener, SubscriptionOptions)` registers with explicit options: an `EventFilter`, and the buffer size, overflow policy and executor used by async methods. The order between listeners always comes from `order`.

### Events you can listen to

All event types are in `org.yanoproject.api.events`.

| Event | Published | Payload |
| --- | --- | --- |
| `BlockReceivedEvent` | Before a block is validated and stored | `era()`, `slot()`, `blockNumber()`, `blockHash()`, `block()` |
| `BlockConsensusEvent` | After body validation, before storage; can be rejected | `slot()`, `blockNumber()`, `blockHash()`, `blockCbor()`, `reject(source, reason)` |
| `GenesisBlockEvent` | First block of a fresh chain | `era()`, `epoch()`, `slot()`, `blockNumber()`, `blockHash()`, `bootstrapData()`, `producerPoolHash()` |
| `PreEpochTransitionEvent` | First block of a new epoch, before `BlockAppliedEvent` (rewards and Ada pots) | `previousEpoch()`, `newEpoch()`, `slot()`, `blockNumber()` |
| `EpochTransitionEvent` | Same block, after the stake snapshot | same |
| `PostEpochTransitionEvent` | Same block, after pool retirements | same |
| `ByronMainBlockAppliedEvent` | Byron main block stored | `slot()`, `blockNumber()`, `blockHash()`, `block()` (Byron model) |
| `ByronBlockProjectionEvent` | Byron epoch-boundary block stored; its block number repeats the previous block's | `slot()`, `blockNumber()`, `blockHash()`, `prevBlockHash()`, `epochBoundaryBlock()` |
| `BlockAppliedEvent` | Every stored block, Byron included | `era()`, `slot()`, `blockNumber()`, `blockHash()`, `block()` (`null` for Byron) |
| `UtxoStateAppliedEvent` | After the block is visible in the UTXO store | `blockAppliedEvent()` |
| `TipChangedEvent` | After each applied block | `currentSlot()`, `currentBlockNo()`, `currentHash()`; the `previous*()` values are `null` |
| `RollbackEvent` | Chain switched to another fork, or a session restarted | `target()`, `realReorg()` |
| `UtxoStateRolledBackEvent` | After the UTXO store rolled back | `rollbackEvent()` |
| `HeaderAppliedEvent` | Header stored, ahead of its body; best effort | `slot()`, `blockNumber()`, `blockHash()` |
| `SyncStatusChangedEvent` | Sync phase changed | `previous()`, `current()`: `INITIAL_SYNC`, `INTERSECT_PHASE`, `STEADY_STATE` |
| `NodeStartedEvent` | Node started | `timestamp()` |
| `TransactionValidateEvent` | Before a transaction enters the mempool; can be rejected | `txCbor()`, `txHash()`, `origin()`, `utxoResolver()`, `reject(source, reason)` |
| `MemPoolTransactionReceivedEvent` | Transaction admitted to the mempool | `transaction()`: hash, bytes, body type, size |
| `BlockProducedEvent` | This node produced a block (devnet or slot leader) | `era()` (era number), `slot()`, `blockNumber()`, `blockHash()` (bytes), `txCount()` |

For one Shelley-era block the order is `BlockReceivedEvent`, `BlockConsensusEvent`, storage, the three epoch events at an epoch boundary, `BlockAppliedEvent` (with `UtxoStateAppliedEvent` inside it when the UTXO store is on), and then `TipChangedEvent`.

The epoch events, `UtxoStateAppliedEvent` and `UtxoStateRolledBackEvent` are published only when the matching store is enabled; see the next section.

### Lossy event stream

`yano.eventStream()` returns an `Optional<NodeEventStream>` that is present whenever events are enabled. The stream never slows the sync. Each subscriber gets a bounded queue of small `NodeEvent(topic, slot, blockNumber, blockHash, txHash)` records for the `block`, `rollback` and `tx` topics. The oldest entry is dropped when the queue is full, and only real reorganizations are delivered as rollbacks. Use it for dashboards or push notifications, not for an index.

## Choose what Yano stores

An embedded node starts lean: it stores blocks and headers, and every derived store is off until you enable it in `RuntimeOptions.globals` (the `yano.globals` map in the example). The keys are constants in `org.yanoproject.api.config.YanoPropertyKeys`.

| Key | Default | Enables |
| --- | --- | --- |
| `yano.utxo.enabled` | `false` | UTXO store: `ledger().getUtxoState()`, `UtxoStateAppliedEvent`, transaction validation |
| `yano.account-state.enabled` | `false` | Stake accounts, delegations, pools, DReps; epoch boundary events |
| `yano.epoch-params.tracking-enabled` | `false` | Protocol parameters per epoch, including on-chain updates; needs account state |
| `yano.rewards.enabled` | `false` | Reward calculation at epoch boundaries |
| `yano.adapot.enabled` | `false` | Treasury and reserves per epoch |
| `yano.governance.enabled` | `false` | Conway governance state: proposals, DRep distribution, ratification |
| `yano.filters.utxo.enabled` | `false` | Keep only UTXOs for `yano.filters.utxo.addresses` or `yano.filters.utxo.payment-credentials` |
| `yano.chain.block-body-prune-depth` | `0` (keep all) | Delete block bodies older than this many blocks |
| `yano.utxo.pruneDepth` | `2160` | How long spent UTXOs are kept for rollback |

Choose deliberately:

- **Scoped indexer.** Leave everything off, as the example does. The sync is fastest, and your listener sees every block.
- **Wallet backend or UTXO queries.** Enable `yano.utxo.enabled`. With the UTXO filter keys you can keep only the addresses your application cares about.
- **Stake, rewards or governance.** Enable account state and the ledger switches you need, and sync from genesis: these values are computed from the whole history.
- **Rebuilding the index from Yano's store.** Leave block-body pruning off. A pruned body cannot be replayed, and the catch-up above fails with a clear message.

Decide before the first sync. When you turn a store on later, Yano rebuilds it from its stored block bodies at the next start, which takes about as long as the original sync and needs every body to be retained. Starting with empty storage is the simpler path.

Yano reads a switch whether it arrives as a boolean or as the string `"true"` or `"false"`. In a Spring Boot YAML map, wrap dotted keys in brackets, as in `"[yano.utxo.enabled]": true`; without them Spring Boot splits the key into nested maps.

## Query Yano from your code

The `Yano` handle exposes read APIs next to the events.

```java
// Chain
ChainTip tip = yano.chain().getLocalTip();               // null before the first block
byte[] cbor = yano.chain().getBlockByNumber(1_000_000);   // raw block CBOR

// Time and parameters
long epoch = yano.ledger().slotToEpoch(tip.getSlot());
long unixSeconds = yano.ledger().slotToUnixTime(tip.getSlot());

// Protocol parameters (yano.account-state.enabled and yano.epoch-params.tracking-enabled)
Optional<ProtocolParamsSnapshot> params = yano.ledger().getProtocolParameters((int) epoch);

// UTXO store (yano.utxo.enabled=true)
UtxoState utxos = yano.ledger().getUtxoState();
List<Utxo> page = utxos.getUtxosByAddress("addr_test1...", 1, 50);   // pages start at 1
Optional<Utxo> output = utxos.getUtxo("9f2c...", 0);

// Ledger state (yano.account-state.enabled=true and the ledger switches)
LedgerStateProvider ledger = yano.ledger().getLedgerStateProvider();
Optional<BigInteger> rewards = ledger.getRewardBalance(0, "stake-key-hash-hex");   // 0 = key, 1 = script
Optional<String> pool = ledger.getDelegatedPool(0, "stake-key-hash-hex");

// Node
NodeStatus status = yano.lifecycle().getStatus();
```

`getUtxoState()` returns `null` when the UTXO store is off and `getLedgerStateProvider()` returns `null` when account state is off, so check what you enabled.

## Submit and validate transactions

`yano.txGateway().submitTransaction(txCbor)` adds a signed transaction to this node's mempool and returns its hash; the node relays it to its peers. Without validation services, Yano does not check the transaction against the ledger rules, and an invalid transaction is rejected later by the network.

To validate against the ledger rules and evaluate Plutus scripts before admission, add `org.yanoproject:yano-tx-services` and install the services when you build the node. The packaged node does the same:

```java
// Same value as yano.epoch-params.tracking-enabled in your globals.
boolean paramsTracking = true;

Yano yano = YanoAssembly.relay(config)
        .runtimeOptions(options)
        .transactionBootstrap(
                TransactionBootstrapOptions.enabled(
                        paramsTracking,    // read protocol parameters from epoch tracking
                        false,             // supplementary ledger rules
                        "scalus"),         // script evaluator: "scalus" or "julc"
                DefaultTransactionServicesFactory::create)
        .build();
```

Validation needs the UTXO store. It reads protocol parameters from epoch-parameter tracking when `yano.account-state.enabled` and `yano.epoch-params.tracking-enabled` are on. Otherwise pass `false` as the first argument and set `protocolParametersFile` in `YanoConfig`; the public-network presets do not ship a protocol parameters file. Then `txEvaluationGateway().evaluateTransaction(cbor)` returns script execution units, much as an Ogmios or Blockfrost evaluation endpoint does.

### Apply your own admission rules

Listen to `TransactionValidateEvent` to reject transactions before they enter the mempool. A rejection makes `submitTransaction` throw:

```java
@DomainEventListener(order = 200)
public void allowListedOnly(TransactionValidateEvent event) {
    if (!policy.allows(event.txCbor())) {
        event.reject("my-policy", "transaction is not allowed by this node");
    }
}
```

Orders below `100` run before Yano's built-in ledger validator, orders above `100` after it. `BlockConsensusEvent` works the same way for blocks. Yano does not store a rejected block and does not move past it: the block is not skipped, the sync stops there. Use it only for rules you need to enforce, not for filtering what you index.

## Serve downstream peers

Set a `serverPort` (the example's `yano.server-port`). Your application then speaks the Cardano node-to-node protocol: a Haskell `cardano-node`, a yaci-store instance or another Yano can sync from it. This makes your indexer double as a relay for your own infrastructure. Leave it at `0` when you only consume the chain.

## Advanced

### Start from a later point

Like yaci-store's start point, `YanoConfig.syncStartSlot` and `syncStartBlockHash` begin a fresh chain store at a given block instead of genesis. Every derived store then starts mid-chain and is incomplete. Use it for indexers that only care about recent data, such as a new dApp's script address, and keep the UTXO store and ledger state off.

### Package your listener as a plugin

The same `@DomainEventListener` methods can run inside the packaged Yano node instead of your own application. Implement `org.yanoproject.api.plugin.NodePlugin`, register your listener object in `start()` with `AnnotationListenerRegistrar.register(ctx.eventBus(), listener, SubscriptionOptions.builder().build())`, close the returned handles in `stop()`, and list the class in `META-INF/services/org.yanoproject.api.plugin.NodePlugin`. Plugins see the event bus, configuration and a scheduler, not the query APIs. See [plugin operations](/operate/plugins/) and the [plugin contract](/reference/plugin-contract/).

### Index inside Yano's storage

For an index that must be atomic with the UTXO store, use the UTXO index contributor SPI (`org.yanoproject.api.utxo.index`). Your contributor stages writes into the same RocksDB batch as Yano's UTXO changes and rollbacks. [`examples/utxo-output-index`](https://github.com/bloxbean/yano/tree/main/examples/utxo-output-index) shows a complete contributor. It suits small, hot lookups; keep relational and reporting data in your own database.

### Partial state from an external provider

Bootstrap mode starts a node from a recent block with the UTXOs of selected addresses fetched from Blockfrost or Koios, instead of replaying history (`enableBootstrap`, `bootstrapAddresses`, `bootstrapProvider` in `YanoConfig`; `yano-bootstrap-providers` supplies `BlockfrostBootstrapProvider` and `KoiosBootstrapProvider`). Pass the provider to `YanoAssembly.Builder.bootstrapDataProvider(...)`, and run your options through `YanoAssembly.applyBootstrapPartialStatePolicy(config, options)`, which turns off the derived ledger stores that need full history. Use it for wallets and payment watchers that only need current UTXOs.

### Test against a devnet

Your listener code does not change between networks. For integration tests, run it against a local devnet with funded addresses and time controls: use `YanoDevnetAssembly` from `yano-devnet-toolkit`, as described in [Embed Yano in Java](/develop/embed/), or the managed [Java testkit](/develop/java-testkit/).

## Operating the application

- **Disk.** Yano's chain store grows with the network: plan for the full chain on mainnet. Derived stores add to it. Put `storage-path` on a fast local disk.
- **Shutdown.** Stop the application with `SIGTERM`. Spring stops the node, then closes the database. A `kill -9` is safe for Yano's RocksDB store and is repaired by the catch-up, but it can leave the last H2 writes unflushed.
- **Logs.** Yano logs through SLF4J under `org.yanoproject` and `com.bloxbean.cardano.yaci`. Lower `org.yanoproject` to `WARN` to quieten sync progress.
- **Upgrades.** Keep all `org.yanoproject` artifacts on one version, and read [Upgrading](/reference/upgrading/) before moving an existing chain store to a new release.
- **Native image.** This guide covers the JVM. Building a native image that embeds Yano needs reflection and resource configuration that is not covered here.

## Known limitations

These apply to `0.1.0-pre18`:

- `NodeLifecycle.addNodeEventListener` and `ChainQuery.addBlockChainDataListener` accept listeners but never call them. Use `@DomainEventListener` methods instead.
- Epoch boundary events are published only when account state is enabled.
- Yano does not resolve bundled genesis files for library users; resolve them with `GenesisFileResolver` as shown above.
- After a listener failure, Yano retries the block through peer recovery. If the same failure repeats within one peer session, the retry is not always requested again, and the sync can stay paused at the indexer's last good block until the application is restarted. The index stays consistent; a restart resumes it.

## Coming from yaci-store

| yaci-store | Yano as a library |
| --- | --- |
| Separate Cardano node, connected over node-to-node | Node runs inside your JVM; `remote-host` points at any relay |
| `@EventListener` on Spring events | `@DomainEventListener` on any object, registered with `registerListeners` |
| `BlockHeaderEvent`, `BlockEvent` | `BlockAppliedEvent` (header and body together) |
| `TransactionEvent`, `AuxDataEvent`, `CertificateEvent`, `MintBurnEvent` | Read from `BlockAppliedEvent.block()`: transaction bodies, auxiliary data, certificates, mint |
| `ByronMainBlockEvent`, `ByronEbBlockEvent` | `ByronMainBlockAppliedEvent`, `ByronBlockProjectionEvent` |
| `RollbackEvent` | `RollbackEvent` (keep everything up to `target().getSlot()`) |
| `EpochChangeEvent`, `PreEpochTransitionEvent` | `Pre`/`Post`/`EpochTransitionEvent` (account state on) |
| `GenesisBlockEvent` | `GenesisBlockEvent` |
| `EventMetadata` (slot, block, era, epoch) | Event fields, or `EventContext<E>.metadata()` (slot, block number, hash, origin) |
| Cursor table managed by the store | Your own cursor, written with each block |
| Start point (`store.cardano.sync-start-slot`) | `syncStartSlot` and `syncStartBlockHash` in `YanoConfig` |
| Parallel or batch mode | Not available: blocks are applied one at a time, in order |
| Store modules (utxo, account, governance) | Yano's own stores, enabled with `globals`; queried through `ledger()` |

A yaci-store application can also keep using yaci-store and point it at a Yano node over node-to-node, which keeps the index in a separate process. The library approach removes that process and gives your listeners the node's ledger state directly.
