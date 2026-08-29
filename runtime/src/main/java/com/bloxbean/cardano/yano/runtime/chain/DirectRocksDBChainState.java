package com.bloxbean.cardano.yano.runtime.chain;

import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.serializers.ByronEbBlockSerializer;
import java.math.BigInteger;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.CanonicalBlockReference;
import com.bloxbean.cardano.yano.api.ByronEpochBoundaryReference;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.api.db.RocksDbAccess;
import com.bloxbean.cardano.yano.api.rollback.PointRollbackCapableStore;
import com.bloxbean.cardano.yano.api.rollback.RollbackCapableStore;
import com.bloxbean.cardano.yano.runtime.blockproducer.NonceStateStore;
import com.bloxbean.cardano.yano.runtime.blockproducer.NonceStateSnapshot;
import com.bloxbean.cardano.yano.ledgerstate.AccountStateCfNames;
import com.bloxbean.cardano.yano.runtime.db.RocksDbContext;
import com.bloxbean.cardano.yano.api.archive.ProjectionCfNames;
import com.bloxbean.cardano.yano.runtime.db.RocksDbSupplier;
import com.bloxbean.cardano.yano.runtime.db.UtxoCfNames;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.*;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Direct RocksDB implementation of ChainState
 * This implementation uses RocksDB directly without any caching layer.
 */
