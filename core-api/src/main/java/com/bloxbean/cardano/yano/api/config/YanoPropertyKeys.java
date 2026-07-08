package com.bloxbean.cardano.yano.api.config;

/**
 * Framework-neutral property names for Yano adapters and runtime globals.
 *
 * <p>These constants are part of the public configuration contract. Quarkus,
 * Spring, Micronaut, plain Java bootstrap code, and optional modules should use
 * these keys instead of repeating string literals.</p>
 */
public final class YanoPropertyKeys {
    public static final String NETWORK = "yano.network";
    public static final String AUTO_SYNC_START = "yano.auto-sync-start";
    public static final String API_PREFIX = "yano.api-prefix";
    public static final String DEV_MODE = "yano.dev-mode";

    private YanoPropertyKeys() {
    }

    /**
     * Remote Cardano node connection settings.
     */
    public static final class Remote {
        public static final String HOST = "yano.remote.host";
        public static final String PORT = "yano.remote.port";
        public static final String PROTOCOL_MAGIC = "yano.remote.protocol-magic";

        private Remote() {
        }
    }

    /**
     * Upstream peer selection, failover, and future multi-peer relay settings.
     */
    public static final class Upstream {
        public static final String MODE = "yano.upstream.mode";
        public static final String PEERS = "yano.upstream.peers";
        public static final String SELECTION_POLICY = "yano.upstream.selection.policy";
        public static final String SELECTION_ROLLBACK_WINDOW_SLOTS =
                "yano.upstream.selection.rollback-window-slots";
        public static final String SELECTION_REQUIRE_BODY_BEFORE_ADOPTION =
                "yano.upstream.selection.require-body-before-adoption";
        public static final String SELECTION_TRUST_POLICY =
                "yano.upstream.selection.trust-policy";
        public static final String SELECTION_QUORUM = "yano.upstream.selection.quorum";
        public static final String SELECTION_TIE_BREAK = "yano.upstream.selection.tie-break";
        public static final String VALIDATION_LEVEL = "yano.upstream.validation.level";
        public static final String VALIDATION_BODY_LEVEL = "yano.upstream.validation.body-level";
        public static final String VALIDATION_OPCERT_COUNTER_MODE =
                "yano.upstream.validation.opcert-counter-mode";
        public static final String VALIDATION_START_MODE = "yano.upstream.validation.start.mode";
        public static final String VALIDATION_START_ERA = "yano.upstream.validation.start.era";
        public static final String VALIDATION_START_SLOT = "yano.upstream.validation.start.slot";
        public static final String VALIDATION_START_HASH = "yano.upstream.validation.start.hash";
        public static final String SYNC_BULK_SOURCE = "yano.upstream.sync.bulk-source";
        public static final String SYNC_FAN_IN_START = "yano.upstream.sync.fan-in-start";
        public static final String FAILOVER_COOLDOWN_MS = "yano.upstream.failover.cooldown-ms";
        public static final String FAILOVER_MAX_FAILURES_BEFORE_COOLDOWN =
                "yano.upstream.failover.max-failures-before-cooldown";
        public static final String TX_FORWARDING = "yano.upstream.tx.forwarding";
        public static final String GOVERNOR_ENABLED = "yano.upstream.governor.enabled";
        public static final String GOVERNOR_TARGET_COLD = "yano.upstream.governor.targets.cold";
        public static final String GOVERNOR_TARGET_WARM = "yano.upstream.governor.targets.warm";
        public static final String GOVERNOR_TARGET_HOT = "yano.upstream.governor.targets.hot";
        public static final String GOVERNOR_MAX_CONCURRENT_DIALS =
                "yano.upstream.governor.max-concurrent-dials";
        public static final String DISCOVERY_ENABLED = "yano.upstream.discovery.enabled";
        public static final String DISCOVERY_PEER_SHARING = "yano.upstream.discovery.peer-sharing";
        public static final String DISCOVERY_SEEDS = "yano.upstream.discovery.seeds";
        public static final String DISCOVERY_PEER_SNAPSHOT_URLS =
                "yano.upstream.discovery.peer-snapshot-urls";
        public static final String DISCOVERY_PEER_SNAPSHOT_FILES =
                "yano.upstream.discovery.peer-snapshot-files";
        public static final String DISCOVERY_PEER_SNAPSHOT_LIMIT =
                "yano.upstream.discovery.peer-snapshot-limit";
        public static final String DISCOVERY_TOPOLOGY_FILE =
                "yano.upstream.discovery.topology-file";
        public static final String DISCOVERY_LEDGER_PEERS =
                "yano.upstream.discovery.ledger-peers";
        public static final String DISCOVERY_USE_LEDGER_AFTER_SLOT =
                "yano.upstream.discovery.use-ledger-after-slot";
        public static final String DISCOVERY_ALLOW_PRIVATE_ADDRESSES =
                "yano.upstream.discovery.allow-private-addresses";
        public static final String DISCOVERY_ALLOWLIST = "yano.upstream.discovery.allowlist";
        public static final String DISCOVERY_DENYLIST = "yano.upstream.discovery.denylist";

