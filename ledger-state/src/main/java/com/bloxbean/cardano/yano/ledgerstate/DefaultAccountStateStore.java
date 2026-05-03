package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yano.api.era.EraProvider;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.DrepVoteThresholds;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.PoolVotingThresholds;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.certs.*;
import com.bloxbean.cardano.yaci.core.model.governance.Drep;
import com.bloxbean.cardano.yaci.core.model.governance.DrepType;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.model.CredentialKey;
import com.bloxbean.cardano.yano.api.account.AccountStateReadStore;
import com.bloxbean.cardano.yano.api.account.AccountStateStore;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.GenesisBlockEvent;
import com.bloxbean.cardano.yano.api.events.RollbackEvent;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import org.rocksdb.*;
import org.slf4j.Logger;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * RocksDB-backed account state store.
 * Tracks stake registration, delegation, DRep delegation, pool registration/retirement,
 * and reward balances with delta-journal rollback support.
 */
public class DefaultAccountStateStore implements AccountStateStore, AccountStateReadStore,
        com.bloxbean.cardano.yano.api.rollback.RollbackCapableStore {

    // Key prefixes
    public static final byte PREFIX_ACCT = 0x01;
    public static final byte PREFIX_POOL_DELEG = 0x02;
    public static final byte PREFIX_DREP_DELEG = 0x03;
    static final byte PREFIX_DREP_DELEG_REVERSE = 0x04; // DRep → delegators reverse index (PV9 stale, rebuilt at PV10)
    static final byte PREFIX_POOL_DEPOSIT = 0x10;
    static final byte PREFIX_POOL_RETIRE = 0x11;
    static final byte PREFIX_DREP_REG = 0x20;
    static final byte PREFIX_COMMITTEE_HOT = 0x30;
    static final byte PREFIX_COMMITTEE_RESIGN = 0x31;
    static final byte PREFIX_MIR_REWARD = 0x40;

    static final byte PREFIX_POOL_PARAMS_HIST = 0x12; // pool params history: poolHash + epoch → PoolRegistrationData
    static final byte PREFIX_POOL_REG_SLOT = 0x13;   // pool registration slot: poolHash → slot (long BE)
    static final byte PREFIX_ACCT_REG_SLOT = 0x14;   // stake account registration slot: credType + credHash → slot (long BE)
    static final byte PREFIX_POINTER_ADDR = 0x15;    // pointer address: slot(8) + txIdx(4) + certIdx(4) → credType(1) + credHash(28)

    // Epoch-scoped prefixes for reward calculation
    static final byte PREFIX_POOL_BLOCK_COUNT = 0x50;
    static final byte PREFIX_EPOCH_FEES = 0x51;
    static final byte PREFIX_ADAPOT = 0x52;
    // 0x53 used by epoch_params CF
    static final byte PREFIX_ACCUMULATED_REWARD = 0x54;
    static final byte PREFIX_STAKE_EVENT = 0x55;
    public static final byte PREFIX_REWARD_REST = 0x56;
    static final byte PREFIX_BLOCK_ISSUER = 0x58;      // per-block issuer: [epoch(4)][slot(8)] → poolHash(28)
    static final byte PREFIX_BLOCK_FEE = 0x59;          // per-block fee: [epoch(4)][slot(8)] → fee (CBOR BigInteger)

    /** Default epochs to retain per-block data after reward calculation consumes it.
     *  Data for epoch E is consumed at E+2 boundary. With lag=5, cleared at E+7 boundary. */
    static final int DEFAULT_EPOCH_BLOCK_DATA_RETENTION_LAG = 5;
    static final int DEFAULT_SNAPSHOT_RETENTION_EPOCHS = 50;

    // Reward rest type constants (ordered by era of introduction)
    // Pool deposit refunds are NOT here — they go directly to account reward balance (regular reward)
    public static final byte REWARD_REST_MIR_RESERVES = 0;        // Shelley era — MIR from reserves pot
    public static final byte REWARD_REST_MIR_TREASURY = 1;        // Shelley era — MIR from treasury pot
    public static final byte REWARD_REST_PROPOSAL_REFUND = 2;     // Conway era — governance proposal deposit refunds
    public static final byte REWARD_REST_TREASURY_WITHDRAWAL = 3; // Conway era — enacted treasury withdrawals

    // MIR pot transfer metadata keys
    private static final byte[] META_MIR_TO_RESERVES = "mir.to_reserves".getBytes(StandardCharsets.UTF_8);
    private static final byte[] META_MIR_TO_TREASURY = "mir.to_treasury".getBytes(StandardCharsets.UTF_8);

    // Metadata keys
    private static final byte[] META_TOTAL_DEPOSITED = "total_dep".getBytes(StandardCharsets.UTF_8);
    private static final byte[] META_LAST_APPLIED_BLOCK = "meta.last_block".getBytes(StandardCharsets.UTF_8);
    private static final byte[] META_LAST_SNAPSHOT_EPOCH = "meta.last_snapshot_epoch".getBytes(StandardCharsets.UTF_8);
    // Epoch boundary completion tracking — stores the last completed step for crash recovery.
    // Format: 8 bytes (epoch as int, step as int). Steps: 0=started, 1=rewards, 2=snapshot, 3=poolreap, 4=governance, 5=complete
    private static final byte[] META_BOUNDARY_STEP = "meta.boundary_step".getBytes(StandardCharsets.UTF_8);
    private static final byte[] MARKER_PV10_REVERSE_REBUILD = "meta.pv10_drep_reverse_rebuild".getBytes(StandardCharsets.UTF_8);
    private static final byte[] META_LAST_APPLIED_SLOT = "meta.last_applied_slot".getBytes(StandardCharsets.UTF_8);
    // Configurable retention — instance fields read from config, defaults match previous constants.
    // Retain snapshots for enough epochs so the background epoch boundary processor can read them.
    // During fast sync, the main thread creates snapshots and prunes old ones rapidly while
    // the background thread processes epoch boundaries sequentially. With a queue depth of N,
    // we need at least N + 3 (snapshotKey = epoch - 3) epochs of retention.
    private final int epochBlockDataRetentionLag;
    private final int snapshotRetentionEpochs;

    // Delta op types
    public static final byte OP_PUT = 0x01;
    public static final byte OP_DELETE = 0x02;

    private final Logger log;
    private final boolean enabled;
    private final EpochParamProvider epochParamProvider;

    private RocksDB db;
    private ColumnFamilyHandle cfState;
    private ColumnFamilyHandle cfDelta;
    private ColumnFamilyHandle cfBoundaryDelta;
    private ColumnFamilyHandle cfEpochSnapshot;

    // Boundary delta phase constants — identify which epoch-boundary sub-step produced a delta entry
    public static final byte PHASE_REWARDS = 1;
    public static final byte PHASE_MIR = 2;
    public static final byte PHASE_SPENDABLE_REST = 3;
    public static final byte PHASE_GOV_ENACT = 4;
    public static final byte PHASE_GOV_RATIFY = 5;
    public static final byte PHASE_POOLREAP = 6;

    // Reverse execution order for boundary phases — used by rollbackInternal()
    // to undo same-slot phases correctly regardless of phase-byte numbering.
    // Execution order: MIR → REWARDS → POOLREAP → GOV_ENACT → GOV_RATIFY → SPENDABLE_REST
    private static final byte[] BOUNDARY_PHASE_REVERSE_ORDER = {
        PHASE_SPENDABLE_REST,
        PHASE_GOV_RATIFY,
        PHASE_GOV_ENACT,
        PHASE_POOLREAP,
        PHASE_REWARDS,
        PHASE_MIR
    };

    /**
     * Per-batch overlay of in-flight cfState values.
     * Needed because RocksDB db.get() does not see pending WriteBatch mutations.
     */
    static final class BatchStateOverlay {
        private final Map<ByteArrayKey, byte[]> values = new HashMap<>();

        boolean contains(byte[] key) {
            return values.containsKey(new ByteArrayKey(key));
        }

        byte[] get(byte[] key) {
            return values.get(new ByteArrayKey(key));
        }

        void put(byte[] key, byte[] value) {
            values.put(new ByteArrayKey(Arrays.copyOf(key, key.length)), value);
        }

        void clear() {
            values.clear();
        }
    }

    private static final class ByteArrayKey {
        private final byte[] bytes;

        private ByteArrayKey(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ByteArrayKey that)) return false;
            return Arrays.equals(bytes, that.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }
    }

    // Optional epoch reward subsystems (null = disabled)
    private volatile EpochStakeSnapshotService stakeSnapshotService;
    private volatile AdaPotTracker adaPotTracker;
    private volatile EpochRewardCalculator rewardCalculator;
    private volatile EpochParamTracker paramTracker;
    private volatile EpochBoundaryProcessor epochBoundaryProcessor;
    private volatile com.bloxbean.cardano.yano.api.utxo.UtxoState utxoState;
    private volatile long networkMagic;
    private PointerAddressResolver pointerAddressResolver; // initialized after RocksDB opens

    // Era provider for Conway detection — uses era metadata instead of protocolMajor >= 9.
    private volatile EraProvider eraProvider;

    // Per-block WriteBatch visibility overlay for DRep delegation reverse index.
    // RocksDB db.get()/newIterator() only see committed state, not pending batch writes.
    // These maps track in-flight changes so same-block delegation+deregistration is handled correctly.
    // Initialized in applyBlock(), cleared after db.write(). Single-threaded block processing.
    private HashMap<String, byte[]> batchForwardDeleg;          // "ct:hash" -> encoded delegation (null=deleted)
    private HashMap<String, HashSet<String>> batchReverseAdded;    // "drepType:drepHash" -> set of "ct:hash"
    private HashMap<String, HashSet<String>> batchReverseRemoved;  // "drepType:drepHash" -> set of "ct:hash"

    // Optional epoch snapshot exporter for debugging (NOOP when disabled)
    private com.bloxbean.cardano.yano.ledgerstate.export.EpochSnapshotExporter snapshotExporter =
            com.bloxbean.cardano.yano.ledgerstate.export.EpochSnapshotExporter.NOOP;

    // Optional governance subsystem
    private volatile com.bloxbean.cardano.yano.ledgerstate.governance.GovernanceBlockProcessor governanceBlockProcessor;
    private volatile DefaultAccountStateReadStore readStore;


    // Supplier for re-initialization after snapshot restore
    private final CfSupplier cfSupplier;

    @FunctionalInterface
    public interface CfSupplier {
        ColumnFamilyHandle handle(String name);

        default RocksDB db() { return null; }
    }

    private static final EpochParamProvider ZERO_PROVIDER = new EpochParamProvider() {
        @Override public BigInteger getKeyDeposit(long epoch) { return BigInteger.ZERO; }
        @Override public BigInteger getPoolDeposit(long epoch) { return BigInteger.ZERO; }
    };

    public DefaultAccountStateStore(RocksDB db, CfSupplier supplier, Logger log, boolean enabled) {
        this(db, supplier, log, enabled, ZERO_PROVIDER, null);
    }

    public DefaultAccountStateStore(RocksDB db, CfSupplier supplier, Logger log, boolean enabled,
                                    EpochParamProvider epochParamProvider) {
        this(db, supplier, log, enabled, epochParamProvider, null);
    }

    public DefaultAccountStateStore(RocksDB db, CfSupplier supplier, Logger log, boolean enabled,
                                    EpochParamProvider epochParamProvider, Map<String, Object> config) {
        this.db = db;
        this.cfSupplier = supplier;
        this.log = log;
        this.enabled = enabled;
        this.epochParamProvider = epochParamProvider != null ? epochParamProvider : ZERO_PROVIDER;
        this.cfState = supplier.handle(AccountStateCfNames.ACCT_STATE);
        this.cfDelta = supplier.handle(AccountStateCfNames.ACCT_DELTA);
        this.cfBoundaryDelta = supplier.handle(AccountStateCfNames.ACCT_BOUNDARY_DELTA);
        this.cfEpochSnapshot = supplier.handle(AccountStateCfNames.EPOCH_DELEG_SNAPSHOT);
        this.pointerAddressResolver = new PointerAddressResolver(db, cfState);
        this.readStore = new DefaultAccountStateReadStore(db, cfEpochSnapshot, () -> governanceBlockProcessor, log);
        this.epochBlockDataRetentionLag = getInt(config,
                "yano.account-state.epoch-block-data-retention-lag", DEFAULT_EPOCH_BLOCK_DATA_RETENTION_LAG);
        this.snapshotRetentionEpochs = getInt(config,
                "yano.account-state.snapshot-retention-epochs", DEFAULT_SNAPSHOT_RETENTION_EPOCHS);
    }

    // --- Optional subsystem wiring ---

    /**
     * Set the UtxoState reference for UTXO balance aggregation at epoch boundary.
     * Must be called before epoch snapshots with amounts are needed.
     */
    public void setUtxoState(com.bloxbean.cardano.yano.api.utxo.UtxoState utxoState) {
        this.utxoState = utxoState;
    }

    /**
     * Set the epoch stake snapshot service for UTXO balance aggregation.
     */
    public void setStakeSnapshotService(EpochStakeSnapshotService service) {
        this.stakeSnapshotService = service;
    }

    /**
     * Set the balance aggregation mode: "full-scan" (default) or "incremental".
     */
    public void setBalanceMode(String mode) {
        this.balanceMode = mode;
    }
    private String balanceMode = "full-scan";

    /**
     * Set the AdaPot tracker for treasury/reserves tracking.
     */
    public void setAdaPotTracker(AdaPotTracker tracker) {
        this.adaPotTracker = tracker;
    }

    /**
     * Set the reward calculator for epoch reward distribution.
     */
    public void setRewardCalculator(EpochRewardCalculator calculator) {
        this.rewardCalculator = calculator;
    }

    /**
     * Get the AdaPot tracker (for external use, e.g., bootstrapping).
     */
    public AdaPotTracker getAdaPotTracker() {
        return adaPotTracker;
    }

    /**
     * Get the reward calculator (for external querying).
     */
    public EpochRewardCalculator getRewardCalculator() {
        return rewardCalculator;
    }

    /**
     * Set the protocol parameter tracker.
     */
    public void setParamTracker(EpochParamTracker tracker) {
        this.paramTracker = tracker;
    }

    public EpochParamTracker getParamTracker() {
        return paramTracker;
    }

    /**
     * Set the epoch boundary processor for coordinating epoch transitions.
     */
    public void setEpochBoundaryProcessor(EpochBoundaryProcessor processor) {
        this.epochBoundaryProcessor = processor;
    }

    public void setSnapshotExporter(com.bloxbean.cardano.yano.ledgerstate.export.EpochSnapshotExporter exporter) {
        this.snapshotExporter = exporter != null ? exporter
                : com.bloxbean.cardano.yano.ledgerstate.export.EpochSnapshotExporter.NOOP;
    }

    /**
     * Set the network magic (needed for reward calculation).
     */
    public void setNetworkMagic(long networkMagic) {
        this.networkMagic = networkMagic;
    }

    public long getNetworkMagic() {
        return networkMagic;
    }

    /**
     * Set the era provider for Conway-era detection.
     */
    public void setEraProvider(EraProvider eraProvider) {
        this.eraProvider = eraProvider;
    }

    /**
     * Check if the given epoch is in the Conway era or later.
     */
    boolean isConwayOrLater(int epoch) {
        return eraProvider != null && eraProvider.isConwayOrLater(epoch);
    }

    public EpochParamProvider getEpochParamProvider() {
        return epochParamProvider;
    }

    /**
     * Set the governance block processor for Conway-era governance state tracking.
     */
    public void setGovernanceBlockProcessor(
            com.bloxbean.cardano.yano.ledgerstate.governance.GovernanceBlockProcessor processor) {
        this.governanceBlockProcessor = processor;
    }

    public com.bloxbean.cardano.yano.ledgerstate.governance.GovernanceBlockProcessor getGovernanceBlockProcessor() {
        return governanceBlockProcessor;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void reinitialize() {
        if (cfSupplier.db() != null) {
            this.db = cfSupplier.db();
        }
        this.cfState = cfSupplier.handle(AccountStateCfNames.ACCT_STATE);
        this.cfDelta = cfSupplier.handle(AccountStateCfNames.ACCT_DELTA);
        this.cfBoundaryDelta = cfSupplier.handle(AccountStateCfNames.ACCT_BOUNDARY_DELTA);
        this.cfEpochSnapshot = cfSupplier.handle(AccountStateCfNames.EPOCH_DELEG_SNAPSHOT);

        if (adaPotTracker != null) {
            adaPotTracker.reinitialize(db, cfState);
        }
        if (paramTracker != null) {
            paramTracker.reinitialize(db, cfSupplier.handle(AccountStateCfNames.EPOCH_PARAMS));
        }
        if (rewardCalculator != null) {
            rewardCalculator.reinitialize(db, cfState, cfEpochSnapshot);
        }
        if (governanceBlockProcessor != null) {
            governanceBlockProcessor.reinitialize(db, cfState);
        }
        if (epochBoundaryProcessor != null) {
            epochBoundaryProcessor.reinitializeAfterSnapshotRestore(db, cfState, cfDelta, cfEpochSnapshot);
        }

        this.pointerAddressResolver = new PointerAddressResolver(db, cfState);
        this.readStore = new DefaultAccountStateReadStore(db, cfEpochSnapshot, () -> governanceBlockProcessor, log);
        log.info("DefaultAccountStateStore reinitialized after snapshot restore");
    }

    /**
     * One-time migration: populate PREFIX_ACCT_REG_SLOT from existing PREFIX_STAKE_EVENT entries.
     * This enables stale delegation detection in snapshots for credentials that were registered
     * before this feature was added. Idempotent — safe to run on every startup.
     */
    public void migrateAcctRegSlots() {
        if (!enabled) return;
        int written = 0;
        // Track latest registration slot per credential from stake events
        java.util.Map<String, long[]> latestRegSlots = new java.util.HashMap<>(); // credKey → [slot]
        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(new byte[]{PREFIX_STAKE_EVENT});
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 14 || key[0] != PREFIX_STAKE_EVENT) break;
                int eventType = AccountStateCborCodec.decodeStakeEvent(it.value());
                if (eventType == AccountStateCborCodec.EVENT_REGISTRATION) {
                    long evSlot = ByteBuffer.wrap(key, 1, 8).order(ByteOrder.BIG_ENDIAN).getLong();
                    int evCredType = key[13] & 0xFF;
                    String evCredHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 14, key.length));
                    String credKey = evCredType + ":" + evCredHash;
                    long[] existing = latestRegSlots.get(credKey);
                    if (existing == null || evSlot > existing[0]) {
                        latestRegSlots.put(credKey, new long[]{evSlot});
                    }
                }
                it.next();
            }
        }
        if (latestRegSlots.isEmpty()) return;

        // Write PREFIX_ACCT_REG_SLOT for each, only if not already set or if newer
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            for (var entry : latestRegSlots.entrySet()) {
                String credKey = entry.getKey();
                long regSlot = entry.getValue()[0];
                int colonIdx = credKey.indexOf(':');
                int credType = Integer.parseInt(credKey.substring(0, colonIdx));
                String credHash = credKey.substring(colonIdx + 1);
                byte[] slotKey = acctRegSlotKey(credType, credHash);
                byte[] existing = db.get(cfState, slotKey);
                if (existing != null) {
                    long existingSlot = ByteBuffer.wrap(existing).order(ByteOrder.BIG_ENDIAN).getLong();
                    if (existingSlot >= regSlot) continue; // Already up to date
                }
                batch.put(cfState, slotKey, ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(regSlot).array());
                written++;
            }
            if (written > 0) {
                db.write(wo, batch);
                log.info("Migrated {} PREFIX_ACCT_REG_SLOT entries from stake events", written);
            }
        } catch (Exception e) {
            log.error("migrateAcctRegSlots failed: {}", e.toString());
        }
    }

    // --- Key builders ---

    static byte[] accountKey(int credType, String credHash) {
        byte[] hash = HexUtil.decodeHexString(credHash);
        byte[] key = new byte[1 + 1 + hash.length];
        key[0] = PREFIX_ACCT;
        key[1] = (byte) credType;
        System.arraycopy(hash, 0, key, 2, hash.length);
        return key;
    }

    static byte[] poolDelegKey(int credType, String credHash) {
        byte[] hash = HexUtil.decodeHexString(credHash);
        byte[] key = new byte[1 + 1 + hash.length];
        key[0] = PREFIX_POOL_DELEG;
        key[1] = (byte) credType;
        System.arraycopy(hash, 0, key, 2, hash.length);
        return key;
    }

    static byte[] drepDelegKey(int credType, String credHash) {
        byte[] hash = HexUtil.decodeHexString(credHash);
        byte[] key = new byte[1 + 1 + hash.length];
        key[0] = PREFIX_DREP_DELEG;
        key[1] = (byte) credType;
        System.arraycopy(hash, 0, key, 2, hash.length);
        return key;
    }

    /**
     * Reverse index key: DRep credential → delegator credential.
     * Key: [PREFIX_DREP_DELEG_REVERSE | drepType(1) | drepHash(28) | delegatorCredType(1) | delegatorHash(28)]
     * <p>
     * Maintained by {@link #delegateToDRep} and {@link #deregisterStake}. Used by
     * {@link #clearDRepDelegationsForDeregisteredDRep} on DRep deregistration.
     * <p>
     * PV9: stale entries preserved (re-delegated creds not removed from old DRep's set).
     * PV10: rebuilt at hardfork boundary by {@link #rebuildDRepDelegReverseIndexIfNeeded}
     * to match Haskell's {@code updateDRepDelegations} (HardFork.hs).
     */
    static byte[] drepDelegReverseKey(int drepType, String drepHash,
                                       int delegatorCredType, String delegatorHash) {
        byte[] dHash = HexUtil.decodeHexString(drepHash);
        byte[] delHash = HexUtil.decodeHexString(delegatorHash);
        byte[] key = new byte[1 + 1 + dHash.length + 1 + delHash.length];
        key[0] = PREFIX_DREP_DELEG_REVERSE;
        key[1] = (byte) drepType;
        System.arraycopy(dHash, 0, key, 2, dHash.length);
        key[2 + dHash.length] = (byte) delegatorCredType;
        System.arraycopy(delHash, 0, key, 3 + dHash.length, delHash.length);
        return key;
    }

    /** Seek prefix for iterating all delegators of a specific DRep in the reverse index. */
    static byte[] drepDelegReverseSeekPrefix(int drepType, String drepHash) {
        byte[] dHash = HexUtil.decodeHexString(drepHash);
        byte[] prefix = new byte[1 + 1 + dHash.length];
        prefix[0] = PREFIX_DREP_DELEG_REVERSE;
        prefix[1] = (byte) drepType;
        System.arraycopy(dHash, 0, prefix, 2, dHash.length);
        return prefix;
    }

    /** Returns true for credential DReps (ADDR_KEYHASH=0, SCRIPTHASH=1), false for predefined (ABSTAIN, NO_CONFIDENCE). */
    private static boolean isCredentialDRep(int drepType) {
        return drepType == 0 || drepType == 1;
    }

    static byte[] poolDepositKey(String poolHash) {
        byte[] hash = HexUtil.decodeHexString(poolHash);
        byte[] key = new byte[1 + hash.length];
        key[0] = PREFIX_POOL_DEPOSIT;
        System.arraycopy(hash, 0, key, 1, hash.length);
        return key;
    }

    static byte[] poolRegSlotKey(String poolHash) {
        byte[] hash = HexUtil.decodeHexString(poolHash);
        byte[] key = new byte[1 + hash.length];
        key[0] = PREFIX_POOL_REG_SLOT;
        System.arraycopy(hash, 0, key, 1, hash.length);
        return key;
    }

    static byte[] poolRetireKey(String poolHash) {
        byte[] hash = HexUtil.decodeHexString(poolHash);
        byte[] key = new byte[1 + hash.length];
        key[0] = PREFIX_POOL_RETIRE;
        System.arraycopy(hash, 0, key, 1, hash.length);
        return key;
    }

    /**
     * Key for pool params history: PREFIX_POOL_PARAMS_HIST + poolHash(28) + epoch(4 BE).
     * Ordered so that seekForPrev can find the latest entry for a pool with epoch <= target.
     */
    static byte[] poolParamsHistKey(String poolHash, int epoch) {
        byte[] hash = HexUtil.decodeHexString(poolHash);
        byte[] key = new byte[1 + hash.length + 4];
        key[0] = PREFIX_POOL_PARAMS_HIST;
        System.arraycopy(hash, 0, key, 1, hash.length);
        ByteBuffer.wrap(key, 1 + hash.length, 4).order(ByteOrder.BIG_ENDIAN).putInt(epoch);
        return key;
    }

    static byte[] drepRegKey(int credType, String credHash) {
        byte[] hash = HexUtil.decodeHexString(credHash);
        byte[] key = new byte[1 + 1 + hash.length];
        key[0] = PREFIX_DREP_REG;
        key[1] = (byte) credType;
        System.arraycopy(hash, 0, key, 2, hash.length);
        return key;
    }

    static byte[] committeeHotKey(int credType, String coldHash) {
        byte[] hash = HexUtil.decodeHexString(coldHash);
        byte[] key = new byte[1 + 1 + hash.length];
        key[0] = PREFIX_COMMITTEE_HOT;
        key[1] = (byte) credType;
        System.arraycopy(hash, 0, key, 2, hash.length);
        return key;
    }

    static byte[] committeeResignKey(int credType, String coldHash) {
        byte[] hash = HexUtil.decodeHexString(coldHash);
        byte[] key = new byte[1 + 1 + hash.length];
        key[0] = PREFIX_COMMITTEE_RESIGN;
        key[1] = (byte) credType;
        System.arraycopy(hash, 0, key, 2, hash.length);
        return key;
    }

    static byte[] mirRewardKey(int credType, String credHash) {
        byte[] hash = HexUtil.decodeHexString(credHash);
        byte[] key = new byte[1 + 1 + hash.length];
        key[0] = PREFIX_MIR_REWARD;
        key[1] = (byte) credType;
        System.arraycopy(hash, 0, key, 2, hash.length);
        return key;
    }


    // --- Key builders for epoch-scoped data ---

    static byte[] poolBlockCountKey(int epoch, String poolHash) {
        byte[] hash = HexUtil.decodeHexString(poolHash);
        byte[] key = new byte[1 + 4 + hash.length];
        key[0] = PREFIX_POOL_BLOCK_COUNT;
        ByteBuffer.wrap(key, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(epoch);
        System.arraycopy(hash, 0, key, 5, hash.length);
        return key;
    }

    static byte[] blockIssuerKey(int epoch, long slot) {
        byte[] key = new byte[1 + 4 + 8];
        key[0] = PREFIX_BLOCK_ISSUER;
        ByteBuffer.wrap(key, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(epoch);
        ByteBuffer.wrap(key, 5, 8).order(ByteOrder.BIG_ENDIAN).putLong(slot);
        return key;
    }

    static byte[] blockFeeKey(int epoch, long slot) {
        byte[] key = new byte[1 + 4 + 8];
        key[0] = PREFIX_BLOCK_FEE;
        ByteBuffer.wrap(key, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(epoch);
        ByteBuffer.wrap(key, 5, 8).order(ByteOrder.BIG_ENDIAN).putLong(slot);
        return key;
    }

    static byte[] epochFeesKey(int epoch) {
        byte[] key = new byte[1 + 4];
        key[0] = PREFIX_EPOCH_FEES;
        ByteBuffer.wrap(key, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(epoch);
        return key;
    }

    static byte[] adaPotKey(int epoch) {
        byte[] key = new byte[1 + 4];
        key[0] = PREFIX_ADAPOT;
        ByteBuffer.wrap(key, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(epoch);
        return key;
    }

    static byte[] accumulatedRewardKey(int credType, String credHash) {
        byte[] hash = HexUtil.decodeHexString(credHash);
        byte[] key = new byte[1 + 1 + hash.length];
        key[0] = PREFIX_ACCUMULATED_REWARD;
        key[1] = (byte) credType;
        System.arraycopy(hash, 0, key, 2, hash.length);
        return key;
    }

    /**
     * Build stake event key: [0x55][slot(8 BE)][txIdx(2 BE)][certIdx(2 BE)][credType(1)][credHash(28)]
     * Slot-first ordering enables efficient range scans for "all events in slot range".
     */
    static byte[] stakeEventKey(long slot, int txIdx, int certIdx, int credType, String credHash) {
        byte[] hash = HexUtil.decodeHexString(credHash);
        // 1 prefix + 8 slot + 2 txIdx + 2 certIdx + 1 credType + 28 hash = 42
        byte[] key = new byte[1 + 8 + 2 + 2 + 1 + hash.length];
        key[0] = PREFIX_STAKE_EVENT;
        ByteBuffer.wrap(key, 1, 8).order(ByteOrder.BIG_ENDIAN).putLong(slot);
        ByteBuffer.wrap(key, 9, 2).order(ByteOrder.BIG_ENDIAN).putShort((short) txIdx);
        ByteBuffer.wrap(key, 11, 2).order(ByteOrder.BIG_ENDIAN).putShort((short) certIdx);
        key[13] = (byte) credType;
        System.arraycopy(hash, 0, key, 14, hash.length);
        return key;
    }

    private static int credTypeFromModel(com.bloxbean.cardano.yaci.core.model.Credential cred) {
        return cred.getType() == StakeCredType.ADDR_KEYHASH ? 0 : 1;
    }

    private static int credTypeInt(StakeCredType t) {
        return t == StakeCredType.ADDR_KEYHASH ? 0 : 1;
    }

    private static int drepTypeInt(DrepType t) {
        return switch (t) {
            case ADDR_KEYHASH -> 0;
            case SCRIPTHASH -> 1;
            case ABSTAIN -> 2;
            case NO_CONFIDENCE -> 3;
        };
    }

    private int getProtocolMajor(int epoch) {
        if (paramTracker != null && paramTracker.isEnabled()) {
            return paramTracker.getProtocolMajor(epoch);
        }
        return epochParamProvider.getProtocolMajor(epoch);
    }

    private int epochForSlot(long slot) {
        return epochParamProvider.getEpochSlotCalc().slotToEpoch(slot);
    }

    public long slotForEpochStart(int epoch) {
        return epochParamProvider.getEpochSlotCalc().epochToStartSlot(epoch);
    }

    private int getLastSnapshotEpoch() {
        try {
            byte[] val = db.get(cfState, META_LAST_SNAPSHOT_EPOCH);
            if (val != null && val.length == 4) {
                return java.nio.ByteBuffer.wrap(val).order(java.nio.ByteOrder.BIG_ENDIAN).getInt();
            }
        } catch (RocksDBException ignored) {}
        return -1;
    }

    // --- LedgerStateProvider reads ---

    @Override
    public Optional<BigInteger> getRewardBalance(int credType, String credentialHash) {
        try {
            byte[] val = db.get(cfState, accountKey(credType, credentialHash));
            if (val == null) return Optional.empty();
            return Optional.of(AccountStateCborCodec.decodeStakeAccount(val).reward());
        } catch (RocksDBException e) {
            throw ledgerStateReadFailure("getRewardBalance", e);
        }
    }

    @Override
    public Optional<BigInteger> getStakeDeposit(int credType, String credentialHash) {
        try {
            byte[] val = db.get(cfState, accountKey(credType, credentialHash));
            if (val == null) return Optional.empty();
            return Optional.of(AccountStateCborCodec.decodeStakeAccount(val).deposit());
        } catch (RocksDBException e) {
            throw ledgerStateReadFailure("getStakeDeposit", e);
        }
    }

    @Override
    public Optional<String> getDelegatedPool(int credType, String credentialHash) {
        return getPoolDelegation(credType, credentialHash)
                .map(LedgerStateProvider.PoolDelegation::poolHash);
    }

    @Override
    public Optional<LedgerStateProvider.PoolDelegation> getPoolDelegation(int credType, String credentialHash) {
        try {
            byte[] val = db.get(cfState, poolDelegKey(credType, credentialHash));
            if (val == null) return Optional.empty();
            var rec = AccountStateCborCodec.decodePoolDelegation(val);
            return Optional.of(new LedgerStateProvider.PoolDelegation(
                    rec.poolHash(), rec.slot(), rec.txIdx(), rec.certIdx()));
        } catch (RocksDBException e) {
            throw ledgerStateReadFailure("getPoolDelegation", e);
        }
    }

    @Override
    public Optional<LedgerStateProvider.DRepDelegation> getDRepDelegation(int credType, String credentialHash) {
        try {
            byte[] val = db.get(cfState, drepDelegKey(credType, credentialHash));
            if (val == null) return Optional.empty();
            var rec = AccountStateCborCodec.decodeDRepDelegation(val);
            return Optional.of(new DRepDelegation(rec.drepType(), rec.drepHash()));
        } catch (RocksDBException e) {
            throw ledgerStateReadFailure("getDRepDelegation", e);
        }
    }

    @Override
    public boolean isStakeCredentialRegistered(int credType, String credentialHash) {
        try {
            byte[] val = db.get(cfState, accountKey(credType, credentialHash));
            return val != null;
        } catch (RocksDBException e) {
            throw ledgerStateReadFailure("isStakeCredentialRegistered", e);
        }
    }

    @Override
    public Optional<Long> getStakeRegistrationSlot(int credType, String credentialHash) {
        try {
            if (db.get(cfState, accountKey(credType, credentialHash)) == null) return Optional.empty();
            byte[] val = db.get(cfState, acctRegSlotKey(credType, credentialHash));
            if (val == null || val.length < Long.BYTES) return Optional.empty();
            return Optional.of(ByteBuffer.wrap(val).order(ByteOrder.BIG_ENDIAN).getLong());
        } catch (RocksDBException e) {
            throw ledgerStateReadFailure("getStakeRegistrationSlot", e);
        }
    }

    @Override
    public BigInteger getTotalDeposited() {
        try {
            byte[] val = db.get(cfState, META_TOTAL_DEPOSITED);
            if (val == null) return BigInteger.ZERO;
            if (val.length < 8) {
                throw new IllegalStateException("Malformed total deposited metadata: expected at least 8 bytes, got " + val.length);
            }
            return new BigInteger(1, val);
        } catch (RocksDBException e) {
            throw ledgerStateReadFailure("getTotalDeposited", e);
        }
    }

    @Override
    public boolean isPoolRegistered(String poolHash) {
        try {
            byte[] val = db.get(cfState, poolDepositKey(poolHash));
            return val != null;
        } catch (RocksDBException e) {
            throw ledgerStateReadFailure("isPoolRegistered", e);
        }
    }

    @Override
    public Optional<BigInteger> getPoolDeposit(String poolHash) {
        try {
            byte[] val = db.get(cfState, poolDepositKey(poolHash));
            if (val == null) return Optional.empty();
            return Optional.of(AccountStateCborCodec.decodePoolDeposit(val));
        } catch (RocksDBException e) {
            throw ledgerStateReadFailure("getPoolDeposit", e);
        }
    }

    /**
     * Get full pool registration data (deposit + params) for a pool.
     */
    public Optional<AccountStateCborCodec.PoolRegistrationData> getPoolRegistrationData(String poolHash) {
        try {
            byte[] val = db.get(cfState, poolDepositKey(poolHash));
            if (val == null) return Optional.empty();
            return Optional.of(AccountStateCborCodec.decodePoolRegistration(val));
        } catch (RocksDBException e) {
            throw ledgerStateReadFailure("getPoolRegistrationData", e);
        }
    }

    @Override
    public Optional<LedgerStateProvider.PoolParams> getPoolParams(String poolHash) {
        return getPoolRegistrationData(poolHash).map(data -> {
            double margin = UnitIntervalUtil.safeRatio(data.marginNum(), data.marginDen()).doubleValue();
            return new LedgerStateProvider.PoolParams(
                    data.deposit(), margin, data.cost(), data.pledge(),
                    data.rewardAccount(), data.owners());
        });
    }

    @Override
    public Optional<LedgerStateProvider.PoolParams> getPoolParams(String poolHash, int epoch) {
        return getPoolRegistrationDataAtEpoch(poolHash, epoch).map(data -> {
            double margin = UnitIntervalUtil.safeRatio(data.marginNum(), data.marginDen()).doubleValue();
            return new LedgerStateProvider.PoolParams(
                    data.deposit(), margin, data.cost(), data.pledge(),
                    data.rewardAccount(), data.owners());
        });
    }

    /**
     * Get pool registration data that was active at the given epoch.
     * Uses seekForPrev to find the latest history entry with epoch &lt;= target.
     * Falls back to the current (latest) registration if no history found.
     */
    public Optional<AccountStateCborCodec.PoolRegistrationData> getPoolRegistrationDataAtEpoch(
            String poolHash, int epoch) {
        try (RocksIterator it = db.newIterator(cfState)) {
            byte[] seekKey = poolParamsHistKey(poolHash, epoch);
            it.seekForPrev(seekKey);

            if (it.isValid()) {
                byte[] foundKey = it.key();
                // Verify the key matches: prefix + same poolHash
                byte[] poolHashBytes = HexUtil.decodeHexString(poolHash);
                if (foundKey.length == 1 + poolHashBytes.length + 4
                        && foundKey[0] == PREFIX_POOL_PARAMS_HIST
                        && Arrays.equals(poolHashBytes, 0, poolHashBytes.length,
                                foundKey, 1, 1 + poolHashBytes.length)) {
                    return Optional.of(AccountStateCborCodec.decodePoolRegistration(it.value()));
                }
            }
        }
        // Fall back to current params if no history entry found (pre-existing pools before history tracking)
        return getPoolRegistrationData(poolHash);
    }

    @Override
    public Optional<Long> getPoolRetirementEpoch(String poolHash) {
        try {
            byte[] val = db.get(cfState, poolRetireKey(poolHash));
            if (val == null) return Optional.empty();
            return Optional.of(AccountStateCborCodec.decodePoolRetirement(val));
        } catch (RocksDBException e) {
            throw ledgerStateReadFailure("getPoolRetirementEpoch", e);
        }
    }

    // --- DRep State reads ---

    @Override
    public boolean isDRepRegistered(int credType, String credentialHash) {
        try {
            byte[] val = db.get(cfState, drepRegKey(credType, credentialHash));
            return val != null;
        } catch (RocksDBException e) {
            throw ledgerStateReadFailure("isDRepRegistered", e);
        }
    }

    @Override
    public Optional<BigInteger> getDRepDeposit(int credType, String credentialHash) {
        try {
            byte[] val = db.get(cfState, drepRegKey(credType, credentialHash));
            if (val == null) return Optional.empty();
            return Optional.of(AccountStateCborCodec.decodeDRepDeposit(val));
        } catch (RocksDBException e) {
            throw ledgerStateReadFailure("getDRepDeposit", e);
        }
    }

    private IllegalStateException ledgerStateReadFailure(String operation, RocksDBException e) {
        log.error("{} failed", operation, e);
        return new IllegalStateException(operation + " failed", e);
    }

    // --- Committee State reads ---

    @Override
    public boolean isCommitteeMember(int credType, String coldCredentialHash) {
        try {
            byte[] val = db.get(cfState, committeeHotKey(credType, coldCredentialHash));
            return val != null;
        } catch (RocksDBException e) {
            throw ledgerStateReadFailure("isCommitteeMember", e);
        }
    }

    @Override
    public Optional<String> getCommitteeHotCredential(int credType, String coldCredentialHash) {
        try {
            byte[] val = db.get(cfState, committeeHotKey(credType, coldCredentialHash));
            if (val == null) return Optional.empty();
            return Optional.of(AccountStateCborCodec.decodeCommitteeHotKey(val).hotHash());
        } catch (RocksDBException e) {
            throw ledgerStateReadFailure("getCommitteeHotCredential", e);
        }
    }

    @Override
    public boolean hasCommitteeMemberResigned(int credType, String coldCredentialHash) {
        try {
            byte[] val = db.get(cfState, committeeResignKey(credType, coldCredentialHash));
            return val != null;
        } catch (RocksDBException e) {
            throw ledgerStateReadFailure("hasCommitteeMemberResigned", e);
        }
    }

    @Override
    public Optional<Boolean> isCommitteeHotCredentialAuthorized(int hotCredType, String hotCredentialHash) {
        return isCommitteeHotCredentialAuthorized(hotCredType, hotCredentialHash, -1);
    }

    @Override
    public Optional<Boolean> isCommitteeHotCredentialAuthorized(int hotCredType, String hotCredentialHash,
                                                                long currentEpoch) {
        var processor = governanceBlockProcessor;
        if (processor == null) return Optional.empty();

        try {
            boolean matched = false;
            for (var entry : processor.getGovernanceStore().getAllCommitteeMembers().entrySet()) {
                var member = entry.getValue();
                if (!member.hasHotKey()) continue;
                if (member.resigned()) continue;
                if (member.hotCredType() != hotCredType) continue;
                if (!hotCredentialHash.equals(member.hotHash())) continue;
                matched = true;

                if (currentEpoch < 0) {
                    // Without the validation epoch, avoid accepting placeholder/future committee state.
                    continue;
                }
                if (member.expiryEpoch() > 0 && member.expiryEpoch() >= currentEpoch) {
                    return Optional.of(true);
                }
            }
            return matched && currentEpoch < 0 ? Optional.empty() : Optional.of(false);
        } catch (Exception e) {
            log.error("isCommitteeHotCredentialAuthorized failed", e);
            throw new IllegalStateException("isCommitteeHotCredentialAuthorized failed", e);
        }
    }

    @Override
    public Optional<LedgerStateProvider.GovernanceActionInfo> getGovernanceAction(String txHash, int govActionIndex) {
        return getGovernanceAction(txHash, govActionIndex, -1);
    }

    @Override
    public Optional<LedgerStateProvider.GovernanceActionInfo> getGovernanceAction(String txHash, int govActionIndex,
                                                                                  long currentEpoch) {
        var processor = governanceBlockProcessor;
        if (processor == null) return Optional.empty();

        try {
            var store = processor.getGovernanceStore();
            var active = store.getProposal(txHash, govActionIndex);
            if (active.isPresent()) {
                GovActionId id = new GovActionId(txHash, govActionIndex);
                boolean pendingResolution = store.isPendingEnactment(id) || store.isPendingDrop(id);
                boolean notExpired = currentEpoch < 0 || currentEpoch <= active.get().expiresAfterEpoch();
                return Optional.of(new LedgerStateProvider.GovernanceActionInfo(
                        active.get().actionType().name(), !pendingResolution && notExpired, false));
            }

            for (GovActionType type : GovActionType.values()) {
                var last = store.getLastEnactedAction(type);
                if (last.isPresent()
                        && txHash.equals(last.get().txHash())
                        && govActionIndex == last.get().govActionIndex()) {
                    return Optional.of(new LedgerStateProvider.GovernanceActionInfo(type.name(), false, true));
                }
            }
        } catch (Exception e) {
            log.error("getGovernanceAction failed for {}#{}", txHash, govActionIndex, e);
            throw new IllegalStateException("getGovernanceAction failed for " + txHash + "#" + govActionIndex, e);
        }
        return Optional.empty();
    }

    // --- MIR State reads ---

    @Override
    public Optional<BigInteger> getInstantReward(int credType, String credentialHash) {
        try {
            byte[] val = db.get(cfState, mirRewardKey(credType, credentialHash));
            if (val == null) return Optional.empty();
            return Optional.of(AccountStateCborCodec.decodeMirReward(val));
        } catch (RocksDBException e) {
            log.error("getInstantReward failed: {}", e.toString());
            return Optional.empty();
        }
    }

    @Override
    public BigInteger getMirPotTransfer(boolean toReserves) {
        try {
            byte[] key = toReserves ? META_MIR_TO_RESERVES : META_MIR_TO_TREASURY;
            byte[] val = db.get(cfState, key);
            if (val == null || val.length < 8) return BigInteger.ZERO;
            return new BigInteger(1, val);
        } catch (RocksDBException e) {
            log.error("getMirPotTransfer failed: {}", e.toString());
            return BigInteger.ZERO;
        }
    }

    /**
     * Key for pre-computed MIR credited total: "mir.total." + earnedEpoch + "." + mirType
     */
    private static byte[] mirCreditedTotalKey(int earnedEpoch, byte mirType) {
        return ("mir.total." + earnedEpoch + "." + mirType).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Get total MIR rewards for a given earned epoch and pot type.
     * Reads the pre-computed credited total stored by creditMirRewardRest(), which
     * already excludes deregistered accounts at the epoch boundary.
     *
     * @param earnedEpoch the epoch the MIR was earned in
     * @param mirType     REWARD_REST_MIR_RESERVES (0) or REWARD_REST_MIR_TREASURY (1)
     * @return total MIR amount for the given epoch and pot type
     */
    public BigInteger getMirEpochTotal(int earnedEpoch, byte mirType) {
        try {
            byte[] val = db.get(cfState, mirCreditedTotalKey(earnedEpoch, mirType));
            if (val == null) return BigInteger.ZERO;
            return AccountStateCborCodec.decodeMirReward(val);
        } catch (RocksDBException e) {
            log.error("getMirEpochTotal failed: {}", e.toString());
            return BigInteger.ZERO;
        }
    }

    // --- Epoch Block Count and Fee queries (per-block facts with legacy fallback) ---

    @Override
    public long getPoolBlockCount(int epoch, String poolHash) {
        return getPoolBlockCounts(epoch).getOrDefault(poolHash, 0L);
    }

    @Override
    public Map<String, Long> getPoolBlockCounts(int epoch) {
        return aggregateBlockIssuerEntries(epoch);
    }

    @Override
    public BigInteger getEpochFees(int epoch) {
        return aggregateBlockFeeEntries(epoch);
    }

    /** Aggregate per-block issuer entries into per-pool block counts for the given epoch. */
    private Map<String, Long> aggregateBlockIssuerEntries(int epoch) {
        Map<String, Long> counts = new HashMap<>();
        byte[] seekKey = new byte[1 + 4];
        seekKey[0] = PREFIX_BLOCK_ISSUER;
        ByteBuffer.wrap(seekKey, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(epoch);

        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(seekKey);
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 5 || key[0] != PREFIX_BLOCK_ISSUER) break;
                int keyEpoch = ByteBuffer.wrap(key, 1, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                if (keyEpoch != epoch) break;

                String poolHash = HexUtil.encodeHexString(it.value());
                counts.merge(poolHash, 1L, Long::sum);
                it.next();
            }
        }
        return counts;
    }

    /** Aggregate per-block fee entries into total epoch fees. */
    private BigInteger aggregateBlockFeeEntries(int epoch) {
        BigInteger total = BigInteger.ZERO;
        byte[] seekKey = new byte[1 + 4];
        seekKey[0] = PREFIX_BLOCK_FEE;
        ByteBuffer.wrap(seekKey, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(epoch);

        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(seekKey);
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 5 || key[0] != PREFIX_BLOCK_FEE) break;
                int keyEpoch = ByteBuffer.wrap(key, 1, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                if (keyEpoch != epoch) break;

                total = total.add(AccountStateCborCodec.decodeEpochFees(it.value()));
                it.next();
            }
        }
        return total;
    }

    /** Clear per-block fact entries for the given epoch (called after reward calculation consumes them). */
    public void clearEpochBlockData(int epoch) {
        if (epoch < 0) return;
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            int deleted = 0;
            for (byte prefix : new byte[]{PREFIX_BLOCK_ISSUER, PREFIX_BLOCK_FEE}) {
                byte[] seekKey = new byte[1 + 4];
                seekKey[0] = prefix;
                ByteBuffer.wrap(seekKey, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(epoch);

                try (RocksIterator it = db.newIterator(cfState)) {
                    it.seek(seekKey);
                    while (it.isValid()) {
                        byte[] key = it.key();
                        if (key.length < 5 || key[0] != prefix) break;
                        int keyEpoch = ByteBuffer.wrap(key, 1, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                        if (keyEpoch != epoch) break;
                        batch.delete(cfState, key);
                        deleted++;
                        it.next();
                    }
                }
            }
            if (deleted > 0) {
                db.write(wo, batch);
                log.info("Cleared {} per-block fact entries for epoch {}", deleted, epoch);
            }
        } catch (Exception e) {
            log.warn("Failed to clear epoch block data for epoch {}: {}", epoch, e.getMessage());
        }
    }

    // --- Retired pool queries ---

    @Override
    public List<LedgerStateProvider.RetiringPool> getPoolsRetiringAtEpoch(int retireEpoch) {
        List<LedgerStateProvider.RetiringPool> result = new ArrayList<>();
        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(new byte[]{PREFIX_POOL_RETIRE});
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 2 || key[0] != PREFIX_POOL_RETIRE) break;

                long epoch = AccountStateCborCodec.decodePoolRetirement(it.value());
                if (epoch == retireEpoch) {
                    String poolHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 1, key.length));
                    BigInteger deposit = getPoolDeposit(poolHash).orElse(BigInteger.ZERO);
                    result.add(new LedgerStateProvider.RetiringPool(poolHash, deposit, epoch));
                }
                it.next();
            }
        }
        return result;
    }

    // --- Registered credential queries ---

    @Override
    public java.util.Set<String> getAllRegisteredCredentials() {
        java.util.Set<String> credentials = new java.util.HashSet<>();
        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(new byte[]{PREFIX_ACCT});
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 2 || key[0] != PREFIX_ACCT) break;
                int credType = key[1] & 0xFF;
                String credHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 2, key.length));
                credentials.add(credType + ":" + credHash);
                it.next();
            }
        }
        return credentials;
    }

    // --- AdaPot queries ---

    @Override
    public boolean isAdaPotTrackingEnabled() {
        return adaPotTracker != null && adaPotTracker.isEnabled();
    }

    @Override
    public Optional<LedgerStateProvider.AdaPotSnapshot> getAdaPot(int epoch) {
        try {
            byte[] key = adaPotKey(epoch);
            byte[] val = db.get(cfState, key);
            if (val == null) return Optional.empty();
            var pot = AccountStateCborCodec.decodeAdaPot(val);
            return Optional.of(toAdaPotSnapshot(epoch, pot));
        } catch (RocksDBException e) {
            log.error("getAdaPot failed for epoch {}: {}", epoch, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public Optional<BigInteger> getTreasury(int epoch) {
        try {
            byte[] key = adaPotKey(epoch);
            byte[] val = db.get(cfState, key);
            if (val == null) return Optional.empty();
            return Optional.of(AccountStateCborCodec.decodeAdaPot(val).treasury());
        } catch (RocksDBException e) {
            log.error("getTreasury failed: {}", e.toString());
            return Optional.empty();
        }
    }

    @Override
    public Optional<BigInteger> getReserves(int epoch) {
        try {
            byte[] key = adaPotKey(epoch);
            byte[] val = db.get(cfState, key);
            if (val == null) return Optional.empty();
            return Optional.of(AccountStateCborCodec.decodeAdaPot(val).reserves());
        } catch (RocksDBException e) {
            log.error("getReserves failed: {}", e.toString());
            return Optional.empty();
        }
    }

    @Override
    public Optional<LedgerStateProvider.ProtocolParamsSnapshot> getProtocolParameters(int epoch) {
        if (epoch < 0) return Optional.empty();
        if (paramTracker != null && paramTracker.isEnabled()
                && paramTracker.getResolvedParams(epoch) == null) {
            return Optional.empty();
        }

        EpochParamProvider provider = effectiveEpochParamProvider();
        var trackedParams = paramTracker != null && paramTracker.isEnabled()
                ? paramTracker.getResolvedParams(epoch)
                : null;
        Integer committeeMinSize;
        Integer committeeMaxTermLength;
        Integer govActionLifetime;
        Integer drepActivity;
        if (trackedParams != null) {
            committeeMinSize = trackedParams.getCommitteeMinSize();
            committeeMaxTermLength = trackedParams.getCommitteeMaxTermLength();
            govActionLifetime = trackedParams.getGovActionLifetime();
            drepActivity = trackedParams.getDrepActivity();
        } else {
            committeeMinSize = provider.getCommitteeMinSize(epoch);
            committeeMaxTermLength = provider.getCommitteeMaxTermLength(epoch);
            govActionLifetime = provider.getGovActionLifetime(epoch);
            drepActivity = provider.getDRepActivity(epoch);
        }
        PoolVotingThresholds poolThresholds = provider.getPoolVotingThresholds(epoch);
        DrepVoteThresholds drepThresholds = provider.getDrepVotingThresholds(epoch);

        return Optional.of(new LedgerStateProvider.ProtocolParamsSnapshot(
                epoch,
                provider.getMinFeeA(epoch),
                provider.getMinFeeB(epoch),
                provider.getMaxBlockSize(epoch),
                provider.getMaxTxSize(epoch),
                provider.getMaxBlockHeaderSize(epoch),
                provider.getKeyDeposit(epoch),
                provider.getPoolDeposit(epoch),
                provider.getMaxEpoch(epoch),
                provider.getNOpt(epoch),
                provider.getA0(epoch),
                provider.getRho(epoch),
                provider.getTau(epoch),
                provider.getDecentralization(epoch),
                provider.getExtraEntropy(epoch),
                provider.getProtocolMajor(epoch),
                provider.getProtocolMinor(epoch),
                provider.getMinUtxo(epoch),
                provider.getMinPoolCost(epoch),
                null,
                provider.getCostModels(epoch),
                provider.getCostModelsRaw(epoch),
                provider.getPriceMem(epoch),
                provider.getPriceStep(epoch),
                provider.getMaxTxExMem(epoch),
                provider.getMaxTxExSteps(epoch),
                provider.getMaxBlockExMem(epoch),
                provider.getMaxBlockExSteps(epoch),
                provider.getMaxValSize(epoch),
                provider.getCollateralPercent(epoch),
                provider.getMaxCollateralInputs(epoch),
                provider.getCoinsPerUtxoSize(epoch),
                provider.getCoinsPerUtxoWord(epoch),
                ratio(poolThresholds != null ? poolThresholds.getPvtMotionNoConfidence() : null),
                ratio(poolThresholds != null ? poolThresholds.getPvtCommitteeNormal() : null),
                ratio(poolThresholds != null ? poolThresholds.getPvtCommitteeNoConfidence() : null),
                ratio(poolThresholds != null ? poolThresholds.getPvtHardForkInitiation() : null),
                ratio(poolThresholds != null ? poolThresholds.getPvtPPSecurityGroup() : null),
                ratio(drepThresholds != null ? drepThresholds.getDvtMotionNoConfidence() : null),
                ratio(drepThresholds != null ? drepThresholds.getDvtCommitteeNormal() : null),
                ratio(drepThresholds != null ? drepThresholds.getDvtCommitteeNoConfidence() : null),
                ratio(drepThresholds != null ? drepThresholds.getDvtUpdateToConstitution() : null),
                ratio(drepThresholds != null ? drepThresholds.getDvtHardForkInitiation() : null),
                ratio(drepThresholds != null ? drepThresholds.getDvtPPNetworkGroup() : null),
                ratio(drepThresholds != null ? drepThresholds.getDvtPPEconomicGroup() : null),
                ratio(drepThresholds != null ? drepThresholds.getDvtPPTechnicalGroup() : null),
                ratio(drepThresholds != null ? drepThresholds.getDvtPPGovGroup() : null),
                ratio(drepThresholds != null ? drepThresholds.getDvtTreasuryWithdrawal() : null),
                committeeMinSize,
                committeeMaxTermLength,
                govActionLifetime,
                provider.getGovActionDeposit(epoch),
                provider.getDRepDeposit(epoch),
                drepActivity,
                provider.getMinFeeRefScriptCostPerByte(epoch)
        ));
    }

    private EpochParamProvider effectiveEpochParamProvider() {
        return paramTracker != null && paramTracker.isEnabled() ? paramTracker : epochParamProvider;
    }

    private static BigDecimal ratio(UnitInterval interval) {
        return interval != null ? interval.safeRatio() : null;
    }

    private static LedgerStateProvider.AdaPotSnapshot toAdaPotSnapshot(int epoch, AccountStateCborCodec.AdaPot pot) {
        return new LedgerStateProvider.AdaPotSnapshot(
                epoch,
                pot.treasury(),
                pot.reserves(),
                pot.deposits(),
                pot.fees(),
                pot.distributed(),
                pot.undistributed(),
                pot.rewardsPot(),
                pot.poolRewardsPot()
        );
    }

    /**
     * Read a previous epoch's delegation snapshot for incremental balance aggregation.
     */
    private java.util.Map<String, AccountStateCborCodec.EpochDelegSnapshot> readStakeSnapshot(int epoch) {
        java.util.Map<String, AccountStateCborCodec.EpochDelegSnapshot> snapshot = new java.util.HashMap<>();
        byte[] epochPrefix = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(epoch).array();
        try (RocksIterator it = db.newIterator(cfEpochSnapshot)) {
            it.seek(epochPrefix);
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 5) break;
                int keyEpoch = ByteBuffer.wrap(key, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                if (keyEpoch != epoch) break;
                int credType = key[4] & 0xFF;
                String credHash = HexUtil.encodeHexString(java.util.Arrays.copyOfRange(key, 5, key.length));
                snapshot.put(credType + ":" + credHash,
                        AccountStateCborCodec.decodeEpochDelegSnapshot(it.value()));
                it.next();
            }
        }
        return snapshot;
    }

    // --- Epoch Delegation Snapshot queries ---

    @Override
    public Optional<String> getEpochDelegation(int epoch, int credType, String credentialHash) {
        try {
            byte[] epochBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(epoch).array();
            byte[] hash = HexUtil.decodeHexString(credentialHash);
            byte[] key = new byte[4 + 1 + hash.length];
            System.arraycopy(epochBytes, 0, key, 0, 4);
            key[4] = (byte) credType;
            System.arraycopy(hash, 0, key, 5, hash.length);

            byte[] val = db.get(cfEpochSnapshot, key);
            if (val == null) return Optional.empty();
            return Optional.of(AccountStateCborCodec.decodeEpochDelegSnapshot(val).poolHash());
        } catch (RocksDBException e) {
            log.error("getEpochDelegation failed: {}", e.toString());
            return Optional.empty();
        }
    }

    @Override
    public List<LedgerStateProvider.EpochDelegator> getPoolDelegatorsAtEpoch(int epoch, String poolHash) {
        List<LedgerStateProvider.EpochDelegator> result = new ArrayList<>();
        try {
            byte[] epochPrefix = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(epoch).array();
            try (RocksIterator it = db.newIterator(cfEpochSnapshot)) {
                it.seek(epochPrefix);
                while (it.isValid()) {
                    byte[] key = it.key();
                    if (key.length < 5) break;
                    int keyEpoch = ByteBuffer.wrap(key, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                    if (keyEpoch != epoch) break;

                    var snapshot = AccountStateCborCodec.decodeEpochDelegSnapshot(it.value());
                    if (snapshot.poolHash().equals(poolHash)) {
                        int credType = key[4] & 0xFF;
                        String credHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 5, key.length));
                        result.add(new LedgerStateProvider.EpochDelegator(credType, credHash));
                    }
                    it.next();
                }
            }
        } catch (Exception e) {
            log.error("getPoolDelegatorsAtEpoch failed: {}", e.toString());
        }
        return result;
    }

    @Override
    public int getLatestSnapshotEpoch() {
        return getLastSnapshotEpoch();
    }

    @Override
    public Optional<AccountStateReadStore.EpochStake> getEpochStake(int epoch, int credType, String credentialHash) {
        return readStore.getEpochStake(epoch, credType, credentialHash);
    }

    @Override
    public Optional<BigInteger> getTotalActiveStake(int epoch) {
        return readStore.getTotalActiveStake(epoch);
    }

    @Override
    public Optional<AccountStateReadStore.PoolStake> getPoolActiveStake(int epoch, String poolHash) {
        return readStore.getPoolActiveStake(epoch, poolHash);
    }

    @Override
    public List<AccountStateReadStore.PoolStakeDelegator> listPoolStakeDelegators(int epoch, String poolHash,
                                                                                 int page, int count,
                                                                                 String order) {
        return readStore.listPoolStakeDelegators(epoch, poolHash, page, count, order);
    }

    @Override
    public List<AccountStateReadStore.GovernanceProposal> listGovernanceProposals() {
        return readStore.listGovernanceProposals();
    }

    @Override
    public Optional<AccountStateReadStore.GovernanceProposal> getGovernanceProposal(String txHash, int certIndex) {
        return readStore.getGovernanceProposal(txHash, certIndex);
    }

    @Override
    public List<AccountStateReadStore.GovernanceVote> getGovernanceProposalVotes(String txHash, int certIndex) {
        return readStore.getGovernanceProposalVotes(txHash, certIndex);
    }

    @Override
    public List<AccountStateReadStore.DRepInfo> listDReps() {
        return readStore.listDReps();
    }

    @Override
    public Optional<AccountStateReadStore.DRepInfo> getDRep(int drepType, String drepHash) {
        return readStore.getDRep(drepType, drepHash);
    }

    @Override
    public Optional<BigInteger> getDRepDistribution(int epoch, int drepType, String drepHash) {
        return readStore.getDRepDistribution(epoch, drepType, drepHash);
    }

    @Override
    public Optional<Integer> getLatestDRepDistributionEpoch(int maxEpoch) {
        return readStore.getLatestDRepDistributionEpoch(maxEpoch);
    }

    // --- Reward Rest (deferred rewards: proposal refunds, treasury withdrawals, etc.) ---

    /**
     * Key: PREFIX_REWARD_REST(1) + spendable_epoch(4 BE) + type(1) + credType(1) + credHash(28) = 35 bytes
     */
    static byte[] rewardRestKey(int spendableEpoch, byte type, int credType, String credHash) {
        byte[] hash = HexUtil.decodeHexString(credHash);
        byte[] key = new byte[1 + 4 + 1 + 1 + hash.length];
        key[0] = PREFIX_REWARD_REST;
        ByteBuffer.wrap(key, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(spendableEpoch);
        key[5] = type;
        key[6] = (byte) credType;
        System.arraycopy(hash, 0, key, 7, hash.length);
        return key;
    }

    /**
     * Store a reward_rest entry (deferred reward: proposal refund, treasury withdrawal, etc.).
     * The reward becomes part of stake at the spendable_epoch snapshot.
     *
     * @param spendableEpoch Epoch when the reward becomes spendable and counts toward stake
     * @param type           Reward type (REWARD_REST_PROPOSAL_REFUND, etc.)
     * @param rewardAccountHex Reward account hex (header + credential hash)
     * @param amount         Amount in lovelace
     * @param earnedEpoch    Epoch when the reward was earned
     * @param slot           Slot of the event that triggered the reward
     * @param batch          WriteBatch for atomic writes
     * @param deltaOps       Delta ops for rollback
     * @return true if stored successfully (account address is valid)
     */
    public boolean storeRewardRest(int spendableEpoch, byte type, String rewardAccountHex,
                                   BigInteger amount, int earnedEpoch, long slot,
                                   org.rocksdb.WriteBatch batch,
                                   java.util.List<DeltaOp> deltaOps) throws RocksDBException {
        if (rewardAccountHex == null || rewardAccountHex.length() < 58 || amount.signum() <= 0) {
            return false;
        }
        int headerByte;
        try {
            headerByte = Integer.parseInt(rewardAccountHex.substring(0, 2), 16);
        } catch (NumberFormatException e) {
            return false;
        }
        int credType = ((headerByte & 0x10) != 0) ? 1 : 0;
        String credHash = rewardAccountHex.substring(2, 58);

        // Check if the stake credential is registered. Per Haskell spec,
        // deposits for deregistered addresses go to treasury (return false).
        // Use the account key (PREFIX_ACCT) which is deleted on deregistration.
        byte[] acctKey = accountKey(credType, credHash);
        byte[] acctVal = db.get(cfState, acctKey);
        if (acctVal == null) {
            return false; // Not registered → deposit goes to treasury
        }

        byte[] key = rewardRestKey(spendableEpoch, type, credType, credHash);
        byte[] prev = db.get(cfState, key);

        // If entry already exists for same key in committed state, add amounts.
        // NOTE: Callers must pre-aggregate amounts per credential before calling this method,
        // because db.get() can't see uncommitted WriteBatch entries from the same batch.
        BigInteger existing = BigInteger.ZERO;
        if (prev != null) {
            existing = AccountStateCborCodec.decodeRewardRest(prev).amount();
        }
        BigInteger total = existing.add(amount);

        byte[] val = AccountStateCborCodec.encodeRewardRest(total, earnedEpoch, slot);
        batch.put(cfState, key, val);
        deltaOps.add(new DeltaOp(OP_PUT, key, prev));
        return true;
    }

    /**
     * Get all spendable reward_rest amounts aggregated per credential for the given epoch.
     * Includes ALL types. Used during snapshot creation.
     *
     * @param epoch Include entries with spendable_epoch ≤ epoch
     * @return map from "credType:credHash" to total spendable reward_rest amount
     */
    public java.util.Map<String, BigInteger> getSpendableRewardRest(int epoch) {
        java.util.Map<String, BigInteger> result = new java.util.HashMap<>();
        byte[] seekKey = new byte[]{PREFIX_REWARD_REST};

        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(seekKey);
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 7 || key[0] != PREFIX_REWARD_REST) break;

                int spendableEpoch = ByteBuffer.wrap(key, 1, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                if (spendableEpoch > epoch) break;

                int credType = key[6] & 0xFF;
                String credHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 7, key.length));

                var rest = AccountStateCborCodec.decodeRewardRest(it.value());
                result.merge(credType + ":" + credHash, rest.amount(), BigInteger::add);

                it.next();
            }
        } catch (Exception e) {
            log.error("getSpendableRewardRest failed: {}", e.toString());
        }
        return result;
    }

    /**
     * Create a {@code GovernanceEpochProcessor.RewardRestStore} adapter backed by this store.
     */
    public com.bloxbean.cardano.yano.ledgerstate.governance.epoch.GovernanceEpochProcessor.RewardRestStore asRewardRestStore() {
        return new com.bloxbean.cardano.yano.ledgerstate.governance.epoch.GovernanceEpochProcessor.RewardRestStore() {
            @Override
            public boolean storeRewardRest(int spendableEpoch, byte type, String rewardAccountHex,
                                           BigInteger amount, int earnedEpoch, long slot,
                                           org.rocksdb.WriteBatch batch, java.util.List<DeltaOp> deltaOps)
                    throws org.rocksdb.RocksDBException {
                return DefaultAccountStateStore.this.storeRewardRest(
                        spendableEpoch, type, rewardAccountHex, amount, earnedEpoch, slot, batch, deltaOps);
            }

            @Override
            public java.util.Map<String, BigInteger> getSpendableRewardRest(int epoch) {
                return DefaultAccountStateStore.this.getSpendableRewardRest(epoch);
            }
        };
    }

    /**
     * Credit spendable MIR reward_rest entries to PREFIX_ACCT.reward and remove them.
     * Called BEFORE snapshot creation so that MIR amounts are in the account balance,
     * allowing on-chain withdrawals to be correctly reflected in the snapshot.
     * Also records the per-pot credited totals (excluding deregistered accounts) as metadata,
     * which getMirEpochTotal() reads for the cf-rewards MIR certificate input.
     */
    public void creditMirRewardRest(int epoch) {
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            List<DeltaOp> deltaOps = new ArrayList<>();

            // Track per-credential totals and per-type (pot) raw totals
            java.util.Map<CredentialKey, BigInteger> perCredentialTotal = new java.util.LinkedHashMap<>();
            // Track which type each credential belongs to (for per-pot totals after deregistration filter)
            java.util.Map<CredentialKey, Byte> credentialTypes = new java.util.HashMap<>();
            java.util.List<byte[]> keysToDelete = new java.util.ArrayList<>();

            try (RocksIterator it = db.newIterator(cfState)) {
                it.seek(new byte[]{PREFIX_REWARD_REST});
                while (it.isValid()) {
                    byte[] key = it.key();
                    if (key.length < 7 || key[0] != PREFIX_REWARD_REST) break;

                    int spendableEpoch = ByteBuffer.wrap(key, 1, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                    if (spendableEpoch > epoch) break;

                    byte type = key[5];
                    // Only credit MIR types, leave other reward_rest for later crediting
                    if (type != REWARD_REST_MIR_RESERVES && type != REWARD_REST_MIR_TREASURY) {
                        it.next();
                        continue;
                    }

                    var cred = CredentialKey.fromKeyBytes(key, 6);

                    var rest = AccountStateCborCodec.decodeRewardRest(it.value());
                    if (rest.amount().signum() > 0) {
                        perCredentialTotal.merge(cred, rest.amount(), BigInteger::add);
                        credentialTypes.putIfAbsent(cred, type);
                    }
                    keysToDelete.add(Arrays.copyOf(key, key.length));
                    it.next();
                }
            }

            if (perCredentialTotal.isEmpty()) {
                return;
            }

            int credited = 0;
            int skipped = 0;
            BigInteger totalCredited = BigInteger.ZERO;
            BigInteger creditedReserves = BigInteger.ZERO;
            BigInteger creditedTreasury = BigInteger.ZERO;
            for (var entry : perCredentialTotal.entrySet()) {
                var ck = entry.getKey();
                BigInteger amount = entry.getValue();

                byte[] acctKey = accountKey(ck.typeInt(), ck.hash());
                byte[] acctVal = db.get(cfState, acctKey);
                if (acctVal == null) {
                    skipped++;
                    if (log.isDebugEnabled()) {
                        log.debug("MIR skip deregistered: epoch={}, cred={}:{}, amount={}", epoch, ck.typeInt(), ck.hash(), amount);
                    }
                    continue; // deregistered — skip
                }

                var acct = AccountStateCborCodec.decodeStakeAccount(acctVal);
                BigInteger newReward = acct.reward().add(amount);
                putStateWithDelta(acctKey, AccountStateCborCodec.encodeStakeAccount(newReward, acct.deposit()), batch, deltaOps);
                credited++;
                totalCredited = totalCredited.add(amount);
                byte mirType = credentialTypes.getOrDefault(ck, REWARD_REST_MIR_RESERVES);
                if (mirType == REWARD_REST_MIR_RESERVES) {
                    creditedReserves = creditedReserves.add(amount);
                } else {
                    creditedTreasury = creditedTreasury.add(amount);
                }
            }
            for (byte[] key : keysToDelete) {
                deleteStateWithDelta(key, batch, deltaOps);
            }

            // Store the credited (deregistration-filtered) MIR totals per pot type as metadata.
            // These are read by getMirEpochTotal() for cf-rewards MIR certificate input.
            // earnedEpoch = epoch - 1 (MIR from previous epoch, spendable at current epoch)
            int earnedEpoch = epoch - 1;
            if (creditedReserves.signum() > 0) {
                byte[] metaKey = mirCreditedTotalKey(earnedEpoch, REWARD_REST_MIR_RESERVES);
                putStateWithDelta(metaKey, AccountStateCborCodec.encodeMirReward(creditedReserves), batch, deltaOps);
            }
            if (creditedTreasury.signum() > 0) {
                byte[] metaKey = mirCreditedTotalKey(earnedEpoch, REWARD_REST_MIR_TREASURY);
                putStateWithDelta(metaKey, AccountStateCborCodec.encodeMirReward(creditedTreasury), batch, deltaOps);
            }

            long boundarySlot = slotForEpochStart(epoch);
            commitBoundaryDelta(boundarySlot, PHASE_MIR, batch, deltaOps);
            db.write(wo, batch);
            if (credited > 0 || skipped > 0) {
                log.info("Credited {} MIR reward_rest entries for epoch {}: total={} (reserves={}, treasury={}), skipped={} deregistered",
                        credited, epoch, totalCredited, creditedReserves, creditedTreasury, skipped);
            }
        } catch (Exception e) {
            log.error("creditMirRewardRest failed: {}", e.toString());
        }
    }

    /**
     * Credit all spendable reward_rest entries to PREFIX_ACCT.reward and remove them.
     * Called at epoch boundary AFTER snapshot creation (phase 2).
     * Entries with spendable_epoch ≤ epoch are credited to the account's reward balance.
     */
    private void creditAndRemoveSpendableRewardRest(int epoch, org.rocksdb.WriteBatch batch, List<DeltaOp> deltaOps) {
        // Phase 1: Iterate reward_rest entries, accumulate per-credential totals.
        // Pre-aggregation avoids WriteBatch visibility bug: if same credential has
        // multiple reward_rest entries (e.g., proposal refund + treasury withdrawal),
        // the second db.get() would read committed state and miss the first batch.put.
        java.util.Map<CredentialKey, BigInteger> perCredentialTotal = new java.util.LinkedHashMap<>();
        java.util.List<byte[]> keysToDelete = new java.util.ArrayList<>();

        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(new byte[]{PREFIX_REWARD_REST});
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 7 || key[0] != PREFIX_REWARD_REST) break;

                int spendableEpoch = ByteBuffer.wrap(key, 1, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                if (spendableEpoch > epoch) break;

                int credType = key[6] & 0xFF;
                String credHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 7, key.length));

                var rest = AccountStateCborCodec.decodeRewardRest(it.value());
                if (rest.amount().signum() > 0) {
                    perCredentialTotal.merge(CredentialKey.of(credType, credHash), rest.amount(), BigInteger::add);
                }
                keysToDelete.add(Arrays.copyOf(key, key.length));
                it.next();
            }
        } catch (Exception e) {
            log.error("creditAndRemoveSpendableRewardRest failed during iteration: {}", e.toString());
            return;
        }

        // Phase 2: Credit each credential once (single db.get + batch.put per credential)
        int credited = 0;
        BigInteger totalCredited = BigInteger.ZERO;
        try {
            for (var entry : perCredentialTotal.entrySet()) {
                CredentialKey ck = entry.getKey();
                BigInteger amount = entry.getValue();

                byte[] acctKey = accountKey(ck.typeInt(), ck.hash());
                byte[] acctVal = db.get(cfState, acctKey);
                if (acctVal != null) {
                    var acct = AccountStateCborCodec.decodeStakeAccount(acctVal);
                    BigInteger newReward = acct.reward().add(amount);
                    putStateWithDelta(acctKey, AccountStateCborCodec.encodeStakeAccount(newReward, acct.deposit()), batch, deltaOps);
                    credited++;
                    totalCredited = totalCredited.add(amount);
                }
            }

            // Phase 3: Delete all processed reward_rest entries
            for (byte[] key : keysToDelete) {
                deleteStateWithDelta(key, batch, deltaOps);
            }
        } catch (Exception e) {
            log.error("creditAndRemoveSpendableRewardRest failed during crediting: {}", e.toString());
        }

        if (credited > 0) {
            log.info("Credited {} spendable reward_rest entries for epoch {}: total={}",
                    credited, epoch, totalCredited);
        }
    }

    /**
     * Credit any pending (uncredited) non-MIR reward_rest entries.
     * Called at startup to repair state after restarting from an auto-checkpoint
     * that was taken between STEP_COMPLETE and PostEpochTransition.
     * Uses the last completed boundary epoch to determine the spendable cutoff.
     */
    public void creditPendingRewardRest() {
        int[] lastState = getLastBoundaryState();
        if (lastState == null) return;
        int lastEpoch = lastState[0];
        int lastStep = lastState[1];
        if (lastStep < com.bloxbean.cardano.yano.ledgerstate.EpochBoundaryProcessor.STEP_COMPLETE) return;

        // Check if there are any non-MIR reward_rest entries with spendableEpoch <= lastEpoch
        var pending = getSpendableRewardRest(lastEpoch);
        if (pending.isEmpty()) return;

        log.info("Repairing missed PostEpochTransition: found {} pending reward_rest entries for epoch <= {}",
                pending.size(), lastEpoch);
        try (org.rocksdb.WriteBatch batch = new org.rocksdb.WriteBatch();
             org.rocksdb.WriteOptions wo = new org.rocksdb.WriteOptions()) {
            List<DeltaOp> deltaOps = new ArrayList<>();
            creditAndRemoveSpendableRewardRest(lastEpoch, batch, deltaOps);
            long boundarySlot = slotForEpochStart(lastEpoch);
            commitBoundaryDelta(boundarySlot, PHASE_SPENDABLE_REST, batch, deltaOps);
            db.write(wo, batch);
        } catch (Exception e) {
            log.error("Failed to repair pending reward_rest: {}", e.toString());
        }
    }

    // --- Governance support: RewardCreditor and PoolStakeResolver ---

    /**
     * Credit a reward amount to the account identified by reward account hex.
     * Used by GovernanceEpochProcessor for proposal deposit refunds and treasury withdrawals.
     *
     * @param rewardAccountHex Reward account in hex (header byte + 28-byte credential hash)
     * @param amount           Amount to credit (lovelace)
     * @param batch            WriteBatch for atomic writes
     * @param deltaOps         Delta ops for rollback
     * @return true if credited to a registered account, false if account not registered (unclaimed)
     */
    public boolean creditRewardAccount(String rewardAccountHex, BigInteger amount,
                                       org.rocksdb.WriteBatch batch,
                                       java.util.List<DeltaOp> deltaOps) throws RocksDBException {
        if (rewardAccountHex == null || rewardAccountHex.length() < 58 || amount.signum() <= 0) {
            return false;
        }

        // Parse reward account: header(1 byte) + credential_hash(28 bytes)
        int headerByte;
        try {
            headerByte = Integer.parseInt(rewardAccountHex.substring(0, 2), 16);
        } catch (NumberFormatException e) {
            return false;
        }
        int credType = ((headerByte & 0x10) != 0) ? 1 : 0; // bit 4: 0=key, 1=script
        String credHash = rewardAccountHex.substring(2, 58);

        byte[] key = accountKey(credType, credHash);
        byte[] prev = db.get(cfState, key);
        if (prev == null) {
            // Account not registered — unclaimed
            return false;
        }

        // Add amount to existing reward balance
        var acct = AccountStateCborCodec.decodeStakeAccount(prev);
        BigInteger newReward = acct.reward().add(amount);
        byte[] val = AccountStateCborCodec.encodeStakeAccount(newReward, acct.deposit());
        batch.put(cfState, key, val);
        deltaOps.add(new DeltaOp(OP_PUT, key, prev));
        return true;
    }

    /**
     * Resolve pool stake distribution from epoch delegation snapshot.
     * Aggregates per-pool stakes from individual delegation entries.
     * Also builds pool→DRep delegation type map for SPO default votes.
     *
     * @param epoch Snapshot epoch number
     * @return PoolStakeData with pool stakes and DRep delegation mapping
     */
    public com.bloxbean.cardano.yano.ledgerstate.governance.epoch.GovernanceEpochProcessor.PoolStakeData
    resolvePoolStakeForEpoch(int epoch) throws RocksDBException {
        java.util.Map<String, BigInteger> poolStakes = new java.util.HashMap<>();
        java.util.Map<String, Integer> poolDRepDelegation = new java.util.HashMap<>();

        // Iterate epoch delegation snapshot: epoch(4 BE) + credType(1) + credHash(28) → {poolHash, amount}
        byte[] epochPrefix = new byte[4];
        java.nio.ByteBuffer.wrap(epochPrefix).order(java.nio.ByteOrder.BIG_ENDIAN).putInt(epoch);

        try (org.rocksdb.RocksIterator it = db.newIterator(cfEpochSnapshot)) {
            it.seek(epochPrefix);
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 5) break;
                int keyEpoch = java.nio.ByteBuffer.wrap(key, 0, 4).order(java.nio.ByteOrder.BIG_ENDIAN).getInt();
                if (keyEpoch != epoch) break;

                var snapshot = AccountStateCborCodec.decodeEpochDelegSnapshot(it.value());
                String poolHash = snapshot.poolHash();
                BigInteger stakeAmount = snapshot.amount();

                // Aggregate per-pool stake
                poolStakes.merge(poolHash, stakeAmount, BigInteger::add);

                it.next();
            }
        }

        // Build pool → DRep delegation type map
        // For each pool, look up its reward account's DRep delegation
        for (String poolHash : poolStakes.keySet()) {
            try {
                byte[] poolDepositKey = poolDepositKey(poolHash);
                byte[] poolVal = db.get(cfState, poolDepositKey);
                if (poolVal != null) {
                    var poolData = AccountStateCborCodec.decodePoolRegistration(poolVal);
                    String rewardAccount = poolData.rewardAccount();
                    if (rewardAccount != null && rewardAccount.length() >= 58) {
                        // Extract credential from pool reward account
                        int hdr = Integer.parseInt(rewardAccount.substring(0, 2), 16);
                        int ct = ((hdr & 0x10) != 0) ? 1 : 0;
                        String ch = rewardAccount.substring(2, 58);
                        byte[] drepDelegKey = drepDelegKey(ct, ch);
                        byte[] drepVal = db.get(cfState, drepDelegKey);
                        if (drepVal != null) {
                            var deleg = AccountStateCborCodec.decodeDRepDelegation(drepVal);
                            poolDRepDelegation.put(poolHash, deleg.drepType());
                        }
                    }
                }
            } catch (Exception e) {
                // Skip this pool's DRep delegation lookup on error
            }
        }

        return new com.bloxbean.cardano.yano.ledgerstate.governance.epoch.GovernanceEpochProcessor.PoolStakeData(
                poolStakes, poolDRepDelegation);
    }

    // --- Listing ---

    @Override
    public List<StakeRegistrationEntry> listStakeRegistrations(int page, int count) {
        if (page < 1) page = 1;
        if (count < 1) count = 1;
        int skip = (page - 1) * count;
        List<StakeRegistrationEntry> result = new ArrayList<>();
        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(new byte[]{PREFIX_ACCT});
            int skipped = 0;
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 2 || key[0] != PREFIX_ACCT) break;
                if (skipped++ < skip) { it.next(); continue; }
                int credType = key[1] & 0xFF;
                String credHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 2, key.length));
                var acct = AccountStateCborCodec.decodeStakeAccount(it.value());
                result.add(new StakeRegistrationEntry(credType, credHash, acct.reward(), acct.deposit()));
                if (result.size() >= count) break;
                it.next();
            }
        } catch (Exception e) {
            log.error("listStakeRegistrations failed: {}", e.toString());
        }
        return result;
    }

    @Override
    public List<PoolDelegationEntry> listPoolDelegations(int page, int count) {
        if (page < 1) page = 1;
        if (count < 1) count = 1;
        int skip = (page - 1) * count;
        List<PoolDelegationEntry> result = new ArrayList<>();
        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(new byte[]{PREFIX_POOL_DELEG});
            int skipped = 0;
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 2 || key[0] != PREFIX_POOL_DELEG) break;
                if (skipped++ < skip) { it.next(); continue; }
                int credType = key[1] & 0xFF;
                String credHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 2, key.length));
                var deleg = AccountStateCborCodec.decodePoolDelegation(it.value());
                result.add(new PoolDelegationEntry(credType, credHash, deleg.poolHash(), deleg.slot(), deleg.txIdx(), deleg.certIdx()));
                if (result.size() >= count) break;
                it.next();
            }
        } catch (Exception e) {
            log.error("listPoolDelegations failed: {}", e.toString());
        }
        return result;
    }

    @Override
    public List<DRepDelegationEntry> listDRepDelegations(int page, int count) {
        if (page < 1) page = 1;
        if (count < 1) count = 1;
        int skip = (page - 1) * count;
        List<DRepDelegationEntry> result = new ArrayList<>();
        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(new byte[]{PREFIX_DREP_DELEG});
            int skipped = 0;
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 2 || key[0] != PREFIX_DREP_DELEG) break;
                if (skipped++ < skip) { it.next(); continue; }
                int credType = key[1] & 0xFF;
                String credHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 2, key.length));
                var drep = AccountStateCborCodec.decodeDRepDelegation(it.value());
                result.add(new DRepDelegationEntry(credType, credHash, drep.drepType(), drep.drepHash(), drep.slot(), drep.txIdx(), drep.certIdx()));
                if (result.size() >= count) break;
                it.next();
            }
        } catch (Exception e) {
            log.error("listDRepDelegations failed: {}", e.toString());
        }
        return result;
    }

    @Override
    public List<PoolEntry> listPools(int page, int count) {
        if (page < 1) page = 1;
        if (count < 1) count = 1;
        int skip = (page - 1) * count;
        List<PoolEntry> result = new ArrayList<>();
        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(new byte[]{PREFIX_POOL_DEPOSIT});
            int skipped = 0;
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 2 || key[0] != PREFIX_POOL_DEPOSIT) break;
                if (skipped++ < skip) { it.next(); continue; }
                String poolHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 1, key.length));
                var deposit = AccountStateCborCodec.decodePoolDeposit(it.value());
                result.add(new PoolEntry(poolHash, deposit));
                if (result.size() >= count) break;
                it.next();
            }
        } catch (Exception e) {
            log.error("listPools failed: {}", e.toString());
        }
        return result;
    }

    @Override
    public List<PoolRetirementEntry> listPoolRetirements(int page, int count) {
        if (page < 1) page = 1;
        if (count < 1) count = 1;
        int skip = (page - 1) * count;
        List<PoolRetirementEntry> result = new ArrayList<>();
        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(new byte[]{PREFIX_POOL_RETIRE});
            int skipped = 0;
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 2 || key[0] != PREFIX_POOL_RETIRE) break;
                if (skipped++ < skip) { it.next(); continue; }
                String poolHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 1, key.length));
                long retireEpoch = AccountStateCborCodec.decodePoolRetirement(it.value());
                result.add(new PoolRetirementEntry(poolHash, retireEpoch));
                if (result.size() >= count) break;
                it.next();
            }
        } catch (Exception e) {
            log.error("listPoolRetirements failed: {}", e.toString());
        }
        return result;
    }

    // --- Epoch transition (called BEFORE first block of new epoch) ---

    @Override
    public void handleGenesisBlock(GenesisBlockEvent event) {
        if (!enabled || paramTracker == null || !paramTracker.isEnabled() || event == null) return;
        paramTracker.bootstrapEpochIfNeeded(event.epoch());
    }

    @Override
    public void handleEpochTransition(int previousEpoch, int newEpoch) {
        if (!enabled) return;

        // Process epoch boundary: rewards, adapot, protocol params, governance
        if (epochBoundaryProcessor != null) {
            try {
                epochBoundaryProcessor.processEpochBoundary(previousEpoch, newEpoch);
            } catch (Exception e) {
                log.warn("Epoch boundary processing failed for {} -> {}: {}",
                        previousEpoch, newEpoch, e.getMessage());
            }
        }

        // Clean up old per-block fact entries (block issuers + fees) with a configurable lag.
        // Data for stakeEpoch (newEpoch - 2) was just consumed by reward calculation.
        int clearEpoch = newEpoch - 2 - epochBlockDataRetentionLag;
        clearEpochBlockData(clearEpoch);
    }

    @Override
    public void handleEpochTransitionSnapshot(int previousEpoch, int newEpoch) {
        if (!enabled) return;
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            // Snapshot already created in handleEpochTransition (between rewards and governance).
            // Here we only prune old snapshots.

            // Prune old delegation snapshots (large, ~20MB each on mainnet).
            // Stake events are NOT pruned — they're small and needed for accurate
            // deregistered-account detection in reward calculations (cf-rewards).
            pruneOldSnapshots(newEpoch - snapshotRetentionEpochs, batch);

            db.write(wo, batch);
            log.info("Epoch transition {} -> {} completed (prune)", previousEpoch, newEpoch);

        } catch (Exception ex) {
            log.error("Epoch transition post-snapshot failed for {} -> {}: {}", previousEpoch, newEpoch, ex.toString());
        }
    }

    @Override
    public void handlePostEpochTransition(int previousEpoch, int newEpoch) {
        if (!enabled) return;

        // Credit spendable reward_rest (proposal refunds, treasury withdrawals) to PREFIX_ACCT.
        // Uses newEpoch: spendable_epoch=N means available at the START of epoch N,
        // so it must be credited at boundary N-1→N. This runs after governance (Phase 1 step 5)
        // which creates the reward_rest entries with spendable=newEpoch.
        // MIR types are already credited before the snapshot via creditMirRewardRest(newEpoch).
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            List<DeltaOp> deltaOps = new ArrayList<>();
            creditAndRemoveSpendableRewardRest(newEpoch, batch, deltaOps);
            long boundarySlot = slotForEpochStart(newEpoch);
            commitBoundaryDelta(boundarySlot, PHASE_SPENDABLE_REST, batch, deltaOps);
            db.write(wo, batch);
        } catch (Exception ex) {
            log.error("Post-epoch reward_rest credit failed for {} -> {}: {}", previousEpoch, newEpoch, ex.toString());
        }

        if (epochBoundaryProcessor == null) return;
        try {
            epochBoundaryProcessor.processPostEpochBoundary(newEpoch);
        } catch (Exception e) {
            log.warn("Post-epoch boundary processing failed for {} -> {}: {}",
                    previousEpoch, newEpoch, e.getMessage());
        }
    }

    // --- Block application ---

    @Override
    public void applyBlock(BlockAppliedEvent event) {
        if (!enabled) return;
        Block block = event.block();
        if (block == null) return;

        long slot = event.slot();
        long blockNo = event.blockNumber();

        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            int currentEpoch = epochForSlot(slot);

            List<DeltaOp> deltaOps = new ArrayList<>();
            BigInteger totalDepositedDelta = BigInteger.ZERO;

            // Identify invalid transactions
            List<Integer> invList = block.getInvalidTransactions();
            Set<Integer> invalidIdx = (invList != null) ? new HashSet<>(invList) : Collections.emptySet();
            List<TransactionBody> txs = block.getTransactionBodies();

            // Initialize per-block WriteBatch overlay for DRep delegation reverse index
            batchForwardDeleg = new HashMap<>();
            batchReverseAdded = new HashMap<>();
            batchReverseRemoved = new HashMap<>();

            if (txs != null) {
                for (int txIdx = 0; txIdx < txs.size(); txIdx++) {
                    if (invalidIdx.contains(txIdx)) continue;
                    TransactionBody tx = txs.get(txIdx);

                    // Per Cardano ledger spec (shelley-ledger.pdf): UTXOW (withdrawals) before DELEGS (certificates).
                    // Processing withdrawals first ensures that a same-tx deregistration correctly
                    // removes the account after the withdrawal debits the reward balance.
                    // Wrong order would cause the withdrawal's batch.put() to overwrite the
                    // deregistration's batch.delete(), leaving the account alive.

                    // Process withdrawals — debit reward balance (UTXOW phase)
                    Map<String, BigInteger> withdrawals = tx.getWithdrawals();
                    if (withdrawals != null) {
                        for (var entry : withdrawals.entrySet()) {
                            processWithdrawal(entry.getKey(), entry.getValue(), batch, deltaOps);
                        }
                    }

                    // Process certificates (DELEGS phase)
                    List<Certificate> certs = tx.getCertificates();
                    if (certs != null) {
                        for (int certIdx = 0; certIdx < certs.size(); certIdx++) {
                            totalDepositedDelta = totalDepositedDelta.add(
                                    processCertificate(certs.get(certIdx), slot, currentEpoch,
                                            txIdx, certIdx, event.era(), batch, deltaOps));
                        }
                    }

                    // Track protocol parameter updates (persisted atomically via batch)
                    if (paramTracker != null && paramTracker.isEnabled()) {
                        paramTracker.processTransaction(tx, slot, txIdx, batch);
                    }
                }
            }

            // Process governance actions (proposals, votes, donations)
            if (governanceBlockProcessor != null) {
                try {
                    governanceBlockProcessor.processBlock(block, slot, currentEpoch, batch, deltaOps);
                } catch (Exception e) {
                    log.warn("Governance block processing failed for block {}: {}", blockNo, e.getMessage());
                }
            }

            // Track per-pool block count and per-epoch fees
            trackBlockCountAndFees(block, currentEpoch, slot, txs, invalidIdx, batch, deltaOps);

            // Update total deposited
            if (totalDepositedDelta.signum() != 0) {
                BigInteger current = getTotalDeposited();
                BigInteger updated = current.add(totalDepositedDelta);
                if (updated.signum() < 0) updated = BigInteger.ZERO;

                byte[] prev = totalDepositedToBytes(current);
                byte[] newVal = totalDepositedToBytes(updated);
                batch.put(cfState, META_TOTAL_DEPOSITED, newVal);
                deltaOps.add(new DeltaOp(OP_PUT, META_TOTAL_DEPOSITED, prev));
            }

            // Write delta log
            byte[] deltaKey = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(blockNo).array();
            byte[] deltaVal = encodeDelta(slot, deltaOps);
            batch.put(cfDelta, deltaKey, deltaVal);

            // Update last applied block and slot
            byte[] blockNoBytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(blockNo).array();
            batch.put(cfState, META_LAST_APPLIED_BLOCK, blockNoBytes);
            batch.put(cfState, META_LAST_APPLIED_SLOT,
                    ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(slot).array());

            db.write(wo, batch);
        } catch (Exception ex) {
            log.error("Account state apply failed for block {}: {}", blockNo, ex.toString());
        } finally {
            batchForwardDeleg = null;
            batchReverseAdded = null;
            batchReverseRemoved = null;
        }
    }

    private BigInteger processCertificate(Certificate cert, long slot, int currentEpoch,
                                          int txIdx, int certIdx, Era era,
                                          WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        BigInteger depositDelta = BigInteger.ZERO;

        switch (cert) {
            case StakeRegistration sr -> {
                depositDelta = registerStake(sr.getStakeCredential(),
                        epochParamProvider.getKeyDeposit(0), slot, txIdx, certIdx, batch, deltaOps);
            }
            case RegCert rc -> {
                BigInteger deposit = rc.getCoin() != null ? rc.getCoin() : BigInteger.ZERO;
                depositDelta = registerStake(rc.getStakeCredential(), deposit,
                        slot, txIdx, certIdx, batch, deltaOps);
            }
            case StakeDeregistration sd -> {
                depositDelta = deregisterStake(sd.getStakeCredential(),
                        slot, txIdx, certIdx, batch, deltaOps);
            }
            case UnregCert uc -> {
                depositDelta = deregisterStake(uc.getStakeCredential(),
                        slot, txIdx, certIdx, batch, deltaOps);
            }
            case StakeDelegation sd -> {
                delegateToPool(sd.getStakeCredential(), sd.getStakePoolId().getPoolKeyHash(),
                        slot, txIdx, certIdx, batch, deltaOps);
            }
            case VoteDelegCert vd -> {
                delegateToDRep(vd.getStakeCredential(), vd.getDrep(),
                        slot, txIdx, certIdx, currentEpoch, batch, deltaOps);
            }
            case StakeVoteDelegCert svd -> {
                delegateToPool(svd.getStakeCredential(), svd.getPoolKeyHash(),
                        slot, txIdx, certIdx, batch, deltaOps);
                delegateToDRep(svd.getStakeCredential(), svd.getDrep(),
                        slot, txIdx, certIdx, currentEpoch, batch, deltaOps);
            }
            case StakeRegDelegCert srd -> {
                BigInteger deposit = srd.getCoin() != null ? srd.getCoin() : BigInteger.ZERO;
                depositDelta = registerStake(srd.getStakeCredential(), deposit,
                        slot, txIdx, certIdx, batch, deltaOps);
                delegateToPool(srd.getStakeCredential(), srd.getPoolKeyHash(),
                        slot, txIdx, certIdx, batch, deltaOps);
            }
            case VoteRegDelegCert vrd -> {
                BigInteger deposit = vrd.getCoin() != null ? vrd.getCoin() : BigInteger.ZERO;
                depositDelta = registerStake(vrd.getStakeCredential(), deposit,
                        slot, txIdx, certIdx, batch, deltaOps);
                delegateToDRep(vrd.getStakeCredential(), vrd.getDrep(),
                        slot, txIdx, certIdx, currentEpoch, batch, deltaOps);
            }
            case StakeVoteRegDelegCert svrd -> {
                BigInteger deposit = svrd.getCoin() != null ? svrd.getCoin() : BigInteger.ZERO;
                depositDelta = registerStake(svrd.getStakeCredential(), deposit,
                        slot, txIdx, certIdx, batch, deltaOps);
                delegateToPool(svrd.getStakeCredential(), svrd.getPoolKeyHash(),
                        slot, txIdx, certIdx, batch, deltaOps);
                delegateToDRep(svrd.getStakeCredential(), svrd.getDrep(),
                        slot, txIdx, certIdx, currentEpoch, batch, deltaOps);
            }
            case PoolRegistration pr -> {
                var params = pr.getPoolParams();
                String poolHash = params.getOperator();
                byte[] key = poolDepositKey(poolHash);
                byte[] prev = db.get(cfState, key);

                var margin = params.getMargin();
                BigInteger marginNum = margin != null ? margin.getNumerator() : BigInteger.ZERO;
                BigInteger marginDen = margin != null ? margin.getDenominator() : BigInteger.ONE;
                BigInteger cost = params.getCost() != null ? params.getCost() : BigInteger.ZERO;
                BigInteger pledge = params.getPledge() != null ? params.getPledge() : BigInteger.ZERO;
                String rewardAccount = params.getRewardAccount() != null ? params.getRewardAccount() : "";
                Set<String> owners = params.getPoolOwners() != null ? params.getPoolOwners() : Set.of();

                var data = new AccountStateCborCodec.PoolRegistrationData(
                        epochParamProvider.getPoolDeposit(0),
                        marginNum, marginDen, cost, pledge, rewardAccount, owners);
                byte[] val = AccountStateCborCodec.encodePoolRegistration(data);
                batch.put(cfState, key, val);
                deltaOps.add(new DeltaOp(OP_PUT, key, prev));

                // Cancel any pending retirement
                byte[] retKey = poolRetireKey(poolHash);
                byte[] retPrev = db.get(cfState, retKey);
                boolean reRegisteredAfterRetirement = false;
                if (retPrev != null) {
                    long retireEpoch = AccountStateCborCodec.decodePoolRetirement(retPrev);
                    reRegisteredAfterRetirement = (retireEpoch <= currentEpoch);
                }
                if (retPrev != null) {
                    batch.delete(cfState, retKey);
                    deltaOps.add(new DeltaOp(OP_DELETE, retKey, retPrev));
                }

                // Write pool params history keyed by ACTIVE epoch.
                // On Cardano, a new pool registration takes effect at epoch + 2.
                // A normal pool update takes effect at epoch + 3.
                // Re-registration after retirement starts a fresh pool lifecycle, so its params
                // should become active on the same cadence as a fresh registration (+2), not +3.
                boolean isNewPool = (prev == null); // no existing pool deposit entry = first registration
                boolean treatAsFreshRegistration = isNewPool || reRegisteredAfterRetirement;
                int activeEpoch = treatAsFreshRegistration ? currentEpoch + 2 : currentEpoch + 3;
                byte[] histKey = poolParamsHistKey(poolHash, activeEpoch);
                byte[] histPrev = db.get(cfState, histKey);
                batch.put(cfState, histKey, val);
                deltaOps.add(new DeltaOp(OP_PUT, histKey, histPrev));

                // Track pool registration slot: set on first registration or re-registration after retirement.
                // Used by snapshot creation to exclude stale delegations (delegated before pool's current lifecycle).
                if (treatAsFreshRegistration) {
                    byte[] regSlotKey = poolRegSlotKey(poolHash);
                    byte[] regSlotPrev = db.get(cfState, regSlotKey);
                    byte[] regSlotVal = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(slot).array();
                    batch.put(cfState, regSlotKey, regSlotVal);
                    deltaOps.add(new DeltaOp(OP_PUT, regSlotKey, regSlotPrev));
                }
            }
            case PoolRetirement pr -> {
                byte[] key = poolRetireKey(pr.getPoolKeyHash());
                byte[] prev = db.get(cfState, key);
                byte[] val = AccountStateCborCodec.encodePoolRetirement(pr.getEpoch());
                batch.put(cfState, key, val);
                deltaOps.add(new DeltaOp(OP_PUT, key, prev));
            }
            case RegDrepCert rd -> {
                int ct = credTypeFromModel(rd.getDrepCredential());
                String hash = rd.getDrepCredential().getHash();
                BigInteger deposit = rd.getCoin() != null ? rd.getCoin() : BigInteger.ZERO;
                byte[] key = drepRegKey(ct, hash);
                byte[] prev = db.get(cfState, key);
                byte[] val = AccountStateCborCodec.encodeDRepRegistration(deposit);
                batch.put(cfState, key, val);
                deltaOps.add(new DeltaOp(OP_PUT, key, prev));
                depositDelta = deposit;
                // Governance dual-write: richer DRepStateRecord
                if (governanceBlockProcessor != null) {
                    governanceBlockProcessor.processDRepRegistration(rd, slot, currentEpoch, batch, deltaOps);
                }
            }
            case UnregDrepCert ud -> {
                int ct = credTypeFromModel(ud.getDrepCredential());
                String hash = ud.getDrepCredential().getHash();
                byte[] key = drepRegKey(ct, hash);
                byte[] prev = db.get(cfState, key);
                if (prev != null) {
                    BigInteger refund = AccountStateCborCodec.decodeDRepDeposit(prev);
                    depositDelta = refund.negate();
                    batch.delete(cfState, key);
                    deltaOps.add(new DeltaOp(OP_DELETE, key, prev));
                }
                // Governance dual-write: track deregistration for v9 bug
                if (governanceBlockProcessor != null) {
                    governanceBlockProcessor.processDRepDeregistration(ud, slot, batch, deltaOps);
                }
                // Haskell origin/master ConwayUnRegDRep (GovCert.hs) clears delegations
                // using drepDelegs (the DRep's reverse delegation set).
                // PV9: drepDelegs has stale entries (re-delegated creds not removed) → over-clears.
                // PV10: drepDelegs rebuilt at hardfork (HardFork.hs updateDRepDelegations) → clean.
                // Yano replicates this via PREFIX_DREP_DELEG_REVERSE:
                //   PV9: stale reverse entries preserved → cleanup matches Haskell PV9 bug behavior.
                //   PV10: rebuildDRepDelegReverseIndexIfNeeded() rebuilds from forward delegations
                //         at the hardfork boundary → cleanup is correct post-rebuild.
                if (isCredentialDRep(ct)) {
                    clearDRepDelegationsForDeregisteredDRep(ct, hash,
                            slot, txIdx, certIdx, batch, deltaOps);
                }
            }
            case UpdateDrepCert upd -> {
                // DRep update only changes anchor — deposit stays the same.
                // We re-write the existing entry to keep the key alive.
                int ct = credTypeFromModel(upd.getDrepCredential());
                String hash = upd.getDrepCredential().getHash();
                byte[] key = drepRegKey(ct, hash);
                byte[] prev = db.get(cfState, key);
                if (prev != null) {
                    // Preserve existing deposit
                    batch.put(cfState, key, prev);
                    deltaOps.add(new DeltaOp(OP_PUT, key, prev));
                }
                // Governance dual-write: update anchor + track interaction
                if (governanceBlockProcessor != null) {
                    governanceBlockProcessor.processDRepUpdate(upd, currentEpoch, batch, deltaOps);
                }
            }
            case AuthCommitteeHotCert ac -> {
                int coldCt = credTypeFromModel(ac.getCommitteeColdCredential());
                String coldHash = ac.getCommitteeColdCredential().getHash();
                int hotCt = credTypeFromModel(ac.getCommitteeHotCredential());
                String hotHash = ac.getCommitteeHotCredential().getHash();

                byte[] key = committeeHotKey(coldCt, coldHash);
                byte[] prev = db.get(cfState, key);
                byte[] val = AccountStateCborCodec.encodeCommitteeHotKey(hotCt, hotHash);
                batch.put(cfState, key, val);
                deltaOps.add(new DeltaOp(OP_PUT, key, prev));
                // Governance dual-write: richer CommitteeMemberRecord
                if (governanceBlockProcessor != null) {
                    governanceBlockProcessor.processCommitteeHotKeyAuth(ac, batch, deltaOps);
                }
            }
            case ResignCommitteeColdCert rc -> {
                int ct = credTypeFromModel(rc.getCommitteeColdCredential());
                String coldHash = rc.getCommitteeColdCredential().getHash();

                byte[] key = committeeResignKey(ct, coldHash);
                byte[] prev = db.get(cfState, key);
                byte[] val = AccountStateCborCodec.encodeCommitteeResignation();
                batch.put(cfState, key, val);
                deltaOps.add(new DeltaOp(OP_PUT, key, prev));
                // Governance dual-write: mark member as resigned
                if (governanceBlockProcessor != null) {
                    governanceBlockProcessor.processCommitteeResignation(rc, batch, deltaOps);
                }
            }
            case MoveInstataneous mir -> {
                processMir(mir, currentEpoch, era, batch, deltaOps);
            }
            default -> {
                // Unknown certificate type — skip
            }
        }

        return depositDelta;
    }

    private static byte[] acctRegSlotKey(int credType, String credHash) {
        byte[] hash = HexUtil.decodeHexString(credHash);
        byte[] key = new byte[1 + 1 + hash.length];
        key[0] = PREFIX_ACCT_REG_SLOT;
        key[1] = (byte) credType;
        System.arraycopy(hash, 0, key, 2, hash.length);
        return key;
    }

    /** Key for pointer address mapping: PREFIX_POINTER_ADDR | slot(8 BE) | txIdx(4 BE) | certIdx(4 BE) */
    private static byte[] pointerAddrKey(long slot, int txIdx, int certIdx) {
        byte[] key = new byte[1 + 8 + 4 + 4];
        key[0] = PREFIX_POINTER_ADDR;
        ByteBuffer.wrap(key, 1, 8).order(ByteOrder.BIG_ENDIAN).putLong(slot);
        ByteBuffer.wrap(key, 9, 4).order(ByteOrder.BIG_ENDIAN).putInt(txIdx);
        ByteBuffer.wrap(key, 13, 4).order(ByteOrder.BIG_ENDIAN).putInt(certIdx);
        return key;
    }

    /** Value for pointer address mapping: credType(1) | credHash(28) */
    private static byte[] pointerAddrValue(int credType, String credHash) {
        byte[] hash = HexUtil.decodeHexString(credHash);
        byte[] val = new byte[1 + hash.length];
        val[0] = (byte) credType;
        System.arraycopy(hash, 0, val, 1, hash.length);
        return val;
    }

    private BigInteger registerStake(StakeCredential cred, BigInteger deposit,
                                     long slot, int txIdx, int certIdx,
                                     WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        int ct = credTypeInt(cred.getType());
        byte[] key = accountKey(ct, cred.getHash());
        byte[] prev = db.get(cfState, key);
        byte[] val = AccountStateCborCodec.encodeStakeAccount(BigInteger.ZERO, deposit);
        batch.put(cfState, key, val);
        deltaOps.add(new DeltaOp(OP_PUT, key, prev));

        // Re-registration after deregistration: clean up stale pool/DRep delegation entries.
        // Per Haskell ledger (Deleg.hs): re-registration starts fresh with no delegation.
        // deregisterStake already deletes these, but backups from older code may have stale entries.
        if (prev == null) {
            byte[] delegKey = poolDelegKey(ct, cred.getHash());
            byte[] delegPrev = db.get(cfState, delegKey);
            if (delegPrev != null) {
                batch.delete(cfState, delegKey);
                deltaOps.add(new DeltaOp(OP_DELETE, delegKey, delegPrev));
            }
            byte[] drepKey = drepDelegKey(ct, cred.getHash());
            byte[] drepPrev = db.get(cfState, drepKey);
            if (drepPrev != null) {
                batch.delete(cfState, drepKey);
                deltaOps.add(new DeltaOp(OP_DELETE, drepKey, drepPrev));
            }
        }

        // Track registration slot — used by snapshot to detect stale delegations
        // from before the last deregistration/re-registration cycle.
        byte[] regSlotKey = acctRegSlotKey(ct, cred.getHash());
        byte[] regSlotPrev = db.get(cfState, regSlotKey);
        byte[] regSlotVal = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(slot).array();
        batch.put(cfState, regSlotKey, regSlotVal);
        deltaOps.add(new DeltaOp(OP_PUT, regSlotKey, regSlotPrev));

        // Write stake event
        byte[] eventKey = stakeEventKey(slot, txIdx, certIdx, ct, cred.getHash());
        byte[] eventVal = AccountStateCborCodec.encodeStakeEvent(AccountStateCborCodec.EVENT_REGISTRATION);
        batch.put(cfState, eventKey, eventVal);
        deltaOps.add(new DeltaOp(OP_PUT, eventKey, null));

        // Persist pointer address mapping in RocksDB (survives restarts).
        // Pointer addresses are only relevant pre-Conway (removed in Conway era per CIP-0019).
        // StakeRegistration certs are pre-Conway; RegCert/StakeRegDelegCert etc. are Conway.
        // We write for all registrations since the storage is tiny and harmless, but the
        // resolver is only used when era < Conway (see createAndCommitDelegationSnapshot).
        byte[] ptrKey = pointerAddrKey(slot, txIdx, certIdx);
        byte[] ptrVal = pointerAddrValue(ct, cred.getHash());
        batch.put(cfState, ptrKey, ptrVal);
        deltaOps.add(new DeltaOp(OP_PUT, ptrKey, null));

        return deposit;
    }

    private BigInteger deregisterStake(StakeCredential cred,
                                       long slot, int txIdx, int certIdx,
                                       WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        int ct = credTypeInt(cred.getType());
        BigInteger depositRefund = BigInteger.ZERO;

        // Remove account
        byte[] acctKey = accountKey(ct, cred.getHash());
        byte[] acctPrev = db.get(cfState, acctKey);
        if (acctPrev != null) {
            depositRefund = AccountStateCborCodec.decodeStakeAccount(acctPrev).deposit().negate();
            batch.delete(cfState, acctKey);
            deltaOps.add(new DeltaOp(OP_DELETE, acctKey, acctPrev));
        }

        // Per Haskell ledger (Deleg.hs): deregistration completely removes the account entry
        // from dsAccounts via Map.extract, discarding pool and DRep delegations with it.
        // Re-registration starts fresh with no delegations.
        // Also cleans the DRep reverse index (PREFIX_DREP_DELEG_REVERSE) below.
        //
        // Always delete unconditionally — the delegation may exist in the uncommitted WriteBatch
        // (e.g., delegation and deregistration in the same block) where db.get() won't find it.
        byte[] delegKey = poolDelegKey(ct, cred.getHash());
        byte[] delegPrev = db.get(cfState, delegKey);
        batch.delete(cfState, delegKey);
        deltaOps.add(new DeltaOp(OP_DELETE, delegKey, delegPrev)); // delegPrev may be null — rollback handles it

        // Remove DRep delegation (same behavior: Conway unregisterConwayAccount calls
        // unDelegReDelegDRep with Nothing, clearing the DRep delegation)
        byte[] drepKey = drepDelegKey(ct, cred.getHash());
        String credId = ct + ":" + cred.getHash();
        // Check overlay first for current delegation (handles same-block delegation+deregistration)
        byte[] drepPrev;
        if (batchForwardDeleg != null && batchForwardDeleg.containsKey(credId)) {
            drepPrev = batchForwardDeleg.get(credId);
        } else {
            drepPrev = db.get(cfState, drepKey);
        }
        batch.delete(cfState, drepKey);
        deltaOps.add(new DeltaOp(OP_DELETE, drepKey, drepPrev)); // drepPrev may be null
        if (batchForwardDeleg != null) batchForwardDeleg.put(credId, null);

        // Also remove this delegator's current reverse index entry (if pointing to a credential DRep).
        // Do NOT try to delete historical/stale reverse entries — preserving PV9 stale entries is intentional.
        if (drepPrev != null) {
            var deleg = AccountStateCborCodec.decodeDRepDelegation(drepPrev);
            if (isCredentialDRep(deleg.drepType())) {
                byte[] revKey = drepDelegReverseKey(deleg.drepType(), deleg.drepHash(), ct, cred.getHash());
                byte[] revPrev = db.get(cfState, revKey);
                batch.delete(cfState, revKey);
                deltaOps.add(new DeltaOp(OP_DELETE, revKey, revPrev));
                // Update overlay
                if (batchReverseRemoved != null) {
                    String drepId = deleg.drepType() + ":" + deleg.drepHash();
                    batchReverseRemoved.computeIfAbsent(drepId, k -> new HashSet<>()).add(credId);
                    var added = batchReverseAdded.get(drepId);
                    if (added != null) added.remove(credId);
                }
            }
        }

        // Write stake event
        byte[] eventKey = stakeEventKey(slot, txIdx, certIdx, ct, cred.getHash());
        byte[] eventVal = AccountStateCborCodec.encodeStakeEvent(AccountStateCborCodec.EVENT_DEREGISTRATION);
        batch.put(cfState, eventKey, eventVal);
        deltaOps.add(new DeltaOp(OP_PUT, eventKey, null));

        return depositRefund;
    }

    /**
     * Clear DRep delegations for credentials in the deregistered DRep's reverse index.
     * Matches Haskell origin/master ConwayUnRegDRep which clears delegations via drepDelegs.
     * <p>
     * In PV9, drepDelegs has stale entries (re-delegated creds not removed on re-delegation).
     * In PV10+, drepDelegs is rebuilt at the hardfork boundary (see rebuildDRepDelegReverseIndex),
     * so only currently-delegated credentials appear. The cleanup behavior is the same in both
     * protocol versions — the difference is in the reverse index state.
     */
    private void clearDRepDelegationsForDeregisteredDRep(int drepType, String drepHash,
                                                          long deregSlot, int deregTxIdx, int deregCertIdx,
                                                          WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        Set<String> processed = new HashSet<>();

        // Pass 1: scan committed RocksDB reverse entries
        byte[] seekPrefix = drepDelegReverseSeekPrefix(drepType, drepHash);
        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(seekPrefix);
            while (it.isValid()) {
                byte[] revKey = it.key();
                if (revKey.length < seekPrefix.length) break;
                boolean prefixMatch = true;
                for (int i = 0; i < seekPrefix.length; i++) {
                    if (revKey[i] != seekPrefix[i]) { prefixMatch = false; break; }
                }
                if (!prefixMatch) break;

                int offset = 2 + 28; // skip PREFIX + drepType + drepHash
                if (revKey.length < offset + 1 + 28) { it.next(); continue; }
                int delegatorCredType = revKey[offset] & 0xFF;
                String delegatorHash = HexUtil.encodeHexString(
                        java.util.Arrays.copyOfRange(revKey, offset + 1, offset + 1 + 28));
                String delegatorId = delegatorCredType + ":" + delegatorHash;

                String drepId = drepType + ":" + drepHash;
                if (batchReverseRemoved != null) {
                    var removed = batchReverseRemoved.get(drepId);
                    if (removed != null && removed.contains(delegatorId)) { it.next(); continue; }
                }

                clearDelegationIfNotAfter(delegatorCredType, delegatorHash, delegatorId,
                        drepType, drepHash, deregSlot, deregTxIdx, deregCertIdx, batch, deltaOps);
                processed.add(delegatorId);

                byte[] revPrev = it.value();
                byte[] revKeyCopy = java.util.Arrays.copyOf(revKey, revKey.length);
                batch.delete(cfState, revKeyCopy);
                deltaOps.add(new DeltaOp(OP_DELETE, revKeyCopy, revPrev));
                if (batchReverseRemoved != null) {
                    batchReverseRemoved.computeIfAbsent(drepId, k -> new HashSet<>()).add(delegatorId);
                    var added = batchReverseAdded.get(drepId);
                    if (added != null) added.remove(delegatorId);
                }

                it.next();
            }
        }

        // Pass 2: scan pending reverse entries from the current block's batch overlay
        if (batchReverseAdded != null) {
            String drepId = drepType + ":" + drepHash;
            Set<String> pending = batchReverseAdded.get(drepId);
            if (pending != null) {
                for (String delegatorId : new ArrayList<>(pending)) {
                    if (processed.contains(delegatorId)) continue;
                    String[] parts = delegatorId.split(":", 2);
                    int delegatorCredType = Integer.parseInt(parts[0]);
                    String delegatorHash = parts[1];

                    clearDelegationIfNotAfter(delegatorCredType, delegatorHash, delegatorId,
                            drepType, drepHash, deregSlot, deregTxIdx, deregCertIdx, batch, deltaOps);

                    byte[] revKey = drepDelegReverseKey(drepType, drepHash, delegatorCredType, delegatorHash);
                    byte[] revPrev = db.get(cfState, revKey);
                    batch.delete(cfState, revKey);
                    deltaOps.add(new DeltaOp(OP_DELETE, revKey, revPrev));
                }
                pending.clear();
            }
        }
    }

    /** Check forward delegation and clear it if its pointer is not strictly after the deregistration pointer. */
    private void clearDelegationIfNotAfter(int delegatorCredType, String delegatorHash, String delegatorId,
                                            int deregDrepType, String deregDrepHash,
                                            long deregSlot, int deregTxIdx, int deregCertIdx,
                                            WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        byte[] fwdKey = drepDelegKey(delegatorCredType, delegatorHash);
        byte[] fwdVal;
        if (batchForwardDeleg != null && batchForwardDeleg.containsKey(delegatorId)) {
            fwdVal = batchForwardDeleg.get(delegatorId);
        } else {
            fwdVal = db.get(cfState, fwdKey);
        }
        if (fwdVal != null) {
            var deleg = AccountStateCborCodec.decodeDRepDelegation(fwdVal);
            if (deleg.drepType() != deregDrepType || !Objects.equals(deleg.drepHash(), deregDrepHash)) {
                return;
            }
            boolean delegAfterDereg = deleg.slot() > deregSlot
                    || (deleg.slot() == deregSlot && deleg.txIdx() > deregTxIdx)
                    || (deleg.slot() == deregSlot && deleg.txIdx() == deregTxIdx
                        && deleg.certIdx() > deregCertIdx);
            if (!delegAfterDereg) {
                batch.delete(cfState, fwdKey);
                deltaOps.add(new DeltaOp(OP_DELETE, fwdKey, fwdVal));
                if (batchForwardDeleg != null) batchForwardDeleg.put(delegatorId, null);
            }
        }
    }

    /**
     * Rebuild PREFIX_DREP_DELEG_REVERSE at PV10 hardfork boundary if not already done.
     * Matches Haskell's {@code updateDRepDelegations} (HardFork.hs) which:
     * <ol>
     *   <li>Resets all drepDelegs to empty</li>
     *   <li>Rebuilds from current account delegations</li>
     *   <li>Removes dangling delegations to non-existent DReps</li>
     * </ol>
     * Owns its own WriteBatch — opens, rebuilds, writes marker, commits atomically.
     *
     * @param newEpoch          The new epoch number
     * @param registeredDRepIds Set of "drepType:drepHash" for currently registered DReps
     *                          (previousDeregistrationSlot == null || registeredAtSlot > previousDeregistrationSlot)
     * @param ep                EpochParamProvider for protocol version lookup
     */
    public void rebuildDRepDelegReverseIndexIfNeeded(int newEpoch,
            Set<String> registeredDRepIds, EpochParamProvider ep) throws RocksDBException {
        int newMajor = ep.getProtocolMajor(newEpoch);
        if (newMajor < 10) return;
        if (db.get(cfState, MARKER_PV10_REVERSE_REBUILD) != null) return;
        // Marker missing and PV10+ → rebuild needed
        if (registeredDRepIds == null) {
            throw new IllegalStateException("registeredDRepIds must not be null for PV10 reverse-index rebuild");
        }

        log.info("PV10 hardfork: rebuilding DRep delegation reverse index...");
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            rebuildDRepDelegReverseIndex(registeredDRepIds, batch);
            batch.put(cfState, MARKER_PV10_REVERSE_REBUILD, new byte[]{1});
            db.write(wo, batch);
        }
    }

    /**
     * Rebuild reverse index from current forward delegations.
     * Deletes all existing reverse entries, then rebuilds from PREFIX_DREP_DELEG.
     * Dangling forward delegations (to non-existent DReps) are deleted.
     */
    private void rebuildDRepDelegReverseIndex(Set<String> registeredDRepIds,
            WriteBatch batch) throws RocksDBException {
        // Assert overlays are inactive (epoch boundary, not block processing)
        if (batchForwardDeleg != null || batchReverseAdded != null || batchReverseRemoved != null) {
            throw new IllegalStateException("Overlay maps must be null during PV10 reverse-index rebuild");
        }

        // 1. Delete all existing reverse entries
        byte[] seekPrefix = new byte[]{PREFIX_DREP_DELEG_REVERSE};
        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(seekPrefix);
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 1 || key[0] != PREFIX_DREP_DELEG_REVERSE) break;
                batch.delete(cfState, java.util.Arrays.copyOf(key, key.length));
                it.next();
            }
        }

        // 2. Iterate all forward delegations, rebuild reverse + clean dangling
        int rebuilt = 0, dangling = 0;
        byte[] fwdSeek = new byte[]{PREFIX_DREP_DELEG};
        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(fwdSeek);
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 2 || key[0] != PREFIX_DREP_DELEG) break;

                byte[] keyCopy = java.util.Arrays.copyOf(key, key.length);
                byte[] rawVal = it.value();
                if (rawVal == null) { it.next(); continue; }
                byte[] valCopy = java.util.Arrays.copyOf(rawVal, rawVal.length);

                int credType = keyCopy[1] & 0xFF;
                String credHash = HexUtil.encodeHexString(
                        java.util.Arrays.copyOfRange(keyCopy, 2, keyCopy.length));
                var deleg = AccountStateCborCodec.decodeDRepDelegation(valCopy);
                int drepType = deleg.drepType();
                String drepHash = deleg.drepHash();

                if (isCredentialDRep(drepType)) {
                    String drepId = drepType + ":" + drepHash;
                    if (registeredDRepIds.contains(drepId)) {
                        // Registered DRep → add reverse entry
                        byte[] revKey = drepDelegReverseKey(drepType, drepHash, credType, credHash);
                        batch.put(cfState, revKey, new byte[]{1});
                        rebuilt++;
                    } else {
                        // Dangling delegation to non-existent DRep → delete forward
                        batch.delete(cfState, keyCopy);
                        dangling++;
                    }
                }
                // Virtual DReps (ABSTAIN, NO_CONFIDENCE): no reverse entry needed

                it.next();
            }
        }
        log.info("PV10 reverse-index rebuild: {} reverse entries rebuilt, {} dangling forward delegations removed",
                rebuilt, dangling);
    }

    private void delegateToPool(StakeCredential cred, String poolHash,
                                long slot, int txIdx, int certIdx,
                                WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        int ct = credTypeInt(cred.getType());
        byte[] key = poolDelegKey(ct, cred.getHash());
        byte[] prev = db.get(cfState, key);
        byte[] val = AccountStateCborCodec.encodePoolDelegation(poolHash, slot, txIdx, certIdx);
        batch.put(cfState, key, val);
        deltaOps.add(new DeltaOp(OP_PUT, key, prev));
    }

    private void delegateToDRep(StakeCredential cred, Drep drep,
                                long slot, int txIdx, int certIdx, int currentEpoch,
                                WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        int ct = credTypeInt(cred.getType());
        String credHash = cred.getHash();
        byte[] key = drepDelegKey(ct, credHash);
        String credId = ct + ":" + credHash;
        int newDrepType = drepTypeInt(drep.getType());

        // Read previous delegation: overlay first (handles same-block re-delegation), then committed
        byte[] prev;
        if (batchForwardDeleg != null && batchForwardDeleg.containsKey(credId)) {
            prev = batchForwardDeleg.get(credId); // may be null (deleted in this batch)
        } else {
            prev = db.get(cfState, key);
        }

        // Maintain reverse index for DRep deregistration cleanup.
        // PV9 bug-compat: on re-delegation from credential DRep A to credential DRep C,
        // do NOT remove A's reverse entry. This preserves the Haskell UMap internal
        // drepDelegs structure where stale entries accumulate in PV9.
        // At PV10 hardfork, rebuildDRepDelegReverseIndexIfNeeded() rebuilds the index
        // from current forward delegations, removing stale entries.
        // PV10+: correct behavior — remove old reverse entry on re-delegation.
        if (prev != null) {
            var oldDeleg = AccountStateCborCodec.decodeDRepDelegation(prev);
            int oldDrepType = oldDeleg.drepType();
            if (isCredentialDRep(oldDrepType)) {
                int protocolMajor = getProtocolMajor(currentEpoch);
                // Conway guard: PV9/PV10 DRep reverse-index logic only applies in Conway-or-later.
                // PV10+: remove old reverse entry on re-delegation. PV9: keep stale entries.
                boolean removeOldReverse = isConwayOrLater(currentEpoch)
                        && ((protocolMajor >= 10) || !isCredentialDRep(newDrepType));
                if (removeOldReverse) {
                    byte[] oldRevKey = drepDelegReverseKey(oldDrepType, oldDeleg.drepHash(), ct, credHash);
                    byte[] oldRevPrev = db.get(cfState, oldRevKey);
                    batch.delete(cfState, oldRevKey);
                    deltaOps.add(new DeltaOp(OP_DELETE, oldRevKey, oldRevPrev));
                    // Update overlay
                    if (batchReverseRemoved != null) {
                        String oldDrepId = oldDrepType + ":" + oldDeleg.drepHash();
                        batchReverseRemoved.computeIfAbsent(oldDrepId, k -> new HashSet<>()).add(credId);
                        var added = batchReverseAdded.get(oldDrepId);
                        if (added != null) added.remove(credId);
                    }
                }
                // PV9 + new DRep is credential: do NOT remove old reverse entry (bug compat)
            }
        }

        // Add new reverse entry if new DRep is a credential DRep
        if (isCredentialDRep(newDrepType)) {
            byte[] newRevKey = drepDelegReverseKey(newDrepType, drep.getHash(), ct, credHash);
            byte[] newRevPrev = db.get(cfState, newRevKey);
            batch.put(cfState, newRevKey, new byte[]{1}); // marker value
            deltaOps.add(new DeltaOp(OP_PUT, newRevKey, newRevPrev));
            // Update overlay
            if (batchReverseAdded != null) {
                String newDrepId = newDrepType + ":" + drep.getHash();
                batchReverseAdded.computeIfAbsent(newDrepId, k -> new HashSet<>()).add(credId);
                var removed = batchReverseRemoved.get(newDrepId);
                if (removed != null) removed.remove(credId);
            }
        }

        // Write forward delegation
        byte[] val = AccountStateCborCodec.encodeDRepDelegation(newDrepType, drep.getHash(), slot, txIdx, certIdx);
        batch.put(cfState, key, val);
        deltaOps.add(new DeltaOp(OP_PUT, key, prev));
        // Update overlay
        if (batchForwardDeleg != null) batchForwardDeleg.put(credId, val);
    }

    private void processWithdrawal(String rewardAddrHex, BigInteger amount,
                                   WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        // Reward address format: header(1) + credential(28)
        // header byte: network_id(4 bits) | type(4 bits)
        // type 0xe0 or 0xf0 for stake addresses
        byte[] addrBytes = HexUtil.decodeHexString(rewardAddrHex);
        if (addrBytes.length < 29) return;

        int headerByte = addrBytes[0] & 0xFF;
        // Extract credential type from bit 4 of header
        // e0 = key hash stake addr, f0 = script hash stake addr
        int credType = ((headerByte & 0x10) != 0) ? 1 : 0;
        byte[] credHash = new byte[28];
        System.arraycopy(addrBytes, 1, credHash, 0, 28);
        String credHashHex = HexUtil.encodeHexString(credHash);

        byte[] key = accountKey(credType, credHashHex);
        byte[] prev = db.get(cfState, key);
        if (prev == null) return;

        // Cardano: withdrawals always withdraw the ENTIRE reward balance (no partial).
        // Set reward to 0 rather than subtracting, which avoids WriteBatch visibility bugs
        // when multiple withdrawals for the same or different credentials occur in the same block.
        // (db.get reads committed state, not pending batch operations — a second withdrawal
        //  in the same block would read stale balance and overwrite the first withdrawal.)
        var acct = AccountStateCborCodec.decodeStakeAccount(prev);
        byte[] val = AccountStateCborCodec.encodeStakeAccount(BigInteger.ZERO, acct.deposit());
        batch.put(cfState, key, val);
        deltaOps.add(new DeltaOp(OP_PUT, key, prev));
    }

    /**
     * Track per-block issuer and fees using idempotent per-block fact entries.
     * <p>
     * Writes one PREFIX_BLOCK_ISSUER entry and one PREFIX_BLOCK_FEE entry per block,
     * keyed by [epoch][slot]. Same slot always maps to the same values, so duplicate
     * application is harmless (idempotent overwrite). Aggregation happens at epoch
     * boundary via {@link #aggregateBlockCounts} and {@link #aggregateEpochFees}.
     */
    private void trackBlockCountAndFees(Block block, int epoch, long slot,
                                        List<TransactionBody> txs, Set<Integer> invalidIdx,
                                        WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        // Per-block issuer entry (idempotent: same slot → same poolHash)
        if (block.getHeader() != null && block.getHeader().getHeaderBody() != null) {
            String issuerVkey = block.getHeader().getHeaderBody().getIssuerVkey();
            if (issuerVkey != null && !issuerVkey.isEmpty()) {
                byte[] vkeyBytes = HexUtil.decodeHexString(issuerVkey);
                byte[] poolHashBytes = com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash224(vkeyBytes);

                byte[] issuerKey = blockIssuerKey(epoch, slot);
                byte[] issuerPrev = db.get(cfState, issuerKey);
                batch.put(cfState, issuerKey, poolHashBytes);
                deltaOps.add(new DeltaOp(OP_PUT, issuerKey, issuerPrev));
            }
        }

        // Per-block fee entry (idempotent: same slot → same fee)
        if (txs != null) {
            var feeResolver = new FeeResolver(utxoState);
            BigInteger blockFees = BigInteger.ZERO;
            for (int i = 0; i < txs.size(); i++) {
                BigInteger fee = feeResolver.resolveFee(txs.get(i), invalidIdx.contains(i));
                if (fee != null) blockFees = blockFees.add(fee);
            }

            if (blockFees.signum() > 0) {
                byte[] feeKey = blockFeeKey(epoch, slot);
                byte[] feePrev = db.get(cfState, feeKey);
                batch.put(cfState, feeKey, AccountStateCborCodec.encodeEpochFees(blockFees));
                deltaOps.add(new DeltaOp(OP_PUT, feeKey, feePrev));
            }
        }
    }

    /**
     * Process a MIR (Move Instantaneous Rewards) certificate.
     * <p>
     * Two modes:
     * 1. Stake credential distribution: adds instant reward amounts to per-credential MIR state
     *    AND stores per-epoch per-pot per-credential data for reward calculation
     * 2. Pot transfer: accumulates reserves↔treasury transfer amounts in metadata keys
     */
    private void processMir(MoveInstataneous mir, int currentEpoch, Era era,
                            WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        Map<StakeCredential, BigInteger> credMap = mir.getStakeCredentialCoinMap();

        if (credMap != null && !credMap.isEmpty()) {
            byte mirType = mir.isTreasury() ? REWARD_REST_MIR_TREASURY : REWARD_REST_MIR_RESERVES;
            String potName = mir.isTreasury() ? "treasury" : "reserves";
            BigInteger mirTotal = credMap.values().stream()
                    .filter(a -> a != null && a.signum() > 0)
                    .reduce(BigInteger.ZERO, BigInteger::add);
            log.info("Processing MIR cert in epoch {}: pot={}, credentials={}, total={}",
                    currentEpoch, potName, credMap.size(), mirTotal);

            int spendableEpoch = currentEpoch + 1;

            // Mode 1: distribute rewards to individual stake credentials
            for (var entry : credMap.entrySet()) {
                StakeCredential cred = entry.getKey();
                BigInteger amount = entry.getValue();
                if (amount == null || amount.signum() <= 0) continue;

                int ct = credTypeInt(cred.getType());

                // Legacy per-credential accumulator (PREFIX_MIR_REWARD)
                byte[] key = mirRewardKey(ct, cred.getHash());
                byte[] prev = db.get(cfState, key);
                BigInteger existing = (prev != null)
                        ? AccountStateCborCodec.decodeMirReward(prev)
                        : BigInteger.ZERO;
                BigInteger updated = existing.add(amount);
                byte[] val = AccountStateCborCodec.encodeMirReward(updated);
                batch.put(cfState, key, val);
                deltaOps.add(new DeltaOp(OP_PUT, key, prev));

                // Store as reward_rest (type=REWARD_REST_MIR) for epoch-scoped tracking.
                // spendable_epoch = earned_epoch + 1 (matching Yaci Store convention).
                // Pre-Alonzo: last MIR cert per credential wins (overwrite, don't accumulate).
                //   Per Haskell ledger spec and Yaci Store InstantRewardSnapshotService.
                // Alonzo+: all MIR certs for same credential in an epoch are summed.
                byte[] restKey = rewardRestKey(spendableEpoch, mirType, ct, cred.getHash());
                byte[] restPrev = db.get(cfState, restKey);
                BigInteger restAmount;
                if (era != null && era.getValue() < Era.Alonzo.getValue()) {
                    // Pre-Alonzo: replace — blocks are processed in slot order,
                    // so the last write is the latest MIR cert for this credential.
                    restAmount = amount;
                } else {
                    // Alonzo+: accumulate all MIR certs for the same credential
                    BigInteger restExisting = BigInteger.ZERO;
                    if (restPrev != null) {
                        restExisting = AccountStateCborCodec.decodeRewardRest(restPrev).amount();
                    }
                    restAmount = restExisting.add(amount);
                }
                byte[] restVal = AccountStateCborCodec.encodeRewardRest(restAmount, currentEpoch, 0L);
                batch.put(cfState, restKey, restVal);
                deltaOps.add(new DeltaOp(OP_PUT, restKey, restPrev));
            }
        } else if (mir.getAccountingPotCoin() != null && mir.getAccountingPotCoin().signum() > 0) {
            // Mode 2: pot transfer (reserves ↔ treasury)
            // reserves=true means source is reserves (reserves → treasury)
            // treasury=true means source is treasury (treasury → reserves)
            byte[] metaKey = mir.isTreasury() ? META_MIR_TO_RESERVES : META_MIR_TO_TREASURY;
            byte[] prev = db.get(cfState, metaKey);

            BigInteger existing = (prev != null && prev.length >= 8)
                    ? new BigInteger(1, prev) : BigInteger.ZERO;
            BigInteger updated = existing.add(mir.getAccountingPotCoin());

            byte[] val = totalDepositedToBytes(updated);
            batch.put(cfState, metaKey, val);
            deltaOps.add(new DeltaOp(OP_PUT, metaKey, prev));
        }
    }

    /**
     * Create and commit the delegation snapshot in its own WriteBatch.
     * Called from EpochBoundaryProcessor between rewards and governance so the snapshot
     * captures post-reward state and is available for DRep distribution calculation.
     */
    /**
     * Aggregate UTXO balances by stake credential for the given epoch.
     * This is a read-only operation that can run in parallel with reward calculation.
     *
     * @param epoch the snapshot epoch label (previousEpoch)
     *              — slot range is based on the end of this epoch, but pointer-address
     *              exclusion must follow the era of the boundary we are crossing into
     *              (epoch+1). At the 645 -> 646 Conway transition on preview, the mark
     *              snapshot is still labeled 645 but must already exclude pointer stake.
     */
    public java.util.Map<UtxoBalanceAggregator.CredentialKey, java.math.BigInteger> aggregateUtxoBalances(int epoch) {
        if (stakeSnapshotService == null || !stakeSnapshotService.isEnabled() || utxoState == null) return null;
        // Conway detection: use the boundary epoch (epoch+1), not the snapshot label.
        // The snapshot labeled E is created at the E -> E+1 boundary, and if E+1 is Conway,
        // pointer addresses are already excluded from stake delegation for that snapshot.
        boolean conwayEraAtBoundary = isConwayOrLater(epoch + 1);

        PointerAddressResolver ptrResolver = conwayEraAtBoundary ? null : pointerAddressResolver;
        long epochLastSlot = slotForEpochStart(epoch + 1) - 1;

        if ("incremental".equals(balanceMode)) {
            int prevSnapshotEpoch = epoch - 1;
            var prevSnapshot = readStakeSnapshot(prevSnapshotEpoch);
            long epochStartSlot = slotForEpochStart(epoch);
            long epochEndSlot = slotForEpochStart(epoch + 1);
            return stakeSnapshotService.aggregateStakeBalancesIncremental(
                    utxoState, ptrResolver, prevSnapshot, epochStartSlot, epochEndSlot, epochLastSlot);
        } else {
            return stakeSnapshotService.aggregateStakeBalances(utxoState, ptrResolver, epochLastSlot);
        }
    }

    /**
     * Create and commit the delegation snapshot. Returns the UTXO balance aggregation
     * so it can be reused for DRep distribution calculation (which needs actual balances
     * for ALL credentials, not just pool-delegated ones).
     *
     * @param precomputedUtxoBalances if non-null, skip UTXO scan and use these balances
     */
    public java.util.Map<UtxoBalanceAggregator.CredentialKey, java.math.BigInteger> createAndCommitDelegationSnapshot(
            int epoch, java.util.Map<UtxoBalanceAggregator.CredentialKey, java.math.BigInteger> precomputedUtxoBalances) {
        java.util.Map<UtxoBalanceAggregator.CredentialKey, java.math.BigInteger> utxoBalances = null;
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            utxoBalances = createDelegationSnapshot(epoch, batch, precomputedUtxoBalances);
            db.write(wo, batch);
        } catch (Exception ex) {
            log.error("Failed to create delegation snapshot for epoch {}: {}", epoch, ex.toString());
        }
        return utxoBalances;
    }

    /**
     * Create and commit the delegation snapshot (backward-compatible overload).
     */
    public java.util.Map<UtxoBalanceAggregator.CredentialKey, java.math.BigInteger> createAndCommitDelegationSnapshot(int epoch) {
        return createAndCommitDelegationSnapshot(epoch, null);
    }

    private java.util.Map<UtxoBalanceAggregator.CredentialKey, java.math.BigInteger> createDelegationSnapshot(
            int epoch, WriteBatch batch,
            java.util.Map<UtxoBalanceAggregator.CredentialKey, java.math.BigInteger> precomputedUtxoBalances) throws RocksDBException {
        byte[] epochBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(epoch).array();
        int count = 0;
        int skippedUnregistered = 0;
        int skippedZeroBalance = 0;
        int skippedRetiredPool = 0;

        // Build set of retired pool hashes (pools with retireEpoch <= snapshot epoch).
        // Delegations to retired pools must be excluded from the snapshot, matching
        // yaci-store's `NOT EXISTS (... p.status = 'RETIRED')` and Haskell node behavior.
        Set<String> retiredPools = new HashSet<>();
        try (RocksIterator retireIt = db.newIterator(cfState)) {
            retireIt.seek(new byte[]{PREFIX_POOL_RETIRE});
            while (retireIt.isValid()) {
                byte[] rk = retireIt.key();
                if (rk.length < 2 || rk[0] != PREFIX_POOL_RETIRE) break;
                long retireEpoch = AccountStateCborCodec.decodePoolRetirement(retireIt.value());
                String poolHash = HexUtil.encodeHexString(Arrays.copyOfRange(rk, 1, rk.length));
                if (retireEpoch <= epoch) {
                    retiredPools.add(poolHash);
                }
                retireIt.next();
            }
        }

        // Build map of pool registration slots for stale delegation check.
        // A delegation is stale if it was made before the pool's current registration slot
        // (i.e., the pool retired and re-registered after the delegation was made).
        Map<String, Long> poolRegSlots = new HashMap<>();
        try (RocksIterator regSlotIt = db.newIterator(cfState)) {
            regSlotIt.seek(new byte[]{PREFIX_POOL_REG_SLOT});
            while (regSlotIt.isValid()) {
                byte[] rk = regSlotIt.key();
                if (rk.length < 2 || rk[0] != PREFIX_POOL_REG_SLOT) break;
                String poolHash = HexUtil.encodeHexString(Arrays.copyOfRange(rk, 1, rk.length));
                long regSlot = ByteBuffer.wrap(regSlotIt.value()).order(ByteOrder.BIG_ENDIAN).getLong();
                poolRegSlots.put(poolHash, regSlot);
                regSlotIt.next();
            }
        }
        int skippedStaleDelegation = 0;
        int skippedDeregAfterDeleg = 0;

        // Pre-build map: credential → latest deregistration position (slot, txIdx, certIdx)
        // Used to detect delegations invalidated by a subsequent deregistration.
        java.util.Map<CredentialKey, long[]> latestDeregistrations = new java.util.HashMap<>();
        try (RocksIterator deregIt = db.newIterator(cfState)) {
            deregIt.seek(new byte[]{PREFIX_STAKE_EVENT});
            while (deregIt.isValid()) {
                byte[] dk = deregIt.key();
                if (dk.length < 14 || dk[0] != PREFIX_STAKE_EVENT) break;
                int eventType = AccountStateCborCodec.decodeStakeEvent(deregIt.value());
                if (eventType == AccountStateCborCodec.EVENT_DEREGISTRATION) {
                    long evSlot = ByteBuffer.wrap(dk, 1, 8).order(ByteOrder.BIG_ENDIAN).getLong();
                    int evTxIdx = ByteBuffer.wrap(dk, 9, 2).order(ByteOrder.BIG_ENDIAN).getShort() & 0xFFFF;
                    int evCertIdx = ByteBuffer.wrap(dk, 11, 2).order(ByteOrder.BIG_ENDIAN).getShort() & 0xFFFF;
                    int evCredType = dk[13] & 0xFF;
                    String evCredHash = HexUtil.encodeHexString(Arrays.copyOfRange(dk, 14, dk.length));
                    var credKey = CredentialKey.of(evCredType, evCredHash);

                    long[] existing = latestDeregistrations.get(credKey);
                    if (existing == null
                            || evSlot > existing[0]
                            || (evSlot == existing[0] && evTxIdx > existing[1])
                            || (evSlot == existing[0] && evTxIdx == existing[1] && evCertIdx > existing[2])) {
                        latestDeregistrations.put(credKey, new long[]{evSlot, evTxIdx, evCertIdx});
                    }
                }
                deregIt.next();
            }
        }
        if (!latestDeregistrations.isEmpty()) {
            log.debug("Pre-built deregistration map: {} credentials with deregistrations", latestDeregistrations.size());
        }

        // Allegra bootstrap UTXO removal is self-contained in DefaultUtxoStore.applyBlock().
        // On the first Allegra-era block, tracked Byron genesis UTXOs are removed from cfUnspent
        // atomically within the block's WriteBatch + delta pipeline (rollback-safe).
        // CF reward library handles the reserve adjustment via bootstrapAddressAmount.

        // Use pre-computed UTXO balances if available (from parallel scan), otherwise compute inline.
        java.util.Map<UtxoBalanceAggregator.CredentialKey, java.math.BigInteger> utxoBalances = precomputedUtxoBalances;
        if (utxoBalances == null && stakeSnapshotService != null && stakeSnapshotService.isEnabled() && utxoState != null) {
            // Fallback: compute inline. Era not available here — uses protocol version fallback.
            utxoBalances = aggregateUtxoBalances(epoch);
        }

        // Collect entries for export (only allocate list when exporter is active)
        final java.util.List<com.bloxbean.cardano.yano.ledgerstate.export.EpochSnapshotExporter.StakeEntry> exportEntries =
                (snapshotExporter != com.bloxbean.cardano.yano.ledgerstate.export.EpochSnapshotExporter.NOOP)
                        ? new java.util.ArrayList<>() : null;

        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(new byte[]{PREFIX_POOL_DELEG});
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 2 || key[0] != PREFIX_POOL_DELEG) break;

                int credType = key[1] & 0xFF;
                String credHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 2, key.length));

                // Only include registered stake credentials (must have PREFIX_ACCT entry)
                byte[] acctKey = accountKey(credType, credHash);
                byte[] acctVal = db.get(cfState, acctKey);
                if (acctVal == null) {
                    skippedUnregistered++;
                    it.next();
                    continue;
                }

                var deleg = AccountStateCborCodec.decodePoolDelegation(it.value());

                // Skip delegations to retired pools
                if (retiredPools.contains(deleg.poolHash())) {
                    skippedRetiredPool++;
                    it.next();
                    continue;
                }

                // Skip stale delegations: delegation made before pool's current registration slot.
                // This happens when a pool retires and re-registers — old delegations are invalid.
                Long poolRegSlot = poolRegSlots.get(deleg.poolHash());
                if (poolRegSlot != null && deleg.slot() < poolRegSlot) {
                    skippedStaleDelegation++;
                    it.next();
                    continue;
                }

                // Skip delegations invalidated by a subsequent deregistration.
                // If a credential was deregistered AFTER the delegation was made (comparing
                // slot, then txIndex, then certIndex), the delegation is stale even if the
                // credential was re-registered later — a new delegation would be needed.
                var deregKey = CredentialKey.of(credType, credHash);
                long[] latestDereg = latestDeregistrations.get(deregKey);
                if (latestDereg != null) {
                    long dSlot = latestDereg[0];
                    long dTxIdx = latestDereg[1];
                    long dCertIdx = latestDereg[2];
                    if (dSlot > deleg.slot()
                            || (dSlot == deleg.slot() && dTxIdx > deleg.txIdx())
                            || (dSlot == deleg.slot() && dTxIdx == deleg.txIdx() && dCertIdx > deleg.certIdx())) {
                        skippedDeregAfterDeleg++;
                        it.next();
                        continue;
                    }
                }

                // Skip delegations that predate the credential's current registration.
                // This catches stale delegations from before a deregistration/re-registration
                // cycle, even when the deregistration stake event has been pruned.
                byte[] acctRegSlotVal = db.get(cfState, acctRegSlotKey(credType, credHash));
                if (acctRegSlotVal != null) {
                    long acctRegSlot = ByteBuffer.wrap(acctRegSlotVal).order(ByteOrder.BIG_ENDIAN).getLong();
                    if (deleg.slot() < acctRegSlot) {
                        skippedDeregAfterDeleg++;
                        it.next();
                        continue;
                    }
                }

                // Compute stake amount = UTXO balance + withdrawable rewards
                // reward_rest (proposal refunds, treasury withdrawals) is already credited to
                // PREFIX_ACCT.reward in PostEpochTransition, so it's included in rewardBal.
                java.math.BigInteger stakeAmount = java.math.BigInteger.ZERO;
                if (utxoBalances != null) {
                    var credKey = new UtxoBalanceAggregator.CredentialKey(credType, credHash);
                    java.math.BigInteger utxoBal = utxoBalances.getOrDefault(credKey, java.math.BigInteger.ZERO);
                    var acctData = AccountStateCborCodec.decodeStakeAccount(acctVal);
                    java.math.BigInteger rewardBal = acctData.reward();
                    stakeAmount = utxoBal.add(rewardBal);

                    // Include zero-balance delegators in the snapshot to match yaci-store's epoch_stake.
                    // Zero-balance delegators may be pool owners, affecting ownerActiveStake in cf-rewards.
                    if (stakeAmount.signum() == 0) {
                        skippedZeroBalance++;
                    }
                }

                // Build snapshot key: [epoch(4)][credType(1)][credHash(28)]
                byte[] snapshotKey = new byte[4 + key.length - 1];
                System.arraycopy(epochBytes, 0, snapshotKey, 0, 4);
                System.arraycopy(key, 1, snapshotKey, 4, key.length - 1);

                byte[] snapshotVal = AccountStateCborCodec.encodeEpochDelegSnapshot(deleg.poolHash(), stakeAmount);
                batch.put(cfEpochSnapshot, snapshotKey, snapshotVal);
                count++;

                // Collect for export (only if exporter is active)
                if (exportEntries != null) {
                    exportEntries.add(new com.bloxbean.cardano.yano.ledgerstate.export.EpochSnapshotExporter.StakeEntry(
                            credType, credHash, deleg.poolHash(), stakeAmount));
                }


                it.next();
            }
        }

        byte[] epochMeta = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(epoch).array();
        batch.put(cfState, META_LAST_SNAPSHOT_EPOCH, epochMeta);
        log.info("Created delegation snapshot for epoch {} ({} delegations, amounts={}, skipped: {} unregistered, {} zero-balance, {} retired-pool, {} stale-delegation, {} dereg-after-deleg)",
                epoch, count, utxoBalances != null, skippedUnregistered, skippedZeroBalance, skippedRetiredPool, skippedStaleDelegation, skippedDeregAfterDeleg);

        // Export stake snapshot for debugging
        if (exportEntries != null) {
            snapshotExporter.exportStakeSnapshot(epoch, exportEntries);
        }

        return utxoBalances;
    }

    private void pruneOldSnapshots(int oldestToKeep, WriteBatch batch) throws RocksDBException {
        if (oldestToKeep <= 0) return;

        try (RocksIterator it = db.newIterator(cfEpochSnapshot)) {
            it.seekToFirst();
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 4) break;
                int epoch = ByteBuffer.wrap(key, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                if (epoch >= oldestToKeep) break;
                batch.delete(cfEpochSnapshot, key);
                it.next();
            }
        }
    }

    /**
     * Prune stake events older than the given cutoff slot.
     * Called at epoch boundary alongside pruneOldSnapshots.
     */
    void pruneOldStakeEvents(long cutoffSlot, WriteBatch batch) throws RocksDBException {
        byte[] seekKey = new byte[1 + 8];
        seekKey[0] = PREFIX_STAKE_EVENT;
        // Start from slot 0

        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(seekKey);
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 9 || key[0] != PREFIX_STAKE_EVENT) break;
                long slot = ByteBuffer.wrap(key, 1, 8).order(ByteOrder.BIG_ENDIAN).getLong();
                if (slot >= cutoffSlot) break;
                batch.delete(cfState, key);
                it.next();
            }
        }
    }

    // --- Stake event queries (for reward calculation) ---

    /**
     * Get credentials whose last stake event in [startSlot, endSlot) is DEREGISTRATION.
     * Returns "credType:credHash" strings.
     */
    @Override
    public Set<String> getDeregisteredAccountsInSlotRange(long startSlot, long endSlot) {
        // Track last event per credential using LinkedHashMap
        Map<String, Integer> lastEvent = new LinkedHashMap<>();

        byte[] seekKey = new byte[1 + 8];
        seekKey[0] = PREFIX_STAKE_EVENT;
        ByteBuffer.wrap(seekKey, 1, 8).order(ByteOrder.BIG_ENDIAN).putLong(startSlot);

        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(seekKey);
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 14 || key[0] != PREFIX_STAKE_EVENT) break;
                long slot = ByteBuffer.wrap(key, 1, 8).order(ByteOrder.BIG_ENDIAN).getLong();
                if (slot >= endSlot) break;

                int credType = key[13] & 0xFF;
                String credHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 14, key.length));
                String credKey = credType + ":" + credHash;
                int eventType = AccountStateCborCodec.decodeStakeEvent(it.value());
                lastEvent.put(credKey, eventType);
                it.next();
            }
        }

        Set<String> deregistered = new HashSet<>();
        for (var entry : lastEvent.entrySet()) {
            if (entry.getValue() == AccountStateCborCodec.EVENT_DEREGISTRATION) {
                deregistered.add(entry.getKey());
            }
        }
        return deregistered;
    }

    /**
     * Get pool reward addresses that had ANY REGISTRATION event before cutoffSlot
     * and are in the given poolRewardAddresses set.
     * <p>
     * This implements "was EVER registered" semantics — once a registration event is found,
     * the credential stays in the set regardless of subsequent deregistrations.
     * cf-rewards uses this as {@code accountsRegisteredInThePast}: whether the operator's
     * reward address was ever registered determines if the operator gets any leader reward.
     * Returns "credType:credHash" strings.
     */
    @Override
    public Set<String> getRegisteredPoolRewardAddressesBeforeSlot(long cutoffSlot, Set<String> poolRewardAddresses) {
        if (poolRewardAddresses == null || poolRewardAddresses.isEmpty()) return Set.of();

        Set<String> registered = new HashSet<>();

        // Scan event log for ANY REGISTRATION event before cutoff.
        // Deregistrations are intentionally ignored — this checks "was ever registered",
        // matching yaci-store's SQL: WHERE type = 'STAKE_REGISTRATION' (no dereg check).
        byte[] seekKey = new byte[1 + 8];
        seekKey[0] = PREFIX_STAKE_EVENT;

        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(seekKey);
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 14 || key[0] != PREFIX_STAKE_EVENT) break;
                long slot = ByteBuffer.wrap(key, 1, 8).order(ByteOrder.BIG_ENDIAN).getLong();
                if (slot >= cutoffSlot) break;

                int credType = key[13] & 0xFF;
                String credHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 14, key.length));
                String credKey = credType + ":" + credHash;

                if (poolRewardAddresses.contains(credKey)) {
                    int eventType = AccountStateCborCodec.decodeStakeEvent(it.value());
                    if (eventType == AccountStateCborCodec.EVENT_REGISTRATION) {
                        registered.add(credKey);
                    }
                    // Deregistration events are NOT removed — "was ever registered" semantics
                }
                it.next();
            }
        }

        // No fallback: if no registration event was found before cutoff, the address is not registered.
        // TODO: genesis-era accounts (devnet) may have implicit registration without events.
        //       Verify with devnet testing and add handling if needed.

        return registered;
    }

    // --- Rollback ---

    @Override
    public void rollbackTo(RollbackEvent event) {
        if (!enabled) return;
        rollbackInternal(event.target().getSlot());
    }

    // --- RollbackCapableStore implementation ---

    @Override
    public String storeName() {
        return "accountStateStore";
    }

    @Override
    public long getLatestAppliedSlot() {
        try {
            byte[] val = db.get(cfState, META_LAST_APPLIED_SLOT);
            if (val != null) return ByteBuffer.wrap(val).order(ByteOrder.BIG_ENDIAN).getLong();
        } catch (Exception e) {
            log.warn("Failed to read account state latest applied slot: {}", e.getMessage());
        }
        return -1;
    }

    @Override
    public long getRollbackFloorSlot() {
        // Can't compute slot without EpochSlotCalc
        if (epochParamProvider == null || epochParamProvider == ZERO_PROVIDER) {
            return 0;
        }

        // Floor 1: per-block reward-input facts (PREFIX_BLOCK_ISSUER / PREFIX_BLOCK_FEE).
        // Reward calc for epoch N reads stakeEpoch = N-2. If earliest retained is E,
        // earliest replayable boundary is newEpoch = E+2, rollback target = end of E+1.
        Integer earliestRewardEpoch = getEarliestReplayableRewardInputEpoch();
        long rewardInputFloor;
        if (earliestRewardEpoch == null) {
            // No per-block facts retained — no safe reward replay window
            return getLatestAppliedSlot();
        } else {
            int earliestSafeNewEpoch = earliestRewardEpoch + 2;
            rewardInputFloor = epochParamProvider.getEpochSlotCalc().epochToStartSlot(earliestSafeNewEpoch) - 1;
        }

        // Floor 2: delegation snapshots (cfEpochSnapshot).
        // Reward calc for epoch N reads snapshotKey = N-4. If earliest retained snapshot is S,
        // earliest replayable boundary is newEpoch = S+4, rollback target = end of S+3.
        Integer earliestSnapshot = getEarliestRetainedSnapshotEpoch();
        long snapshotFloor;
        if (earliestSnapshot == null) {
            return getLatestAppliedSlot();
        } else {
            int earliestSafeNewEpoch = earliestSnapshot + 4;
            snapshotFloor = epochParamProvider.getEpochSlotCalc().epochToStartSlot(earliestSafeNewEpoch) - 1;
        }

        return Math.max(rewardInputFloor, snapshotFloor);
    }

    /**
     * Scan the earliest retained epoch for a given per-block prefix (BLOCK_ISSUER or BLOCK_FEE).
     * Keys are sorted: [prefix(1)][epoch(4 BE)][slot(8 BE)], so seekToFirst gives earliest epoch.
     */
    private Integer getEarliestRetainedEpochForPrefix(byte prefix) {
        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(new byte[]{prefix});
            if (it.isValid()) {
                byte[] key = it.key();
                if (key.length >= 5 && key[0] == prefix) {
                    return ByteBuffer.wrap(key, 1, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                }
            }
        }
        return null;
    }

    /**
     * Returns the earliest epoch for which both block issuer and fee facts are retained,
     * or null if neither is retained.
     */
    private Integer getEarliestReplayableRewardInputEpoch() {
        Integer issuerEpoch = getEarliestRetainedEpochForPrefix(PREFIX_BLOCK_ISSUER);
        Integer feeEpoch = getEarliestRetainedEpochForPrefix(PREFIX_BLOCK_FEE);
        if (issuerEpoch == null && feeEpoch == null) return null;
        if (issuerEpoch == null) return feeEpoch;
        if (feeEpoch == null) return issuerEpoch;
        return Math.max(issuerEpoch, feeEpoch); // both must exist — use more conservative
    }

    /**
     * Scan cfEpochSnapshot for the earliest retained snapshot epoch.
     * Snapshot keys start with the epoch as a 4-byte big-endian int.
     */
    private Integer getEarliestRetainedSnapshotEpoch() {
        try (RocksIterator it = db.newIterator(cfEpochSnapshot)) {
            it.seekToFirst();
            if (it.isValid()) {
                byte[] key = it.key();
                if (key.length >= 4) {
                    return ByteBuffer.wrap(key, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                }
            }
        }
        return null;
    }

    @Override
    public void rollbackToSlot(long targetSlot) {
        rollbackInternal(targetSlot);
    }

    /**
     * Undo boundary delta entries for a single slot in explicit reverse execution order.
     * Within each phase entry, ops are also undone in reverse order.
     */
    private void undoBoundaryPhasesInOrder(Map<Byte, byte[]> phaseEntries, WriteBatch batch)
            throws RocksDBException {
        for (byte phase : BOUNDARY_PHASE_REVERSE_ORDER) {
            byte[] encoded = phaseEntries.get(phase);
            if (encoded == null) continue;
            DecodedDelta delta = decodeDelta(encoded);
            for (int i = delta.ops.size() - 1; i >= 0; i--) {
                DeltaOp op = delta.ops.get(i);
                if (op.prevValue != null) {
                    batch.put(cfState, op.key, op.prevValue);
                } else {
                    batch.delete(cfState, op.key);
                }
            }
        }
    }

    private boolean isValidBlockDelta(RocksIterator it) {
        return it.isValid();
    }

    private boolean isValidBoundaryDelta(RocksIterator it) {
        return it.isValid() && it.key().length >= 9;
    }

    private long currentBlockDeltaSlot(RocksIterator it) {
        return decodeDelta(it.value()).slot();
    }

    private long currentBoundaryDeltaSlot(RocksIterator it) {
        return ByteBuffer.wrap(it.key(), 0, 8).order(ByteOrder.BIG_ENDIAN).getLong();
    }

    private void undoBlockDeltaEntry(RocksIterator it, WriteBatch batch) throws RocksDBException {
        DecodedDelta delta = decodeDelta(it.value());

        for (int i = delta.ops.size() - 1; i >= 0; i--) {
            DeltaOp op = delta.ops.get(i);
            if (op.prevValue != null) {
                batch.put(cfState, op.key, op.prevValue);
            } else {
                batch.delete(cfState, op.key);
            }
        }

        batch.delete(cfDelta, it.key());
        it.prev();
    }

    private void undoBoundaryDeltaSlot(RocksIterator it, WriteBatch batch) throws RocksDBException {
        long slot = currentBoundaryDeltaSlot(it);
        Map<Byte, byte[]> phaseEntries = new HashMap<>();

        while (isValidBoundaryDelta(it) && currentBoundaryDeltaSlot(it) == slot) {
            byte[] key = it.key();
            byte phase = key[8];
            if (phaseEntries.containsKey(phase)) {
                log.warn("Duplicate boundary delta entry for slot {} phase {} — overwriting", slot, phase);
            }
            phaseEntries.put(phase, it.value().clone());
            batch.delete(cfBoundaryDelta, key);
            it.prev();
        }

        undoBoundaryPhasesInOrder(phaseEntries, batch);
    }

    private void rollbackInternal(long targetSlot) {
        if (!enabled) return;

        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions();
             RocksIterator it = db.newIterator(cfDelta);
             RocksIterator bdIt = db.newIterator(cfBoundaryDelta)) {

            it.seekToLast();
            bdIt.seekToLast();

            // Undo block deltas and boundary deltas in one reverse-chronological timeline.
            // The old two-pass order ("all blocks first, then all boundaries") could cross
            // epoch boundaries and restore post-withdrawal intermediate values. Equal-slot
            // ordering undoes block deltas first because block application happens after the
            // epoch-boundary transition at that slot.
            while (true) {
                boolean blockValid = isValidBlockDelta(it);
                boolean boundaryValid = isValidBoundaryDelta(bdIt);

                if (!blockValid && !boundaryValid) {
                    break;
                }

                long blockSlot = blockValid ? currentBlockDeltaSlot(it) : Long.MIN_VALUE;
                long boundarySlot = boundaryValid ? currentBoundaryDeltaSlot(bdIt) : Long.MIN_VALUE;
                long nextSlot = Math.max(blockSlot, boundarySlot);

                if (nextSlot <= targetSlot) {
                    break;
                }

                if (blockSlot == nextSlot) {
                    undoBlockDeltaEntry(it, batch);
                }
                if (boundarySlot == nextSlot) {
                    undoBoundaryDeltaSlot(bdIt, batch);
                }
            }

            // Clean up epoch snapshots, AdaPot entries, and pending jobs BEYOND the rollback target.
            // Snapshots have stale reward balances from rolled-back epoch boundary processing.
            // AdaPot entries bypass delta tracking (written via db.put, not WriteBatch).
            // Pending epoch boundary jobs for rolled-back epochs must be cancelled.
            int targetEpoch = epochForSlot(targetSlot);
            int lastSnapshot = getLastSnapshotEpoch();

            if (targetEpoch <= lastSnapshot) {
                // Delete snapshots for epochs >= targetEpoch.
                // Snapshot E is created during boundary E -> E+1. After rollback to epoch E,
                // that boundary no longer exists, so snapshot E is stale and must be deleted.
                try (RocksIterator snapIt = db.newIterator(cfEpochSnapshot)) {
                    byte[] seekKey = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                            .putInt(targetEpoch).array();
                    snapIt.seek(seekKey);
                    while (snapIt.isValid()) {
                        batch.delete(cfEpochSnapshot, snapIt.key());
                        snapIt.next();
                    }
                }

                // Delete AdaPot entries for epochs > targetEpoch
                // These bypass delta tracking and may have stale values from rolled-back processing.
                // AdaPot(targetEpoch) is valid at end-of-epoch — keep it.
                for (int e = targetEpoch + 1; e <= lastSnapshot + 5; e++) {
                    byte[] adapotKey = adaPotKey(e);
                    byte[] existing = db.get(cfState, adapotKey);
                    if (existing != null) {
                        batch.delete(cfState, adapotKey);
                    }
                }

                // Update META_LAST_SNAPSHOT_EPOCH from actual remaining snapshot state.
                // Find the greatest retained snapshot epoch strictly < targetEpoch.
                int actualLastSnapshot = -1;
                try (RocksIterator lastSnapIt = db.newIterator(cfEpochSnapshot)) {
                    byte[] seekKey = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                            .putInt(targetEpoch).array();
                    lastSnapIt.seek(seekKey);
                    if (!lastSnapIt.isValid()) {
                        lastSnapIt.seekToLast();
                    } else {
                        lastSnapIt.prev();
                    }
                    while (lastSnapIt.isValid()) {
                        byte[] key = lastSnapIt.key();
                        if (key.length >= 4) {
                            int epoch = ByteBuffer.wrap(key, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                            if (epoch < targetEpoch) {
                                actualLastSnapshot = epoch;
                                break;
                            }
                        }
                        lastSnapIt.prev();
                    }
                }
                if (actualLastSnapshot >= 0) {
                    byte[] epochMeta = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                            .putInt(actualLastSnapshot).array();
                    batch.put(cfState, META_LAST_SNAPSHOT_EPOCH, epochMeta);
                } else {
                    batch.delete(cfState, META_LAST_SNAPSHOT_EPOCH);
                }
            }

            // Clear stale boundary-step marker if it refers to an epoch beyond the rollback target.
            // Without this, recoverInterruptedBoundary() would attempt to resume a future epoch's
            // boundary processing that was already rolled back.
            byte[] boundaryVal = db.get(cfState, META_BOUNDARY_STEP);
            if (boundaryVal != null && boundaryVal.length == 8) {
                int boundaryEpoch = ByteBuffer.wrap(boundaryVal).order(ByteOrder.BIG_ENDIAN).getInt();
                if (boundaryEpoch > targetEpoch) {
                    batch.delete(cfState, META_BOUNDARY_STEP);
                    log.info("Cleared stale boundary step for epoch {} (rolled back to epoch {})",
                            boundaryEpoch, targetEpoch);
                }
            }

            // Rollback epoch param tracker (pending + finalized keys beyond target)
            // Must be outside the if-block: even same-epoch rollback can invalidate
            // a pending protocol update tx.
            if (paramTracker != null && paramTracker.isEnabled()) {
                paramTracker.addRollbackOps(targetSlot, targetEpoch, batch);
            }

            // Update last applied slot
            batch.put(cfState, META_LAST_APPLIED_SLOT,
                    ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(targetSlot).array());

            db.write(wo, batch);

            // Rebuild tracker in-memory state from rolled-back RocksDB
            if (paramTracker != null && paramTracker.isEnabled()) {
                paramTracker.reloadAfterRollback();
            }

            log.info("Account state rolled back to slot {}", targetSlot);
        } catch (Exception ex) {
            throw new RuntimeException("Account state rollback to slot " + targetSlot + " failed", ex);
        }
    }

    // --- Reconcile ---

    /**
     * Get the last completed boundary step for the given epoch.
     * Returns -1 if no boundary processing has been started for this epoch.
     * Steps: 0=started, 1=rewards, 2=snapshot, 3=poolreap, 4=governance, 5=complete
     */
    public int getBoundaryStep(int epoch) {
        try {
            byte[] val = db.get(cfState, META_BOUNDARY_STEP);
            if (val != null && val.length == 8) {
                ByteBuffer buf = ByteBuffer.wrap(val).order(ByteOrder.BIG_ENDIAN);
                int storedEpoch = buf.getInt();
                int step = buf.getInt();
                return (storedEpoch == epoch) ? step : -1;
            }
        } catch (Exception e) {
            log.warn("Failed to read boundary step: {}", e.getMessage());
        }
        return -1;
    }

    /**
     * Get the last boundary state (epoch and step), regardless of which epoch.
     * Returns int[]{epoch, step} or null if no boundary has been tracked.
     */
    public int[] getLastBoundaryState() {
        try {
            byte[] val = db.get(cfState, META_BOUNDARY_STEP);
            if (val != null && val.length == 8) {
                ByteBuffer buf = ByteBuffer.wrap(val).order(ByteOrder.BIG_ENDIAN);
                return new int[]{buf.getInt(), buf.getInt()};
            }
        } catch (Exception e) {
            log.warn("Failed to read boundary state: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Record a completed boundary step for the given epoch (standalone, non-atomic).
     * Use {@link #setBoundaryStepBatch} for atomic commits with phase mutations.
     */
    public void setBoundaryStep(int epoch, int step) {
        try {
            byte[] val = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(epoch).putInt(step).array();
            db.put(cfState, META_BOUNDARY_STEP, val);
        } catch (Exception e) {
            log.warn("Failed to write boundary step: {}", e.getMessage());
        }
    }

    /**
     * Record a completed boundary step atomically within a WriteBatch.
     * Ensures the step marker is committed together with the phase's mutations,
     * preventing crash-recovery from double-applying non-idempotent boundary writes.
     */
    public void setBoundaryStepBatch(int epoch, int step, WriteBatch batch) throws RocksDBException {
        byte[] val = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(epoch).putInt(step).array();
        batch.put(cfState, META_BOUNDARY_STEP, val);
    }

    @Override
    public void reconcile(ChainState chainState) {
        if (!enabled || chainState == null) return;

        long lastAppliedBlock = 0L;
        try {
            byte[] b = db.get(cfState, META_LAST_APPLIED_BLOCK);
            if (b != null && b.length == 8) {
                lastAppliedBlock = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).getLong();
            }
        } catch (Exception ignored) {}

        ChainTip tip = chainState.getTip();
        if (tip == null) return;
        long tipBlock = tip.getBlockNumber();

        if (lastAppliedBlock == tipBlock) return;

        if (lastAppliedBlock > tipBlock) {
            String hashHex = tip.getBlockHash() != null ? HexUtil.encodeHexString(tip.getBlockHash()) : null;
            rollbackTo(new RollbackEvent(new Point(tip.getSlot(), hashHex), true));
            log.info("Account state reconciled: rolled back from {} to tip {}", lastAppliedBlock, tipBlock);
            return;
        }

        // Skip forward reconciliation if store is empty and tip is Byron era.
        // Account state only tracks Shelley+ data (staking, rewards, delegations).
        // Replaying Byron blocks is pure overhead — no relevant state to reconcile.
        if (lastAppliedBlock == 0) {
            Era tipEra = chainState.getBlockEra(tipBlock);
            if (tipEra == Era.Byron) {
                log.info("Account state reconcile skipped: tip block {} is Byron era, nothing to reconcile", tipBlock);
                return;
            }
        }

        // Forward replay
        log.info("Account state reconcile: replaying blocks {} to {}", lastAppliedBlock + 1, tipBlock);
        for (long bn = lastAppliedBlock + 1; bn <= tipBlock; bn++) {
            if ((bn - lastAppliedBlock) % 1000 == 0) {
                log.info("Account state reconcile progress: block {}/{}", bn, tipBlock);
            }
            byte[] blockBytes = chainState.getBlockByNumber(bn);
            if (blockBytes == null) continue;

            try {
                Block block = com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer.INSTANCE
                        .deserialize(blockBytes);
                long blockSlot = block.getHeader().getHeaderBody().getSlot();
                String blockHash = block.getHeader().getHeaderBody().getBlockHash();
                applyBlock(new BlockAppliedEvent(block.getEra(), blockSlot, bn, blockHash, block));
            } catch (Throwable t) {
                log.warn("Account state reconcile: skip block {} due to: {}", bn, t.toString());
            }
        }
        log.info("Account state reconciled: replayed from {} to tip {}", lastAppliedBlock, tipBlock);
    }

    // --- Boundary delta helpers ---

    /**
     * Check which boundary delta phases have been committed for a given boundary slot.
     * Used by crash recovery to detect already-committed phases even if META_BOUNDARY_STEP
     * was not updated before the crash.
     */
    public Set<Byte> getCommittedBoundaryPhases(long boundarySlot) {
        Set<Byte> phases = new HashSet<>();
        byte[] seekKey = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                .putLong(boundarySlot).array();
        try (RocksIterator it = db.newIterator(cfBoundaryDelta)) {
            it.seek(seekKey);
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 9) break;
                long keySlot = ByteBuffer.wrap(key, 0, 8).order(ByteOrder.BIG_ENDIAN).getLong();
                if (keySlot != boundarySlot) break;
                phases.add(key[8]);
                it.next();
            }
        }
        return phases;
    }

    /**
     * Put a value into cfState with rollback journaling. Reads the previous committed value
     * and appends a DeltaOp so the write can be undone on rollback.
     */
    byte[] getStateWithOverlay(byte[] key, BatchStateOverlay overlay) throws RocksDBException {
        if (overlay != null && overlay.contains(key)) {
            return overlay.get(key);
        }
        return db.get(cfState, key);
    }

    void putStateWithDelta(byte[] key, byte[] newVal, WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        putStateWithDelta(key, newVal, batch, deltaOps, null);
    }

    void putStateWithDelta(byte[] key, byte[] newVal, WriteBatch batch, List<DeltaOp> deltaOps,
                           BatchStateOverlay overlay) throws RocksDBException {
        byte[] prev = getStateWithOverlay(key, overlay);
        deltaOps.add(new DeltaOp(OP_PUT, key, prev));
        batch.put(cfState, key, newVal);
        if (overlay != null) {
            overlay.put(key, newVal);
        }
    }

    /**
     * Delete a key from cfState with rollback journaling. Reads the previous committed value
     * and appends a DeltaOp so the deletion can be undone on rollback.
     */
    void deleteStateWithDelta(byte[] key, WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        deleteStateWithDelta(key, batch, deltaOps, null);
    }

    void deleteStateWithDelta(byte[] key, WriteBatch batch, List<DeltaOp> deltaOps,
                              BatchStateOverlay overlay) throws RocksDBException {
        byte[] prev = getStateWithOverlay(key, overlay);
        if (prev != null) {
            deltaOps.add(new DeltaOp(OP_DELETE, key, prev));
            batch.delete(cfState, key);
            if (overlay != null) {
                overlay.put(key, null);
            }
        }
    }

    /**
     * Persist a boundary delta journal entry. Called atomically with the same WriteBatch
     * that contains the boundary mutations, ensuring the journal and mutations are committed together.
     *
     * @param slot  the slot of the first block that triggered this epoch boundary
     * @param phase one of PHASE_REWARDS, PHASE_MIR, PHASE_SPENDABLE_REST, PHASE_GOV_ENACT, PHASE_GOV_RATIFY
     */
    public void commitBoundaryDelta(long slot, byte phase, WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        if (deltaOps.isEmpty()) return;
        byte[] key = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN)
                .putLong(slot).put(phase).array();
        byte[] val = encodeDelta(slot, deltaOps);
        batch.put(cfBoundaryDelta, key, val);
    }

    // --- Delta encoding ---

    public record DeltaOp(byte opType, byte[] key, byte[] prevValue) {}
    private record DecodedDelta(long slot, List<DeltaOp> ops) {}

    private byte[] encodeDelta(long slot, List<DeltaOp> ops) {
        // Format: slot(8) + numOps(4) + [opType(1) + keyLen(2) + key(N) + prevLen(2) + prev(M)]*
        int size = 8 + 4;
        for (DeltaOp op : ops) {
            size += 1 + 2 + op.key.length + 2 + (op.prevValue != null ? op.prevValue.length : 0);
        }

        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        buf.putLong(slot);
        buf.putInt(ops.size());
        for (DeltaOp op : ops) {
            buf.put(op.opType);
            buf.putShort((short) op.key.length);
            buf.put(op.key);
            if (op.prevValue != null) {
                buf.putShort((short) op.prevValue.length);
                buf.put(op.prevValue);
            } else {
                buf.putShort((short) 0);
            }
        }
        return buf.array();
    }

    private DecodedDelta decodeDelta(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        long slot = buf.getLong();
        int numOps = buf.getInt();
        List<DeltaOp> ops = new ArrayList<>(numOps);
        for (int i = 0; i < numOps; i++) {
            byte opType = buf.get();
            int keyLen = buf.getShort() & 0xFFFF;
            byte[] key = new byte[keyLen];
            buf.get(key);
            int prevLen = buf.getShort() & 0xFFFF;
            byte[] prevValue = null;
            if (prevLen > 0) {
                prevValue = new byte[prevLen];
                buf.get(prevValue);
            }
            ops.add(new DeltaOp(opType, key, prevValue));
        }
        return new DecodedDelta(slot, ops);
    }

    private static byte[] totalDepositedToBytes(BigInteger value) {
        // Store as big-endian bytes, padded to at least 8 bytes
        byte[] raw = value.toByteArray();
        if (raw.length >= 8) return raw;
        byte[] padded = new byte[8];
        System.arraycopy(raw, 0, padded, 8 - raw.length, raw.length);
        return padded;
    }

    private static int getInt(Map<String, Object> cfg, String key, int def) {
        Object v = cfg != null ? cfg.get(key) : null;
        if (v instanceof Number n) return n.intValue();
        if (v != null) try { return Integer.parseInt(String.valueOf(v)); } catch (Exception ignored) {}
        return def;
    }
}