@Slf4j
public class DirectRocksDBChainState implements ChainState, AutoCloseable, RocksDbSupplier,
        NonceStateStore, RocksDbAccess, PointRollbackCapableStore, ByronEbHeaderStore,
        OriginRollbackCapable, PointRollbackCapable, ChainStateRecovery, ChainStateSnapshots,
        NearestSlotLookup, NearestPointLookup,
        BootstrapChainStateWriter, EraMetadataStore, ByronGenesisUtxoMetadataStore,
        ArchiveChainStateCapabilities {

    private static final byte[] TIP_KEY = "tip".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HEADER_TIP_KEY = "header_tip".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EPOCH_NONCE_STATE_KEY = "epoch_nonce_state".getBytes(StandardCharsets.UTF_8);
    private static final String LEGACY_PROJ_BYRON_UTXO = "proj_byron_utxo";

    //For read apis
    private static final byte[] EPOCH_NONCE_KEY_PREFIX = "epoch_nonce_by_epoch_".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EPOCH_NONCE_CHECKPOINT_KEY_PREFIX =
            "epoch_nonce_checkpoint_".getBytes(StandardCharsets.UTF_8);
    private static final long RECOVERY_HEADER_SCAN_LIMIT =
            Long.getLong(YanoPropertyKeys.Chain.RECOVERY_HEADER_SCAN_BLOCKS, 100_000L);

    private RocksDB db;
    private Cache sharedBlockCache;
    private WriteBufferManager sharedWriteBufferManager;
    private long sharedBlockCacheCapacityBytes;
    private final String dbPath;
    private final LegacyColumnFamilyDropper legacyColumnFamilyDropper;
    private List<ColumnFamilyHandle> openedColumnFamilyHandles = List.of();

    // Column families (mutable for snapshot restore / reopen)
    private ColumnFamilyHandle blocksHandle;
    private ColumnFamilyHandle headersHandle;
    private ColumnFamilyHandle numberBySlotHandle;
    private ColumnFamilyHandle slotByNumberHandle;
    private ColumnFamilyHandle metadataHandle;
    private ColumnFamilyHandle slotToHashHandle;
    // CF to store EBBs by epoch start absolute slot (slot 0 of each epoch)
    private ColumnFamilyHandle ebbBySlot0Handle;

    // Name → CF handle registry (includes UTXO CFs)
    private final Map<String, ColumnFamilyHandle> cfByName = new HashMap<>();


    static {
        RocksDB.loadLibrary();
    }

    public DirectRocksDBChainState(String dbPath) {
        this(dbPath, RocksDB::dropColumnFamily);
    }

    DirectRocksDBChainState(String dbPath, LegacyColumnFamilyDropper legacyColumnFamilyDropper) {
        this.dbPath = dbPath;
        this.legacyColumnFamilyDropper = Objects.requireNonNull(
                legacyColumnFamilyDropper, "legacyColumnFamilyDropper");
        openDb();
    }

    @FunctionalInterface
    interface LegacyColumnFamilyDropper {
        void drop(RocksDB db, ColumnFamilyHandle handle) throws RocksDBException;
    }

    /**
     * Open (or reopen) the RocksDB database at {@code dbPath}.
     * Assigns all column family handles and populates the name→handle map.
     */
    private void openDb() {
        DBOptions dbOptions = null;
        final List<ColumnFamilyHandle> cfHandles = new ArrayList<>();
        final List<ColumnFamilyOptions> cfOptions = new ArrayList<>();
        try {
            // Determine if tuning is enabled (system property or env override)
            final boolean tuningEnabled = isRocksTuningEnabled();
            final long blockCacheBytes = getLong(
                    YanoPropertyKeys.RocksDb.BLOCK_CACHE_BYTES,
                    "YANO_ROCKSDB_BLOCK_CACHE_BYTES",
                    32L * 1024 * 1024);
            final long writeBufferBytes = getLong(
                    YanoPropertyKeys.RocksDb.WRITE_BUFFER_BYTES,
                    "YANO_ROCKSDB_WRITE_BUFFER_BYTES",
                    64L * 1024 * 1024);
            final boolean writeBufferAllowStall = getBool(
                    YanoPropertyKeys.RocksDb.WRITE_BUFFER_ALLOW_STALL,
                    "YANO_ROCKSDB_WRITE_BUFFER_ALLOW_STALL",
                    false);
            final int maxBackgroundJobs = getInt(
                    YanoPropertyKeys.RocksDb.MAX_BACKGROUND_JOBS,
                    "YANO_ROCKSDB_MAX_BACKGROUND_JOBS",
                    2);
            // Select write behavior (mutually exclusive when enabled)
            boolean pipelined = getBool(
                    YanoPropertyKeys.RocksDb.PIPELINED_WRITE,
                    "YANO_ROCKSDB_PIPELINED_WRITE",
                    true);
            boolean atomic = getBool(
                    YanoPropertyKeys.RocksDb.ATOMIC_FLUSH,
                    "YANO_ROCKSDB_ATOMIC_FLUSH",
                    false);
            if (pipelined && atomic) {
                // Prefer pipelined for throughput unless explicitly disabled
                log.warn("atomic_flush is incompatible with enable_pipelined_write. Preferring pipelined_write; atomic_flush will be disabled.");
                atomic = false;
            }

            // Configure RocksDB (global)
            final int cores = Math.max(2, Runtime.getRuntime().availableProcessors());
            dbOptions = new DBOptions()
                    .setCreateIfMissing(true)
                    .setCreateMissingColumnFamilies(true)
                    .setMaxOpenFiles(256)
                    .setKeepLogFileNum(5);
            if (tuningEnabled) {
                // When a WriteBufferManager is backed by a Cache, RocksDB charges
                // memtable usage to that cache. Preserve the configured block-cache
                // budget by sizing the shared cache for both consumers.
                sharedBlockCacheCapacityBytes = Math.addExact(blockCacheBytes, writeBufferBytes);
                sharedBlockCache = new LRUCache(sharedBlockCacheCapacityBytes);
                sharedWriteBufferManager = new WriteBufferManager(
                        writeBufferBytes, sharedBlockCache, writeBufferAllowStall);
                dbOptions
                        .setAllowConcurrentMemtableWrite(true)
                        .setIncreaseParallelism(Math.min(cores, maxBackgroundJobs))
                        .setMaxBackgroundJobs(maxBackgroundJobs)
                        .setDbWriteBufferSize(writeBufferBytes)
                        .setWriteBufferManager(sharedWriteBufferManager);
                if (pipelined) dbOptions.setEnablePipelinedWrite(true);
                if (atomic) dbOptions.setAtomicFlush(true);
            }

            // UTXO CF-specific options (only when tuning enabled)
            ColumnFamilyOptions utxoPointLookup = null;
            ColumnFamilyOptions utxoAddrPrefix = null;
            ColumnFamilyOptions utxoDeltaOpts = null;
            if (tuningEnabled) {
                utxoPointLookup = buildPointLookupCfOptions(sharedBlockCache); // utxo_unspent, utxo_spent
                utxoAddrPrefix = buildPrefixScanCfOptions(28, sharedBlockCache); // utxo_addr
                utxoDeltaOpts = buildSequentialCfOptions(sharedBlockCache);    // utxo_block_delta

                // Log effective CF tuning plan for visibility
                log.info("RocksDB CF tuning: utxo_unspent/utxo_spent/utxo_stake_balance/utxo_pointer "
                        + "=> point-lookup (ZSTD, bloom≈10bpk, whole-key, evictable L0, partitioned filters)");
                log.info("RocksDB CF tuning: utxo_addr => prefix-scan (ZSTD, prefixExtractor=28, memtablePrefixBloom≈0.10, bloom≈10bpk, evictable L0, partitioned filters)");
                log.info("RocksDB CF tuning: utxo_block_delta => sequential (ZSTD)");
            } else {
                log.info("RocksDB tuning disabled via flag; using defaults for CF options");
            }

            // Column family descriptors. The legacy Byron projection resolver is opened
            // only when an existing database contains it, then dropped before this chain
            // state is made available to the rest of the runtime (ADR-042).
            boolean legacyByronProjectionCf = existingColumnFamily(LEGACY_PROJ_BYRON_UTXO);
            final List<ColumnFamilyDescriptor> cfDescriptors = new ArrayList<>(Arrays.asList(
                    descriptor(RocksDB.DEFAULT_COLUMN_FAMILY, tuningEnabled),
                    descriptor("blocks", tuningEnabled),
                    descriptor("headers", tuningEnabled),
                    descriptor("number_by_slot", tuningEnabled),
                    descriptor("slot_by_number", tuningEnabled),
                    descriptor("slot_to_hash", tuningEnabled),
                    descriptor("metadata", tuningEnabled),
                    descriptor("ebb_by_slot0", tuningEnabled),
                    // UTXO CFs (tuned or defaults)
                    new ColumnFamilyDescriptor(
                            UtxoCfNames.UTXO_UNSPENT.getBytes(),
                            tuningEnabled ? utxoPointLookup : new ColumnFamilyOptions()),
                    new ColumnFamilyDescriptor(
                            UtxoCfNames.UTXO_SPENT.getBytes(),
                            tuningEnabled ? utxoPointLookup : new ColumnFamilyOptions()),
                    new ColumnFamilyDescriptor(
                            UtxoCfNames.UTXO_ADDR.getBytes(),
                            tuningEnabled ? utxoAddrPrefix : new ColumnFamilyOptions()),
                    new ColumnFamilyDescriptor(
                            UtxoCfNames.UTXO_BLOCK_DELTA.getBytes(),
                            tuningEnabled ? utxoDeltaOpts : new ColumnFamilyOptions()),
                    descriptor(UtxoCfNames.UTXO_META, tuningEnabled),
                    descriptor(UtxoCfNames.SCRIPT_REF, tuningEnabled),
                    new ColumnFamilyDescriptor(
                            UtxoCfNames.UTXO_STAKE_BALANCE.getBytes(),
                            tuningEnabled ? utxoPointLookup : new ColumnFamilyOptions()),
                    new ColumnFamilyDescriptor(
                            UtxoCfNames.UTXO_POINTER.getBytes(),
                            tuningEnabled ? utxoPointLookup : new ColumnFamilyOptions()),
                    // Account state CFs
                    descriptor(AccountStateCfNames.ACCT_STATE, tuningEnabled),
                    descriptor(AccountStateCfNames.ACCT_DELTA, tuningEnabled),
                    descriptor(AccountStateCfNames.ACCT_BOUNDARY_DELTA, tuningEnabled),
                    descriptor(AccountStateCfNames.EPOCH_DELEG_SNAPSHOT, tuningEnabled),
                    descriptor(AccountStateCfNames.EPOCH_PARAMS, tuningEnabled),
                    // Canonical projection outbox (ADR-039). Declared here so a contributor
                    // can write its section inside the same WriteBatch as the state it was
                    // derived from. Created on open, so a node that never enables history
                    // simply leaves them empty.
                    new ColumnFamilyDescriptor(ProjectionCfNames.PROJ_HEADER.getBytes(),
                            buildSequentialCfOptions(tuningEnabled ? sharedBlockCache : null)),
                    new ColumnFamilyDescriptor(ProjectionCfNames.PROJ_SECTION.getBytes(),
                            buildSequentialCfOptions(tuningEnabled ? sharedBlockCache : null)),
                    descriptor(ProjectionCfNames.PROJ_META, tuningEnabled),
                    descriptor(ProjectionCfNames.PROJ_ARTIFACT, tuningEnabled)
            ));
            if (legacyByronProjectionCf) {
                cfDescriptors.add(new ColumnFamilyDescriptor(
                        LEGACY_PROJ_BYRON_UTXO.getBytes(StandardCharsets.UTF_8)));
            }
            var seenOptions = Collections.newSetFromMap(
                    new IdentityHashMap<ColumnFamilyOptions, Boolean>());
            for (ColumnFamilyDescriptor descriptor : cfDescriptors) {
                if (seenOptions.add(descriptor.getOptions())) {
                    cfOptions.add(descriptor.getOptions());
                }
            }

            // Open database
            db = RocksDB.open(dbOptions, dbPath, cfDescriptors, cfHandles);

            // Assign handles (skip default at index 0)
            blocksHandle = cfHandles.get(1);
            headersHandle = cfHandles.get(2);
            numberBySlotHandle = cfHandles.get(3);
            slotByNumberHandle = cfHandles.get(4);
            slotToHashHandle = cfHandles.get(5);
            metadataHandle = cfHandles.get(6);
            ebbBySlot0Handle = cfHandles.get(7);

            // Populate name → handle map
            cfByName.clear();
            for (int i = 0; i < cfDescriptors.size(); i++) {
                String name;
                if (i == 0) {
                    name = new String(RocksDB.DEFAULT_COLUMN_FAMILY);
                } else {
                    name = new String(cfDescriptors.get(i).getName());
                }
                cfByName.put(name, cfHandles.get(i));
            }

            if (legacyByronProjectionCf) {
                ColumnFamilyHandle legacyHandle = cfByName.remove(LEGACY_PROJ_BYRON_UTXO);
                if (legacyHandle == null) {
                    throw new IllegalStateException("Legacy Byron projection CF was detected but not opened");
                }
                try {
                    legacyColumnFamilyDropper.drop(db, legacyHandle);
                    log.info("Removed obsolete RocksDB column family {}", LEGACY_PROJ_BYRON_UTXO);
                } finally {
                    // Remove by identity while the native handle is still live. RocksJava's
                    // equals() calls getID(), which asserts after close.
                    cfHandles.removeIf(candidate -> candidate == legacyHandle);
                    legacyHandle.close();
                }
            }

            openedColumnFamilyHandles = new ArrayList<>(cfHandles);

            log.info("RocksDB initialized at: {} (tuningEnabled={}, pipelinedWrite={}, atomicFlush={}, "
                            + "parallelism={}, blockCacheBytes={}, sharedCacheCapacityBytes={}, writeBufferBytes={}, "
                            + "writeBufferAllowStall={}, maxBackgroundJobs={})",
                    dbPath, tuningEnabled, pipelined && tuningEnabled, atomic && tuningEnabled,
                    Math.min(cores, maxBackgroundJobs), blockCacheBytes, sharedBlockCacheCapacityBytes,
                    writeBufferBytes,
                    writeBufferAllowStall,
                    maxBackgroundJobs);

        } catch (Exception e) {
            cfHandles.forEach(handle -> {
                try {
                    handle.close();
                } catch (Exception ignored) {
                }
            });
            openedColumnFamilyHandles = List.of();
            if (db != null) {
                try {
                    db.close();
                } catch (Exception ignored) {
                }
                db = null;
            }
            closeNativeMemoryBudgets();
            throw new RuntimeException("Failed to initialize RocksDB", e);
        } finally {
            cfOptions.forEach(ColumnFamilyOptions::close);
            if (dbOptions != null) {
                dbOptions.close();
            }
        }
    }

    private boolean existingColumnFamily(String name) throws RocksDBException {
        Path path = Path.of(dbPath);
        if (!Files.exists(path.resolve("CURRENT"))) return false;
        try (Options options = new Options()) {
            for (byte[] columnFamily : RocksDB.listColumnFamilies(options, dbPath)) {
                if (name.equals(new String(columnFamily, StandardCharsets.UTF_8))) return true;
            }
            return false;
        }
    }

    private static boolean isRocksTuningEnabled() {
        try {
            String prop = System.getProperty(YanoPropertyKeys.RocksDb.TUNING_ENABLED);
            if (prop != null) return !"false".equalsIgnoreCase(prop);
            String env = System.getenv("YANO_ROCKSDB_TUNING_ENABLED");
            if (env != null) return !"false".equalsIgnoreCase(env);
        } catch (Throwable ignored) {
        }
        return true; // default enabled
    }

    private static boolean getBool(String sysProp, String envVar, boolean defVal) {
        try {
            String prop = System.getProperty(sysProp);
            if (prop != null) return Boolean.parseBoolean(prop);
            String env = System.getenv(envVar);
            if (env != null) return Boolean.parseBoolean(env);
        } catch (Throwable ignored) {
        }
        return defVal;
    }

    private static long getLong(String sysProp, String envVar, long defaultValue) {
        String value = System.getProperty(sysProp);
        if (value == null) value = System.getenv(envVar);
        if (value == null || value.isBlank()) return defaultValue;
        long parsed = Long.parseLong(value);
        if (parsed <= 0) throw new IllegalArgumentException(sysProp + " must be positive");
        return parsed;
    }

    private static int getInt(String sysProp, String envVar, int defaultValue) {
        long value = getLong(sysProp, envVar, defaultValue);
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(sysProp + " exceeds integer range");
        }
        return (int) value;
    }

    boolean isWriteBufferStallEnabled() {
        return sharedWriteBufferManager != null && sharedWriteBufferManager.allowStall();
    }

    long sharedBlockCacheCapacityBytes() {
        return sharedBlockCacheCapacityBytes;
    }

    private ColumnFamilyDescriptor descriptor(String name, boolean tuningEnabled) {
        return descriptor(name.getBytes(StandardCharsets.UTF_8), tuningEnabled);
    }

    private ColumnFamilyDescriptor descriptor(byte[] name, boolean tuningEnabled) {
        return new ColumnFamilyDescriptor(name,
                tuningEnabled ? buildSequentialCfOptions(sharedBlockCache) : new ColumnFamilyOptions());
    }

    private static ColumnFamilyOptions buildPointLookupCfOptions(Cache cache) {
        ColumnFamilyOptions opts = new ColumnFamilyOptions();
        opts.setCompressionType(CompressionType.ZSTD_COMPRESSION);
        BlockBasedTableConfig table = new BlockBasedTableConfig();
        table.setFilterPolicy(new BloomFilter(10, false)); // ~10 bits/key
        table.setWholeKeyFiltering(true);
        table.setPinL0FilterAndIndexBlocksInCache(false);
        table.setPartitionFilters(true);
        configureSharedCache(table, cache);
        opts.setTableFormatConfig(table);
        return opts;
    }

    private static ColumnFamilyOptions buildPrefixScanCfOptions(int prefixLen, Cache cache) {
        ColumnFamilyOptions opts = new ColumnFamilyOptions();
        opts.setCompressionType(CompressionType.ZSTD_COMPRESSION);
        // Try to set a fixed prefix extractor if available in this RocksJava version
        try {
            Class<?> stClazz = Class.forName("org.rocksdb.SliceTransform");
            Method factory = stClazz.getMethod("createFixedPrefix", int.class);
            Object st = factory.invoke(null, prefixLen);
            Method setter = ColumnFamilyOptions.class.getMethod("setPrefixExtractor", stClazz);
            setter.invoke(opts, st);
        } catch (Throwable t) {
            // Prefix extractor not available; continue without it (bloom still helps)
            log.warn("RocksDB SliceTransform fixed prefix not available; proceeding without prefix extractor");
        }
        opts.setMemtablePrefixBloomSizeRatio(0.10);
        BlockBasedTableConfig table = new BlockBasedTableConfig();
        table.setFilterPolicy(new BloomFilter(10, false));
        table.setWholeKeyFiltering(false);
        table.setPinL0FilterAndIndexBlocksInCache(false);
        table.setPartitionFilters(true);
        configureSharedCache(table, cache);
        opts.setTableFormatConfig(table);
        return opts;
    }

    private static ColumnFamilyOptions buildSequentialCfOptions() {
        return buildSequentialCfOptions(null);
    }

    private static ColumnFamilyOptions buildSequentialCfOptions(Cache cache) {
        ColumnFamilyOptions opts = new ColumnFamilyOptions();
        opts.setCompressionType(CompressionType.ZSTD_COMPRESSION);
        BlockBasedTableConfig table = new BlockBasedTableConfig();
        configureSharedCache(table, cache);
        opts.setTableFormatConfig(table);
        return opts;
    }

    private static void configureSharedCache(BlockBasedTableConfig table, Cache cache) {
        if (cache == null) return;
        table.setBlockCache(cache);
        table.setCacheIndexAndFilterBlocks(true);
        table.setCacheIndexAndFilterBlocksWithHighPriority(true);
    }

    private void closeNativeMemoryBudgets() {
        if (sharedWriteBufferManager != null) {
            sharedWriteBufferManager.close();
            sharedWriteBufferManager = null;
        }
        if (sharedBlockCache != null) {
            sharedBlockCache.close();
            sharedBlockCache = null;
        }
        sharedBlockCacheCapacityBytes = 0;
    }

    @Override
    public RocksDbContext rocks() {
        return new RocksDbContext(db, Collections.unmodifiableMap(cfByName));
    }

    // --- RocksDbAccess ---

    @Override
    public Object getDb() {
        return db;
    }

    @Override
    public Object getColumnFamilyHandle(String cfName) {
        return cfByName.get(cfName);
    }

    @Override
    public void storeBlock(byte[] blockHash, Long blockNumber, Long slot, byte[] block) {
        try {
            // MANDATORY CONTINUITY CHECK: Prevent gaps in chainstate
            if (blockNumber > 1) {
                byte[] previousBlock = getBlockByNumber(blockNumber - 1);
                if (previousBlock == null) {
                    String errorMsg = String.format(
                            "🚨 CONTINUITY VIOLATION: Cannot store block #%d - previous block #%d is missing! " +
                                    "This would create gaps in chainstate. slot=%d, hash=%s",
                            blockNumber, blockNumber - 1, slot, HexUtil.encodeHexString(blockHash));
                    log.error(errorMsg);

                    // Throw exception to stop sync and prevent gaps
                    throw new IllegalStateException(errorMsg);
                }
                log.debug("✅ Continuity check passed for block #{}", blockNumber);
            } else if (blockNumber == 1) {
                log.info("📍 Storing genesis/first block #{}", blockNumber);
            }

            // HASH CONSISTENCY CHECK: Validate block matches stored header for main blocks.
            // For Byron EBBs, slot_to_hash maps the main block at the same absolute slot boundary.
            // In that case, allow storing the body if ebb_by_slot0 points to this hash.
            byte[] expectedHash = db.get(slotToHashHandle, longToBytes(slot));
            if (expectedHash != null && !Arrays.equals(expectedHash, blockHash)) {
                byte[] ebbHashAtSlot0 = db.get(ebbBySlot0Handle, longToBytes(slot));
                boolean isEbbAtThisSlot = ebbHashAtSlot0 != null && Arrays.equals(ebbHashAtSlot0, blockHash);
                if (!isEbbAtThisSlot) {
                    String errorMsg = String.format(
                            "FORK MISMATCH: Block #%d at slot %d has different hash than main header. Expected(main): %s, Got: %s",
                            blockNumber, slot,
                            HexUtil.encodeHexString(expectedHash),
                            HexUtil.encodeHexString(blockHash));
                    log.warn("🚨 {}", errorMsg);
                    throw new IllegalStateException(errorMsg);
                }
                // It's an EBB body at the epoch boundary; proceed to store body keyed by hash only.
                if (log.isDebugEnabled()) {
                    log.debug("EBB body store allowed at slot {} (hash {}), main header maps to {}",
                            slot, HexUtil.encodeHexString(blockHash), HexUtil.encodeHexString(expectedHash));
                }
            }

            // Use write batch for atomic updates
            try (WriteBatch batch = new WriteBatch();
                 WriteOptions writeOptions = new WriteOptions()) {
                // Store block
                batch.put(blocksHandle, blockHash, block);

                // IMPORTANT: Do NOT update indices here - only headers should manage indices
                // This prevents body sync from overwriting header mappings during forks
                // updateChainState(batch, blockHash, blockNumber, slot); // REMOVED

                // Update tip if this is a newer block
                updateTip(batch, blockHash, blockNumber, slot);

                // Write batch atomically
                db.write(writeOptions, batch);

                log.debug("Stored block: number={}, slot={}, hash={}",
                        blockNumber, slot, HexUtil.encodeHexString(blockHash));
            }
        } catch (Exception e) {
            log.error("Failed to store block: slot={}, blockNumber={}", slot, blockNumber, e);
            throw new RuntimeException("Failed to store block", e);
        }
    }

    @Override
    public byte[] getBlock(byte[] blockHash) {
        try {
            return db.get(blocksHandle, blockHash);
        } catch (Exception e) {
            log.error("Failed to get block", e);
            return null;
        }
    }

    @Override
    public boolean hasBlock(byte[] blockHash) {
        try {
            try (ReadOptions ro = new ReadOptions().setFillCache(false)) {
                byte[] val = db.get(blocksHandle, ro, blockHash);
                return val != null;
            }
        } catch (Exception e) {
            log.warn("hasBlock check failed", e);
            return false;
        }
    }

    @Override
    public void storeBlockHeader(byte[] blockHash, Long blockNumber, Long slot, byte[] blockHeader) {
        try {
            // MANDATORY CONTINUITY CHECK: Prevent gaps in header chainstate
            if (blockNumber != null && blockNumber > 1) {
                byte[] previousHeader = getBlockHeaderByNumber(blockNumber - 1);
                if (previousHeader == null) {
                    String errorMsg = String.format(
                            "🚨 HEADER CONTINUITY VIOLATION: Cannot store header #%d - previous header #%d is missing! " +
                                    "This would create gaps in header chainstate. slot=%d, hash=%s",
                            blockNumber, blockNumber - 1, slot, HexUtil.encodeHexString(blockHash));
                    log.error(errorMsg);

                    // Throw exception to stop sync and prevent gaps
                    throw new IllegalStateException(errorMsg);
                }
                log.debug("✅ Header continuity check passed for header #{}", blockNumber);
            } else if (blockNumber != null && blockNumber == 1) {
                log.info("📍 Storing genesis/first header #{}", blockNumber);
            }

            // Use write batch for atomic updates
            try (WriteBatch batch = new WriteBatch();
                 WriteOptions writeOptions = new WriteOptions()) {
                // Store header
                batch.put(headersHandle, blockHash, blockHeader);
                ChainTip newHeaderTip = new ChainTip(slot, blockHash, blockNumber);
                batch.put(metadataHandle, HEADER_TIP_KEY, serializeChainTip(newHeaderTip));

                // If we successfully extracted slot and block number, update indices
                if (slot != null && blockNumber != null) {
                    updateChainState(batch, blockHash, blockNumber, slot);
                    // MAIN header path (default): also update number -> slot mapping
                    batch.put(slotByNumberHandle, longToBytes(blockNumber), longToBytes(slot));
                    if (log.isDebugEnabled()) {
                        log.debug("Updated Metadata: slot={}, blockNumber={}", slot, blockNumber);
                    }
                }

                // Write batch atomically
                db.write(writeOptions, batch);

                log.debug("Stored header: hash={}, extracted slot={}, blockNumber={}",
                        HexUtil.encodeHexString(blockHash), slot, blockNumber);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to store block header", e);
        }
    }

    @Override
    public byte[] getBlockHeader(byte[] blockHash) {
        try {
            return db.get(headersHandle, blockHash);
        } catch (Exception e) {
            log.error("Failed to get block header", e);
            return null;
        }
    }

    // Store Byron EBB header: keep header bytes and header_tip, index in ebb_by_slot0 only
    public void storeByronEbHeader(byte[] blockHash, Long blockNumber, Long slot, byte[] blockHeader) {
        try (WriteBatch batch = new WriteBatch();
             WriteOptions writeOptions = new WriteOptions()) {
            // Store EBB header bytes (by hash)
            batch.put(headersHandle, blockHash, blockHeader);

            // Update header_tip
            ChainTip newHeaderTip = new ChainTip(slot, blockHash, blockNumber);
            batch.put(metadataHandle, HEADER_TIP_KEY, serializeChainTip(newHeaderTip));

            // Index EBB by epoch start absolute slot; do not populate slot_to_hash or number mappings
            if (slot != null) {
                batch.put(ebbBySlot0Handle, longToBytes(slot), blockHash);
            }

            db.write(writeOptions, batch);
            log.debug("Stored Byron EBB header (ebb_by_slot0 only): slot={}, blockNumber={}", slot, blockNumber);
        } catch (Exception e) {
            throw new RuntimeException("Failed to store Byron EBB header", e);
        }
    }

    /**
     * Store a block header without continuity check.
     * Used only during bootstrap to seed synthetic chain entries at arbitrary block numbers.
     */
    public void forceStoreBlockHeader(byte[] blockHash, Long blockNumber, Long slot, byte[] blockHeader) {
        try (WriteBatch batch = new WriteBatch();
             WriteOptions writeOptions = new WriteOptions()) {
            batch.put(headersHandle, blockHash, blockHeader);
            ChainTip newHeaderTip = new ChainTip(slot, blockHash, blockNumber);
            batch.put(metadataHandle, HEADER_TIP_KEY, serializeChainTip(newHeaderTip));

            if (slot != null && blockNumber != null) {
                updateChainState(batch, blockHash, blockNumber, slot);
                batch.put(slotByNumberHandle, longToBytes(blockNumber), longToBytes(slot));
            }

            db.write(writeOptions, batch);
            log.info("Bootstrap: stored header #{}, slot={}, hash={}",
                    blockNumber, slot, HexUtil.encodeHexString(blockHash));
        } catch (Exception e) {
            throw new RuntimeException("Failed to force-store block header", e);
        }
    }

    /**
     * Store a block body without continuity or hash-consistency check.
     * Used only during bootstrap to seed synthetic chain entries at arbitrary block numbers.
     */
    public void forceStoreBlock(byte[] blockHash, Long blockNumber, Long slot, byte[] block) {
        try (WriteBatch batch = new WriteBatch();
             WriteOptions writeOptions = new WriteOptions()) {
            batch.put(blocksHandle, blockHash, block);
            updateTip(batch, blockHash, blockNumber, slot);
            db.write(writeOptions, batch);
            log.info("Bootstrap: stored block #{}, slot={}, hash={}",
                    blockNumber, slot, HexUtil.encodeHexString(blockHash));
        } catch (Exception e) {
            throw new RuntimeException("Failed to force-store block", e);
        }
    }

    /**
     * Check if a block body exists for the given block number, without reading the full body.
     * Uses index lookups (number → slot → hash) then hasBlock (fillCache=false).
     * Much cheaper than getBlockByNumber which reads the full block body.
     */
    public boolean hasBlockBodyByNumber(long blockNumber) {
        try {
            byte[] slotBytes = db.get(slotByNumberHandle, longToBytes(blockNumber));
            if (slotBytes == null) return false;
            long slot = bytesToLong(slotBytes);
            byte[] blockHash = db.get(slotToHashHandle, longToBytes(slot));
            if (blockHash == null) return false;
            return hasBlock(blockHash);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Optional<CanonicalBlockReference> getCanonicalBlockReference(long blockNumber) {
        if (blockNumber < 0) return Optional.empty();
        try {
            byte[] slotBytes = db.get(slotByNumberHandle, longToBytes(blockNumber));
            if (slotBytes == null) {
                // The genesis EBB is the only EBB that can own an otherwise unclaimed
                // projection coordinate. Later EBBs share the preceding main block's
                // difficulty and that main block owns slot_by_number.
                if (blockNumber != 0) return Optional.empty();
                try (RocksIterator it = db.newIterator(ebbBySlot0Handle)) {
                    it.seekToFirst();
                    if (!it.isValid()) return Optional.empty();
                    return Optional.of(new CanonicalBlockReference(
                            blockNumber, bytesToLong(it.key()), it.value()));
                }
            }
            long slot = bytesToLong(slotBytes);
            byte[] blockHash = db.get(slotToHashHandle, longToBytes(slot));
            return blockHash == null
                    ? Optional.empty()
                    : Optional.of(new CanonicalBlockReference(blockNumber, slot, blockHash));
        } catch (Exception e) {
            log.warn("Failed to read canonical reference for block {}: {}", blockNumber, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public Optional<ByronEpochBoundaryReference> getByronEpochBoundaryBlockAtOrBefore(long slot) {
        if (slot < 0) return Optional.empty();
        try (RocksIterator iterator = db.newIterator(ebbBySlot0Handle)) {
            iterator.seekForPrev(longToBytes(slot));
            if (!iterator.isValid()) return Optional.empty();
            long ebbSlot = bytesToLong(iterator.key());
            byte[] blockHash = Arrays.copyOf(iterator.value(), iterator.value().length);
            byte[] body = db.get(blocksHandle, blockHash);
            if (body == null) return Optional.empty();
            String parentHash = ByronEbBlockSerializer.INSTANCE.deserialize(body).getHeader().getPrevBlock();
            if (parentHash == null || parentHash.isBlank()) return Optional.empty();
            return Optional.of(new ByronEpochBoundaryReference(ebbSlot, blockHash,
                    HexUtil.decodeHexString(parentHash)));
        } catch (Exception e) {
            log.warn("Failed to read Byron EBB reference at or before slot {}: {}", slot, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public OptionalLong getEarliestRetainedBodyBlockNumber() {
        try {
            long start = 0;
            byte[] cursorBytes = db.get(metadataHandle, BlockPruner.CURSOR_KEY);
            if (cursorBytes != null && cursorBytes.length == Long.BYTES) {
                start = Math.max(0, bytesToLong(cursorBytes) + 1);
            }
            try (RocksIterator it = db.newIterator(slotByNumberHandle)) {
                it.seek(longToBytes(start));
                while (it.isValid()) {
                    if (it.key().length == Long.BYTES && it.value().length == Long.BYTES) {
                        long blockNumber = bytesToLong(it.key());
                        long slot = bytesToLong(it.value());
                        byte[] hash = db.get(slotToHashHandle, longToBytes(slot));
                        if (hash != null && db.get(blocksHandle, hash) != null) {
                            return OptionalLong.of(blockNumber);
                        }
                    }
                    it.next();
                }
            }
            return OptionalLong.empty();
        } catch (Exception e) {
            log.warn("Failed to read earliest retained block body: {}", e.toString());
            return OptionalLong.empty();
        }
    }

    @Override
    public byte[] getBlockByNumber(Long blockNumber) {
        try {
            byte[] slotBytes = db.get(slotByNumberHandle, longToBytes(blockNumber));
            if (slotBytes != null) {
                long slot = bytesToLong(slotBytes);
                byte[] blockHash = db.get(slotToHashHandle, longToBytes(slot));
                if (blockHash != null) {
                    return db.get(blocksHandle, blockHash);
                }
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to get block by number", e);
            return null;
        }
    }

    @Override
    public byte[] getBlockHeaderByNumber(Long blockNumber) {
        try {
            byte[] slotBytes = db.get(slotByNumberHandle, longToBytes(blockNumber));
            if (slotBytes != null) {
                long slot = bytesToLong(slotBytes);
                byte[] blockHash = db.get(slotToHashHandle, longToBytes(slot));
                if (blockHash != null) {
                    return db.get(headersHandle, blockHash);
                }
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to get block header by number", e);
            return null;
        }
    }

    @Override
    public void rollbackTo(Long slot) {
        try {
            byte[] blockNumberBytes = db.get(numberBySlotHandle, longToBytes(slot));
            if (blockNumberBytes == null) {
                if (slot == 0 && getTip() == null && getHeaderTip() == null) {
                    log.info("Rollback to origin requested on empty chain state; treating as no-op");
                    return;
                }
                log.error("Rollback failed: requested slot {} does not exist in storage", slot);
                throw new RuntimeException("Cannot rollback to slot " + slot + " - slot not found in storage");
            }

            long rollbackBlockNumber = bytesToLong(blockNumberBytes);
            byte[] rollbackHash = db.get(slotToHashHandle, longToBytes(slot));

            if (rollbackHash == null) {
                log.error("Rollback failed: block hash not found for slot {} block {}", slot, rollbackBlockNumber);
                throw new RuntimeException("Cannot rollback to slot " + slot + " - block hash not found");
            }
            rollbackTo(new Point(slot, HexUtil.encodeHexString(rollbackHash)));
        } catch (Exception e) {
            log.error("Rollback failed: to slot={}", slot, e);
            throw new RuntimeException("Failed to rollback to slot " + slot, e);
        }
    }

    @Override
    public synchronized void rollbackTo(Point target) {
        if (target == null) throw new IllegalArgumentException("Rollback target is required");
        if (target.getHash() == null) {
            rollbackToOrigin();
            return;
        }

        try {
            ResolvedRollbackPoint resolved = resolveRollbackPoint(target);
            ChainTip bodyTip = getTip();
            ResolvedRollbackPoint resolvedBodyTip = resolveTip(bodyTip);
            boolean headerOnly = bodyTip == null || comparePoints(resolved, resolvedBodyTip) > 0;

            if (headerOnly) {
                log.info("Header-only rollback to point slot={}, hash={} (body tip at {})",
                        resolved.slot(), target.getHash(), bodyTip != null ? bodyTip.getSlot() : "null");
                performHeaderOnlyRollback(resolved);
            } else {
                log.warn("Full rollback to point slot={}, hash={} (affecting headers and bodies)",
                        resolved.slot(), target.getHash());
                performFullRollback(resolved);
            }

            ChainTip resultingHeaderTip = getHeaderTip();
            if (!samePoint(resultingHeaderTip, resolved)) {
                throw new IllegalStateException("Header rollback ended at " + describePoint(resultingHeaderTip)
                        + " instead of " + describePoint(resolved));
            }
            if (!headerOnly && !samePoint(getTip(), resolved)) {
                throw new IllegalStateException("Body rollback ended at " + describePoint(getTip())
                        + " instead of " + describePoint(resolved));
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to rollback to point " + target, e);
        }
    }

    @Override
    public void rollbackToPoint(Point target) {
        rollbackTo(target);
    }

    public void rollbackToOrigin() {
        WriteBatch batch = new WriteBatch();
        int slotsDeleted = 0;
        int blocksDeleted = 0;
        int headersDeleted = 0;
        int ebbDeleted = 0;
        int metadataDeleted = 0;

        try {
            try (RocksIterator iterator = db.newIterator(numberBySlotHandle)) {
                for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                    byte[] slotKey = Arrays.copyOf(iterator.key(), iterator.key().length);
                    byte[] blockNumberValue = Arrays.copyOf(iterator.value(), iterator.value().length);
                    long blockNumber = bytesToLong(blockNumberValue);
                    byte[] blockHash = db.get(slotToHashHandle, slotKey);

                    if (blockHash != null) {
                        if (db.get(blocksHandle, blockHash) != null) {
                            batch.delete(blocksHandle, blockHash);
                            blocksDeleted++;
                        }
                        if (db.get(headersHandle, blockHash) != null) {
                            batch.delete(headersHandle, blockHash);
                            headersDeleted++;
                        }
                    }

                    batch.delete(numberBySlotHandle, slotKey);
                    batch.delete(slotByNumberHandle, longToBytes(blockNumber));
                    batch.delete(slotToHashHandle, slotKey);
                    slotsDeleted++;
                }
            }

            try (RocksIterator iterator = db.newIterator(ebbBySlot0Handle)) {
                for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                    byte[] key = Arrays.copyOf(iterator.key(), iterator.key().length);
                    byte[] hash = Arrays.copyOf(iterator.value(), iterator.value().length);
                    if (db.get(blocksHandle, hash) != null) {
                        batch.delete(blocksHandle, hash);
                        blocksDeleted++;
                    }
                    if (db.get(headersHandle, hash) != null) {
                        batch.delete(headersHandle, hash);
                        headersDeleted++;
                    }
                    batch.delete(ebbBySlot0Handle, key);
                    ebbDeleted++;
                }
            }

            batch.delete(metadataHandle, TIP_KEY);
            batch.delete(metadataHandle, HEADER_TIP_KEY);
            batch.delete(metadataHandle, EPOCH_NONCE_STATE_KEY);
            metadataDeleted += 3;

            try (RocksIterator iterator = db.newIterator(metadataHandle)) {
                for (iterator.seek(EPOCH_NONCE_KEY_PREFIX); iterator.isValid(); iterator.next()) {
                    byte[] key = Arrays.copyOf(iterator.key(), iterator.key().length);
                    if (!startsWith(key, EPOCH_NONCE_KEY_PREFIX)) break;
                    batch.delete(metadataHandle, key);
                    metadataDeleted++;
                }
            }

            try (RocksIterator iterator = db.newIterator(metadataHandle)) {
                for (iterator.seek(EPOCH_NONCE_CHECKPOINT_KEY_PREFIX); iterator.isValid(); iterator.next()) {
                    byte[] key = Arrays.copyOf(iterator.key(), iterator.key().length);
                    if (!startsWith(key, EPOCH_NONCE_CHECKPOINT_KEY_PREFIX)) break;
                    batch.delete(metadataHandle, key);
                    metadataDeleted++;
                }
            }

            byte[] eraStartPrefix = ERA_START_SLOT_PREFIX.getBytes(StandardCharsets.UTF_8);
            try (RocksIterator iterator = db.newIterator(metadataHandle)) {
                for (iterator.seek(eraStartPrefix); iterator.isValid(); iterator.next()) {
                    byte[] key = Arrays.copyOf(iterator.key(), iterator.key().length);
                    if (!startsWith(key, eraStartPrefix)) break;
                    batch.delete(metadataHandle, key);
                    metadataDeleted++;
                }
            }

            try (WriteOptions wo = new WriteOptions()) {
                db.write(wo, batch);
            }

            log.warn("Rollback to origin completed: deleted {} slots, {} blocks, {} headers, {} EBBs, {} metadata entries",
                    slotsDeleted, blocksDeleted, headersDeleted, ebbDeleted, metadataDeleted);
        } catch (Exception e) {
            log.error("Rollback to origin failed", e);
            throw new RuntimeException("Failed to rollback to origin", e);
        } finally {
            batch.close();
        }
    }

    private ResolvedRollbackPoint resolveRollbackPoint(Point target) throws RocksDBException {
        byte[] requestedHash;
        try {
            requestedHash = HexUtil.decodeHexString(target.getHash());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid rollback hash: " + target.getHash(), e);
        }
        if (requestedHash.length != 32) {
            throw new IllegalArgumentException("Rollback hash must be exactly 32 bytes");
        }

        long slot = target.getSlot();
        byte[] mainHash = db.get(slotToHashHandle, longToBytes(slot));
        if (Arrays.equals(mainHash, requestedHash)) {
            byte[] blockNumber = db.get(numberBySlotHandle, longToBytes(slot));
            if (blockNumber == null) {
                throw new IllegalStateException("Missing block number for canonical main block at slot " + slot);
            }
            return new ResolvedRollbackPoint(slot, bytesToLong(blockNumber), requestedHash, false);
        }

        byte[] ebbHash = db.get(ebbBySlot0Handle, longToBytes(slot));
        if (Arrays.equals(ebbHash, requestedHash)) {
            return new ResolvedRollbackPoint(slot, inferEbbBlockNumber(slot, requestedHash), requestedHash, true);
        }
        throw new IllegalArgumentException("Rollback point is not canonical at slot " + slot);
    }

    private ResolvedRollbackPoint resolveTip(ChainTip tip) throws RocksDBException {
        if (tip == null || tip.getBlockHash() == null) return null;
        byte[] ebbHash = db.get(ebbBySlot0Handle, longToBytes(tip.getSlot()));
        boolean ebb = Arrays.equals(ebbHash, tip.getBlockHash());
        return new ResolvedRollbackPoint(tip.getSlot(), tip.getBlockNumber(), tip.getBlockHash(), ebb);
    }

    private long inferEbbBlockNumber(long slot, byte[] hash) throws RocksDBException {
        ChainTip bodyTip = getTip();
        if (bodyTip != null && bodyTip.getSlot() == slot && Arrays.equals(bodyTip.getBlockHash(), hash)) {
            return bodyTip.getBlockNumber();
        }
        ChainTip headerTip = getHeaderTip();
        if (headerTip != null && headerTip.getSlot() == slot && Arrays.equals(headerTip.getBlockHash(), hash)) {
            return headerTip.getBlockNumber();
        }

        byte[] sameSlotNumber = db.get(numberBySlotHandle, longToBytes(slot));
        if (sameSlotNumber != null) return Math.max(0L, bytesToLong(sameSlotNumber) - 1L);

        try (RocksIterator iterator = db.newIterator(numberBySlotHandle)) {
            iterator.seekForPrev(longToBytes(slot));
            if (iterator.isValid()) return bytesToLong(iterator.value());
        }
        return 0L;
    }

    private static boolean isMainAfter(long slot, byte[] hash, ResolvedRollbackPoint target) {
        if (slot != target.slot()) return slot > target.slot();
        return target.ebb() || !Arrays.equals(hash, target.hash());
    }

    private static boolean isEbbAfter(long slot, byte[] hash, ResolvedRollbackPoint target) {
        if (slot != target.slot()) return slot > target.slot();
        return target.ebb() && !Arrays.equals(hash, target.hash());
    }

    private static int comparePoints(ResolvedRollbackPoint left, ResolvedRollbackPoint right) {
        if (right == null) return 1;
        int slot = Long.compare(left.slot(), right.slot());
        if (slot != 0) return slot;
        if (left.ebb() == right.ebb()) return 0;
        return left.ebb() ? -1 : 1;
    }

    private static boolean samePoint(ChainTip tip, ResolvedRollbackPoint point) {
        return tip != null
                && tip.getSlot() == point.slot()
                && Arrays.equals(tip.getBlockHash(), point.hash());
    }

    private static String describePoint(ChainTip tip) {
        if (tip == null) return "origin";
        return "slot=" + tip.getSlot() + ", block=" + tip.getBlockNumber()
                + ", hash=" + HexUtil.encodeHexString(tip.getBlockHash());
    }

    private static String describePoint(ResolvedRollbackPoint point) {
        return "slot=" + point.slot() + ", block=" + point.blockNumber()
                + ", hash=" + HexUtil.encodeHexString(point.hash())
                + (point.ebb() ? ", kind=EBB" : ", kind=main");
    }

    private record ResolvedRollbackPoint(long slot, long blockNumber, byte[] hash, boolean ebb) {
        ChainTip toTip() {
            return new ChainTip(slot, hash, blockNumber);
        }
    }

    private record DeleteCounts(int mainSlots, int bodies, int mainHeaders, int ebbs, int ebbHeaders) {
    }

    private void performHeaderOnlyRollback(ResolvedRollbackPoint target) throws RocksDBException {
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            DeleteCounts deleted = stageDeletesAfter(batch, target, false);
            batch.put(metadataHandle, HEADER_TIP_KEY, serializeChainTip(target.toTip()));
            db.write(wo, batch);
            log.info("Header-only rollback completed: point={}, deleted {} main headers and {} EBB headers",
                    describePoint(target), deleted.mainHeaders(), deleted.ebbHeaders());
        }
    }

    private void performFullRollback(ResolvedRollbackPoint target) throws RocksDBException {
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            DeleteCounts deleted = stageDeletesAfter(batch, target, true);
            ChainTip newTip = target.toTip();
            batch.put(metadataHandle, HEADER_TIP_KEY, serializeChainTip(newTip));
            batch.put(metadataHandle, TIP_KEY, serializeChainTip(newTip));
            db.write(wo, batch);
            log.warn("Full rollback completed: point={}, deleted {} main slots, {} bodies, {} headers, {} EBBs",
                    describePoint(target), deleted.mainSlots(), deleted.bodies(),
                    deleted.mainHeaders() + deleted.ebbHeaders(), deleted.ebbs());
        }
    }

    private DeleteCounts stageDeletesAfter(WriteBatch batch,
                                           ResolvedRollbackPoint target,
                                           boolean deleteBodies) throws RocksDBException {
        int mainSlots = 0;
        int bodies = 0;
        int mainHeaders = 0;
        int ebbs = 0;
        int ebbHeaders = 0;

        try (RocksIterator iterator = db.newIterator(numberBySlotHandle)) {
            iterator.seekToLast();
            while (iterator.isValid()) {
                long slot = bytesToLong(iterator.key());
                if (slot < target.slot()) break;
                byte[] slotKey = Arrays.copyOf(iterator.key(), iterator.key().length);
                long blockNumber = bytesToLong(iterator.value());
                byte[] blockHash = db.get(slotToHashHandle, slotKey);
                if (!isMainAfter(slot, blockHash, target)) break;

                if (blockHash != null) {
                    if (deleteBodies && db.get(blocksHandle, blockHash) != null) {
                        batch.delete(blocksHandle, blockHash);
                        bodies++;
                    }
                    if (db.get(headersHandle, blockHash) != null) {
                        batch.delete(headersHandle, blockHash);
                        mainHeaders++;
                    }
                }
                batch.delete(numberBySlotHandle, slotKey);
                batch.delete(slotByNumberHandle, longToBytes(blockNumber));
                batch.delete(slotToHashHandle, slotKey);
                mainSlots++;
                iterator.prev();
            }
        }

        try (RocksIterator iterator = db.newIterator(ebbBySlot0Handle)) {
            iterator.seekToLast();
            while (iterator.isValid()) {
                long slot = bytesToLong(iterator.key());
                if (slot < target.slot()) break;
                byte[] slotKey = Arrays.copyOf(iterator.key(), iterator.key().length);
                byte[] blockHash = Arrays.copyOf(iterator.value(), iterator.value().length);
                if (!isEbbAfter(slot, blockHash, target)) break;

                if (deleteBodies && db.get(blocksHandle, blockHash) != null) {
                    batch.delete(blocksHandle, blockHash);
                    bodies++;
                }
                if (db.get(headersHandle, blockHash) != null) {
                    batch.delete(headersHandle, blockHash);
                    ebbHeaders++;
                }
                batch.delete(ebbBySlot0Handle, slotKey);
                ebbs++;
                iterator.prev();
            }
        }

        return new DeleteCounts(mainSlots, bodies, mainHeaders, ebbs, ebbHeaders);
    }

    @Override
    public ChainTip getTip() {
        try {
            byte[] tipData = db.get(metadataHandle, TIP_KEY);
            if (tipData != null) {
                return deserializeChainTip(tipData);
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to get tip", e);
            return null;
        }
    }

    @Override
    public ChainTip getHeaderTip() {
        try {
            byte[] tipData = db.get(metadataHandle, HEADER_TIP_KEY);
            if (tipData != null) {
                return deserializeChainTip(tipData);
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to get tip", e);
            return null;
        }
    }

    @Override
    public Point findNextBlock(Point currentPoint) {
        // Merged iteration of EBB + main, bounded by header tip
        try (RocksIterator mainIter = db.newIterator(slotToHashHandle);
             RocksIterator ebbIter = db.newIterator(ebbBySlot0Handle)) {
            ChainTip headerTip = getHeaderTip();
            if (headerTip == null) return null;
            long tipSlot = headerTip.getSlot();

            long slotC = currentPoint.getSlot();
            String hashC = currentPoint.getHash();

            if (slotC == 0 && hashC == null) {
                mainIter.seekToFirst();
                ebbIter.seekToFirst();
            } else {
                mainIter.seek(longToBytes(slotC));
                while (mainIter.isValid()) {
                    long s = bytesToLong(mainIter.key());
                    if (s < slotC) {
                        mainIter.next();
                        continue;
                    }
                    if (s == slotC && hashC != null && HexUtil.encodeHexString(mainIter.value()).equals(hashC)) {
                        mainIter.next();
                        continue;
                    }
                    break;
                }
                ebbIter.seek(longToBytes(slotC));
                while (ebbIter.isValid()) {
                    long s = bytesToLong(ebbIter.key());
                    if (s < slotC) {
                        ebbIter.next();
                        continue;
                    }
                    if (s == slotC && hashC != null && HexUtil.encodeHexString(ebbIter.value()).equals(hashC)) {
                        ebbIter.next();
                        continue;
                    }
                    break;
                }

                // Deterministic ordering at equal slot: if current point is the main block at this slot,
                // we must skip the EBB at the same slot so that we don't emit it again on the next call.
                // This prevents flipping between EBB and main at slotC.
                if (hashC != null) {
                    try {
                        byte[] mainAtSlot = db.get(slotToHashHandle, longToBytes(slotC));
                        if (mainAtSlot != null && hashC.equals(HexUtil.encodeHexString(mainAtSlot))) {
                            // Current is main(s). Advance ebb iterator past slotC so next result is strictly after slotC.
                            while (ebbIter.isValid() && bytesToLong(ebbIter.key()) == slotC) {
                                ebbIter.next();
                            }
                        }
                    } catch (Exception ignore) {
                        // Non-fatal; fall back to existing iterator positions
                    }
                }
            }

            long mSlot = mainIter.isValid() ? bytesToLong(mainIter.key()) : Long.MAX_VALUE;
            long eSlot = ebbIter.isValid() ? bytesToLong(ebbIter.key()) : Long.MAX_VALUE;
            long nextSlot = Math.min(mSlot, eSlot);
            if (nextSlot == Long.MAX_VALUE || nextSlot > tipSlot) return null;

            if (eSlot < mSlot) {
                return new Point(eSlot, HexUtil.encodeHexString(ebbIter.value()));
            } else if (mSlot < eSlot) {
                return new Point(mSlot, HexUtil.encodeHexString(mainIter.value()));
            } else { // equal slot: EBB first
                return new Point(eSlot, HexUtil.encodeHexString(ebbIter.value()));
            }
        } catch (Exception e) {
            log.error("Failed to find next block after slot {}", currentPoint.getSlot(), e);
            return null;
        }
    }

    @Override
    public Point findNextBlockHeader(Point currentPoint) {
        // Use the same merged iteration as findNextBlock
        return findNextBlock(currentPoint);
    }

    @Override
    public List<Point> findBlocksInRange(Point from, Point to) {
        List<Point> out = new ArrayList<>();
        long fromSlot = from.getSlot();
        long toSlot = to.getSlot();
        try (RocksIterator mainIter = db.newIterator(slotToHashHandle);
             RocksIterator ebbIter = db.newIterator(ebbBySlot0Handle)) {
            mainIter.seek(longToBytes(fromSlot));
            ebbIter.seek(longToBytes(fromSlot));

            // If the starting point is the main block at fromSlot, skip EBB at the same slot
            String fromHash = from.getHash();
            if (fromHash != null) {
                try {
                    byte[] mainAtSlot = db.get(slotToHashHandle, longToBytes(fromSlot));
                    if (mainAtSlot != null && fromHash.equals(HexUtil.encodeHexString(mainAtSlot))) {
                        while (ebbIter.isValid() && bytesToLong(ebbIter.key()) == fromSlot) {
                            ebbIter.next();
                        }
                    }
                } catch (Exception ignore) {
                }
            }
            while (true) {
                long mSlot = mainIter.isValid() ? bytesToLong(mainIter.key()) : Long.MAX_VALUE;
                long eSlot = ebbIter.isValid() ? bytesToLong(ebbIter.key()) : Long.MAX_VALUE;
                long nextSlot = Math.min(mSlot, eSlot);
                if (nextSlot == Long.MAX_VALUE || nextSlot > toSlot) break;
                if (eSlot < mSlot) {
                    out.add(new Point(eSlot, HexUtil.encodeHexString(ebbIter.value())));
                    ebbIter.next();
                } else if (mSlot < eSlot) {
                    out.add(new Point(mSlot, HexUtil.encodeHexString(mainIter.value())));
                    mainIter.next();
                } else { // equal slot: EBB first
                    out.add(new Point(eSlot, HexUtil.encodeHexString(ebbIter.value())));
                    ebbIter.next();
                }
            }
            return out;
        } catch (Exception e) {
            log.error("Failed to find blocks in range", e);
            return out;
        }
    }


    @Override
    public Point findLastPointAfterNBlocks(Point from, long batchSize) {
        if (log.isDebugEnabled())
            log.debug("🔍 findLastPointAfterNBlocks called: from={}, batchSize={}", from, batchSize);

        try (RocksIterator mainIter = db.newIterator(slotToHashHandle);
             RocksIterator ebbIter = db.newIterator(ebbBySlot0Handle)) {
            long fromSlot = from.getSlot();
            String fromHash = from.getHash();

            if (fromSlot == 0 && fromHash == null) {
                mainIter.seekToFirst();
                ebbIter.seekToFirst();
            } else {
                mainIter.seek(longToBytes(fromSlot));
                if (mainIter.isValid() && fromHash != null && bytesToLong(mainIter.key()) == fromSlot &&
                        HexUtil.encodeHexString(mainIter.value()).equals(fromHash)) {
                    mainIter.next();
                }
                ebbIter.seek(longToBytes(fromSlot));
                if (ebbIter.isValid() && fromHash != null && bytesToLong(ebbIter.key()) == fromSlot &&
                        HexUtil.encodeHexString(ebbIter.value()).equals(fromHash)) {
                    ebbIter.next();
                }

                // If starting from main(fromSlot), skip EBB at fromSlot to avoid re-emitting it in the merged stream
                if (fromHash != null) {
                    try {
                        byte[] mainAtSlot = db.get(slotToHashHandle, longToBytes(fromSlot));
                        if (mainAtSlot != null && fromHash.equals(HexUtil.encodeHexString(mainAtSlot))) {
                            while (ebbIter.isValid() && bytesToLong(ebbIter.key()) == fromSlot) {
                                ebbIter.next();
                            }
                        }
                    } catch (Exception ignore) {
                    }
                }
            }

            long count = 0;
            Point last = null;
            while (count < batchSize) {
                long mSlot = mainIter.isValid() ? bytesToLong(mainIter.key()) : Long.MAX_VALUE;
                long eSlot = ebbIter.isValid() ? bytesToLong(ebbIter.key()) : Long.MAX_VALUE;
                long nextSlot = Math.min(mSlot, eSlot);
                if (nextSlot == Long.MAX_VALUE) break;
                if (eSlot < mSlot) {
                    last = new Point(eSlot, HexUtil.encodeHexString(ebbIter.value()));
                    ebbIter.next();
                } else if (mSlot < eSlot) {
                    last = new Point(mSlot, HexUtil.encodeHexString(mainIter.value()));
                    mainIter.next();
                } else { // equal: emit EBB then continue; main at same slot will be seen next round
                    last = new Point(eSlot, HexUtil.encodeHexString(ebbIter.value()));
                    ebbIter.next();
                }
                count++;
            }
            if (log.isDebugEnabled()) log.debug("✅ findLastPointAfterNBlocks returning: {}", last);
            return last;
        } catch (Exception e) {
            log.error("Failed to find last point after n blocks", e);
            return null;
        }
    }

    @Override
    public boolean hasPoint(Point point) {
        try {
            // Point.ORIGIN (slot=0, hash=null) is always valid if chain has blocks
            if (point.getSlot() == 0 && point.getHash() == null) {
                return getTip() != null;
            }

            long slot = point.getSlot();
            String hash = point.getHash();
            byte[] mainHash = db.get(slotToHashHandle, longToBytes(slot));
            if (mainHash != null) {
                if (hash == null || HexUtil.encodeHexString(mainHash).equals(hash)) return true;
            }
            byte[] ebbHash = db.get(ebbBySlot0Handle, longToBytes(slot));
            if (ebbHash != null) {
                if (hash == null || HexUtil.encodeHexString(ebbHash).equals(hash)) return true;
            }
            return false;
        } catch (Exception e) {
            log.error("Failed to check point", e);
            return false;
        }
    }

    /**
     * Find the nearest stored block slot at or before the given target slot.
     * Uses seekForPrev on the numberBySlot column family.
     */
    public Long findNearestSlotAtOrBefore(long targetSlot) {
        try (RocksIterator it = db.newIterator(numberBySlotHandle)) {
            it.seekForPrev(longToBytes(targetSlot));
            if (it.isValid()) {
                return bytesToLong(it.key());
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to find nearest slot at or before {}", targetSlot, e);
            return null;
        }
    }

    @Override
    public Point findNearestPointAtOrBefore(long targetSlot) {
        try (RocksIterator it = db.newIterator(numberBySlotHandle)) {
            it.seekForPrev(longToBytes(targetSlot));
            if (!it.isValid()) return null;
            long slot = bytesToLong(it.key());
            byte[] hash = db.get(slotToHashHandle, it.key());
            return hash != null ? new Point(slot, HexUtil.encodeHexString(hash)) : null;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to resolve canonical point at or before " + targetSlot, e);
        }
    }

    public Long getBlockNumberBySlot(Long slot) {
        try {
            byte[] blockNumberBytes = db.get(numberBySlotHandle, longToBytes(slot));
            if (blockNumberBytes != null) {
                return bytesToLong(blockNumberBytes);
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to get block number by slot", e);
            return null;
        }
    }

    @Override
    public Long getSlotByBlockNumber(Long blockNumber) {
        try {
            byte[] slotBytes = db.get(slotByNumberHandle, longToBytes(blockNumber));
            if (slotBytes != null) {
                return bytesToLong(slotBytes);
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to get slot by block number", e);
            return null;
        }
    }

    /**
     * Get the first block in the chain
     */
    public Point getFirstBlock() {
        try (RocksIterator mainIter = db.newIterator(slotToHashHandle);
             RocksIterator ebbIter = db.newIterator(ebbBySlot0Handle)) {
            mainIter.seekToFirst();
            ebbIter.seekToFirst();
            long mSlot = mainIter.isValid() ? bytesToLong(mainIter.key()) : Long.MAX_VALUE;
            long eSlot = ebbIter.isValid() ? bytesToLong(ebbIter.key()) : Long.MAX_VALUE;
            if (mSlot == Long.MAX_VALUE && eSlot == Long.MAX_VALUE) return null;
            if (eSlot < mSlot) return new Point(eSlot, HexUtil.encodeHexString(ebbIter.value()));
            if (mSlot < eSlot) return new Point(mSlot, HexUtil.encodeHexString(mainIter.value()));
            return new Point(eSlot, HexUtil.encodeHexString(ebbIter.value()));
        } catch (Exception e) {
            log.error("Failed to get first block", e);
            return null;
        }
    }

    /**
     * Recover from corrupted chain state by finding the last valid continuous point
     * and removing all data after that point.
     * <p>
     * This method:
     * 1. Computes last continuous header/body block numbers up to their tips
     * 2. Removes all data after the recovery point
     * 3. Updates tips to the recovered position
     */
    public void recoverFromCorruption() {
        log.warn("🔧 Starting chain state recovery from corruption...");

        try {
            ChainTip currentHeaderTip = getHeaderTip();
            ChainTip currentBodyTip = getTip();

            if (currentHeaderTip == null && currentBodyTip == null) {
                log.info("✅ Chain state is empty, no recovery needed");
                return;
            }

            // Find the last continuous header sequence
            Long lastValidHeaderBlock = findLastContinuousHeaderBlock();
            Long lastValidBodyBlock = findLastContinuousBodyBlock();

            log.info("🔍 Recovery analysis: Last valid header block: {}, Last valid body block: {}",
                    lastValidHeaderBlock, lastValidBodyBlock);

            // Determine recovery point - use the lower of the two
            Long recoveryBlockNumber = null;
            if (lastValidHeaderBlock != null && lastValidBodyBlock != null) {
                recoveryBlockNumber = Math.min(lastValidHeaderBlock, lastValidBodyBlock);
            } else if (lastValidHeaderBlock != null) {
                recoveryBlockNumber = lastValidHeaderBlock;
            } else if (lastValidBodyBlock != null) {
                recoveryBlockNumber = lastValidBodyBlock;
            }

            if (recoveryBlockNumber == null || recoveryBlockNumber <= 0) {
                log.error("❌ Cannot find any valid continuous data. Manual intervention required.");
                return;
            }

            // Get the slot for the recovery point
            byte[] recoverySlotBytes = db.get(slotByNumberHandle, longToBytes(recoveryBlockNumber));
            if (recoverySlotBytes == null) {
                log.error("❌ Cannot find slot for recovery block {}. Manual intervention required.", recoveryBlockNumber);
                return;
            }

            long recoverySlot = bytesToLong(recoverySlotBytes);

            log.warn("🔧 RECOVERY: Rolling back to block #{} at slot {} to restore continuity",
                    recoveryBlockNumber, recoverySlot);

            // Use the existing rollback mechanism to clean up everything after the recovery point
            rollbackTo(recoverySlot);

            log.info("✅ Chain state recovery completed successfully at block #{}, slot {}",
                    recoveryBlockNumber, recoverySlot);

        } catch (Exception e) {
            log.error("❌ Chain state recovery failed", e);
            throw new RuntimeException("Recovery from corruption failed", e);
        }
    }

    /**
     * Quick corruption detection - checks for gaps near current tips
     * More efficient than full scan, suitable for startup checks
     */
    public boolean detectCorruption() {
        try {
            ChainTip headerTip = getHeaderTip();
            ChainTip bodyTip = getTip();

            if (headerTip == null && bodyTip == null) return false;

            // Sanity check: body tip's body must exist
            if (bodyTip != null) {
                byte[] tipHash = db.get(slotToHashHandle, longToBytes(bodyTip.getSlot()));
                if (tipHash == null) return true;
                byte[] tipBody = db.get(blocksHandle, tipHash);
                if (tipBody == null) return true;
            }

            if (headerTip != null) {
                byte[] headerTipHash = db.get(slotToHashHandle, longToBytes(headerTip.getSlot()));
                if (headerTipHash == null || !Arrays.equals(headerTipHash, headerTip.getBlockHash())) {
                    return true;
                }
                byte[] tipHeader = db.get(headersHandle, headerTip.getBlockHash());
                if (tipHeader == null) {
                    return true;
                }
                if (headerTip.getBlockNumber() > 1 && getBlockHeaderByNumber(headerTip.getBlockNumber() - 1) == null) {
                    return true;
                }
            }

            long maxSlot = 0;
            if (headerTip != null) maxSlot = Math.max(maxSlot, headerTip.getSlot());
            if (bodyTip != null) maxSlot = Math.max(maxSlot, bodyTip.getSlot());

            long startSlot = Math.max(0, maxSlot - 1000);

            try (RocksIterator it = db.newIterator(slotToHashHandle)) {
                it.seek(longToBytes(startSlot));
                while (it.isValid()) {
                    long slot = bytesToLong(it.key());
                    if (slot > maxSlot) break;
                    byte[] hash = it.value();
                    if (hash == null) return true;
                    if (bodyTip != null && slot <= bodyTip.getSlot()) {
                        byte[] body = db.get(blocksHandle, hash);
                        if (body == null) return true;
                    }
                    it.next();
                }
            }

            return false;

        } catch (Exception e) {
            log.warn("Error during corruption detection", e);
            return false; // Assume not corrupted if we can't check
        }
    }

    /**
     * Find the last block where header and body have matching hashes
     */
    private long findLastAlignedBlock(long maxBlockNumber) throws RocksDBException {
        log.info("🔍 Searching for last aligned block where header and body hashes match (slot-based)...");

        // Determine starting slot from block number if possible
        long startSlot = 0;
        byte[] slotBytes = db.get(slotByNumberHandle, longToBytes(maxBlockNumber));
        if (slotBytes != null) startSlot = bytesToLong(slotBytes);

        try (RocksIterator it = db.newIterator(slotToHashHandle)) {
            if (startSlot > 0) {
                it.seekForPrev(longToBytes(startSlot));
            } else {
                it.seekToLast();
            }
            while (it.isValid()) {
                long slot = bytesToLong(it.key());
                byte[] hash = it.value();
                if (hash != null) {
                    byte[] header = db.get(headersHandle, hash);
                    byte[] body = db.get(blocksHandle, hash);
                    if (header != null && body != null) {
                        Long number = getBlockNumberBySlot(slot);
                        long bn = number != null ? number : 0L;
                        log.info("✅ Found aligned block at slot {} (number {}): header and body present", slot, bn);
                        return bn;
                    }
                }
                it.prev();
            }
        }

        log.warn("Could not find aligned block by slot");
        return 0;
    }

    /**
     * Find the last block number where headers form a continuous sequence.
     * Scans backward from the current header tip until a valid header with consistent indices is found.
     */
    private Long findLastContinuousHeaderBlock() throws RocksDBException {
        log.info("🔍 Scanning backward for last continuous header from header tip...");

        ChainTip headerTip = getHeaderTip();
        if (headerTip == null) {
            log.info("No header tip present; cannot determine header continuity");
            return null;
        }

        long headerBlock = headerTip.getBlockNumber();
        if (headerBlock <= 0) {
            return headerBlock;
        }

        long lowerBound = Math.max(1L, headerBlock - RECOVERY_HEADER_SCAN_LIMIT + 1);
        for (long blockNumber = headerBlock; blockNumber >= lowerBound; blockNumber--) {
            byte[] header = getBlockHeaderByNumber(blockNumber);
            if (header == null) {
                long candidate = blockNumber - 1;
                while (candidate >= lowerBound && getBlockHeaderByNumber(candidate) == null) {
                    candidate--;
                }
                if (candidate >= lowerBound) {
                    log.info("📄 Header continuity gap found at block #{}; last available header is #{}",
                            blockNumber, candidate);
                    return candidate;
                }
                log.warn("📄 Could not find any valid header block before gap at #{} within scan limit {}",
                        blockNumber, RECOVERY_HEADER_SCAN_LIMIT);
                return null;
            }
        }

        log.info("📄 Headers are continuous through the last {} blocks at tip #{}",
                Math.min(RECOVERY_HEADER_SCAN_LIMIT, headerBlock), headerBlock);
        return headerBlock;
    }

    /**
     * Find the last block number where bodies form a continuous sequence.
     * Scans backward from the current body tip until a valid body with consistent indices is found.
     */
    private Long findLastContinuousBodyBlock() throws RocksDBException {
        log.info("🔍 Scanning backward for last continuous body from body tip...");

        ChainTip bodyTip = getTip();
        if (bodyTip == null) {
            log.info("No body tip present; cannot determine body continuity");
            return null;
        }

        try (RocksIterator it = db.newIterator(slotToHashHandle)) {
            it.seekForPrev(longToBytes(bodyTip.getSlot()));
            while (it.isValid()) {
                long slot = bytesToLong(it.key());
                byte[] hash = it.value();
                byte[] body = db.get(blocksHandle, hash);
                if (body != null) {
                    Long number = getBlockNumberBySlot(slot);
                    log.info("🧱 Last continuous body determined at slot {} (number {})", slot, number);
                    return number != null ? number : 0L;
                }
                it.prev();
            }
        }

        log.warn("🧱 Could not find any valid continuous body block");
        return null;
    }

    /**
     * Create an atomic snapshot of the database using RocksDB's Checkpoint API.
     * The snapshot uses hard links, making it fast and space-efficient.
     *
     * @param snapshotPath directory to create the checkpoint in (must not exist)
     */
    public void createSnapshot(String snapshotPath) {
        try (Checkpoint checkpoint = Checkpoint.create(db)) {
            checkpoint.createCheckpoint(snapshotPath);
            log.info("RocksDB snapshot created at: {}", snapshotPath);
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to create RocksDB snapshot at " + snapshotPath, e);
        }
    }

    /**
     * Restore from a snapshot: close current DB, delete DB dir, copy snapshot, reopen.
     * Caller must ensure no concurrent reads/writes during restore.
     *
     * @param snapshotPath directory containing the checkpoint to restore from
     */
    public void restoreFromSnapshot(String snapshotPath) {
        Path snapshotDir = Path.of(snapshotPath);
        if (!Files.isDirectory(snapshotDir)) {
            throw new IllegalArgumentException("Snapshot directory does not exist: " + snapshotPath);
        }

        log.info("Restoring RocksDB from snapshot: {} -> {}", snapshotPath, dbPath);

        // 1. Close current DB
        close();

        // 2. Delete current DB directory
        Path dbDir = Path.of(dbPath);
        try {
            if (Files.exists(dbDir)) {
                deleteRecursively(dbDir);
                log.info("Deleted existing DB directory: {}", dbPath);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete DB directory: " + dbPath, e);
        }

        // 3. Copy snapshot to DB path
        try {
            copyRecursively(snapshotDir, dbDir);
            log.info("Copied snapshot to DB directory: {}", dbPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to copy snapshot to DB path", e);
        }

        // 4. Reopen DB
        openDb();
        log.info("RocksDB restored and reopened from snapshot: {}", snapshotPath);
    }

    public String getDbPath() {
        return dbPath;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void copyRecursively(Path src, Path dest) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dest.resolve(src.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, dest.resolve(src.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // --- Era start slot tracking ---

    // --- NonceStateStore implementation ---

    @Override
    public void storeEpochNonceState(byte[] serialized) {
        try {
            db.put(metadataHandle, EPOCH_NONCE_STATE_KEY, serialized);
        } catch (RocksDBException e) {
            log.error("Failed to store epoch nonce state", e);
        }
    }

    @Override
    public byte[] getEpochNonceState() {
        try {
            return db.get(metadataHandle, EPOCH_NONCE_STATE_KEY);
        } catch (RocksDBException e) {
            log.error("Failed to read epoch nonce state", e);
            return null;
        }
    }

    @Override
    public void storeEpochNonce(int epoch, byte[] nonce) {
        if (nonce == null) return;
        try {
            db.put(metadataHandle, epochNonceKey(epoch), nonce);
        } catch (RocksDBException e) {
            log.error("Failed to store epoch nonce for epoch {}", epoch, e);
        }
    }

    @Override
    public byte[] getEpochNonce(int epoch) {
        try {
            return db.get(metadataHandle, epochNonceKey(epoch));
        } catch (RocksDBException e) {
            log.error("Failed to read epoch nonce for epoch {}", epoch, e);
            return null;
        }
    }

    @Override
    public void pruneEpochNoncesAfter(int epoch) {
        try (RocksIterator iterator = db.newIterator(metadataHandle)) {
            for (iterator.seek(EPOCH_NONCE_KEY_PREFIX); iterator.isValid(); iterator.next()) {
                byte[] key = iterator.key();
                if (!startsWith(key, EPOCH_NONCE_KEY_PREFIX)) break;
                int storedEpoch = ByteBuffer.wrap(key, EPOCH_NONCE_KEY_PREFIX.length, Integer.BYTES).getInt();
                if (storedEpoch > epoch) {
                    db.delete(metadataHandle, key);
                }
            }
        } catch (RocksDBException e) {
            log.error("Failed to prune epoch nonces after epoch {}", epoch, e);
        }
    }

    @Override
    public void storeEpochNonceCheckpoint(int epoch, NonceStateSnapshot snapshot) {
        if (snapshot == null) return;
        try {
            db.put(metadataHandle, epochNonceCheckpointKey(epoch), snapshot.serialize());
        } catch (RocksDBException e) {
            log.error("Failed to store epoch nonce checkpoint for epoch {}", epoch, e);
        }
    }

    @Override
    public List<NonceStateSnapshot> getEpochNonceCheckpointsAtOrBeforeSlot(long slot) {
        List<NonceStateSnapshot> checkpoints = new ArrayList<>();
        try (RocksIterator iterator = db.newIterator(metadataHandle)) {
            for (iterator.seek(EPOCH_NONCE_CHECKPOINT_KEY_PREFIX); iterator.isValid(); iterator.next()) {
                byte[] key = iterator.key();
                if (!startsWith(key, EPOCH_NONCE_CHECKPOINT_KEY_PREFIX)) break;
                try {
                    NonceStateSnapshot snapshot = NonceStateSnapshot.deserialize(iterator.value());
                    if (snapshot.slot() <= slot) {
                        checkpoints.add(snapshot);
                    }
                } catch (Exception e) {
                    log.warn("Ignoring malformed epoch nonce checkpoint: {}", e.toString());
                }
            }
        }
        checkpoints.sort(java.util.Comparator
                .comparingLong(NonceStateSnapshot::slot)
                .thenComparingLong(NonceStateSnapshot::blockNumber)
                .reversed());
        return checkpoints;
    }

    @Override
    public void pruneEpochNonceCheckpointsAfter(int epoch) {
        try (RocksIterator iterator = db.newIterator(metadataHandle)) {
            for (iterator.seek(EPOCH_NONCE_CHECKPOINT_KEY_PREFIX); iterator.isValid(); iterator.next()) {
                byte[] key = iterator.key();
                if (!startsWith(key, EPOCH_NONCE_CHECKPOINT_KEY_PREFIX)) break;
                int storedEpoch = ByteBuffer.wrap(key, EPOCH_NONCE_CHECKPOINT_KEY_PREFIX.length, Integer.BYTES).getInt();
                if (storedEpoch > epoch) {
                    db.delete(metadataHandle, key);
                }
            }
        } catch (RocksDBException e) {
            log.error("Failed to prune epoch nonce checkpoints after epoch {}", epoch, e);
        }
    }

    private static byte[] epochNonceKey(int epoch) {
        byte[] key = Arrays.copyOf(EPOCH_NONCE_KEY_PREFIX, EPOCH_NONCE_KEY_PREFIX.length + Integer.BYTES);
        ByteBuffer.wrap(key, EPOCH_NONCE_KEY_PREFIX.length, Integer.BYTES).putInt(epoch);
        return key;
    }

    private static byte[] epochNonceCheckpointKey(int epoch) {
        byte[] key = Arrays.copyOf(EPOCH_NONCE_CHECKPOINT_KEY_PREFIX,
                EPOCH_NONCE_CHECKPOINT_KEY_PREFIX.length + Integer.BYTES);
        ByteBuffer.wrap(key, EPOCH_NONCE_CHECKPOINT_KEY_PREFIX.length, Integer.BYTES).putInt(epoch);
        return key;
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value == null || value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (value[i] != prefix[i]) return false;
        }
        return true;
    }

    private static final String ERA_START_SLOT_PREFIX = "era_";
    private static final String ERA_START_SLOT_SUFFIX = "_start_slot";

    /**
     * Store the start slot for a given era value in the metadata column family.
     * Idempotent: if already stored, this is a no-op.
     *
     * @param eraValue the era ordinal (e.g. 2 for Shelley, 7 for Conway)
     * @param slot     the first slot of this era
     */
    public void setEraStartSlot(int eraValue, long slot) {
        byte[] key = (ERA_START_SLOT_PREFIX + eraValue + ERA_START_SLOT_SUFFIX).getBytes(StandardCharsets.UTF_8);
        try {
            byte[] existing = db.get(metadataHandle, key);
            if (existing != null) {
                return; // already stored
            }
            db.put(metadataHandle, key, longToBytes(slot));
            log.info("Stored era {} start slot: {}", eraValue, slot);
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to store era " + eraValue + " start slot", e);
        }
    }

    /**
     * Get the start slot for a given era value.
     *
     * @param eraValue the era ordinal
     * @return the start slot, or empty if not stored
     */
    public OptionalLong getEraStartSlot(int eraValue) {
        byte[] key = (ERA_START_SLOT_PREFIX + eraValue + ERA_START_SLOT_SUFFIX).getBytes(StandardCharsets.UTF_8);
        try {
            byte[] val = db.get(metadataHandle, key);
            if (val != null) {
                if (val.length != Long.BYTES) {
                    throw new IllegalStateException("Malformed era " + eraValue
                            + " start slot metadata length: " + val.length);
                }
                return OptionalLong.of(bytesToLong(val));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to read era " + eraValue + " start slot", e);
        }
        return OptionalLong.empty();
    }

    /**
     * Scan for the smallest era start slot where era > 1 (i.e., first non-Byron era).
     *
     * @return the first non-Byron era start slot, or empty if none stored
     */
    public OptionalLong getFirstNonByronEraStartSlot() {
        long minSlot = Long.MAX_VALUE;
        boolean found = false;
        // Eras 2 (Shelley) through 7 (Conway)
        for (int era = Era.Shelley.value;
             era <= Era.Conway.value; era++) {
            var opt = getEraStartSlot(era);
            if (opt.isPresent() && opt.getAsLong() < minSlot) {
                minSlot = opt.getAsLong();
                found = true;
            }
        }
        return found ? OptionalLong.of(minSlot) : OptionalLong.empty();
    }

    // --- Shelley-start UTXO total (for custom networks with Byron history) ---

    private static final String META_SHELLEY_START_UTXO_TOTAL = "shelley_start_utxo_total";
    private static final String META_BYRON_GENESIS_UTXO_KEYS = "byron_genesis_utxo_keys";
    private static final String META_ALLEGRA_BOOTSTRAP_DONE = "allegra_bootstrap_done";

    /**
     * Persist the total UTXO lovelace at the Shelley-start boundary.
     * Captured once when sync crosses the first non-Byron slot.
     */
    public void setShelleyStartUtxoTotal(BigInteger total) {
        byte[] key = META_SHELLEY_START_UTXO_TOTAL.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] existing = db.get(metadataHandle, key);
            if (existing != null) return; // already stored
            db.put(metadataHandle, key, total.toString().getBytes(StandardCharsets.UTF_8));
            log.info("Persisted Shelley-start UTXO total: {}", total);
        } catch (Exception e) {
            log.error("Failed to persist Shelley-start UTXO total: {}", e.getMessage());
        }
    }

    /**
     * Read the persisted Shelley-start UTXO total, or empty if not stored.
     */
    public Optional<BigInteger> getShelleyStartUtxoTotal() {
        byte[] key = META_SHELLEY_START_UTXO_TOTAL.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] val = db.get(metadataHandle, key);
            if (val != null) {
                return Optional.of(new BigInteger(new String(val, StandardCharsets.UTF_8)));
            }
        } catch (Exception e) {
            log.error("Failed to read Shelley-start UTXO total: {}", e.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public byte[] getShelleyStartUtxoTotalKey() {
        return META_SHELLEY_START_UTXO_TOTAL.getBytes(StandardCharsets.UTF_8);
    }

    // --- Byron genesis UTXO keys (for Allegra bootstrap removal) ---

    /**
     * Persist Byron genesis UTXO outpoint keys.
     * Each key is 34 bytes (32 txHash + 2 outputIndex). Stored as concatenated byte array.
     */
    public void setByronGenesisUtxoKeys(java.util.List<byte[]> outpointKeys) {
        byte[] key = META_BYRON_GENESIS_UTXO_KEYS.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] existing = db.get(metadataHandle, key);
            if (existing != null) return; // already stored
            // Concatenate all 34-byte keys
            int totalSize = outpointKeys.stream().mapToInt(k -> k.length).sum();
            byte[] value = new byte[totalSize];
            int offset = 0;
            for (byte[] k : outpointKeys) {
                System.arraycopy(k, 0, value, offset, k.length);
                offset += k.length;
            }
            db.put(metadataHandle, key, value);
            log.info("Persisted {} Byron genesis UTXO outpoint keys ({} bytes)", outpointKeys.size(), totalSize);
        } catch (Exception e) {
            log.error("Failed to persist Byron genesis UTXO keys: {}", e.getMessage());
        }
    }

    /**
     * Read persisted Byron genesis UTXO outpoint keys.
     * Returns empty list if not stored.
     */
    public java.util.List<byte[]> getByronGenesisUtxoKeys() {
        byte[] key = META_BYRON_GENESIS_UTXO_KEYS.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] value = db.get(metadataHandle, key);
            if (value == null || value.length == 0) return java.util.Collections.emptyList();
            // Each outpoint key is 34 bytes
            int keySize = 34;
            java.util.List<byte[]> keys = new java.util.ArrayList<>(value.length / keySize);
            for (int i = 0; i + keySize <= value.length; i += keySize) {
                byte[] k = new byte[keySize];
                System.arraycopy(value, i, k, 0, keySize);
                keys.add(k);
            }
            return keys;
        } catch (Exception e) {
            log.error("Failed to read Byron genesis UTXO keys: {}", e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    @Override
    public byte[] getByronGenesisUtxoKeysKey() {
        return META_BYRON_GENESIS_UTXO_KEYS.getBytes(StandardCharsets.UTF_8);
    }

    // --- Allegra bootstrap completion marker ---

    /**
     * Check if the Allegra bootstrap UTXO removal has been completed.
     */
    public boolean isAllegraBootstrapDone() {
        byte[] key = META_ALLEGRA_BOOTSTRAP_DONE.getBytes(StandardCharsets.UTF_8);
        try {
            return db.get(metadataHandle, key) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get the metadata CF key bytes for the Allegra completion marker.
     * Used by DefaultUtxoStore to write/clear atomically within a block's WriteBatch.
     */
    public byte[] getAllegraBootstrapDoneKey() {
        return META_ALLEGRA_BOOTSTRAP_DONE.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Get the metadata column family handle for atomic writes from UTXO store.
     */
    public ColumnFamilyHandle getMetadataHandle() {
        return metadataHandle;
    }

    // --- RollbackCapableStore implementation ---

    @Override
    public String storeName() {
        return "chainState";
    }

    @Override
    public long getLatestAppliedSlot() {
        ChainTip tip = getTip();
        return tip != null ? tip.getSlot() : -1;
    }

    @Override
    public RollbackCapableStore.AppliedPoint getLatestAppliedPoint() {
        ChainTip tip = getTip();
        return tip == null
                ? new RollbackCapableStore.AppliedPoint(-1L, null)
                : new RollbackCapableStore.AppliedPoint(
                        tip.getSlot(), HexUtil.encodeHexString(tip.getBlockHash()));
    }

    @Override
    public long getRollbackFloorSlot() {
        return 0; // all blocks retained, no pruning
    }

    @Override
    public void rollbackToSlot(long targetSlot) {
        rollbackTo(targetSlot);
    }

    /**
     * Close the database connection
     */
    public void close() {
        for (ColumnFamilyHandle handle : openedColumnFamilyHandles) {
            try {
                handle.close();
            } catch (Exception e) {
                log.warn("Failed to close RocksDB column-family handle", e);
            }
        }
        openedColumnFamilyHandles = List.of();
        if (db != null) {
            try {
                db.close();
            } catch (Exception e) {
                log.error("Failed to close RocksDB", e);
            } finally {
                db = null;
            }
        }
        closeNativeMemoryBudgets();
    }

    // Helper methods

    private void updateChainState(WriteBatch batch, byte[] blockHash, Long blockNumber, Long slot) throws RocksDBException {
        // Store mappings (slot-first)
        batch.put(numberBySlotHandle, longToBytes(slot), longToBytes(blockNumber));
        batch.put(slotToHashHandle, longToBytes(slot), blockHash);
        // NOTE: slot_by_number will be written only for MAIN blocks (not EBB) by specialized methods
    }

    private void updateTip(WriteBatch batch, byte[] blockHash, Long blockNumber, Long slot) throws RocksDBException {
        // Update tip if this is a newer block OR same slot with higher block number (fork handling)
        ChainTip currentTip = getTip();
        if (currentTip == null || slot > currentTip.getSlot() ||
                (slot.equals(currentTip.getSlot()) && blockNumber > currentTip.getBlockNumber())) {
            ChainTip newTip = new ChainTip(slot, blockHash, blockNumber);
            batch.put(metadataHandle, TIP_KEY, serializeChainTip(newTip));
            log.debug("Updated tip: slot={}, blockNumber={} (fork handling: same-slot={})",
                    slot, blockNumber, currentTip != null && slot.equals(currentTip.getSlot()));
        } else if (currentTip != null && slot.equals(currentTip.getSlot()) && blockNumber.equals(currentTip.getBlockNumber())) {
            // Same slot, same block number but potentially different hash (fork scenario)
            if (!Arrays.equals(blockHash, currentTip.getBlockHash())) {
                log.warn("⚠️ FORK DETECTED: Same slot {} and block #{} but different hash! Current: {}, New: {}",
                        slot, blockNumber,
                        HexUtil.encodeHexString(currentTip.getBlockHash()),
                        HexUtil.encodeHexString(blockHash));
                // In this case, we should update to the new hash as it represents the canonical chain
                ChainTip newTip = new ChainTip(slot, blockHash, blockNumber);
                batch.put(metadataHandle, TIP_KEY, serializeChainTip(newTip));
                log.info("Updated tip to new fork: slot={}, blockNumber={}", slot, blockNumber);
            }
        }
    }

    private byte[] longToBytes(long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }

    private long bytesToLong(byte[] bytes) {
        return ByteBuffer.wrap(bytes).getLong();
    }

    private byte[] serializeChainTip(ChainTip tip) {
        try {
            ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES * 2 + tip.getBlockHash().length);
            buffer.putLong(tip.getSlot());
            buffer.putLong(tip.getBlockNumber());
            buffer.put(tip.getBlockHash());
            return buffer.array();
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize chain tip", e);
        }
    }

    private ChainTip deserializeChainTip(byte[] data) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            long slot = buffer.getLong();
            long blockNumber = buffer.getLong();
            byte[] blockHash = new byte[buffer.remaining()];
            buffer.get(blockHash);
            return new ChainTip(slot, blockHash, blockNumber);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize chain tip", e);
        }
    }

}