        private Upstream() {
        }
    }

    /**
     * Chain-sync client enablement settings.
     */
    public static final class Client {
        public static final String ENABLED = "yano.client.enabled";

        private Client() {
        }
    }

    /**
     * Node-to-node server settings exposed by the runtime.
     */
    public static final class Server {
        public static final String ENABLED = "yano.server.enabled";
        public static final String PORT = "yano.server.port";

        private Server() {
        }
    }

    /**
     * Relay behavior exposed by the node-to-node server.
     */
    public static final class Relay {
        public static final String AUTO_DISCOVERY = "yano.relay.auto-discovery";
        public static final String ADVERTISED_HOST = "yano.relay.advertised-host";
        public static final String ADVERTISED_PORT = "yano.relay.advertised-port";
        public static final String ALLOW_PRIVATE_ADDRESSES = "yano.relay.allow-private-addresses";
        public static final String CONNECTION_MAX_INBOUND_CONNECTIONS =
                "yano.relay.connection.max-inbound-connections";
        public static final String CONNECTION_MAX_CONNECTIONS_PER_IP =
                "yano.relay.connection.max-connections-per-ip";
        public static final String CONNECTION_SOURCE_PORT_REUSE =
                "yano.relay.connection.source-port-reuse";

        private Relay() {
        }
    }

    /**
     * Chain-state storage backend settings.
     */
    public static final class Storage {
        public static final String ROCKSDB = "yano.storage.rocksdb";
        public static final String PATH = "yano.storage.path";

        private Storage() {
        }
    }

    /**
     * Event bus settings used by runtime listeners and plugins.
     */
    public static final class Events {
        public static final String ENABLED = "yaci.events.enabled";

        private Events() {
        }
    }

    /**
     * Plugin discovery and plugin runtime settings.
     */
    public static final class Plugins {
        public static final String ENABLED = "yaci.plugins.enabled";
        public static final String DIRECTORY = "yaci.plugins.directory";
        public static final String LOGGING_ENABLED = "yaci.plugins.logging.enabled";

        private Plugins() {
        }
    }

    /**
     * JVM DNS cache tuning settings used by client mode.
     */
    public static final class Dns {
        public static final String CACHE_TTL = "yano.dns.cache.ttl";
        public static final String CACHE_NEGATIVE_TTL = "yano.dns.cache.negative.ttl";

        private Dns() {
        }
    }

    /**
     * Umbrella rollback-retention settings that derive lower-level windows.
     */
    public static final class RollbackRetention {
        public static final String EPOCHS = "yano.rollback-retention-epochs";

        private RollbackRetention() {
        }
    }

    /**
     * UTXO tracking, indexing, pruning, and rollback settings.
     */
    public static final class Utxo {
        public static final String ENABLED = "yano.utxo.enabled";
        public static final String PRUNE_DEPTH = "yano.utxo.pruneDepth";
        public static final String ROLLBACK_WINDOW = "yano.utxo.rollbackWindow";
        public static final String PRUNE_BATCH_SIZE = "yano.utxo.pruneBatchSize";
        public static final String PRUNE_SCHEDULE_SECONDS = "yano.utxo.prune.schedule.seconds";
        public static final String METRICS_LAG_LOG_SECONDS = "yano.utxo.metrics.lag.logSeconds";
        public static final String LAG_FAIL_IF_ABOVE = "yano.utxo.lag.failIfAbove";
        public static final String INDEX_ADDRESS_HASH = "yano.utxo.index.address_hash";
        public static final String INDEX_PAYMENT_CREDENTIAL = "yano.utxo.index.payment_credential";
        public static final String INDEXING_STRATEGY = "yano.utxo.indexingStrategy";
        public static final String DELTA_SELF_CONTAINED = "yano.utxo.delta.selfContained";
        public static final String APPLY_ASYNC = "yano.utxo.applyAsync";

        private Utxo() {
        }
    }

    /**
     * Runtime metrics and storage sampler settings.
     */
    public static final class Metrics {
        public static final String ENABLED = "yano.metrics.enabled";
        public static final String ROCKSDB_SAMPLE_SECONDS = "yano.metrics.sample.rocksdb.seconds";

        private Metrics() {
        }
    }

    /**
     * Transaction validation listener settings.
     */
    public static final class Validation {
        public static final String DEFAULT_VALIDATOR_ENABLED = "yano.validation.default-validator-enabled";
        public static final String SUPPLEMENTARY_RULES_ENABLED = "yano.validation.supplementary-rules-enabled";

        private Validation() {
        }
    }

    /**
     * Transaction mempool and transaction diffusion settings.
     */
    public static final class Tx {
        public static final String MEMPOOL_MAX_TXS = "yano.tx.mempool.max-txs";
        public static final String MEMPOOL_MAX_BYTES = "yano.tx.mempool.max-bytes";
        public static final String MEMPOOL_TTL_SECONDS = "yano.tx.mempool.ttl-seconds";
        public static final String DIFFUSION_ENABLED = "yano.tx.diffusion.enabled";
        public static final String DIFFUSION_MODE = "yano.tx.diffusion.mode";
        public static final String DIFFUSION_MAX_IN_FLIGHT_TXS_PER_PEER =
                "yano.tx.diffusion.limits.max-in-flight-txs-per-peer";
        public static final String DIFFUSION_MAX_IN_FLIGHT_BYTES_PER_PEER =
                "yano.tx.diffusion.limits.max-in-flight-bytes-per-peer";
        public static final String DIFFUSION_PEER_COOLDOWN_MS =
                "yano.tx.diffusion.limits.peer-cooldown-ms";

        private Tx() {
        }
    }

    /**
     * Account-state ledger index settings.
     */
    public static final class AccountState {
        public static final String ENABLED = "yano.account-state.enabled";
        public static final String EPOCH_BLOCK_DATA_RETENTION_LAG =
                "yano.account-state.epoch-block-data-retention-lag";
        public static final String SNAPSHOT_RETENTION_EPOCHS =
                "yano.account-state.snapshot-retention-epochs";
        public static final String STAKE_BALANCE_INDEX_ENABLED =
                "yano.account.stake-balance-index-enabled";

        private AccountState() {
        }
    }

    /**
     * Account-history index and retention settings.
     */
    public static final class AccountHistory {
        public static final String ENABLED = "yano.account-history.enabled";
        public static final String TX_EVENTS_ENABLED = "yano.account-history.tx-events-enabled";
        public static final String REWARDS_ENABLED = "yano.account-history.rewards-enabled";
        public static final String RETENTION_EPOCHS = "yano.account-history.retention-epochs";
        public static final String PRUNE_INTERVAL_SECONDS =
                "yano.account-history.prune-interval-seconds";
        public static final String PRUNE_BATCH_SIZE = "yano.account-history.prune-batch-size";
        public static final String ROLLBACK_SAFETY_SLOTS =
                "yano.account-history.rollback-safety-slots";

        private AccountHistory() {
        }
    }

    /**
     * Epoch snapshot materialization settings.
     */
    public static final class EpochSnapshot {
        public static final String AMOUNTS_ENABLED = "yano.epoch-snapshot.amounts-enabled";
        public static final String BALANCE_MODE = "yano.epoch-snapshot.balance-mode";

        private EpochSnapshot() {
        }
    }

    /**
     * Derived ledger-state, reward, governance, and protocol-parameter settings.
     */
    public static final class Ledger {
        public static final String ADAPOT_ENABLED = "yano.adapot.enabled";
        public static final String REWARDS_ENABLED = "yano.rewards.enabled";
        public static final String EPOCH_PARAMS_TRACKING_ENABLED =
                "yano.epoch-params.tracking-enabled";
        public static final String GOVERNANCE_ENABLED = "yano.governance.enabled";
        public static final String EXIT_ON_EPOCH_CALC_ERROR =
                "yano.exit-on-epoch-calc-error";
        public static final String AUTO_CHECKPOINT_INTERVAL =
                "yano.auto-checkpoint-interval";

        private Ledger() {
        }
    }

    /**
     * Snapshot export settings for offline diagnostics and comparisons.
     */
    public static final class SnapshotExport {
        public static final String ENABLED = "yano.snapshot-export.enabled";
        public static final String DIR = "yano.snapshot-export.dir";
        public static final String STAKE = "yano.snapshot-export.stake";
        public static final String DREP_DIST = "yano.snapshot-export.drep-dist";
        public static final String ADAPOT = "yano.snapshot-export.adapot";
        public static final String PROPOSALS = "yano.snapshot-export.proposals";

        private SnapshotExport() {
        }
    }

    /**
     * Chain storage recovery and block-body pruning settings.
     */
    public static final class Chain {
        public static final String BLOCK_BODY_PRUNE_DEPTH =
                "yano.chain.block-body-prune-depth";
        public static final String BLOCK_PRUNE_BATCH_SIZE =
                "yano.chain.block-prune-batch-size";
        public static final String BLOCK_PRUNE_INTERVAL_SECONDS =
                "yano.chain.block-prune-interval-seconds";
        public static final String RECOVERY_HEADER_SCAN_BLOCKS =
                "yano.chainstate.recoveryHeaderScanBlocks";

        private Chain() {
        }
    }

    /**
     * UTXO event filtering settings.
     */
    public static final class UtxoFilter {
        public static final String ENABLED = "yano.filters.utxo.enabled";
        public static final String ADDRESSES = "yano.filters.utxo.addresses";
        public static final String PAYMENT_CREDENTIALS =
                "yano.filters.utxo.payment-credentials";

        private UtxoFilter() {
        }
    }

    /**
     * Debug-only startup rollback controls.
     */
    public static final class Debug {
        public static final String ROLLBACK_TO_SLOT = "yano.debug.rollback-to-slot";
        public static final String ROLLBACK_TO_EPOCH = "yano.debug.rollback-to-epoch";

        private Debug() {
        }
    }

    /**
     * Devnet, slot-leader, and past-time-travel block producer settings.
     */
    public static final class BlockProducer {
        public static final String ENABLED = "yano.block-producer.enabled";
        public static final String BLOCK_TIME_MILLIS =
                "yano.block-producer.block-time-millis";
        public static final String LAZY = "yano.block-producer.lazy";
        public static final String GENESIS_TIMESTAMP =
                "yano.block-producer.genesis-timestamp";
        public static final String SLOT_LENGTH_MILLIS =
                "yano.block-producer.slot-length-millis";
        public static final String TX_EVALUATION =
                "yano.block-producer.tx-evaluation";
        public static final String SCRIPT_EVALUATOR =
                "yano.block-producer.script-evaluator";
        public static final String VRF_SKEY_FILE =
                "yano.block-producer.vrf-skey-file";
        public static final String KES_SKEY_FILE =
                "yano.block-producer.kes-skey-file";
        public static final String OPCERT_FILE = "yano.block-producer.opcert-file";
        public static final String SLOT_LEADER_MODE =
                "yano.block-producer.slot-leader-mode";
        public static final String STAKE_DATA_PROVIDER_URL =
                "yano.block-producer.stake-data-provider-url";
        public static final String INITIAL_EPOCH_NONCE =
                "yano.block-producer.initial-epoch-nonce";
        public static final String INITIAL_EPOCH =
                "yano.block-producer.initial-epoch";
        public static final String START_EPOCH = "yano.block-producer.start-epoch";
        public static final String PAST_TIME_TRAVEL_MODE =
                "yano.block-producer.past-time-travel-mode";
        public static final String PAST_TIME_TRAVEL_SLOT_LEADER_MODE =
                "yano.block-producer.past-time-travel-slot-leader-mode";

        private BlockProducer() {
        }
    }

    /**
     * Bootstrap state provider settings.
     */
    public static final class Bootstrap {
        public static final String ENABLED = "yano.bootstrap.enabled";
        public static final String BLOCK_NUMBER = "yano.bootstrap.block-number";
        public static final String PROVIDER = "yano.bootstrap.provider";
        public static final String ADDRESSES = "yano.bootstrap.addresses";
        public static final String UTXOS = "yano.bootstrap.utxos";
        public static final String BLOCKFROST_API_KEY =
                "yano.bootstrap.blockfrost.api-key";
        public static final String BLOCKFROST_BASE_URL =
                "yano.bootstrap.blockfrost.base-url";
        public static final String KOIOS_BASE_URL = "yano.bootstrap.koios.base-url";

        private Bootstrap() {
        }
    }

    /**
     * Genesis and static protocol-parameter file settings.
     */
    public static final class Genesis {
        public static final String SHELLEY_FILE = "yano.genesis.shelley-genesis-file";
        public static final String BYRON_FILE = "yano.genesis.byron-genesis-file";
        public static final String ALONZO_FILE = "yano.genesis.alonzo-genesis-file";
        public static final String CONWAY_FILE = "yano.genesis.conway-genesis-file";
        public static final String SHELLEY_HASH = "yano.genesis.shelley-genesis-hash";
        public static final String PROTOCOL_PARAMETERS_FILE =
                "yano.genesis.protocol-parameters-file";

        private Genesis() {
        }
    }

    /**
     * Header/body pipeline tuning and recovery settings.
     */
    public static final class Pipeline {
        public static final String SLOW_BODY_CALLBACK_WARN_MS =
                "yano.pipeline.slowBodyCallbackWarnMs";
        public static final String NON_RECOVERING_ROLLBACK_WAIT_MS =
                "yano.pipeline.nonRecoveringRollbackWaitMs";
        public static final String HEADER_CONTINUITY_VALIDATION_BLOCKS =
                "yano.pipeline.headerContinuityValidationBlocks";

        private Pipeline() {
        }
    }

    /**
     * Body-fetch timing and fallback settings.
     */
    public static final class BodyFetch {
        public static final String SLOW_EPOCH_TRANSITION_WARN_MS =
                "yano.bodyFetch.slowEpochTransitionWarnMs";
        public static final String REALTIME_FALLBACK_POLL_MS =
                "yano.bodyFetch.realtimeFallbackPollMs";

        private BodyFetch() {
        }
    }

    /**
     * Header-applied event publication settings.
     */
    public static final class HeaderAppliedEvent {
        public static final String QUEUE_CAPACITY =
                "yano.headerAppliedEvent.queueCapacity";

        private HeaderAppliedEvent() {
        }
    }

    /**
     * RocksDB write and tuning settings.
     */
    public static final class RocksDb {
        public static final String PIPELINED_WRITE = "yano.rocksdb.pipelined_write";
        public static final String ATOMIC_FLUSH = "yano.rocksdb.atomic_flush";
        public static final String TUNING_ENABLED = "yano.rocksdb.tuning.enabled";

        private RocksDb() {
        }
    }

    /**
     * App-chain (parallel application ledger over the appmsg protocol) settings.
     * See adr/app-layer/005-yano-app-chain-framework.md.
     */
    public static final class AppChain {
        public static final String ENABLED = "yano.app-chain.enabled";
        public static final String CHAIN_ID = "yano.app-chain.chain-id";
        /** This member's Ed25519 private key (hex, 32-byte seed). Required when enabled. */
        public static final String SIGNING_KEY = "yano.app-chain.signing-key";
        /** Comma-separated hex Ed25519 public keys of group members. */
        public static final String MEMBERS = "yano.app-chain.members";
        /** Comma-separated app-group peers as host:port. */
        public static final String PEERS = "yano.app-chain.peers";
        public static final String MAX_MESSAGE_BYTES = "yano.app-chain.max-message-bytes";
        public static final String MAX_TTL_SECONDS = "yano.app-chain.max-ttl-seconds";
        public static final String DEFAULT_TTL_SECONDS = "yano.app-chain.default-ttl-seconds";
        /** Fixed sequencer's Ed25519 public key (hex). Empty = diffusion-only (no ledger). */
        public static final String SEQUENCER_PROPOSER = "yano.app-chain.sequencer.proposer";
        /** Finality certificate signature threshold (n of members). */
        public static final String THRESHOLD = "yano.app-chain.threshold";
        public static final String BLOCK_INTERVAL_MS = "yano.app-chain.block.interval-ms";
        public static final String BLOCK_MAX_MESSAGES = "yano.app-chain.block.max-messages";
        /** Built-in state machine id; default "ordered-log". */
        public static final String STATE_MACHINE = "yano.app-chain.state-machine";
        public static final String ANCHOR_ENABLED = "yano.app-chain.anchor.enabled";
        /** Anchor wallet Ed25519 payment key (hex, 32-byte seed). */
        public static final String ANCHOR_SIGNING_KEY = "yano.app-chain.anchor.signing-key";
        public static final String ANCHOR_EVERY_BLOCKS = "yano.app-chain.anchor.every-blocks";
        public static final String ANCHOR_MAX_INTERVAL_MINUTES = "yano.app-chain.anchor.max-interval-minutes";
        public static final String ANCHOR_METADATA_LABEL = "yano.app-chain.anchor.metadata-label";
        /** Depth (L1 blocks) of the stable L1 reference in app blocks; 0 = disabled. */
        public static final String L1_STABILITY_DEPTH = "yano.app-chain.l1.stability-depth";

        private AppChain() {
        }
    }
}
