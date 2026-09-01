package com.bloxbean.cardano.yano.runtime.utxo;

import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressType;
import com.bloxbean.cardano.client.api.util.ReferenceScriptUtil;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.model.*;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.ByronBlockSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.ByronEbBlockSerializer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.CanonicalBlockReference;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.api.utxo.PointerAddressId;
import com.bloxbean.cardano.yano.api.utxo.PointerIndexPreparation;
import com.bloxbean.cardano.yano.api.utxo.PointerUtxo;
import com.bloxbean.cardano.yano.api.utxo.PointerUtxoView;
import com.bloxbean.cardano.yano.api.utxo.StakeBalanceConsistencyException;
import com.bloxbean.cardano.yano.api.utxo.StakeBalanceView;
import com.bloxbean.cardano.yano.api.utxo.StakeCredentialBalance;
import com.bloxbean.cardano.yano.api.utxo.StakeCredentialExtractor;
import com.bloxbean.cardano.yano.api.utxo.StakeCredentialId;
import com.bloxbean.cardano.yano.api.utxo.model.AssetAmount;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;
import com.bloxbean.cardano.yano.api.plugin.UtxoFilterContext;
import com.bloxbean.cardano.yano.api.util.StoredBlockUtil;
import com.bloxbean.cardano.yano.api.archive.CanonicalProjectionContributor;
import com.bloxbean.cardano.yano.api.archive.ConsumedOutputAddresses;
import com.bloxbean.cardano.yano.runtime.db.RocksDbSupplier;
import com.bloxbean.cardano.yano.runtime.db.UtxoCfNames;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.ByronMainBlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.RollbackEvent;
import com.bloxbean.cardano.yano.api.rollback.PointRollbackCapableStore;
import com.bloxbean.cardano.yano.runtime.chain.ByronGenesisUtxoMetadataStore;
import org.rocksdb.*;
import org.slf4j.Logger;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default UTXO store backed by RocksDB column families.
 * Listens to BlockAppliedEvent and RollbackEvent, applies compact deltas.
 */
public final class DefaultUtxoStore implements UtxoState, UtxoStoreWriter, Prunable, UtxoStatusProvider, AutoCloseable,
        PointRollbackCapableStore {
    private RocksDB db;
    private final Logger log;
    private final boolean enabled;
    private final RocksDbSupplier supplier;

    private ColumnFamilyHandle cfUnspent;
    private ColumnFamilyHandle cfSpent;
    private ColumnFamilyHandle cfAddr;
    private ColumnFamilyHandle cfDelta;
    private ColumnFamilyHandle cfMeta;
    private ColumnFamilyHandle cfScriptRef;
    private ColumnFamilyHandle cfStakeBalance;
    private ColumnFamilyHandle cfPointer;

    private final int pruneDepth;
    private final int rollbackWindow;
    private final int pruneBatchSize;
    private final boolean indexAddressHash;
    private final boolean indexPaymentCred;
    private final boolean stakeBalanceIndexEnabled;
    private final boolean configuredUtxoFiltersEnabled;
    private volatile boolean stakeBalanceIndexReady;
    private UtxoProcessor processor;
    private ByronUtxoApplier byronUtxoApplier;
    private volatile StorageFilterChain filterChain;
    // Metrics
    private final boolean metricsEnabled;
    private ScheduledExecutorService metricsScheduler;
    private final long rocksSampleMillis;
    private volatile long lastPruneMs = 0L;
    private volatile long lastDeltaDeleted = 0L;
    private volatile long lastSpentDeleted = 0L;
    private final ArrayDeque<Long> applyLatencies = new ArrayDeque<>();
    private final int applyLatencyWindow = 200;
    private volatile int lastApplyCreated = 0;
    private volatile int lastApplySpent = 0;
    private final ArrayDeque<Long> applyTimestamps = new ArrayDeque<>();
    private final ArrayDeque<Long> blockSizes = new ArrayDeque<>();
    private final int blockSizeWindow = 200;
    private volatile long lastBlockSize = 0L;
    private final AtomicReference<java.util.Map<String, Long>> cfEstimates = new AtomicReference<>(java.util.Map.of());

    /** Shared RocksDB context, cached so the projection hook resolves handles without reallocating. */
    private volatile com.bloxbean.cardano.yano.runtime.db.RocksDbContext rocksContext;
    /** ADR-039 projection contributor; NOOP unless history is enabled. */
    private volatile CanonicalProjectionContributor projectionContributor = CanonicalProjectionContributor.NOOP;
    private volatile Runnable shelleyStartBoundaryCapture = () -> { };

    // ADR-039 gate 1 instrument. Cross-run wall-clock A/B legs cannot resolve a 5% effect on a
    // shared host - measured spread was 16-49% - so the projection's cost is attributed
    // *within* a single run instead: time spent inside the contributor against time spent in
    // the whole block apply, both measured on the same thread in the same run. Cross-run
    // variance cancels completely because there is no second run.
    //
    // Thread CPU time would be preferable but is unavailable here: applyBlock runs on a
    // virtual thread, where ThreadMXBean reports no CPU time. Wall time is a good substitute
    // for this particular ratio because the contributor performs no I/O - it stages into an
    // in-memory WriteBatch - so its wall time is essentially its CPU time, while the
    // denominator legitimately includes the RocksDB write that projection also inflates.
    //
    // This measures the *apply-stage* share. RocksDB write amplification from the extra column
    // families is captured separately as a disk measurement (~1.4x logical).
    private final java.util.concurrent.atomic.LongAdder applyNanos = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder projectionNanos = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder attributedBlocks = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder continuityWarnings = new java.util.concurrent.atomic.LongAdder();
    /**
     * Last committed delta used by the diagnostic continuity guard.
     *
     * <p>Apply is serialized, so reopening an iterator and seeking the delta tail for every
     * block only repeats information this store just committed. The cache is loaded once on
     * startup and explicitly refreshed by rollback, rebuild and snapshot reinitialization.
     */
    private boolean continuityCacheInitialized;
    private UtxoDeltaCodec.Decoded continuityPrevious;
    private final java.util.concurrent.atomic.LongAdder shelleyUnresolvedInputs = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder shelleyFilteredStoreUnresolvedInputs = new java.util.concurrent.atomic.LongAdder();
    private final AtomicLong lastShelleyUnresolvedWarningMillis = new AtomicLong();
    // Full per-block cycle: apply plus the gap until the next block reaches apply. Recording it
    // here means the projection's share of *sync* - not just of apply - is reported by the node
    // rather than derived by hand from a throughput figure taken over a different window.
    private final java.util.concurrent.atomic.LongAdder cycleNanos = new java.util.concurrent.atomic.LongAdder();
    private long previousApplyStartNanos;

    public DefaultUtxoStore(RocksDbSupplier supplier, Logger logger, java.util.Map<String, Object> config) {
        this.supplier = supplier;
        this.rocksContext = supplier.rocks();
        this.db = supplier.rocks().db();
        this.log = logger;
        Object ev = config != null ? config.getOrDefault(YanoPropertyKeys.Utxo.ENABLED, Boolean.TRUE) : Boolean.TRUE;
        this.enabled = (ev instanceof Boolean b) ? b : Boolean.parseBoolean(String.valueOf(ev));

        this.cfUnspent = supplier.rocks().handle(UtxoCfNames.UTXO_UNSPENT);
        this.cfSpent = supplier.rocks().handle(UtxoCfNames.UTXO_SPENT);
        this.cfAddr = supplier.rocks().handle(UtxoCfNames.UTXO_ADDR);
        this.cfDelta = supplier.rocks().handle(UtxoCfNames.UTXO_BLOCK_DELTA);
        this.cfMeta = supplier.rocks().handle(UtxoCfNames.UTXO_META);
        this.cfScriptRef = supplier.rocks().handle(UtxoCfNames.SCRIPT_REF);
        this.cfStakeBalance = supplier.rocks().handle(UtxoCfNames.UTXO_STAKE_BALANCE);
        this.cfPointer = supplier.rocks().handle(UtxoCfNames.UTXO_POINTER);

        this.pruneDepth = getInt(config, YanoPropertyKeys.Utxo.PRUNE_DEPTH, 2160);
        // Default 2 epochs (864000 slots) to support incremental balance aggregation at epoch boundaries.
        // The delta log must retain at least one full epoch's worth of entries.
        this.rollbackWindow = getInt(config, YanoPropertyKeys.Utxo.ROLLBACK_WINDOW, 864000);
        this.pruneBatchSize = getInt(config, YanoPropertyKeys.Utxo.PRUNE_BATCH_SIZE, 500);
        // Indexing strategy
        boolean addrIdx = getBool(config, YanoPropertyKeys.Utxo.INDEX_ADDRESS_HASH, true);
        boolean payCredIdx = getBool(config, YanoPropertyKeys.Utxo.INDEX_PAYMENT_CREDENTIAL, true);
        Object strat = config != null ? config.get(YanoPropertyKeys.Utxo.INDEXING_STRATEGY) : null;
        if (strat != null) {
            String s = String.valueOf(strat);
            if ("address_hash".equalsIgnoreCase(s)) {
                addrIdx = true;
                payCredIdx = false;
            } else if ("payment_credential".equalsIgnoreCase(s)) {
                addrIdx = false;
                payCredIdx = true;
            }
        }
        this.indexAddressHash = addrIdx;
        this.indexPaymentCred = payCredIdx;
        this.stakeBalanceIndexEnabled = getBool(
                config, YanoPropertyKeys.AccountState.STAKE_BALANCE_INDEX_ENABLED, true);
        this.configuredUtxoFiltersEnabled = getBool(config, YanoPropertyKeys.UtxoFilter.ENABLED, false);

        this.processor = new DefaultUtxoProcessor(this.db);
        this.byronUtxoApplier = createByronUtxoApplier();
        refreshStakeBalanceIndexReady();

        // Metrics setup
        this.metricsEnabled = getBool(config, YanoPropertyKeys.Metrics.ENABLED, true);
        int sampleSec = getInt(config, YanoPropertyKeys.Metrics.ROCKSDB_SAMPLE_SECONDS, 0);
        this.rocksSampleMillis = Math.max(0, sampleSec) * 1000L;
        startMetricsSampler();

        log.info("DefaultUtxoStore initialized (enabled={})", enabled);
    }

    /**
     * Install the ADR-039 projection contributor. Called once during composition when
     * history is enabled; otherwise the store keeps the NOOP contributor.
     */
    public void setProjectionContributor(CanonicalProjectionContributor contributor) {
        this.projectionContributor = contributor == null ? CanonicalProjectionContributor.NOOP : contributor;
    }

    CanonicalProjectionContributor swapProjectionContributor(
            CanonicalProjectionContributor contributor) {
        CanonicalProjectionContributor previous = projectionContributor;
        setProjectionContributor(contributor);
        return previous;
    }

    CanonicalProjectionContributor projectionContributorForTest() {
        return projectionContributor;
    }

    public void setShelleyStartBoundaryCapture(Runnable capture) {
        this.shelleyStartBoundaryCapture = capture != null ? capture : () -> { };
    }

    /**
     * Reinitialize DB and CF handles from the supplier after a snapshot restore.
     * The supplier's underlying RocksDB has been closed and reopened, so all
     * cached handles are stale.
     */
    public synchronized void reinitialize() {
        var ctx = supplier.rocks();
        this.rocksContext = ctx;
        this.db = ctx.db();
        this.cfUnspent = ctx.handle(UtxoCfNames.UTXO_UNSPENT);
        this.cfSpent = ctx.handle(UtxoCfNames.UTXO_SPENT);
        this.cfAddr = ctx.handle(UtxoCfNames.UTXO_ADDR);
        this.cfDelta = ctx.handle(UtxoCfNames.UTXO_BLOCK_DELTA);
        this.cfMeta = ctx.handle(UtxoCfNames.UTXO_META);
        this.cfScriptRef = ctx.handle(UtxoCfNames.SCRIPT_REF);
        this.cfStakeBalance = ctx.handle(UtxoCfNames.UTXO_STAKE_BALANCE);
        this.cfPointer = ctx.handle(UtxoCfNames.UTXO_POINTER);
        if (this.metadataHandle != null) {
            // Chain metadata CF is passed in for Allegra bootstrap marker writes.
            this.metadataHandle = ctx.handle("metadata");
        }
        this.processor = new DefaultUtxoProcessor(this.db);
        this.byronUtxoApplier = createByronUtxoApplier();
        invalidateContinuityCache();
        this.projectionContributor.reinitializeAfterSnapshotRestore();
        refreshStakeBalanceIndexReady();
        log.info("DefaultUtxoStore reinitialized after snapshot restore");
    }

    /**
     * Set the storage filter chain for filtering UTXO outputs before persistence.
     * Must be called before block application starts.
     * A null value explicitly clears the previously installed chain.
     */
    public void setFilterChain(StorageFilterChain filterChain) {
        this.filterChain = filterChain;
        if (filterChain != null && !filterChain.isEmpty()) {
            clearStakeBalanceIndexReadyNow("UTXO storage filter chain is active");
        }
    }

    int activeStorageFilterCount() {
        StorageFilterChain current = filterChain;
        return current != null ? current.size() : 0;
    }

    private ByronUtxoApplier createByronUtxoApplier() {
        return new ByronUtxoApplier(db, cfUnspent, cfSpent, cfAddr,
                indexAddressHash, () -> filterChain, log);
    }

    long getByronUnresolvedInputCount() {
        return byronUtxoApplier.unresolvedInputCount();
    }

    long getByronFilteredStoreUnresolvedInputCount() {
        return byronUtxoApplier.filteredStoreUnresolvedInputCount();
    }

    @Override
    public List<Utxo> getUtxosByAddress(String bech32OrHexAddress, int page, int pageSize) {
        if (!enabled) return List.of();
        try {
            if (page < 1 || pageSize <= 0) return List.of();
            byte[] addrKey = UtxoKeyUtil.addrHash28(bech32OrHexAddress);
            try (RocksIterator it = db.newIterator(cfAddr)) {
                it.seek(addrKey);
                int skipped = (page - 1) * pageSize;
                List<Utxo> results = new ArrayList<>();
                while (it.isValid()) {
                    byte[] key = it.key();
                    if (!UtxoKeyUtil.prefixMatches(key, addrKey, 28)) break;
                    if (skipped > 0) {
                        skipped--;
                        it.next();
                        continue;
                    }
                    if (results.size() >= pageSize) break;
                    // suffix: 28 addr | 8 slot | 32 hash | 2 idx
                    int off = 28 + 8;
                    String txHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, off, off + 32));
                    int idx = ByteBuffer.wrap(key, off + 32, 2).order(ByteOrder.BIG_ENDIAN).getShort() & 0xffff;
                    byte[] ukey = UtxoKeyUtil.outpointKey(txHash, idx);
                    byte[] val = db.get(cfUnspent, ukey);
                    if (val != null) {
                        results.add(decodeStoredToUtxo(val, new Outpoint(txHash, idx)));
                    }
                    it.next();
                }
                return results;
            }
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @Override
    public List<Utxo> getUtxosByPaymentCredential(String credentialHexOrAddress, int page, int pageSize) {
        if (!enabled) return List.of();
        try {
            if (page < 1 || pageSize <= 0) return List.of();
            // Derive 28-byte prefix from credential hex or address
            byte[] prefix = UtxoKeyUtil.hex28(credentialHexOrAddress);
            if (prefix == null) {
                // Try to extract from address
                prefix = UtxoKeyUtil.paymentCred28(credentialHexOrAddress);
            }
            if (prefix == null) return List.of();

            try (RocksIterator it = db.newIterator(cfAddr)) {
                it.seek(prefix);
                int skipped = (page - 1) * pageSize;
                List<Utxo> results = new ArrayList<>();
                while (it.isValid()) {
                    byte[] key = it.key();
                    if (!UtxoKeyUtil.prefixMatches(key, prefix, 28)) break;
                    if (skipped > 0) {
                        skipped--;
                        it.next();
                        continue;
                    }
                    if (results.size() >= pageSize) break;
                    int off = 28 + 8;
                    String txHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, off, off + 32));
                    int idx = ByteBuffer.wrap(key, off + 32, 2).order(ByteOrder.BIG_ENDIAN).getShort() & 0xffff;
                    byte[] ukey = UtxoKeyUtil.outpointKey(txHash, idx);
                    byte[] val = db.get(cfUnspent, ukey);
                    if (val != null) {
                        results.add(decodeStoredToUtxo(val, new Outpoint(txHash, idx)));
                    }
                    it.next();
                }
                return results;
            }
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @Override
    public Optional<Utxo> getUtxo(Outpoint outpoint) {
        if (!enabled) return Optional.empty();
        try {
            byte[] key = UtxoKeyUtil.outpointKey(outpoint.txHash(), outpoint.index());
            byte[] val = db.get(cfUnspent, key);
            if (val == null) return Optional.empty();
            return Optional.of(decodeStoredToUtxo(val, outpoint));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public List<Utxo> getOutputsByTxHash(String txHash) {
        if (!enabled || txHash == null) return List.of();
        try {
            byte[] hashBytes = HexUtil.decodeHexString(txHash);
            if (hashBytes.length != 32) return List.of();
            List<Utxo> results = new ArrayList<>();
            // Scan cfUnspent with txHash prefix
            scanCfForTxHash(cfUnspent, hashBytes, txHash, results, false);
            // Scan cfSpent with txHash prefix (unwrap original UTXO from key 6)
            scanCfForTxHash(cfSpent, hashBytes, txHash, results, true);
            return results;
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public Optional<Utxo> getUtxoSpentOrUnspent(Outpoint outpoint) {
        if (!enabled) return Optional.empty();
        try {
            byte[] key = UtxoKeyUtil.outpointKey(outpoint.txHash(), outpoint.index());
            byte[] val = db.get(cfUnspent, key);
            if (val != null) {
                return Optional.of(decodeStoredToUtxo(val, outpoint));
            }
            val = db.get(cfSpent, key);
            if (val != null) {
                return Optional.of(decodeSpentToUtxo(val, outpoint));
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<byte[]> getScriptRefBytesByHash(String scriptHashHex) {
        if (!enabled || scriptHashHex == null) return Optional.empty();
        try {
            byte[] key = HexUtil.decodeHexString(scriptHashHex);
            byte[] val = db.get(cfScriptRef, key);
            return Optional.ofNullable(val);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private void scanCfForTxHash(ColumnFamilyHandle cf, byte[] hashPrefix, String txHashHex, List<Utxo> results, boolean isSpent) {
        try (RocksIterator it = db.newIterator(cf)) {
            it.seek(hashPrefix);
            while (it.isValid()) {
                byte[] key = it.key();
                // Key format: txHash(32) + index(2) = 34 bytes
                if (key.length < 34) {
                    it.next();
                    continue;
                }
                if (!UtxoKeyUtil.prefixMatches(key, hashPrefix, 32)) break;
                int idx = ByteBuffer.wrap(key, 32, 2).order(ByteOrder.BIG_ENDIAN).getShort() & 0xffff;
                Outpoint outpoint = new Outpoint(txHashHex, idx);
                byte[] val = it.value();
                results.add(isSpent ? decodeSpentToUtxo(val, outpoint) : decodeStoredToUtxo(val, outpoint));
                it.next();
            }
        }
    }

    private Utxo decodeStoredToUtxo(byte[] val, Outpoint outpoint) {
        return storedToUtxo(UtxoCborCodec.decodeUtxoRecord(val), outpoint);
    }

    private Utxo decodeSpentToUtxo(byte[] spentVal, Outpoint outpoint) {
        return storedToUtxo(UtxoCborCodec.decodeSpentUtxoRecord(spentVal), outpoint);
    }

    private Utxo storedToUtxo(UtxoCborCodec.StoredUtxo stored, Outpoint outpoint) {
        List<AssetAmount> amts = new ArrayList<>();
        if (stored.assets != null) {
            for (Amount a : stored.assets) {
                if (a.getPolicyId() == null) continue;
                String nameHex = a.getAssetNameBytes() != null ? HexUtil.encodeHexString(a.getAssetNameBytes()) : null;
                amts.add(new AssetAmount(a.getPolicyId(), nameHex, a.getQuantity()));
            }
        }

        String scriptRef = null;
        if (stored.referenceScriptHash != null && !stored.referenceScriptHash.isEmpty()) {
            scriptRef = getScriptRefBytesByHash(stored.referenceScriptHash)
                    .map(HexUtil::encodeHexString)
                    .orElse(null);
        }

        return new Utxo(outpoint, stored.address, stored.lovelace,
                amts, stored.datumHash, stored.inlineDatum,
                scriptRef, stored.referenceScriptHash,
                stored.collateralReturn, stored.slot,
                stored.blockNumber, stored.blockHash);
    }

    @Override
    public void forEachUtxo(java.util.function.BiConsumer<String, BigInteger> consumer) {
        if (!enabled || db == null) return;
        try (RocksIterator it = db.newIterator(cfUnspent)) {
            it.seekToFirst();
            while (it.isValid()) {
                try {
                    var stored = UtxoCborCodec.decodeUtxoRecord(it.value());
                    consumer.accept(stored.address, stored.lovelace);
                } catch (Exception ex) {
                    throw new RuntimeException("Failed to scan UTXO record", ex);
                }
                it.next();
            }
        }
    }

    @Override
    public long forEachUtxoRecord(java.util.function.Consumer<Utxo> consumer) {
        if (!enabled || db == null) return -1;
        org.rocksdb.Snapshot snapshot = db.getSnapshot();
        try (org.rocksdb.ReadOptions options = new org.rocksdb.ReadOptions().setSnapshot(snapshot);
             RocksIterator it = db.newIterator(cfUnspent, options)) {
            byte[] applied = db.get(cfMeta, options, META_LAST_APPLIED_BLOCK);
            long snapshotBlock = applied == null ? 0
                    : ByteBuffer.wrap(applied).order(ByteOrder.BIG_ENDIAN).getLong();
            for (it.seekToFirst(); it.isValid(); it.next()) {
                String txHash = UtxoKeyUtil.txHashFromOutpointKey(it.key());
                int index = UtxoKeyUtil.outputIndexFromOutpointKey(it.key());
                consumer.accept(decodeStoredToUtxo(it.value(), new Outpoint(txHash, index)));
            }
            return snapshotBlock;
        } catch (RocksDBException e) {
            throw new IllegalStateException("failed to capture UTXO snapshot", e);
        } finally {
            db.releaseSnapshot(snapshot);
        }
    }

    @Override
    public void forEachUtxoAtSlot(long maxSlot, java.util.function.BiConsumer<String, BigInteger> consumer) {
        if (!enabled || db == null) return;
        org.rocksdb.Snapshot snapshot = db.getSnapshot();
        try (org.rocksdb.ReadOptions readOptions = new org.rocksdb.ReadOptions()
                .setSnapshot(snapshot).setFillCache(false);
             RocksIterator it = db.newIterator(cfUnspent, readOptions)) {
            long latestSlot = readLastAppliedSlot(readOptions);
            it.seekToFirst();
            while (it.isValid()) {
                try {
                    var stored = UtxoCborCodec.decodeUtxoRecord(it.value());
                    if (stored.slot <= maxSlot) {
                        consumer.accept(stored.address, stored.lovelace);
                    }
                } catch (Exception ex) {
                    throw new RuntimeException("Failed to scan UTXO record at snapshot slot " + maxSlot, ex);
                }
                it.next();
            }

            // A historical caller can observe a UTXO tip newer than its target boundary.
            // Creation-slot filtering removes future outputs, but the live set alone cannot
            // recover an output that existed at the boundary and was spent afterward. Replay
            // retained post-boundary spend deltas into this read-only view. The normal
            // boundary path has latestSlot <= maxSlot and pays no delta-scan cost.
            if (latestSlot > maxSlot) {
                restorePostTargetSpends(maxSlot, latestSlot, readOptions, consumer);
            }
        } catch (RocksDBException e) {
            throw new IllegalStateException("Failed to read UTXO view at slot " + maxSlot, e);
        } finally {
            db.releaseSnapshot(snapshot);
        }
    }

    private void restorePostTargetSpends(long maxSlot,
                                         long latestSlot,
                                         ReadOptions readOptions,
                                         java.util.function.BiConsumer<String, BigInteger> consumer)
            throws RocksDBException {
        try (RocksIterator deltas = db.newIterator(cfDelta, readOptions)) {
            deltas.seekToFirst();
            if (!deltas.isValid()) {
                throw historicalUtxoViewUnavailable(maxSlot, latestSlot, "delta log is empty");
            }
            UtxoDeltaCodec.Decoded first = UtxoDeltaCodec.decode(deltas.value());
            long historyFloor = first.slot();
            if (maxSlot < historyFloor) {
                throw historicalUtxoViewUnavailable(maxSlot, latestSlot,
                        "history floor is slot " + historyFloor);
            }

            for (; deltas.isValid(); deltas.next()) {
                UtxoDeltaCodec.Decoded delta = UtxoDeltaCodec.decode(deltas.value());
                if (delta.slot() <= maxSlot) continue;
                for (UtxoDeltaCodec.OutRef ref : delta.spent()) {
                    byte[] outpoint = UtxoKeyUtil.outpointKey(ref.txHash(), ref.index());
                    byte[] spentValue = db.get(cfSpent, readOptions, outpoint);
                    if (spentValue == null) {
                        throw historicalUtxoViewUnavailable(maxSlot, latestSlot,
                                "missing spent record " + ref.txHash() + "#" + ref.index()
                                        + " from block " + delta.blockNumber());
                    }
                    UtxoCborCodec.StoredUtxo stored =
                            UtxoCborCodec.decodeSpentUtxoRecord(spentValue);
                    if (stored.slot <= maxSlot) {
                        consumer.accept(stored.address, stored.lovelace);
                    }
                }
            }
        }
    }

    private static IllegalStateException historicalUtxoViewUnavailable(long maxSlot,
                                                                         long latestSlot,
                                                                         String reason) {
        return new IllegalStateException("Cannot reconstruct UTXO view at slot " + maxSlot
                + " from current slot " + latestSlot + ": " + reason);
    }

    @Override
    public void forEachUtxoDeltaInSlotRange(long startSlot, long endSlot, UtxoDeltaConsumer consumer) {
        if (!enabled || db == null) return;
        try (RocksIterator it = db.newIterator(cfDelta)) {
            it.seekToFirst();
            while (it.isValid()) {
                var dec = UtxoDeltaCodec.decode(it.value());
                if (dec.slot() < startSlot) { it.next(); continue; }
                if (dec.slot() >= endSlot) break;

                // Created UTXOs: look up in cfUnspent
                for (var ref : dec.created()) {
                    byte[] okey = UtxoKeyUtil.outpointKey(ref.txHash(), ref.index());
                    try {
                        byte[] val = db.get(cfUnspent, okey);
                        if (val != null) {
                            var stored = UtxoCborCodec.decodeUtxoRecord(val);
                            consumer.accept(stored.address, stored.lovelace, true);
                        }
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to scan created UTXO delta", e);
                    }
                }

                // Spent UTXOs: look up in cfSpent (contains the original UTXO data)
                for (var ref : dec.spent()) {
                    byte[] okey = UtxoKeyUtil.outpointKey(ref.txHash(), ref.index());
                    try {
                        byte[] val = db.get(cfSpent, okey);
                        if (val != null) {
                            var stored = UtxoCborCodec.decodeSpentUtxoRecord(val);
                            consumer.accept(stored.address, stored.lovelace, false);
                        }
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to scan spent UTXO delta", e);
                    }
                }

                it.next();
            }
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean isStakeBalanceIndexEnabled() {
        return stakeBalanceIndexEnabled;
    }

    @Override
    public boolean isStakeBalanceIndexReady() {
        return stakeBalanceIndexEnabled && stakeBalanceIndexReady && hasCompleteStakeBalanceSource();
    }

    @Override
    public Optional<BigInteger> getUtxoBalanceByStakeCredential(int credType, String credentialHash) {
        if (!enabled || !isStakeBalanceIndexReady()) return Optional.empty();
        byte[] key = stakeBalanceKey(credType, credentialHash);
        if (key == null) return Optional.empty();
        try {
            byte[] val = db.get(cfStakeBalance, key);
            return Optional.of(val != null ? decodeStakeBalance(val) : BigInteger.ZERO);
        } catch (Exception e) {
            log.error("Failed to read stake balance for {}:{}: {}", credType, credentialHash, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public Optional<StakeBalanceView> openStakeBalanceView(CanonicalBlockReference expectedCoordinate) {
        Objects.requireNonNull(expectedCoordinate, "expectedCoordinate");
        if (!enabled || !stakeBalanceIndexEnabled || !hasCompleteStakeBalanceSource()
                || db == null || cfMeta == null || cfStakeBalance == null) {
            return Optional.empty();
        }

        Snapshot snapshot = db.getSnapshot();
        ReadOptions readOptions = new ReadOptions().setSnapshot(snapshot).setFillCache(false);
        RocksIterator iterator = null;
        try {
            if (!StakeBalanceIndexKeys.isCurrent(db.get(cfMeta, readOptions,
                    StakeBalanceIndexKeys.READY_MARKER))) {
                readOptions.close();
                db.releaseSnapshot(snapshot);
                return Optional.empty();
            }

            CanonicalBlockReference actual = readStakeBalanceCoordinate(readOptions);
            requireSameCoordinate(expectedCoordinate, actual);
            PointerIndexMarker pointerMarker = PointerIndexMarker.decode(
                    db.get(cfMeta, readOptions, PointerIndexMarker.KEY));
            boolean pointerIndexReady = pointerMarker != null
                    && pointerMarker.isUsableAt(actual);
            iterator = db.newIterator(cfStakeBalance, readOptions);
            return Optional.of(new RocksStakeBalanceView(
                    db, snapshot, readOptions, iterator, cfPointer,
                    actual, pointerIndexReady));
        } catch (StakeBalanceConsistencyException e) {
            if (iterator != null) iterator.close();
            readOptions.close();
            db.releaseSnapshot(snapshot);
            throw e;
        } catch (Exception e) {
            if (iterator != null) iterator.close();
            readOptions.close();
            db.releaseSnapshot(snapshot);
            throw new StakeBalanceConsistencyException(
                    "Failed to open coordinate-bound stake balance view", e);
        }
    }

    @Override
    public PointerIndexPreparation preparePointerIndex(
            CanonicalBlockReference expectedCoordinate, long maxCreationSlot) {
        Objects.requireNonNull(expectedCoordinate, "expectedCoordinate");
        if (expectedCoordinate.slot() > maxCreationSlot) {
            log.warn("Pointer UTXO index cannot serve a historical cutoff: "
                            + "coordinateSlot={}, maxCreationSlot={}; using pointer scan",
                    expectedCoordinate.slot(), maxCreationSlot);
            return PointerIndexPreparation.unavailable();
        }
        if (!enabled || db == null || cfMeta == null || cfPointer == null
                || !hasCompleteStakeBalanceSource()) {
            return PointerIndexPreparation.unavailable();
        }

        try (ReadOptions readOptions = new ReadOptions().setFillCache(false)) {
            CanonicalBlockReference actual = readStakeBalanceCoordinate(readOptions);
            requireSameCoordinate(expectedCoordinate, actual);
            PointerIndexMarker marker = PointerIndexMarker.decode(
                    db.get(cfMeta, readOptions, PointerIndexMarker.KEY));
            if (marker != null && marker.isUsableAt(actual)) {
                return PointerIndexPreparation.available();
            }
        } catch (RocksDBException e) {
            throw new StakeBalanceConsistencyException(
                    "Failed to inspect pointer index marker", e);
        }
        return PointerIndexPreparation.unavailable();
    }

    @Override
    public boolean isPointerIndexApplicable() {
        if (!enabled || cfPointer == null || !hasCompleteStakeBalanceSource()) {
            return false;
        }
        return !isCompletelyUninitialized();
    }

    private boolean isCompletelyUninitialized() {
        if (db == null || cfMeta == null || cfUnspent == null || cfDelta == null) {
            return false;
        }
        try (ReadOptions readOptions = new ReadOptions().setFillCache(false)) {
            if (db.get(cfMeta, readOptions, META_LAST_APPLIED_BLOCK) != null
                    || db.get(cfMeta, readOptions, META_LAST_APPLIED_SLOT) != null
                    || db.get(cfMeta, readOptions, META_LAST_APPLIED_HASH) != null
                    || db.get(cfMeta, readOptions, PointerIndexMarker.KEY) != null) {
                return false;
            }
            return isColumnFamilyEmpty(cfUnspent, readOptions)
                    && isColumnFamilyEmpty(cfDelta, readOptions);
        } catch (RocksDBException e) {
            throw new StakeBalanceConsistencyException(
                    "Failed to inspect pointer index initialization state", e);
        }
    }

    private boolean isColumnFamilyEmpty(ColumnFamilyHandle handle, ReadOptions readOptions) {
        try (RocksIterator iterator = db.newIterator(handle, readOptions)) {
            iterator.seekToFirst();
            return !iterator.isValid();
        }
    }

    @Override
    public boolean isPointerIndexReadyAtCurrentCoordinate() {
        if (!isPointerIndexApplicable() || db == null || cfMeta == null) {
            return false;
        }
        try (ReadOptions readOptions = new ReadOptions().setFillCache(false)) {
            CanonicalBlockReference actual = readStakeBalanceCoordinate(readOptions);
            PointerIndexMarker marker = PointerIndexMarker.decode(
                    db.get(cfMeta, readOptions, PointerIndexMarker.KEY));
            return marker != null && marker.isUsableAt(actual);
        } catch (RocksDBException e) {
            throw new StakeBalanceConsistencyException(
                    "Failed to inspect pointer index marker", e);
        }
    }

    private CanonicalBlockReference readStakeBalanceCoordinate(ReadOptions readOptions)
            throws RocksDBException {
        byte[] blockBytes = db.get(cfMeta, readOptions, META_LAST_APPLIED_BLOCK);
        byte[] slotBytes = db.get(cfMeta, readOptions, META_LAST_APPLIED_SLOT);
        byte[] hashBytes = db.get(cfMeta, readOptions, META_LAST_APPLIED_HASH);
        if (blockBytes == null && slotBytes == null && hashBytes == null) {
            PointerIndexMarker genesisMarker = PointerIndexMarker.decode(
                    db.get(cfMeta, readOptions, PointerIndexMarker.KEY));
            if (genesisMarker != null && isColumnFamilyEmpty(cfDelta, readOptions)) {
                return new CanonicalBlockReference(
                        genesisMarker.blockNumber(), genesisMarker.slot(), genesisMarker.blockHash());
            }
        }
        if (blockBytes == null || blockBytes.length != Long.BYTES
                || slotBytes == null || slotBytes.length != Long.BYTES
                || hashBytes == null || hashBytes.length != 32) {
            throw new StakeBalanceConsistencyException(
                    "UTXO stake index coordinate metadata is missing or malformed");
        }
        long block = ByteBuffer.wrap(blockBytes).order(ByteOrder.BIG_ENDIAN).getLong();
        long slot = ByteBuffer.wrap(slotBytes).order(ByteOrder.BIG_ENDIAN).getLong();
        return new CanonicalBlockReference(block, slot, hashBytes);
    }

    private static void requireSameCoordinate(CanonicalBlockReference expected,
                                              CanonicalBlockReference actual) {
        if (expected.blockNumber() != actual.blockNumber()
                || expected.slot() != actual.slot()
                || !Arrays.equals(expected.blockHash(), actual.blockHash())) {
            throw new StakeBalanceConsistencyException(
                    "UTXO stake index coordinate mismatch: expected block="
                            + expected.blockNumber() + " slot=" + expected.slot()
                            + ", actual block=" + actual.blockNumber() + " slot=" + actual.slot());
        }
    }

    private boolean isUnfilteredUtxoStore() {
        StorageFilterChain fc = this.filterChain;
        return fc == null || fc.isEmpty();
    }

    private void refreshStakeBalanceIndexReady() {
        if (!enabled || !stakeBalanceIndexEnabled || cfStakeBalance == null || cfMeta == null) {
            stakeBalanceIndexReady = false;
            return;
        }

        try {
            if (!hasCompleteStakeBalanceSource()) {
                clearStakeBalanceIndexReadyNow("complete stake-balance index is unavailable for filtered UTXO storage");
                return;
            }

            if (StakeBalanceIndexKeys.isCurrent(
                    db.get(cfMeta, StakeBalanceIndexKeys.READY_MARKER))) {
                stakeBalanceIndexReady = true;
                return;
            }

            if (isColumnFamilyEmpty(cfUnspent) && isColumnFamilyEmpty(cfDelta)) {
                db.put(cfMeta, StakeBalanceIndexKeys.READY_MARKER,
                        StakeBalanceIndexKeys.READY_VERSION);
                stakeBalanceIndexReady = true;
                return;
            }

            stakeBalanceIndexReady = false;
            log.info("Stake balance index is enabled but not ready; rebuild is required before controlled_amount APIs can use it");
        } catch (Exception e) {
            stakeBalanceIndexReady = false;
            log.warn("Failed to determine stake balance index readiness: {}", e.toString());
        }
    }

    private boolean isColumnFamilyEmpty(ColumnFamilyHandle cf) {
        try (RocksIterator it = db.newIterator(cf)) {
            it.seekToFirst();
            return !it.isValid();
        }
    }

    private void markStakeBalanceIndexReady(WriteBatch batch) throws RocksDBException {
        if (stakeBalanceIndexEnabled && cfMeta != null && hasCompleteStakeBalanceSource()) {
            batch.put(cfMeta, StakeBalanceIndexKeys.READY_MARKER,
                    StakeBalanceIndexKeys.READY_VERSION);
        }
    }

    private void markStakeBalanceIndexReadyNow() {
        if (!stakeBalanceIndexEnabled || cfMeta == null || !hasCompleteStakeBalanceSource()) return;
        try {
            db.put(cfMeta, StakeBalanceIndexKeys.READY_MARKER,
                    StakeBalanceIndexKeys.READY_VERSION);
            stakeBalanceIndexReady = true;
        } catch (RocksDBException e) {
            log.warn("Failed to mark stake balance index ready: {}", e.toString());
        }
    }

    private java.util.Map<StakeCredentialId, BigInteger> newStakeBalanceDeltaMap() {
        return shouldMaintainStakeBalanceIndex() ? new HashMap<>() : null;
    }

    private boolean shouldMaintainStakeBalanceIndex() {
        return enabled && stakeBalanceIndexEnabled && cfStakeBalance != null && hasCompleteStakeBalanceSource();
    }

    private PointerAddressExtraction addStakeBalanceDelta(
            java.util.Map<StakeCredentialId, BigInteger> deltas,
            String address, BigInteger delta) {
        if (address == null) return PointerAddressExtraction.NOT_POINTER;
        Address parsed = StakeCredentialExtractor.parseAddressOrNull(address);
        if (parsed != null && parsed.getAddressType() == AddressType.Ptr) {
            return new PointerAddressExtraction(
                    true, StakeCredentialExtractor.extractPointer(parsed));
        }
        if (deltas == null || delta == null || delta.signum() == 0) {
            return PointerAddressExtraction.NOT_POINTER;
        }
        StakeCredentialId key = StakeCredentialExtractor.extractNonPointer(parsed);
        if (key != null) deltas.merge(key, delta, BigInteger::add);
        return PointerAddressExtraction.NOT_POINTER;
    }

    private void stagePointerPut(WriteBatch batch, byte[] outpoint,
                                 long creationSlot, BigInteger lovelace,
                                 PointerAddressExtraction extraction) throws RocksDBException {
        if (!extraction.pointerAddress() || cfPointer == null) return;
        // Preserve exact UTXO membership even for zero-lovelace or
        // unresolvable pointer rows; aggregation ignores zero and counts an
        // undecodable payload as failed, matching the historical scan.
        batch.put(cfPointer, outpoint,
                PointerUtxoCodec.encode(new PointerUtxo(
                        creationSlot, lovelace, extraction.pointer())));
    }

    private void stagePointerDelete(WriteBatch batch, byte[] outpoint,
                                    PointerAddressExtraction extraction) throws RocksDBException {
        if (!extraction.pointerAddress() || cfPointer == null) return;
        batch.delete(cfPointer, outpoint);
    }

    private record PointerAddressExtraction(
            boolean pointerAddress, PointerAddressId pointer) {
        private static final PointerAddressExtraction NOT_POINTER =
                new PointerAddressExtraction(false, null);
    }

    private void stagePointerIndexMarker(WriteBatch batch, long blockNumber,
                                         long slot, String blockHash) throws RocksDBException {
        if (cfMeta == null || cfPointer == null) return;
        CanonicalBlockReference coordinate = new CanonicalBlockReference(
                blockNumber, slot, decodeCanonicalHash(blockHash));
        batch.put(cfMeta, PointerIndexMarker.KEY,
                PointerIndexMarker.encode(PointerIndexMarker.at(coordinate)));
    }

    private void markPointerIndexReadyNow(long blockNumber, long slot, String blockHash) {
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            stagePointerIndexMarker(batch, blockNumber, slot, blockHash);
            db.write(options, batch);
        } catch (RocksDBException e) {
            throw new IllegalStateException("Failed to mark pointer UTXO index ready", e);
        }
    }

    private void applyStakeBalanceDeltas(WriteBatch batch,
                                         java.util.Map<StakeCredentialId, BigInteger> deltas) throws RocksDBException {
        if (!shouldMaintainStakeBalanceIndex()) {
            invalidateStakeBalanceIndex(batch);
            return;
        }
        if (deltas == null || deltas.isEmpty()) return;

        for (var entry : deltas.entrySet()) {
            BigInteger delta = entry.getValue();
            if (delta == null || delta.signum() == 0) continue;

            byte[] key = stakeBalanceKey(entry.getKey());
            BigInteger current = readStakeBalance(key);
            BigInteger updated = current.add(delta);
            if (updated.signum() < 0) {
                log.warn("Stake balance index underflow for {}:{}, current={}, delta={}; clamping to zero",
                        entry.getKey().credentialType(),
                        HexUtil.encodeHexString(entry.getKey().credentialHash()), current, delta);
                updated = BigInteger.ZERO;
            }

            if (updated.signum() == 0) {
                batch.delete(cfStakeBalance, key);
            } else {
                batch.put(cfStakeBalance, key, encodeStakeBalance(updated));
            }
        }
    }

    private void invalidateStakeBalanceIndex(WriteBatch batch) throws RocksDBException {
        if (cfMeta != null) {
            batch.delete(cfMeta, StakeBalanceIndexKeys.READY_MARKER);
        }
        stakeBalanceIndexReady = false;
    }

    private boolean hasCompleteStakeBalanceSource() {
        return !configuredUtxoFiltersEnabled && isUnfilteredUtxoStore();
    }

    private void clearStakeBalanceIndexReadyNow(String reason) {
        stakeBalanceIndexReady = false;
        if (cfMeta == null) return;
        try {
            db.delete(cfMeta, StakeBalanceIndexKeys.READY_MARKER);
            log.info("Stake balance index ready marker cleared: {}", reason);
        } catch (RocksDBException e) {
            log.warn("Failed to clear stake balance index ready marker: {}", e.toString());
        }
    }

    private BigInteger readStakeBalance(byte[] key) throws RocksDBException {
        byte[] val = db.get(cfStakeBalance, key);
        return val != null ? decodeStakeBalance(val) : BigInteger.ZERO;
    }

    private static byte[] stakeBalanceKey(StakeCredentialId key) {
        byte[] result = new byte[1 + StakeCredentialId.HASH_LENGTH];
        result[0] = (byte) key.credentialType();
        System.arraycopy(key.credentialHash(), 0, result, 1, StakeCredentialId.HASH_LENGTH);
        return result;
    }

    private static byte[] stakeBalanceKey(int credType, String credentialHash) {
        if (credType != 0 && credType != 1) return null;
        try {
            byte[] hash = HexUtil.decodeHexString(credentialHash);
            if (hash.length != 28) return null;
            ByteBuffer bb = ByteBuffer.allocate(29).order(ByteOrder.BIG_ENDIAN);
            bb.put((byte) credType);
            bb.put(hash);
            return bb.array();
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] encodeStakeBalance(BigInteger balance) {
        return CborSerializationUtil.serialize(new UnsignedInteger(balance), true);
    }

    private static BigInteger decodeStakeBalance(byte[] bytes) {
        return CborSerializationUtil.toBigInteger(CborSerializationUtil.deserializeOne(bytes));
    }

    private static final class RocksStakeBalanceView implements StakeBalanceView {
        private final RocksDB db;
        private final Snapshot snapshot;
        private final ReadOptions readOptions;
        private final RocksIterator iterator;
        private final CanonicalBlockReference coordinate;
        private final ColumnFamilyHandle pointerColumnFamily;
        private final boolean pointerIndexReady;
        private StakeCredentialBalance current;
        private boolean started;
        private boolean closed;

        private RocksStakeBalanceView(RocksDB db,
                                      Snapshot snapshot,
                                      ReadOptions readOptions,
                                      RocksIterator iterator,
                                      ColumnFamilyHandle pointerColumnFamily,
                                      CanonicalBlockReference coordinate,
                                      boolean pointerIndexReady) {
            this.db = db;
            this.snapshot = snapshot;
            this.readOptions = readOptions;
            this.iterator = iterator;
            this.pointerColumnFamily = pointerColumnFamily;
            this.coordinate = coordinate;
            this.pointerIndexReady = pointerIndexReady;
        }

        @Override
        public CanonicalBlockReference coordinate() {
            return coordinate;
        }

        @Override
        public boolean advance() {
            requireOpen();
            if (!started) {
                iterator.seekToFirst();
                started = true;
            } else {
                iterator.next();
            }
            if (!iterator.isValid()) {
                try {
                    iterator.status();
                } catch (RocksDBException e) {
                    throw new StakeBalanceConsistencyException(
                            "Stake balance index iteration failed", e);
                }
                current = null;
                return false;
            }

            byte[] key = iterator.key();
            if (key.length != 1 + StakeCredentialId.HASH_LENGTH) {
                throw new StakeBalanceConsistencyException(
                        "Malformed stake balance key length: " + key.length);
            }
            int credentialType = key[0] & 0xFF;
            byte[] credentialHash = Arrays.copyOfRange(key, 1, key.length);
            BigInteger lovelace;
            try {
                lovelace = decodeStakeBalance(iterator.value());
            } catch (Exception e) {
                throw new StakeBalanceConsistencyException(
                        "Malformed stake balance value", e);
            }
            if (lovelace.signum() <= 0) {
                throw new StakeBalanceConsistencyException(
                        "Stake balance index contains a non-positive row");
            }
            current = new StakeCredentialBalance(
                    new StakeCredentialId(credentialType, credentialHash), lovelace);
            return true;
        }

        @Override
        public StakeCredentialBalance current() {
            requireOpen();
            if (current == null) {
                throw new IllegalStateException("advance() has not produced a row");
            }
            return current;
        }

        @Override
        public Optional<PointerUtxoView> openPointerUtxoView(long maxCreationSlot) {
            requireOpen();
            if (!pointerIndexReady || pointerColumnFamily == null
                    || coordinate.slot() > maxCreationSlot) {
                return Optional.empty();
            }
            return Optional.of(new RocksPointerUtxoView(
                    db.newIterator(pointerColumnFamily, readOptions), maxCreationSlot));
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            current = null;
            iterator.close();
            readOptions.close();
            db.releaseSnapshot(snapshot);
        }

        private void requireOpen() {
            if (closed) throw new IllegalStateException("stake balance view is closed");
        }
    }

    private static final class RocksPointerUtxoView implements PointerUtxoView {
        private final RocksIterator iterator;
        private final long maxCreationSlot;
        private PointerUtxo current;
        private boolean started;
        private boolean closed;

        private RocksPointerUtxoView(RocksIterator iterator, long maxCreationSlot) {
            this.iterator = iterator;
            this.maxCreationSlot = maxCreationSlot;
        }

        @Override
        public boolean advance() {
            requireOpen();
            if (!started) {
                iterator.seekToFirst();
                started = true;
            } else {
                iterator.next();
            }
            while (iterator.isValid()) {
                byte[] key = iterator.key();
                if (key.length != 34) {
                    throw new StakeBalanceConsistencyException(
                            "Malformed pointer UTXO outpoint key length: " + key.length);
                }
                PointerUtxo decoded;
                try {
                    decoded = PointerUtxoCodec.decode(iterator.value());
                } catch (RuntimeException malformed) {
                    throw new StakeBalanceConsistencyException(
                            "Malformed pointer UTXO index value", malformed);
                }
                if (decoded.creationSlot() <= maxCreationSlot) {
                    current = decoded;
                    return true;
                }
                iterator.next();
            }
            try {
                iterator.status();
            } catch (RocksDBException e) {
                throw new StakeBalanceConsistencyException(
                        "Pointer UTXO index iteration failed", e);
            }
            current = null;
            return false;
        }

        @Override
        public PointerUtxo current() {
            requireOpen();
            if (current == null) {
                throw new IllegalStateException("advance() has not produced a pointer UTXO");
            }
            return current;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            current = null;
            iterator.close();
        }

        private void requireOpen() {
            if (closed) throw new IllegalStateException("pointer UTXO view is closed");
        }
    }

    @Override
    public synchronized void applyByronBlock(ByronMainBlockAppliedEvent event) {
        if (!enabled) return;
        if (event == null) throw new IllegalArgumentException("Byron main block event is required");
        observeApplyContinuity(event.blockNumber(), event.slot(), event.blockHash(), "Byron");
        long started = System.nanoTime();
        long projectionCpu = 0L;
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            ConsumedAddressCapture consumedAddresses = ConsumedAddressCapture.create(
                    projectionContributor.enabled() && projectionContributor.needsConsumedOutputAddresses());
            ByronUtxoApplier.ApplyResult result = byronUtxoApplier.stageBlock(event, batch, consumedAddresses);
            stageDeltaAndCursor(batch, event.blockNumber(), event.slot(), event.blockHash(),
                    result.created(), result.spent());
            if (projectionContributor.enabled()) {
                long projectionCpu0 = metricsEnabled ? System.nanoTime() : 0L;
                projectionContributor.contributeByronMainBlock(event, result.consumedAddresses(),
                        (cf, key, value) -> {
                            try {
                                batch.put(rocksContext.handle(cf), key, value);
                            } catch (RocksDBException rex) {
                                throw new RuntimeException(
                                        "Failed to stage Byron projection record in UTXO batch", rex);
                            }
                        });
                if (metricsEnabled) projectionCpu = System.nanoTime() - projectionCpu0;
            }
            db.write(options, batch);
            rememberAppliedContinuity(event.blockNumber(), event.slot(), event.blockHash());
            lastApplyCreated = result.created().size();
            lastApplySpent = result.spent().size();
            if (metricsEnabled) {
                applyNanos.add(System.nanoTime() - started);
                projectionNanos.add(projectionCpu);
                attributedBlocks.increment();
            }
            log.debug("Byron UTXO applied: block={} slot={} created={} spent={} filtered={}",
                    event.blockNumber(), event.slot(), result.created().size(),
                    result.spent().size(), result.filteredOutputs());
        } catch (Exception ex) {
            log.error("Byron UTXO apply failed for block {}: {}",
                    event.blockNumber(), ex.toString(), ex);
            throw new RuntimeException("Byron UTXO apply failed for block " + event.blockNumber(), ex);
        }
    }

    @Override
    public synchronized void applyBlock(BlockAppliedEvent e) {
        if (!enabled) return;
        if (e.block() == null) return; // header-only or EBB
        observeApplyContinuity(e.blockNumber(), e.slot(), e.blockHash(),
                e.era() != null ? e.era().name() : "Shelley-family");
        long t0 = System.nanoTime();
        long projectionCpu = 0L;
        // Addresses of the outputs this block consumes, captured while they are still current.
        // Only collected when a contributor actually needs them (the address-transaction
        // section); otherwise the shared disabled sentinel costs one reference check per input.
        ConsumedAddressCapture consumedAddresses = ConsumedAddressCapture.create(
                projectionContributor.enabled() && projectionContributor.needsConsumedOutputAddresses());

        // Determine Allegra bootstrap outpoints to remove (before ctx is created).
        // These are collected here but written into the block's WriteBatch for atomicity.
        // The set is used to filter ctx results so tx input processing treats them as absent.
        Set<ByteArrayKey> removedBootstrapOutpoints = Collections.emptySet();
        boolean doAllegraRemoval = false;
        if (e.era() != null && e.era().getValue() >= Era.Allegra.getValue()
                && allegraBootstrapDoneChecker != null && !allegraBootstrapDoneChecker.get()) {
            if (byronGenesisKeysSupplier != null) {
                var keys = byronGenesisKeysSupplier.get();
                if (keys != null && !keys.isEmpty()) {
                    removedBootstrapOutpoints = new HashSet<>();
                    for (byte[] outKey : keys) {
                        try {
                            if (db.get(cfUnspent, outKey) != null) {
                                removedBootstrapOutpoints.add(new ByteArrayKey(outKey));
                            }
                        } catch (Exception ex) {
                            throw new RuntimeException("Failed to inspect Allegra bootstrap UTXO", ex);
                        }
                    }
                    doAllegraRemoval = !removedBootstrapOutpoints.isEmpty();
                    if (!doAllegraRemoval) {
                        // All bootstrap UTXOs already absent — will mark done in the batch
                        doAllegraRemoval = true; // still need to write the completion marker
                        removedBootstrapOutpoints = Collections.emptySet();
                    }
                }
            }
        }

        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions(); UtxoProcessor.ApplyContext ctx = processor.prepare(e, cfUnspent)) {
            long slot = e.slot();
            long blockNo = e.blockNumber();
            String blockHash = e.blockHash();
            var block = e.block();
            // Capture block body size for metrics
            try {
                long bodySize = block.getHeader() != null && block.getHeader().getHeaderBody() != null ? block.getHeader().getHeaderBody().getBlockBodySize() : 0L;
                if (metricsEnabled) {
                    lastBlockSize = bodySize;
                    synchronized (blockSizes) {
                        blockSizes.addLast(bodySize);
                        if (blockSizes.size() > blockSizeWindow) blockSizes.removeFirst();
                    }
                }
            } catch (Throwable ignored) {
            }

            List<Integer> invList = block.getInvalidTransactions();
            Set<Integer> invalidIdx = (invList != null) ? new HashSet<>(invList) : Collections.emptySet();
            List<TransactionBody> txs = block.getTransactionBodies();
            List<UtxoDeltaCodec.OutRef> createdRefs = new ArrayList<>();
            List<UtxoDeltaCodec.OutRef> spentRefs = new ArrayList<>();
            java.util.Map<StakeCredentialId, BigInteger> stakeBalanceDeltas = newStakeBalanceDeltaMap();
            int filteredOutputs = 0;

            // Write Allegra bootstrap removals into this block's WriteBatch (atomic with delta)
            if (doAllegraRemoval) {
                BigInteger bootstrapRemoved = processAllegraRemoval(batch, spentRefs, slot);
                batch.put(metadataHandle, allegraBootstrapDoneKey, "1".getBytes());
                if (bootstrapRemoved.signum() > 0) {
                    log.info("Allegra bootstrap: removed {} lovelace of Byron genesis UTXOs (block {})",
                            bootstrapRemoved, blockNo);
                } else {
                    log.debug("Allegra bootstrap: no UTXOs to remove (already spent/removed)");
                }
            }

            // Track outputs created within this block for intra-block spend detection.
            // The pre-fetched ctx only sees cfUnspent state BEFORE this block's outputs.
            java.util.HashMap<String, byte[]> intraBlockOutputs = new java.util.HashMap<>();

            for (int i = 0; i < txs.size(); i++) {
                var tx = txs.get(i);
                boolean invalid = invalidIdx.contains(i);
                if (!invalid) {
                    if (tx.getInputs() != null) {
                        for (var in : tx.getInputs()) {
                            byte[] key = UtxoKeyUtil.outpointKey(in.getTransactionId(), in.getIndex());
                            // Filter: bootstrap UTXOs removed by Allegra are unspendable
                            boolean deliberatelyRemoved = removedBootstrapOutpoints.contains(new ByteArrayKey(key));
                            byte[] prev = deliberatelyRemoved
                                    ? null : ctx.getUnspent(key);
                            // Also check intra-block outputs (created earlier in this block)
                            String intraKey = in.getTransactionId() + ":" + in.getIndex();
                            if (prev == null) {
                                prev = intraBlockOutputs.remove(intraKey);
                                if (prev != null) {
                                    // Intra-block spend: output was created and spent in same block.
                                    // Delete from cfUnspent (was added to batch by earlier tx)
                                    batch.delete(cfUnspent, key);
                                }
                            } else {
                                intraBlockOutputs.remove(intraKey);
                            }
                            if (prev == null && !deliberatelyRemoved) {
                                observeShelleyUnresolvedInput(e, in.getTransactionId(), in.getIndex());
                            }
                            if (prev != null) {
                                Map spentMap = new Map();
                                spentMap.put(new UnsignedInteger(2), CborSerializationUtil.deserializeOne(prev));
                                spentMap.put(new UnsignedInteger(1), new UnsignedInteger(slot));
                                byte[] spentVal = CborSerializationUtil.serialize(spentMap, true);
                                batch.put(cfSpent, key, spentVal);
                                batch.delete(cfUnspent, key);
                                var stored = UtxoCborCodec.decodeUtxoRecord(prev);
                                consumedAddresses.recordSpent(in.getTransactionId(), in.getIndex(), stored);
                                if (indexAddressHash) {
                                    byte[] akey = UtxoKeyUtil.addrHash28(stored.address);
                                    byte[] aIdx = UtxoKeyUtil.addressIndexKey(akey, stored.slot, in.getTransactionId(), in.getIndex());
                                    batch.delete(cfAddr, aIdx);
                                }
                                if (indexPaymentCred) {
                                    byte[] pc = UtxoKeyUtil.paymentCred28(stored.address);
                                    if (pc != null) {
                                        byte[] pIdx = UtxoKeyUtil.addressIndexKey(pc, stored.slot, in.getTransactionId(), in.getIndex());
                                        batch.delete(cfAddr, pIdx);
                                    }
                                }
                                spentRefs.add(new UtxoDeltaCodec.OutRef(in.getTransactionId(), in.getIndex()));
                                PointerAddressExtraction pointer = addStakeBalanceDelta(
                                        stakeBalanceDeltas, stored.address, stored.lovelace.negate());
                                stagePointerDelete(batch, key, pointer);
                            }
                        }
                    }
                    if (tx.getOutputs() != null) {
                        for (int outIdx = 0; outIdx < tx.getOutputs().size(); outIdx++) {
                            var out = tx.getOutputs().get(outIdx);
                            BigInteger lovelace = BigInteger.ZERO;
                            var amounts = out.getAmounts();
                            if (amounts != null) for (Amount a : amounts)
                                if ("lovelace".equals(a.getUnit())) lovelace = a.getQuantity();
                            // Apply storage filter chain
                            StorageFilterChain fc = this.filterChain;
                            if (fc != null && !fc.isEmpty()) {
                                byte[] pcBytes = UtxoKeyUtil.paymentCred28(out.getAddress());
                                String pcHex = pcBytes != null ? HexUtil.encodeHexString(pcBytes) : null;
                                var filterCtx = new UtxoFilterContext(
                                        out.getAddress(), pcHex,
                                        lovelace.longValueExact(), amounts,
                                        slot, blockNo, tx.getTxHash(), outIdx);
                                if (!fc.acceptUtxoOutput(filterCtx, block, tx)) {
                                    filteredOutputs++;
                                    continue;
                                }
                            }

                            byte[] referenceScriptHash = getReferenceScriptHash(out);
                            byte[] val = UtxoCborCodec.encodeUtxoRecord(out.getAddress(), lovelace, amounts, out.getDatumHash(),
                                    out.getInlineDatum() != null ? HexUtil.decodeHexString(out.getInlineDatum()) : null,
                                    referenceScriptHash, false, slot, blockNo, blockHash);

                            byte[] outKey = UtxoKeyUtil.outpointKey(tx.getTxHash(), outIdx);
                            batch.put(cfUnspent, outKey, val);
                            // Track for intra-block spend detection
                            intraBlockOutputs.put(tx.getTxHash() + ":" + outIdx, val);
                            // Capture on creation as well as on spend. An output created and
                            // consumed inside one block is never read back from the store, so
                            // the spend path alone leaves it unresolvable - observed on preprod
                            // block 1,809,762, where an invalid transaction's collateral return
                            // is spent in the same block.
                            if (referenceScriptHash != null && out.getScriptRef() != null) {
                                batch.put(cfScriptRef, referenceScriptHash, HexUtil.decodeHexString(out.getScriptRef()));
                            }
                            consumedAddresses.recordCreated(tx.getTxHash(), outIdx, out.getAddress());
                            //log.info("UTXO created: {}:{}", tx.getTxHash(), outIdx);
                            if (indexAddressHash) {
                                byte[] addrHash = UtxoKeyUtil.addrHash28(out.getAddress());
                                byte[] addrIdxKey = UtxoKeyUtil.addressIndexKey(addrHash, slot, tx.getTxHash(), outIdx);
                                batch.put(cfAddr, addrIdxKey, new byte[0]);
                            }
                            if (indexPaymentCred) {
                                byte[] pc = UtxoKeyUtil.paymentCred28(out.getAddress());
                                if (pc != null) {
                                    byte[] pIdx = UtxoKeyUtil.addressIndexKey(pc, slot, tx.getTxHash(), outIdx);
                                    batch.put(cfAddr, pIdx, new byte[0]);
                                }
                            }
                            createdRefs.add(new UtxoDeltaCodec.OutRef(tx.getTxHash(), outIdx));
                            PointerAddressExtraction pointer = addStakeBalanceDelta(
                                    stakeBalanceDeltas, out.getAddress(), lovelace);
                            stagePointerPut(batch, outKey, slot, lovelace, pointer);
                        }
                    }
                } else {
                    if (tx.getCollateralInputs() != null) {
                        for (var in : tx.getCollateralInputs()) {
                            byte[] key = UtxoKeyUtil.outpointKey(in.getTransactionId(), in.getIndex());
                            // Filter: bootstrap UTXOs removed by Allegra are unspendable
                            boolean deliberatelyRemoved = removedBootstrapOutpoints.contains(new ByteArrayKey(key));
                            byte[] prev = deliberatelyRemoved
                                    ? null : ctx.getUnspent(key);
                            String intraKey = in.getTransactionId() + ":" + in.getIndex();
                            if (prev == null) {
                                prev = intraBlockOutputs.remove(intraKey);
                                if (prev != null) {
                                    batch.delete(cfUnspent, key);
                                }
                            } else {
                                intraBlockOutputs.remove(intraKey);
                            }
                            if (prev == null && !deliberatelyRemoved) {
                                observeShelleyUnresolvedInput(e, in.getTransactionId(), in.getIndex());
                            }
                            if (prev != null) {
                                Map spentMap = new Map();
                                spentMap.put(new UnsignedInteger(2), CborSerializationUtil.deserializeOne(prev));
                                spentMap.put(new UnsignedInteger(1), new UnsignedInteger(slot));
                                byte[] spentVal = CborSerializationUtil.serialize(spentMap, true);
                                batch.put(cfSpent, key, spentVal);
                                batch.delete(cfUnspent, key);
                                var stored = UtxoCborCodec.decodeUtxoRecord(prev);
                                consumedAddresses.recordSpent(in.getTransactionId(), in.getIndex(), stored);
                                if (indexAddressHash) {
                                    byte[] akey = UtxoKeyUtil.addrHash28(stored.address);
                                    byte[] aIdx = UtxoKeyUtil.addressIndexKey(akey, stored.slot, in.getTransactionId(), in.getIndex());
                                    batch.delete(cfAddr, aIdx);
                                }
                                if (indexPaymentCred) {
                                    byte[] pc = UtxoKeyUtil.paymentCred28(stored.address);
                                    if (pc != null) {
                                        byte[] pIdx = UtxoKeyUtil.addressIndexKey(pc, stored.slot, in.getTransactionId(), in.getIndex());
                                        batch.delete(cfAddr, pIdx);
                                    }
                                }
                                spentRefs.add(new UtxoDeltaCodec.OutRef(in.getTransactionId(), in.getIndex()));
                                PointerAddressExtraction pointer = addStakeBalanceDelta(
                                        stakeBalanceDeltas, stored.address, stored.lovelace.negate());
                                stagePointerDelete(batch, key, pointer);
                            }
                        }
                    }
                    if (tx.getCollateralReturn() != null) {
                        var out = tx.getCollateralReturn();
                        BigInteger lovelace = BigInteger.ZERO;
                        var amounts = out.getAmounts();
                        if (amounts != null) for (Amount a : amounts)
                            if ("lovelace".equals(a.getUnit())) lovelace = a.getQuantity();
                        int outIdx = tx.getOutputs() != null ? tx.getOutputs().size() : 0;

                        byte[] referenceScriptHash = getReferenceScriptHash(out);
                        byte[] val = UtxoCborCodec.encodeUtxoRecord(out.getAddress(), lovelace, amounts, out.getDatumHash(),
                                out.getInlineDatum() != null ? HexUtil.decodeHexString(out.getInlineDatum()) : null,
                                referenceScriptHash, true, slot, blockNo, blockHash);

                        byte[] outKey = UtxoKeyUtil.outpointKey(tx.getTxHash(), outIdx);
                        batch.put(cfUnspent, outKey, val);
                        // Track for intra-block spend detection, exactly as ordinary outputs are.
                        // Without this a collateral return spent later in the same block resolves
                        // to nothing — the committed read cannot see this batch — and the spend is
                        // skipped, leaving the output unspent forever.
                        intraBlockOutputs.put(tx.getTxHash() + ":" + outIdx, val);
                        consumedAddresses.recordCreated(tx.getTxHash(), outIdx, out.getAddress());
                        if (referenceScriptHash != null && out.getScriptRef() != null) {
                            batch.put(cfScriptRef, referenceScriptHash, HexUtil.decodeHexString(out.getScriptRef()));
                        }
                        if (indexAddressHash) {
                            byte[] addrHash = UtxoKeyUtil.addrHash28(out.getAddress());
                            byte[] addrIdxKey = UtxoKeyUtil.addressIndexKey(addrHash, slot, tx.getTxHash(), outIdx);
                            batch.put(cfAddr, addrIdxKey, new byte[0]);
                        }
                        if (indexPaymentCred) {
                            byte[] pc = UtxoKeyUtil.paymentCred28(out.getAddress());
                            if (pc != null) {
                                byte[] pIdx = UtxoKeyUtil.addressIndexKey(pc, slot, tx.getTxHash(), outIdx);
                                batch.put(cfAddr, pIdx, new byte[0]);
                            }
                        }
                        createdRefs.add(new UtxoDeltaCodec.OutRef(tx.getTxHash(), outIdx));
                        PointerAddressExtraction pointer = addStakeBalanceDelta(
                                stakeBalanceDeltas, out.getAddress(), lovelace);
                        stagePointerPut(batch, outKey, slot, lovelace, pointer);
                    }
                }
            }

            stageDeltaAndCursor(batch, blockNo, slot, blockHash, createdRefs, spentRefs);
            applyStakeBalanceDeltas(batch, stakeBalanceDeltas);
            // ADR-039: stage this block's projection sections into the SAME batch as the
            // UTXO state they were derived from. That is the whole atomicity argument —
            // a section cannot become durable without its state, or the reverse — and it
            // avoids any transaction spanning chain, UTXO and ledger. When history is
            // disabled this is one predictable false check.
            if (projectionContributor.enabled()) {
                long projectionCpu0 = metricsEnabled ? System.nanoTime() : 0L;
                projectionContributor.contributeBlock(e, consumedAddresses.view(), (cf, key, value) -> {
                    try {
                        batch.put(rocksContext.handle(cf), key, value);
                    } catch (RocksDBException rex) {
                        throw new RuntimeException("Failed to stage projection record in UTXO batch", rex);
                    }
                });
                if (metricsEnabled) projectionCpu = System.nanoTime() - projectionCpu0;
            }
            db.write(wo, batch);
            rememberAppliedContinuity(blockNo, slot, blockHash);

            if (log.isDebugEnabled()) {
                if (filteredOutputs > 0) {
                    log.debug("UTXO applied: block={} slot={} created={} spent={} filtered={} era={}", blockNo, slot, createdRefs.size(), spentRefs.size(), filteredOutputs, e.era());
                } else {
                    log.debug("UTXO applied: block={} slot={} created={} spent={} era={}", blockNo, slot, createdRefs.size(), spentRefs.size(), e.era());
                }
            }
            if (metricsEnabled) {
                applyNanos.add(System.nanoTime() - t0);
                projectionNanos.add(projectionCpu);
                attributedBlocks.increment();
                // Skip the first block: there is no previous start to measure a cycle against.
                if (previousApplyStartNanos != 0) cycleNanos.add(t0 - previousApplyStartNanos);
                previousApplyStartNanos = t0;
            }
            if (metricsEnabled) {
                long dtMs = (System.nanoTime() - t0) / 1_000_000L;
                synchronized (applyLatencies) {
                    applyLatencies.addLast(dtMs);
                    if (applyLatencies.size() > applyLatencyWindow) applyLatencies.removeFirst();
                }
                lastApplyCreated = createdRefs.size();
                lastApplySpent = spentRefs.size();
                long now = System.currentTimeMillis();
                synchronized (applyTimestamps) {
                    applyTimestamps.addLast(now);
                    while (!applyTimestamps.isEmpty() && now - applyTimestamps.peekFirst() > 30_000L)
                        applyTimestamps.removeFirst();
                }
            }
        } catch (Exception ex) {
            log.error("UTXO apply failed for block {}: {}", e.blockNumber(), ex.toString(), ex);
            throw new RuntimeException("UTXO apply failed for block " + e.blockNumber(), ex);
        }
    }

    private void stageDeltaAndCursor(WriteBatch batch,
                                     long blockNumber,
                                     long slot,
                                     String blockHash,
                                     List<UtxoDeltaCodec.OutRef> created,
                                     List<UtxoDeltaCodec.OutRef> spent) throws RocksDBException {
        byte[] delta = UtxoDeltaCodec.encode(blockNumber, slot, blockHash, created, spent);
        byte[] key = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(blockNumber).array();
        batch.put(cfDelta, key, delta);
        batch.put(cfMeta, META_LAST_APPLIED_SLOT,
                ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(slot).array());
        batch.put(cfMeta, META_LAST_APPLIED_BLOCK,
                ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(blockNumber).array());
        batch.put(cfMeta, META_LAST_APPLIED_HASH, decodeCanonicalHash(blockHash));
    }

    private static byte[] decodeCanonicalHash(String blockHash) {
        if (blockHash == null || blockHash.isBlank()) {
            throw new IllegalArgumentException("Canonical block hash is required");
        }
        byte[] hash = HexUtil.decodeHexString(blockHash);
        if (hash.length != 32) {
            throw new IllegalArgumentException("Canonical block hash must be exactly 32 bytes");
        }
        return hash;
    }

    private void observeApplyContinuity(long blockNumber, long slot, String blockHash, String eraLabel) {
        try {
            ensureContinuityCache();
            UtxoDeltaCodec.Decoded previous = continuityPrevious;
            if (previous == null) return; // origin, genesis pseudo-cursor, or bootstrap injection
            boolean identical = previous.blockNumber() == blockNumber
                    && previous.slot() == slot
                    && previous.blockHash() != null
                    && previous.blockHash().equalsIgnoreCase(blockHash);
            if (identical) return;

            boolean continuous = blockNumber == previous.blockNumber() + 1 && slot >= previous.slot();
            if (!continuous) {
                continuityWarnings.increment();
                log.warn("UTXO apply continuity anomaly (diagnostic only): era={}, previous={}/{}:{}, "
                                + "incoming={}/{}:{}",
                        eraLabel, previous.blockNumber(), previous.slot(), previous.blockHash(),
                        blockNumber, slot, blockHash);
            }
        } catch (Throwable t) {
            continuityWarnings.increment();
            log.warn("Unable to observe UTXO apply continuity for block {} (diagnostic only): {}",
                    blockNumber, t.toString());
        }
    }

    private void ensureContinuityCache() {
        if (continuityCacheInitialized) return;
        try (RocksIterator iterator = db.newIterator(cfDelta)) {
            iterator.seekToLast();
            continuityPrevious = iterator.isValid()
                    ? UtxoDeltaCodec.decode(iterator.value()) : null;
            continuityCacheInitialized = true;
        }
    }

    private void rememberAppliedContinuity(long blockNumber, long slot, String blockHash) {
        continuityPrevious = new UtxoDeltaCodec.Decoded(
                blockNumber, slot, blockHash, List.of(), List.of());
        continuityCacheInitialized = true;
    }

    private void rememberRollbackContinuity(UtxoDeltaCodec.Decoded retained) {
        continuityPrevious = retained;
        continuityCacheInitialized = true;
    }

    private void invalidateContinuityCache() {
        continuityPrevious = null;
        continuityCacheInitialized = false;
    }

    private void observeShelleyUnresolvedInput(BlockAppliedEvent event, String txHash, int index) {
        StorageFilterChain filters = filterChain;
        boolean selective = filters != null && !filters.isEmpty();
        if (selective) shelleyFilteredStoreUnresolvedInputs.increment();
        else shelleyUnresolvedInputs.increment();
        long now = System.currentTimeMillis();
        long previous = lastShelleyUnresolvedWarningMillis.get();
        if (now - previous >= 30_000L
                && lastShelleyUnresolvedWarningMillis.compareAndSet(previous, now)) {
            log.warn("Unresolved Shelley-family input {}#{} at block {} slot {} hash {} "
                            + "(selectiveStore={}, warnOnly=true)",
                    txHash, index, event.blockNumber(), event.slot(), event.blockHash(), selective);
        }
    }

    private byte[] getReferenceScriptHash(TransactionOutput out) {
        if (out.getScriptRef() != null) {
            try {
                var script = ReferenceScriptUtil.deserializeScriptRef(HexUtil.decodeHexString(out.getScriptRef()));
                return script.getScriptHash();
            } catch (Exception ex) {
                throw new IllegalArgumentException("Invalid reference script: " + out.getScriptRef(), ex);
            }
        }
        return null;
    }

    @Override
    public synchronized void storeGenesisUtxos(java.util.Map<String, BigInteger> shelleyFunds, long networkMagic, long slot, long blockNumber, String blockHash) {
        if (!enabled) return;
        if (shelleyFunds == null || shelleyFunds.isEmpty()) {
            markStakeBalanceIndexReadyNow();
            markPointerIndexReadyNow(blockNumber, slot, blockHash);
            return;
        }

        boolean isMainnet = (networkMagic == Constants.MAINNET_PROTOCOL_MAGIC);
        String addrPrefix = isMainnet ? "addr" : "addr_test";
        int stored = 0;

        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            java.util.Map<StakeCredentialId, BigInteger> stakeBalanceDeltas = newStakeBalanceDeltaMap();
            for (var entry : shelleyFunds.entrySet()) {
                String hexAddr = entry.getKey();
                BigInteger lovelace = entry.getValue();

                // Normalised once, shared. The tx-hash convention, the bech32 form and the
                // output index live in GenesisUtxos so the ADR-039 projection derives exactly
                // the same outputs rather than reimplementing them.
                var genesisUtxo = com.bloxbean.cardano.yano.api.genesis.GenesisUtxos.shelley(
                        hexAddr, lovelace, networkMagic, blockNumber, slot, blockHash);
                String txHash = genesisUtxo.txHash();
                int outputIndex = genesisUtxo.outputIndex();
                String bech32Addr = genesisUtxo.address();
                if (bech32Addr.equals(hexAddr)) {
                    log.warn("Could not convert genesis address to bech32: {}", hexAddr);
                }

                // Encode UTXO record
                byte[] val = UtxoCborCodec.encodeUtxoRecord(bech32Addr, lovelace, null, null, null, null, false, slot, blockNumber, blockHash);
                byte[] outKey = UtxoKeyUtil.outpointKey(txHash, outputIndex);
                batch.put(cfUnspent, outKey, val);

                // Address index
                if (indexAddressHash) {
                    byte[] addrHash = UtxoKeyUtil.addrHash28(bech32Addr);
                    byte[] addrIdxKey = UtxoKeyUtil.addressIndexKey(addrHash, slot, txHash, outputIndex);
                    batch.put(cfAddr, addrIdxKey, new byte[0]);
                }
                if (indexPaymentCred) {
                    byte[] pc = UtxoKeyUtil.paymentCred28(bech32Addr);
                    if (pc != null) {
                        byte[] pIdx = UtxoKeyUtil.addressIndexKey(pc, slot, txHash, outputIndex);
                        batch.put(cfAddr, pIdx, new byte[0]);
                    }
                }
                PointerAddressExtraction pointer = addStakeBalanceDelta(
                        stakeBalanceDeltas, bech32Addr, lovelace);
                stagePointerPut(batch, outKey, slot, lovelace, pointer);
                stored++;
            }
            applyStakeBalanceDeltas(batch, stakeBalanceDeltas);
            markStakeBalanceIndexReady(batch);
            stagePointerIndexMarker(batch, blockNumber, slot, blockHash);
            db.write(wo, batch);
            if (stakeBalanceIndexEnabled) stakeBalanceIndexReady = true;
            log.info("Stored {} Shelley genesis UTXOs (tx_hash = blake2b(address), outputIndex=0)", stored);
        } catch (Exception ex) {
            log.error("Failed to store Shelley genesis UTXOs: {}", ex.toString(), ex);
            throw new RuntimeException("Failed to store Shelley genesis UTXOs", ex);
        }
    }

    // Byron genesis outpoint keys — collected during genesis init, persisted separately.
    // Only kept in memory briefly during storeByronGenesisUtxos() to return to caller for persistence.
    private java.util.List<byte[]> byronGenesisOutpointKeys = new java.util.ArrayList<>();

    // Allegra bootstrap removal — self-contained in applyBlock(), no external signal needed
    private java.util.function.Supplier<java.util.List<byte[]>> byronGenesisKeysSupplier;
    private java.util.function.Supplier<Boolean> allegraBootstrapDoneChecker;
    private byte[] allegraBootstrapDoneKey;        // metadata CF key for completion marker
    private byte[] byronGenesisKeysMetadataKey;
    private byte[] shelleyStartUtxoTotalMetadataKey;
    private org.rocksdb.ColumnFamilyHandle metadataHandle; // metadata CF handle for atomic write

    private static final byte[] META_BYRON_MAIN_APPLY_CAPABILITY =
            "utxo.capability.byron_main_apply.v1".getBytes(StandardCharsets.UTF_8);

    public synchronized boolean hasByronMainApplyCapability() {
        if (!enabled) return true;
        try {
            return db.get(cfMeta, META_BYRON_MAIN_APPLY_CAPABILITY) != null;
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to read Byron UTXO capability marker", e);
        }
    }

    public synchronized void requireByronMainApplyCapability(ChainState chainState, boolean snapshotRestore) {
        if (!enabled || hasByronMainApplyCapability()) return;
        boolean hasCanonicalState = chainState != null
                && (chainState.getTip() != null || chainState.getHeaderTip() != null);
        if (snapshotRestore || hasCanonicalState) {
            String source = snapshotRestore ? "restored snapshot" : "existing chain database";
            throw new IllegalStateException("The " + source
                    + " has no Byron-main UTXO capability marker. Its derived UTXO history may omit "
                    + "Byron transactions; rebuild UTXO state from genesis or perform a full resync.");
        }
    }

    /**
     * Atomically seeds every genesis distribution and establishes the versioned
     * full-history capability. This is the only automatic marker-writing path.
     */
    public synchronized void initializeFreshFullStateGenesis(
            java.util.Map<String, BigInteger> shelleyFunds,
            long networkMagic,
            java.util.Map<String, BigInteger> nonAvvmBalances,
            java.util.Map<String, BigInteger> avvmBalances,
            long slot,
            long blockNumber,
            String blockHash) {
        if (!enabled || hasByronMainApplyCapability()) return;
        if (metadataHandle == null || byronGenesisKeysMetadataKey == null) {
            throw new IllegalStateException("Chain metadata must be wired before fresh UTXO genesis initialization");
        }
        if (!isColumnFamilyEmpty(cfUnspent) || !isColumnFamilyEmpty(cfSpent)
                || !isColumnFamilyEmpty(cfAddr) || !isColumnFamilyEmpty(cfDelta)
                || !isColumnFamilyEmpty(cfScriptRef) || !isColumnFamilyEmpty(cfStakeBalance)
                || !isColumnFamilyEmpty(cfPointer)) {
            throw new IllegalStateException("Cannot establish Byron UTXO capability on non-empty unmarked state; "
                    + "use the explicit rebuild operation");
        }

        java.util.Map<String, BigInteger> shelley = shelleyFunds != null
                ? shelleyFunds : java.util.Map.of();
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            java.util.Map<StakeCredentialId, BigInteger> stakeBalanceDeltas = newStakeBalanceDeltaMap();
            for (var entry : shelley.entrySet()) {
                var output = com.bloxbean.cardano.yano.api.genesis.GenesisUtxos.shelley(
                        entry.getKey(), entry.getValue(), networkMagic, blockNumber, slot, blockHash);
                byte[] value = UtxoCborCodec.encodeUtxoRecord(output.address(), output.amount(), null,
                        null, null, null, false, slot, blockNumber, blockHash);
                byte[] outpoint = UtxoKeyUtil.outpointKey(output.txHash(), output.outputIndex());
                batch.put(cfUnspent, outpoint, value);
                if (indexAddressHash) {
                    batch.put(cfAddr, UtxoKeyUtil.addressIndexKey(
                            UtxoKeyUtil.addrHash28(output.address()), slot,
                            output.txHash(), output.outputIndex()), new byte[0]);
                }
                if (indexPaymentCred) {
                    byte[] credential = UtxoKeyUtil.paymentCred28(output.address());
                    if (credential != null) {
                        batch.put(cfAddr, UtxoKeyUtil.addressIndexKey(
                                credential, slot, output.txHash(), output.outputIndex()), new byte[0]);
                    }
                }
                PointerAddressExtraction pointer = addStakeBalanceDelta(
                        stakeBalanceDeltas, output.address(), output.amount());
                stagePointerPut(batch, outpoint, slot, output.amount(), pointer);
            }

            ByronUtxoApplier.GenesisResult byron = byronUtxoApplier.stageGenesisOutputs(
                    nonAvvmBalances, avvmBalances, slot, blockNumber, blockHash, batch);
            batch.put(metadataHandle, byronGenesisKeysMetadataKey,
                    encodeOutpointKeys(byron.avvmOutpointKeys()));
            applyStakeBalanceDeltas(batch, stakeBalanceDeltas);
            markStakeBalanceIndexReady(batch);
            stagePointerIndexMarker(batch, blockNumber, slot, blockHash);
            batch.put(cfMeta, META_BYRON_MAIN_APPLY_CAPABILITY, new byte[]{1});
            db.write(options, batch);
            invalidateContinuityCache();
            if (stakeBalanceIndexEnabled) stakeBalanceIndexReady = true;
            log.info("Initialized full UTXO genesis state atomically: shelley={}, byronNonAvvm={}, "
                            + "byronAvvm={}, persistedAvvmKeys={}",
                    shelley.size(), nonAvvmBalances != null ? nonAvvmBalances.size() : 0,
                    avvmBalances != null ? avvmBalances.size() : 0,
                    byron.avvmOutpointKeys().size());
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize fresh full UTXO genesis state", e);
        }
    }

    private static byte[] encodeOutpointKeys(java.util.List<byte[]> keys) {
        int size = 0;
        for (byte[] key : keys) {
            if (key == null || key.length != 34) {
                throw new IllegalArgumentException("Byron genesis outpoint key must be 34 bytes");
            }
            size += key.length;
        }
        byte[] encoded = new byte[size];
        int offset = 0;
        for (byte[] key : keys) {
            System.arraycopy(key, 0, encoded, offset, key.length);
            offset += key.length;
        }
        return encoded;
    }

    /**
     * Destructively rebuild derived UTXO state from retained canonical bodies.
     * The caller must hold the runtime maintenance lock and pause apply/prune/snapshot workers.
     */
    public synchronized void rebuildFullStateFromGenesis(
            ChainState chainState,
            java.util.Map<String, BigInteger> shelleyFunds,
            long networkMagic,
            java.util.Map<String, BigInteger> nonAvvmBalances,
            java.util.Map<String, BigInteger> avvmBalances) {
        if (!enabled) return;
        if (chainState == null) throw new IllegalArgumentException("ChainState is required for UTXO rebuild");
        if (metadataHandle == null || byronGenesisKeysMetadataKey == null) {
            throw new IllegalStateException("Chain metadata must be wired before UTXO rebuild");
        }

        // Remove the capability marker first. A crash at any later point therefore
        // fails closed on restart instead of exposing a half-cleared state as complete.
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            batch.delete(cfMeta, META_BYRON_MAIN_APPLY_CAPABILITY);
            batch.delete(metadataHandle, byronGenesisKeysMetadataKey);
            if (allegraBootstrapDoneKey != null) batch.delete(metadataHandle, allegraBootstrapDoneKey);
            if (shelleyStartUtxoTotalMetadataKey == null) {
                throw new IllegalStateException("Shelley-start UTXO metadata key was not wired");
            }
            batch.delete(metadataHandle, shelleyStartUtxoTotalMetadataKey);
            db.write(options, batch);
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to mark UTXO rebuild in progress", e);
        }

        clearColumnFamilyForRebuild(cfUnspent);
        clearColumnFamilyForRebuild(cfSpent);
        clearColumnFamilyForRebuild(cfAddr);
        clearColumnFamilyForRebuild(cfDelta);
        clearColumnFamilyForRebuild(cfMeta);
        clearColumnFamilyForRebuild(cfScriptRef);
        clearColumnFamilyForRebuild(cfStakeBalance);
        clearColumnFamilyForRebuild(cfPointer);
        invalidateContinuityCache();
        stakeBalanceIndexReady = false;

        initializeFreshFullStateGenesis(shelleyFunds, networkMagic,
                nonAvvmBalances, avvmBalances, 0L, 0L, "00".repeat(32));
        try {
            reconcile(chainState);
        } catch (RuntimeException e) {
            throw new IllegalStateException("UTXO rebuild could not replay retained canonical bodies. "
                    + "If Byron bodies were pruned, a full resync or validated complete checkpoint is required.", e);
        }
    }

    private void clearColumnFamilyForRebuild(ColumnFamilyHandle handle) {
        final int batchSize = 10_000;
        try (RocksIterator iterator = db.newIterator(handle); WriteOptions options = new WriteOptions()) {
            iterator.seekToFirst();
            while (iterator.isValid()) {
                try (WriteBatch batch = new WriteBatch()) {
                    int staged = 0;
                    while (iterator.isValid() && staged < batchSize) {
                        batch.delete(handle, Arrays.copyOf(iterator.key(), iterator.key().length));
                        staged++;
                        iterator.next();
                    }
                    db.write(options, batch);
                }
            }
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to clear UTXO column family during rebuild", e);
        }
    }

    @Override
    public synchronized void storeByronGenesisUtxos(java.util.Map<String, BigInteger> nonAvvmBalances, long slot, long blockNumber, String blockHash) {
        if (!enabled) return;
        if (nonAvvmBalances == null || nonAvvmBalances.isEmpty()) {
            markStakeBalanceIndexReadyNow();
            markPointerIndexReadyNow(blockNumber, slot, blockHash);
            return;
        }

        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            ByronUtxoApplier.GenesisResult staged = byronUtxoApplier.stageGenesisOutputs(
                    nonAvvmBalances, java.util.Map.of(), slot, blockNumber, blockHash, batch);
            byronGenesisOutpointKeys.addAll(staged.allOutpointKeys());
            markStakeBalanceIndexReady(batch);
            stagePointerIndexMarker(batch, blockNumber, slot, blockHash);
            db.write(wo, batch);
            if (stakeBalanceIndexEnabled) stakeBalanceIndexReady = true;
            log.info("Stored {} Byron genesis UTXOs (tx_hash = blake2b(Base58.decode(address)), outputIndex=0)",
                    staged.allOutpointKeys().size());
        } catch (Exception ex) {
            log.error("Failed to store Byron genesis UTXOs: {}", ex.toString(), ex);
            throw new RuntimeException("Failed to store Byron genesis UTXOs", ex);
        }
    }

    /**
     * Get the outpoint keys of Byron genesis UTXOs collected during initialization.
     * Only valid immediately after storeByronGenesisUtxos() — caller should persist these
     * and then this list can be discarded.
     */
    public java.util.List<byte[]> getByronGenesisOutpointKeys() {
        return java.util.Collections.unmodifiableList(byronGenesisOutpointKeys);
    }

    /**
     * Clear the in-memory Byron genesis outpoint keys after they've been persisted.
     */
    public void clearByronGenesisOutpointKeys() {
        byronGenesisOutpointKeys.clear();
    }

    /** Wrapper for byte[] to use in HashSet with proper equals/hashCode. */
    private record ByteArrayKey(byte[] data) {
        @Override public boolean equals(Object o) {
            return o instanceof ByteArrayKey b && java.util.Arrays.equals(data, b.data);
        }
        @Override public int hashCode() { return java.util.Arrays.hashCode(data); }
    }

    /** Access to RocksDB for batch operations. */
    public RocksDB getDb() {
        return db;
    }

    /** Access to cfUnspent handle for direct queries. */
    public ColumnFamilyHandle getCfUnspent() {
        return cfUnspent;
    }

    /**
     * Wire Allegra bootstrap removal dependencies. Called once during Yano wiring.
     */
    public void wireAllegraBootstrapRemoval(ByronGenesisUtxoMetadataStore metadataStore) {
        if (metadataStore == null) {
            return;
        }

        ColumnFamilyHandle metadataCfHandle = supplier.rocks().handle("metadata");
        if (metadataCfHandle == null) {
            log.warn("Allegra bootstrap removal not wired: missing chain metadata column family");
            return;
        }

        wireAllegraBootstrapRemoval(
                metadataStore::getByronGenesisUtxoKeys,
                metadataStore::isAllegraBootstrapDone,
                metadataStore.getAllegraBootstrapDoneKey(),
                metadataCfHandle);
        this.byronGenesisKeysMetadataKey = metadataStore.getByronGenesisUtxoKeysKey();
        this.shelleyStartUtxoTotalMetadataKey = metadataStore.getShelleyStartUtxoTotalKey();
    }

    /**
     * Wire Allegra bootstrap removal dependencies. Called once during Yano wiring.
     *
     * @param keysSupplier       loads persisted Byron genesis outpoint keys on-demand
     * @param doneChecker        checks if META_ALLEGRA_BOOTSTRAP_DONE marker is set
     * @param doneKey            metadata CF key bytes for the completion marker
     * @param metadataCfHandle   metadata column family handle for atomic marker write
     */
    public void wireAllegraBootstrapRemoval(
            java.util.function.Supplier<java.util.List<byte[]>> keysSupplier,
            java.util.function.Supplier<Boolean> doneChecker,
            byte[] doneKey,
            org.rocksdb.ColumnFamilyHandle metadataCfHandle) {
        this.byronGenesisKeysSupplier = keysSupplier;
        this.allegraBootstrapDoneChecker = doneChecker;
        this.allegraBootstrapDoneKey = doneKey;
        this.metadataHandle = metadataCfHandle;
    }

    /**
     * Process Allegra bootstrap UTXO removal within a block's WriteBatch.
     * Adds removed bootstrap UTXOs to spentRefs so they participate in the delta/rollback pipeline.
     */
    private BigInteger processAllegraRemoval(WriteBatch batch, java.util.List<UtxoDeltaCodec.OutRef> spentRefs, long slot) {
        if (byronGenesisKeysSupplier == null) return BigInteger.ZERO;
        java.util.List<byte[]> keys = byronGenesisKeysSupplier.get();
        if (keys == null || keys.isEmpty()) return BigInteger.ZERO;

        BigInteger totalRemoved = BigInteger.ZERO;
        for (byte[] outKey : keys) {
            try {
                byte[] val = db.get(cfUnspent, outKey);
                if (val == null) continue; // already spent or removed

                var utxo = UtxoCborCodec.decodeUtxoRecord(val);
                if (utxo != null && utxo.lovelace != null) {
                    totalRemoved = totalRemoved.add(utxo.lovelace);
                }

                // Move to cfSpent (same as normal spent UTXO) for rollback support
                byte[] spentWrapper = UtxoCborCodec.wrapSpent(val, slot);
                batch.put(cfSpent, outKey, spentWrapper);
                batch.delete(cfUnspent, outKey);

                // Remove address index
                if (indexAddressHash && utxo != null) {
                    byte[] addrHash = UtxoKeyUtil.addrHash28(utxo.address);
                    String txHash = UtxoKeyUtil.txHashFromOutpointKey(outKey);
                    int outputIdx = UtxoKeyUtil.outputIndexFromOutpointKey(outKey);
                    byte[] addrIdxKey = UtxoKeyUtil.addressIndexKey(addrHash, utxo.slot, txHash, outputIdx);
                    batch.delete(cfAddr, addrIdxKey);
                }

                // Add to spentRefs for delta tracking
                String txHash = UtxoKeyUtil.txHashFromOutpointKey(outKey);
                int outputIdx = UtxoKeyUtil.outputIndexFromOutpointKey(outKey);
                spentRefs.add(new UtxoDeltaCodec.OutRef(txHash, outputIdx));

            } catch (Exception e) {
                throw new RuntimeException("Failed to remove bootstrap UTXO", e);
            }
        }
        return totalRemoved;
    }

    /**
     * Compute the total lovelace across all unspent UTXOs.
     * Used at era boundaries to capture Shelley-start UTXO total.
     * Scans all cfUnspent entries — expensive but only called once at boundary.
     */
    public BigInteger computeTotalUtxoLovelace() {
        if (!enabled) return BigInteger.ZERO;
        BigInteger total = BigInteger.ZERO;
        try (RocksIterator it = db.newIterator(cfUnspent)) {
            it.seekToFirst();
            while (it.isValid()) {
                try {
                    var utxo = UtxoCborCodec.decodeUtxoRecord(it.value());
                    if (utxo != null && utxo.lovelace != null) {
                        total = total.add(utxo.lovelace);
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Failed to decode UTXO while computing total lovelace", e);
                }
                it.next();
            }
        }
        return total;
    }

    /**
     * Remove specific UTXOs from cfUnspent and cfAddr by their outpoint keys.
     * Returns the total lovelace removed. Used at Allegra boundary for bootstrap UTXO removal.
     *
     * @param outpointKeys list of outpoint keys (txHash + outputIndex encoded)
     * @param batch        WriteBatch to accumulate deletions (caller commits)
     * @param previousValues previous values list for rollback safety
     * @return total lovelace of removed UTXOs
     */
    public BigInteger removeUtxosByOutpointKeys(
            java.util.List<byte[]> outpointKeys,
            WriteBatch batch,
            java.util.List<byte[]> previousValues) throws RocksDBException {
        BigInteger removedTotal = BigInteger.ZERO;
        java.util.Map<StakeCredentialId, BigInteger> stakeBalanceDeltas = newStakeBalanceDeltaMap();
        for (byte[] outKey : outpointKeys) {
            byte[] val = db.get(cfUnspent, outKey);
            if (val == null) continue; // already spent/removed

            var utxo = UtxoCborCodec.decodeUtxoRecord(val);
            if (utxo != null && utxo.lovelace != null) {
                removedTotal = removedTotal.add(utxo.lovelace);
            }

            // Record previous value for rollback
            if (previousValues != null) {
                previousValues.add(val);
            }

            batch.delete(cfUnspent, outKey);
            if (utxo != null && utxo.lovelace != null) {
                PointerAddressExtraction pointer = addStakeBalanceDelta(
                        stakeBalanceDeltas, utxo.address, utxo.lovelace.negate());
                stagePointerDelete(batch, outKey, pointer);
            }

            // Remove address index entry if applicable
            if (indexAddressHash && utxo != null) {
                byte[] addrHash = UtxoKeyUtil.addrHash28(utxo.address);
                String txHash = UtxoKeyUtil.txHashFromOutpointKey(outKey);
                int outputIdx = UtxoKeyUtil.outputIndexFromOutpointKey(outKey);
                byte[] addrIdxKey = UtxoKeyUtil.addressIndexKey(addrHash, utxo.slot, txHash, outputIdx);
                batch.delete(cfAddr, addrIdxKey);
            }
        }
        applyStakeBalanceDeltas(batch, stakeBalanceDeltas);
        return removedTotal;
    }

    private final AtomicLong faucetNonce = new AtomicLong(System.nanoTime());

    @Override
    public synchronized String injectFaucetUtxo(String address, long lovelace) {
        if (!enabled) throw new IllegalStateException("UTXO store is not enabled");

        // Generate unique tx hash: blake2b-256(address_bytes + nonce)
        byte[] addrBytes = address.getBytes(StandardCharsets.UTF_8);
        long nonce = faucetNonce.incrementAndGet();
        byte[] nonceBytes = ByteBuffer.allocate(8).putLong(nonce).array();
        byte[] combined = new byte[addrBytes.length + nonceBytes.length];
        System.arraycopy(addrBytes, 0, combined, 0, addrBytes.length);
        System.arraycopy(nonceBytes, 0, combined, addrBytes.length, nonceBytes.length);

        String txHash = HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(combined));
        int outputIndex = 0;

        // Use slot 0 / block 0 — faucet UTXOs are synthetic
        long slot = 0;
        long blockNumber = 0;
        String blockHash = "0000000000000000000000000000000000000000000000000000000000000000";

        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            byte[] val = UtxoCborCodec.encodeUtxoRecord(address, BigInteger.valueOf(lovelace), null, null, null, null, false, slot, blockNumber, blockHash);
            byte[] outKey = UtxoKeyUtil.outpointKey(txHash, outputIndex);
            batch.put(cfUnspent, outKey, val);

            // Address index
            if (indexAddressHash) {
                byte[] addrHash = UtxoKeyUtil.addrHash28(address);
                byte[] addrIdxKey = UtxoKeyUtil.addressIndexKey(addrHash, slot, txHash, outputIndex);
                batch.put(cfAddr, addrIdxKey, new byte[0]);
            }
            if (indexPaymentCred) {
                byte[] pc = UtxoKeyUtil.paymentCred28(address);
                if (pc != null) {
                    byte[] pIdx = UtxoKeyUtil.addressIndexKey(pc, slot, txHash, outputIndex);
                    batch.put(cfAddr, pIdx, new byte[0]);
                }
            }
            java.util.Map<StakeCredentialId, BigInteger> stakeBalanceDeltas = newStakeBalanceDeltaMap();
            PointerAddressExtraction pointer = addStakeBalanceDelta(
                    stakeBalanceDeltas, address, BigInteger.valueOf(lovelace));
            stagePointerPut(batch, outKey, slot, BigInteger.valueOf(lovelace), pointer);
            applyStakeBalanceDeltas(batch, stakeBalanceDeltas);

            db.write(wo, batch);
            log.info("Faucet UTXO injected: txHash={}, address={}, lovelace={}", txHash, address, lovelace);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to inject faucet UTXO", ex);
        }
        return txHash;
    }

    @Override
    public void injectBootstrapUtxos(List<com.bloxbean.cardano.yano.api.bootstrap.BootstrapUtxo> utxos,
                                     long blockNumber, long slot, String blockHash) {
        if (!enabled) throw new IllegalStateException("UTXO store is not enabled");
        if (utxos == null || utxos.isEmpty()) return;

        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            List<UtxoDeltaCodec.OutRef> created = new ArrayList<>();
            java.util.Map<StakeCredentialId, BigInteger> stakeBalanceDeltas = newStakeBalanceDeltaMap();

            for (var utxo : utxos) {
                // Convert BootstrapAsset list to Amount list for encoding
                List<Amount> amounts = null;
                if (utxo.assets() != null && !utxo.assets().isEmpty()) {
                    amounts = new ArrayList<>();
                    for (var asset : utxo.assets()) {
                        Amount a = Amount.builder()
                                .policyId(asset.policyId())
                                .assetNameBytes(asset.assetName() != null
                                        ? HexUtil.decodeHexString(asset.assetName()) : new byte[0])
                                .quantity(asset.quantity())
                                .build();
                        amounts.add(a);
                    }
                }

                byte[] inlineDatum = utxo.inlineDatumCbor() != null
                        ? HexUtil.decodeHexString(utxo.inlineDatumCbor()) : null;

                byte[] val = UtxoCborCodec.encodeUtxoRecord(
                        utxo.address(), utxo.lovelace(), amounts,
                        utxo.datumHash(), inlineDatum,
                        null, false, slot, blockNumber, blockHash);

                byte[] outKey = UtxoKeyUtil.outpointKey(utxo.txHash(), utxo.outputIndex());
                batch.put(cfUnspent, outKey, val);

                // Address index
                if (indexAddressHash) {
                    byte[] addrHash = UtxoKeyUtil.addrHash28(utxo.address());
                    byte[] addrIdxKey = UtxoKeyUtil.addressIndexKey(addrHash, slot,
                            utxo.txHash(), utxo.outputIndex());
                    batch.put(cfAddr, addrIdxKey, new byte[0]);
                }
                if (indexPaymentCred) {
                    byte[] pc = UtxoKeyUtil.paymentCred28(utxo.address());
                    if (pc != null) {
                        byte[] pIdx = UtxoKeyUtil.addressIndexKey(pc, slot,
                                utxo.txHash(), utxo.outputIndex());
                        batch.put(cfAddr, pIdx, new byte[0]);
                    }
                }

                created.add(new UtxoDeltaCodec.OutRef(utxo.txHash(), utxo.outputIndex()));
                PointerAddressExtraction pointer = addStakeBalanceDelta(
                        stakeBalanceDeltas, utxo.address(), utxo.lovelace());
                stagePointerPut(batch, outKey, slot, utxo.lovelace(), pointer);
            }

            // Write delta for rollback support
            byte[] deltaKey = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(blockNumber).array();
            byte[] deltaVal = UtxoDeltaCodec.encode(blockNumber, slot, blockHash,
                    created, Collections.emptyList());
            batch.put(cfDelta, deltaKey, deltaVal);

            // Update meta high-water marks
            batch.put(cfMeta, META_LAST_APPLIED_SLOT,
                    ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(slot).array());
            batch.put(cfMeta, META_LAST_APPLIED_BLOCK,
                    ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(blockNumber).array());
            batch.put(cfMeta, META_LAST_APPLIED_HASH, decodeCanonicalHash(blockHash));
            applyStakeBalanceDeltas(batch, stakeBalanceDeltas);

            db.write(wo, batch);
            rememberAppliedContinuity(blockNumber, slot, blockHash);
            log.info("***********************************************************************");
            log.info("*** Bootstrap: injected {} UTXOs at block #{}, slot={}", utxos.size(), blockNumber, slot);
            log.info("***********************************************************************");
        } catch (Exception ex) {
            throw new RuntimeException("Failed to inject bootstrap UTXOs", ex);
        }
    }

    @Override
    public synchronized void rollbackTo(RollbackEvent e) {
        rollbackToPoint(e.target());
    }

    @Override
    public synchronized void rollbackToPoint(Point target) {
        if (!enabled) return;
        rollbackInternal(UtxoRollbackTarget.exact(target), "exact-point");
    }

    @Override
    public void close() {
        pauseMetricsSampler(Duration.ofSeconds(5));
    }

    // --- RollbackCapableStore implementation ---

    @Override
    public String storeName() {
        return "utxoStore";
    }

    @Override
    public long getLatestAppliedSlot() {
        if (!enabled) return -1;
        try {
            byte[] val = db.get(cfMeta, META_LAST_APPLIED_SLOT);
            if (val != null) {
                if (val.length != 8) {
                    throw new IllegalStateException("Malformed UTXO last applied slot metadata length: " + val.length);
                }
                return ByteBuffer.wrap(val).order(ByteOrder.BIG_ENDIAN).getLong();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to read UTXO latest applied slot", e);
        }
        return -1;
    }

    @Override
    public synchronized com.bloxbean.cardano.yano.api.rollback.RollbackCapableStore.AppliedPoint
            getLatestAppliedPoint() {
        long latestSlot = getLatestAppliedSlot();
        if (latestSlot < 0) {
            return new com.bloxbean.cardano.yano.api.rollback.RollbackCapableStore.AppliedPoint(
                    latestSlot, null);
        }
        try (RocksIterator it = db.newIterator(cfDelta)) {
            it.seekToLast();
            if (!it.isValid()) {
                return new com.bloxbean.cardano.yano.api.rollback.RollbackCapableStore.AppliedPoint(
                        latestSlot, null);
            }
            UtxoDeltaCodec.Decoded latest = UtxoDeltaCodec.decode(it.value());
            if (latest.slot() != latestSlot) {
                throw new IllegalStateException("UTXO applied-point metadata disagrees with delta log: "
                        + latestSlot + " != " + latest.slot());
            }
            return new com.bloxbean.cardano.yano.api.rollback.RollbackCapableStore.AppliedPoint(
                    latestSlot, latest.blockHash());
        } catch (Exception e) {
            throw new RuntimeException("Failed to read UTXO latest applied point", e);
        }
    }

    @Override
    public long getRollbackFloorSlot() {
        if (!enabled) return 0;
        long latestAppliedSlot = getLatestAppliedSlot();

        // Deltas alone are not enough for a safe rollback. Restoring a spent UTXO also
        // needs the original record from cfSpent, and cfSpent is pruned by slot using
        // max(pruneDepth, rollbackWindow). If delta pruning lags behind spent pruning,
        // the earliest delta can advertise an unsafe rollback point.
        long spentRetentionWindow = Math.max(pruneDepth, rollbackWindow);
        long spentRetentionFloor = latestAppliedSlot >= 0
                ? Math.max(0L, latestAppliedSlot - spentRetentionWindow)
                : 0L;

        long deltaFloor = latestAppliedSlot >= 0 ? latestAppliedSlot : 0L;
        try (RocksIterator it = db.newIterator(cfDelta)) {
            it.seekToFirst();
            if (it.isValid()) {
                var dec = UtxoDeltaCodec.decode(it.value());
                deltaFloor = dec.slot();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to read UTXO rollback floor", e);
        }
        return Math.max(deltaFloor, spentRetentionFloor);
    }

    @Override
    public synchronized void rollbackToSlot(long targetSlot) {
        if (!enabled) return;
        rollbackInternal(UtxoRollbackTarget.legacySlot(targetSlot), "adhoc");
    }

    @Override
    public synchronized void reconcile(ChainState chainState) {
        if (!enabled || chainState == null) return;
        long lastAppliedBlock = 0L;
        boolean hasCursor = false;
        try {
            byte[] b = db.get(cfMeta, META_LAST_APPLIED_BLOCK);
            if (b != null) {
                if (b.length != 8) {
                    throw new IllegalStateException("Malformed UTXO last applied block metadata length: " + b.length);
                }
                lastAppliedBlock = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).getLong();
                hasCursor = true;
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to read UTXO last applied block", e);
        }

        ChainTip tip = chainState.getTip();
        if (tip == null) return;
        long tipBlock = tip.getBlockNumber();

        if (hasCursor && lastAppliedBlock == tipBlock) return; // in sync

        // Byron begins with block-0 EBB, which deliberately has no number index and
        // no UTXO transition. A restart in the short window before main block 1 must
        // validate that body and consider the empty transition stream current.
        if (!hasCursor && tipBlock == 0L && chainState.getBlockByNumber(0L) == null) {
            byte[] tipBody = tip.getBlockHash() != null ? chainState.getBlock(tip.getBlockHash()) : null;
            if (tipBody == null) {
                throw new IllegalStateException("UTXO reconcile missing local block body for Byron genesis EBB");
            }
            try {
                if (StoredBlockUtil.requireByronEnvelopeKind(tipBody)
                        != StoredBlockUtil.ByronEnvelopeKind.EBB) {
                    throw new IllegalArgumentException("Byron genesis block is not an EBB envelope");
                }
                ByronEbBlockSerializer.INSTANCE.deserialize(tipBody);
                log.debug("UTXO reconcile validated and ignored the Byron genesis EBB");
                return;
            } catch (RuntimeException notEbb) {
                throw new RuntimeException("UTXO reconcile failed to deserialize Byron genesis EBB", notEbb);
            }
        }

        if (lastAppliedBlock > tipBlock) {
            // Roll back to tip slot (fork safe within rollback window)
            String hashHex = tip.getBlockHash() != null ? HexUtil.encodeHexString(tip.getBlockHash()) : null;
            rollbackTo(new RollbackEvent(new Point(tip.getSlot(), hashHex), true));
            return;
        }

        // Forward replay: apply missing blocks using stored bodies
        long firstBlock = hasCursor ? lastAppliedBlock + 1
                : (tipBlock == 0L || chainState.getBlockByNumber(0L) != null ? 0L : 1L);
        log.info("UTXO reconcile: replaying blocks {} to {}", firstBlock, tipBlock);
        boolean boundaryCaptureAttempted = false;
        for (long bn = firstBlock; bn <= tipBlock; bn++) {
            if ((bn - lastAppliedBlock) % 1000 == 0) {
                log.info("UTXO reconcile progress: block {}/{}", bn, tipBlock);
            }
            byte[] blockBytes = chainState.getBlockByNumber(bn);
            if (blockBytes == null) {
                throw new IllegalStateException("UTXO reconcile missing local block body for block " + bn);
            }
            Era storedEra = chainState.getBlockEra(bn);
            if (storedEra == Era.Byron || StoredBlockUtil.isStoredByronBlock(storedEra, blockBytes)) {
                StoredBlockUtil.ByronEnvelopeKind envelopeKind;
                try {
                    envelopeKind = StoredBlockUtil.requireByronEnvelopeKind(blockBytes);
                } catch (RuntimeException malformedEnvelope) {
                    throw new RuntimeException(
                            "UTXO reconcile failed to classify Byron block " + bn, malformedEnvelope);
                }
                if (envelopeKind == StoredBlockUtil.ByronEnvelopeKind.EBB) {
                    try {
                        ByronEbBlockSerializer.INSTANCE.deserialize(blockBytes);
                    } catch (RuntimeException malformedEbb) {
                        throw new RuntimeException(
                                "UTXO reconcile failed to deserialize Byron EBB " + bn, malformedEbb);
                    }
                    log.debug("UTXO reconcile ignored Byron epoch-boundary block {}", bn);
                    continue;
                }

                final com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock byron;
                try {
                    byron = ByronBlockSerializer.INSTANCE.deserialize(blockBytes);
                } catch (RuntimeException malformedMain) {
                    throw new RuntimeException(
                            "UTXO reconcile failed to deserialize Byron main block " + bn, malformedMain);
                }
                long slot = byron.getHeader().getConsensusData().getAbsoluteSlot();
                // Intentionally outside all classification/deserialization catches:
                // an apply failure must fail reconciliation with its original cause.
                applyByronBlock(new ByronMainBlockAppliedEvent(
                        slot, bn, byron.getHeader().getBlockHash(), byron));
                continue;
            }
            Block block;
            try {
                block = BlockSerializer.INSTANCE.deserialize(blockBytes);
            } catch (RuntimeException t) {
                throw new RuntimeException("UTXO reconcile failed to deserialize block " + bn, t);
            }
            long slot = block.getHeader().getHeaderBody().getSlot();
            String blockHash = block.getHeader().getHeaderBody().getBlockHash();
            Era era = block.getEra() != null ? block.getEra() : storedEra;
            if (!boundaryCaptureAttempted && era != null && era.getValue() > Era.Byron.getValue()) {
                shelleyStartBoundaryCapture.run();
                boundaryCaptureAttempted = true;
            }
            applyBlock(new BlockAppliedEvent(era, slot, bn, blockHash, block));
        }
    }

    // ---- Prune Scheduler Support ----

    private static final byte[] META_LAST_APPLIED_SLOT = "meta.last_applied_slot".getBytes(StandardCharsets.UTF_8);
    private static final byte[] META_LAST_APPLIED_BLOCK = "meta.last_applied_block".getBytes(StandardCharsets.UTF_8);
    private static final byte[] META_LAST_APPLIED_HASH = "meta.last_applied_hash".getBytes(StandardCharsets.UTF_8);
    private static final byte[] META_PRUNE_DELTA_CURSOR = "prune.delta.cursor".getBytes(StandardCharsets.UTF_8);
    private static final byte[] META_PRUNE_SPENT_CURSOR = "prune.spent.cursor".getBytes(StandardCharsets.UTF_8);

    private void rollbackInternal(UtxoRollbackTarget target, String operation) {
        ensureRollbackTargetIsSafe(target.slot());
        try (WriteBatch batch = new WriteBatch();
             WriteOptions wo = new WriteOptions();
             RocksIterator it = db.newIterator(cfDelta)) {
            java.util.Map<StakeCredentialId, BigInteger> stakeBalanceDeltas = newStakeBalanceDeltaMap();
            UtxoDeltaCodec.Decoded retained = null;
            it.seekToLast();
            while (it.isValid()) {
                UtxoDeltaCodec.Decoded dec = UtxoDeltaCodec.decode(it.value());
                if (target.retains(dec)) {
                    retained = dec;
                    break;
                }

                for (UtxoDeltaCodec.OutRef ref : dec.spent()) {
                    byte[] outpoint = UtxoKeyUtil.outpointKey(ref.txHash(), ref.index());
                    byte[] spentValue = db.get(cfSpent, outpoint);
                    if (spentValue == null) {
                        throw missingSpentRecordForRollback(target.slot(), dec, ref);
                    }
                    byte[] unspentValue = UtxoCborCodec.unwrapSpentUtxo(spentValue);
                    batch.put(cfUnspent, outpoint, unspentValue);
                    UtxoCborCodec.StoredUtxo stored = UtxoCborCodec.decodeUtxoRecord(unspentValue);
                    if (indexAddressHash) {
                        byte[] addressHash = UtxoKeyUtil.addrHash28(stored.address);
                        byte[] addressIndex = UtxoKeyUtil.addressIndexKey(
                                addressHash, stored.slot, ref.txHash(), ref.index());
                        batch.put(cfAddr, addressIndex, new byte[0]);
                    }
                    if (indexPaymentCred) {
                        byte[] paymentCredential = UtxoKeyUtil.paymentCred28(stored.address);
                        if (paymentCredential != null) {
                            byte[] paymentIndex = UtxoKeyUtil.addressIndexKey(
                                    paymentCredential, stored.slot, ref.txHash(), ref.index());
                            batch.put(cfAddr, paymentIndex, new byte[0]);
                        }
                    }
                    batch.delete(cfSpent, outpoint);
                    PointerAddressExtraction pointer = addStakeBalanceDelta(
                            stakeBalanceDeltas, stored.address, stored.lovelace);
                    stagePointerPut(batch, outpoint, stored.slot, stored.lovelace, pointer);
                }

                for (UtxoDeltaCodec.OutRef ref : dec.created()) {
                    byte[] outpoint = UtxoKeyUtil.outpointKey(ref.txHash(), ref.index());
                    byte[] record = db.get(cfUnspent, outpoint);
                    if (record == null) {
                        byte[] spent = db.get(cfSpent, outpoint);
                        if (spent != null) record = UtxoCborCodec.unwrapSpentUtxo(spent);
                    }
                    if (record != null) {
                        UtxoCborCodec.StoredUtxo stored = UtxoCborCodec.decodeUtxoRecord(record);
                        if (indexAddressHash) {
                            byte[] addressHash = UtxoKeyUtil.addrHash28(stored.address);
                            byte[] addressIndex = UtxoKeyUtil.addressIndexKey(
                                    addressHash, stored.slot, ref.txHash(), ref.index());
                            batch.delete(cfAddr, addressIndex);
                        }
                        if (indexPaymentCred) {
                            byte[] paymentCredential = UtxoKeyUtil.paymentCred28(stored.address);
                            if (paymentCredential != null) {
                                byte[] paymentIndex = UtxoKeyUtil.addressIndexKey(
                                        paymentCredential, stored.slot, ref.txHash(), ref.index());
                                batch.delete(cfAddr, paymentIndex);
                            }
                        }
                        batch.delete(cfUnspent, outpoint);
                        batch.delete(cfSpent, outpoint);
                        PointerAddressExtraction pointer = addStakeBalanceDelta(
                                stakeBalanceDeltas, stored.address, stored.lovelace.negate());
                        stagePointerDelete(batch, outpoint, pointer);
                    }
                }
                batch.delete(cfDelta, Arrays.copyOf(it.key(), it.key().length));
                it.prev();
            }

            if (allegraBootstrapDoneKey != null && metadataHandle != null) {
                batch.delete(metadataHandle, allegraBootstrapDoneKey);
            }
            updateRollbackMetadata(batch,
                    retained != null ? retained.blockNumber() : null,
                    retained != null ? retained.slot() : null,
                    retained != null ? retained.blockHash() : null);
            restorePointerMarkerAfterRollback(batch, retained);
            applyStakeBalanceDeltas(batch, stakeBalanceDeltas);
            db.write(wo, batch);
            rememberRollbackContinuity(retained);
            log.info("UTXO {} rollback complete: slot={}, hash={}",
                    operation, target.slot(), target.hash());
        } catch (Exception ex) {
            log.error("UTXO {} rollback failed: {}", operation, ex.toString(), ex);
            throw new RuntimeException("UTXO " + operation + " rollback failed", ex);
        }
    }

    private void restorePointerMarkerAfterRollback(
            WriteBatch batch, UtxoDeltaCodec.Decoded retained) throws RocksDBException {
        PointerIndexMarker marker;
        CanonicalBlockReference current;
        try (ReadOptions readOptions = new ReadOptions().setFillCache(false)) {
            marker = PointerIndexMarker.decode(
                    db.get(cfMeta, readOptions, PointerIndexMarker.KEY));
            if (marker == null) return;
            current = readStakeBalanceCoordinate(readOptions);
        }
        if (!marker.isUsableAt(current)) {
            batch.delete(cfMeta, PointerIndexMarker.KEY);
            log.warn("Pointer UTXO index marker cleared during rollback because its "
                            + "pre-rollback proof was unusable: currentBlock={}, currentSlot={}, "
                            + "markerBlock={}, markerSlot={}",
                    current.blockNumber(), current.slot(),
                    marker.blockNumber(), marker.slot());
            return;
        }

        CanonicalBlockReference restored;
        if (retained == null) {
            if (current.blockNumber() != 0 || current.slot() != 0) {
                batch.delete(cfMeta, PointerIndexMarker.KEY);
                log.warn("Pointer UTXO index marker not preserved for rollback to origin "
                                + "from advanced state: currentBlock={}, currentSlot={}",
                        current.blockNumber(), current.slot());
                return;
            }
            restored = new CanonicalBlockReference(0, 0, new byte[32]);
        } else {
            restored = new CanonicalBlockReference(
                    retained.blockNumber(), retained.slot(),
                    decodeCanonicalHash(retained.blockHash()));
        }
        batch.put(cfMeta, PointerIndexMarker.KEY,
                PointerIndexMarker.encode(PointerIndexMarker.at(restored)));
        log.info("Pointer UTXO index readiness proof restored after rollback: "
                        + "block={}, slot={}",
                restored.blockNumber(), restored.slot());
    }

    private record UtxoRollbackTarget(long slot, String hash, boolean origin, boolean exact) {
        static UtxoRollbackTarget exact(Point point) {
            if (point == null) throw new IllegalArgumentException("Rollback target is required");
            if (point.getHash() == null) return new UtxoRollbackTarget(-1L, null, true, true);
            try {
                byte[] decoded = HexUtil.decodeHexString(point.getHash());
                if (decoded.length != 32) {
                    throw new IllegalArgumentException("Rollback hash must be exactly 32 bytes");
                }
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid rollback hash: " + point.getHash(), e);
            }
            return new UtxoRollbackTarget(point.getSlot(), point.getHash(), false, true);
        }

        static UtxoRollbackTarget legacySlot(long slot) {
            return slot < 0
                    ? new UtxoRollbackTarget(-1L, null, true, false)
                    : new UtxoRollbackTarget(slot, null, false, false);
        }

        boolean retains(UtxoDeltaCodec.Decoded delta) {
            if (origin) return false;
            if (delta.slot() < slot) return true;
            if (delta.slot() > slot) return false;
            if (!exact) return true;
            if (delta.blockHash() == null || delta.blockHash().isBlank()) {
                throw new IllegalStateException("UTXO delta at target slot " + slot
                        + " has no block hash; exact rollback is ambiguous");
            }
            return delta.blockHash().equalsIgnoreCase(hash);
        }
    }

    private void updateRollbackMetadata(WriteBatch batch, Long retainedBlock, Long retainedSlot,
                                        String retainedHash) throws RocksDBException {
        if (retainedBlock != null && retainedSlot != null && retainedHash != null) {
            batch.put(cfMeta, META_LAST_APPLIED_SLOT,
                    ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(retainedSlot).array());
            batch.put(cfMeta, META_LAST_APPLIED_BLOCK,
                    ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(retainedBlock).array());
            batch.put(cfMeta, META_LAST_APPLIED_HASH, decodeCanonicalHash(retainedHash));
        } else {
            batch.delete(cfMeta, META_LAST_APPLIED_SLOT);
            batch.delete(cfMeta, META_LAST_APPLIED_BLOCK);
            batch.delete(cfMeta, META_LAST_APPLIED_HASH);
        }
    }

    private IllegalStateException missingSpentRecordForRollback(long targetSlot,
                                                                UtxoDeltaCodec.Decoded delta,
                                                                UtxoDeltaCodec.OutRef outRef) {
        return new IllegalStateException("Cannot rollback UTXO store to slot " + targetSlot
                + ": missing spent record for " + outRef.txHash() + "#" + outRef.index()
                + " while undoing block " + delta.blockNumber() + " at slot " + delta.slot()
                + ". The spent UTXO data was likely pruned; choose a newer rollback point "
                + "or restore from a checkpoint/full resync.");
    }

    private void ensureRollbackTargetIsSafe(long targetSlot) {
        if (targetSlot < 0) {
            return;
        }
        long floor = getRollbackFloorSlot();
        if (targetSlot < floor) {
            throw new IllegalStateException("Cannot rollback UTXO store to slot " + targetSlot
                    + ": rollback floor is " + floor
                    + ". Deltas or spent records required for an older rollback may have been pruned; "
                    + "choose a newer rollback point or restore from a checkpoint/full resync.");
        }
    }

    /**
     * Execute one bounded prune pass using persisted cursors, outside the hot apply path.
     * Uses lastAppliedSlot to compute safe cutoffs.
     */
    @Override
    public synchronized void pruneOnce() {
        if (!enabled) return;
        long t0 = System.nanoTime();
        long currentSlot = readLastAppliedSlot();
        if (currentSlot <= 0) return;
        long deltaCutoff = currentSlot - rollbackWindow;
        long spentRetentionWindow = Math.max(pruneDepth, rollbackWindow);
        long spentCutoff = currentSlot - spentRetentionWindow;

        // Deltas CF: sequential keys by block number; stop at cutoff
        long dd = pruneDeltasAndCount(deltaCutoff);
        // Spent CF: key order unrelated to slot; scan in slices across runs
        long sd = pruneSpentAndCount(spentCutoff);
        if (metricsEnabled) {
            lastPruneMs = (System.nanoTime() - t0) / 1_000_000L;
            lastDeltaDeleted = dd;
            lastSpentDeleted = sd;
        }
    }

    private long readLastAppliedSlot() {
        try (ReadOptions readOptions = new ReadOptions()) {
            return readLastAppliedSlot(readOptions);
        }
    }

    private long readLastAppliedSlot(ReadOptions readOptions) {
        try {
            byte[] v = db.get(cfMeta, readOptions, META_LAST_APPLIED_SLOT);
            if (v == null) return 0L;
            if (v.length != 8) {
                throw new IllegalStateException("Malformed UTXO last applied slot metadata length: " + v.length);
            }
            return ByteBuffer.wrap(v).order(ByteOrder.BIG_ENDIAN).getLong();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read UTXO last applied slot", e);
        }
    }

    public long readLastAppliedBlock() {
        try {
            byte[] v = db.get(cfMeta, META_LAST_APPLIED_BLOCK);
            if (v == null) return 0L;
            if (v.length != 8) {
                throw new IllegalStateException("Malformed UTXO last applied block metadata length: " + v.length);
            }
            return ByteBuffer.wrap(v).order(ByteOrder.BIG_ENDIAN).getLong();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read UTXO last applied block", e);
        }
    }

    // ---- UtxoStatusProvider ----
    @Override
    public String storeType() {
        return "default";
    }

    @Override
    public int getPruneDepth() {
        return pruneDepth;
    }

    @Override
    public int getRollbackWindow() {
        return rollbackWindow;
    }

    @Override
    public int getPruneBatchSize() {
        return pruneBatchSize;
    }

    @Override
    public long getLastAppliedBlock() {
        return readLastAppliedBlock();
    }

    @Override
    public long getLastAppliedSlot() {
        return readLastAppliedSlot();
    }

    @Override
    public byte[] getDeltaCursorKey() {
        try {
            return db.get(cfMeta, META_PRUNE_DELTA_CURSOR);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public byte[] getSpentCursorKey() {
        try {
            return db.get(cfMeta, META_PRUNE_SPENT_CURSOR);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public java.util.Map<String, Object> getMetrics() {
        if (!metricsEnabled) return java.util.Map.of();
        List<Long> snap;
        synchronized (applyLatencies) {
            snap = new ArrayList<>(applyLatencies);
        }
        double avg = 0, p95 = 0;
        if (!snap.isEmpty()) {
            long sum = 0;
            for (long v : snap) sum += v;
            avg = sum * 1.0 / snap.size();
            Collections.sort(snap);
            p95 = snap.get((int) Math.floor(0.95 * (snap.size() - 1)));
        }
        long now = System.currentTimeMillis();
        int within;
        synchronized (applyTimestamps) {
            while (!applyTimestamps.isEmpty() && now - applyTimestamps.peekFirst() > 30_000L)
                applyTimestamps.removeFirst();
            within = applyTimestamps.size();
        }
        double bps = within / 30.0;
        java.util.Map<String, Object> m = new HashMap<>();
        m.put("apply.ms.avg", avg);
        m.put("apply.ms.p95", p95);
        // ADR-039 gate 1: the projection's share of block-apply time, attributed within the
        // run rather than inferred by differencing two runs. Cumulative since start, so it is
        // a stable ratio rather than a noisy instantaneous one.
        long attributed = attributedBlocks.sum();
        if (attributed > 0) {
            long applyNs = applyNanos.sum();
            long projNs = projectionNanos.sum();
            long cycleNs = cycleNanos.sum();
            m.put("apply.ns.avg", applyNs / attributed);
            m.put("projection.ns.avg", projNs / attributed);
            m.put("projection.applyShare", applyNs == 0 ? 0.0 : (double) projNs / applyNs);
            m.put("attributedBlocks", attributed);
            if (cycleNs > 0) {
                m.put("cycle.ns.avg", cycleNs / attributed);
                m.put("apply.cycleShare", (double) applyNs / cycleNs);
                // The gate-1 number: an upper bound on the sync-throughput cost of projection
                // staging. An upper bound rather than a point estimate because it assumes apply
                // sits wholly on the critical path; where pipeline stages overlap, removing the
                // projection would recover less than this.
                m.put("projection.cycleShare", (double) projNs / cycleNs);
            }
        }
        m.put("apply.created.last", lastApplyCreated);
        m.put("apply.spent.last", lastApplySpent);
        m.put("apply.continuityWarnings", continuityWarnings.sum());
        m.put("apply.byron.unresolvedInputs", byronUtxoApplier.unresolvedInputCount());
        m.put("apply.byron.filteredStoreUnresolvedInputs",
                byronUtxoApplier.filteredStoreUnresolvedInputCount());
        m.put("apply.shelley.unresolvedInputs", shelleyUnresolvedInputs.sum());
        m.put("apply.shelley.filteredStoreUnresolvedInputs",
                shelleyFilteredStoreUnresolvedInputs.sum());
        m.put("throughput.blocksPerSec", bps);
        m.put("prune.ms.last", lastPruneMs);
        m.put("prune.deltaDeleted.last", lastDeltaDeleted);
        m.put("prune.spentDeleted.last", lastSpentDeleted);
        // Block size metrics
        long bsAvg = 0;
        List<Long> bsnap;
        synchronized (blockSizes) {
            bsnap = new ArrayList<>(blockSizes);
        }
        if (!bsnap.isEmpty()) {
            long sum = 0;
            for (long v : bsnap) sum += v;
            bsAvg = Math.round(sum * 1.0 / bsnap.size());
        }
        m.put("block.size.last", lastBlockSize);
        m.put("block.size.avg", bsAvg);
        return m;
    }

    @Override
    public java.util.Map<String, Long> getCfEstimates() {
        return cfEstimates.get();
    }

    private void sampleCfEstimates() {
        try {
            java.util.Map<String, Long> m = new HashMap<>();
            m.put("utxo_unspent.estimateNumKeys", parseEstimate(cfUnspent));
            m.put("utxo_spent.estimateNumKeys", parseEstimate(cfSpent));
            m.put("utxo_addr.estimateNumKeys", parseEstimate(cfAddr));
            m.put("utxo_block_delta.estimateNumKeys", parseEstimate(cfDelta));
            m.put("utxo_pointer.estimateNumKeys", parseEstimate(cfPointer));
            cfEstimates.set(Collections.unmodifiableMap(m));
        } catch (Throwable ignored) {
        }
    }

    public synchronized boolean isMetricsSamplerRunning() {
        return metricsScheduler != null && !metricsScheduler.isShutdown();
    }

    public synchronized boolean pauseMetricsSampler(Duration timeout) {
        if (metricsScheduler == null) {
            return true;
        }
        Duration effectiveTimeout = timeout != null ? timeout : Duration.ofSeconds(5);
        ScheduledExecutorService scheduler = metricsScheduler;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(Math.max(1L, effectiveTimeout.toMillis()), TimeUnit.MILLISECONDS)) {
                scheduler.shutdownNow();
                if (!scheduler.awaitTermination(Math.max(1L, effectiveTimeout.toMillis()), TimeUnit.MILLISECONDS)) {
                    return false;
                }
            }
            metricsScheduler = null;
            return true;
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public synchronized void resumeMetricsSampler() {
        startMetricsSampler();
    }

    private void startMetricsSampler() {
        if (!metricsEnabled || rocksSampleMillis <= 0 || metricsScheduler != null) {
            return;
        }
        metricsScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "yano-utxo-rocksdb-metrics");
            thread.setDaemon(true);
            return thread;
        });
        metricsScheduler.scheduleAtFixedRate(this::sampleCfEstimates, rocksSampleMillis, rocksSampleMillis, TimeUnit.MILLISECONDS);
    }

    private long parseEstimate(ColumnFamilyHandle cf) {
        try {
            String v = db.getProperty(cf, "rocksdb.estimate-num-keys");
            return Long.parseLong(v.trim());
        } catch (Exception e) {
            return -1L;
        }
    }

    // package-private raw accessor for unspent values
    byte[] rawUnspentValue(byte[] outpointKey) throws Exception {
        return db.get(cfUnspent, outpointKey);
    }

    private long pruneDeltasAndCount(long deltaCutoff) {
        int remaining = pruneBatchSize;
        byte[] cursor = null;
        try {
            cursor = db.get(cfMeta, META_PRUNE_DELTA_CURSOR);
        } catch (Exception ignored) {
        }
        long deleted = 0L;
        try (RocksIterator it = db.newIterator(cfDelta); WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            if (cursor != null) {
                it.seek(cursor);
                if (it.isValid() && Arrays.equals(it.key(), cursor)) it.next();
            } else {
                it.seekToFirst();
            }
            byte[] lastProcessed = cursor;
            while (it.isValid() && remaining > 0) {
                byte[] k = it.key();
                byte[] v = it.value();
                var dec = UtxoDeltaCodec.decode(v);
                if (dec.slot() <= deltaCutoff) {
                    batch.delete(cfDelta, k);
                    lastProcessed = k;
                    remaining--;
                    deleted++;
                    it.next();
                } else {
                    break;
                }
            }
            if (remaining != pruneBatchSize) {
                db.write(wo, batch);
            }
            // Persist cursor (last processed). If reached end, keep the last key; next run will seek and advance.
            if (lastProcessed != null) {
                try (WriteBatch mb = new WriteBatch(); WriteOptions mwo = new WriteOptions()) {
                    mb.put(cfMeta, META_PRUNE_DELTA_CURSOR, lastProcessed);
                    db.write(mwo, mb);
                }
            }
        } catch (Exception ignored) {
        }
        return deleted;
    }

    private long pruneSpentAndCount(long spentCutoff) {
        int remaining = pruneBatchSize;
        byte[] cursor = null;
        try {
            cursor = db.get(cfMeta, META_PRUNE_SPENT_CURSOR);
        } catch (Exception ignored) {
        }
        boolean wrapped = false;
        long deleted = 0L;
        try (RocksIterator it = db.newIterator(cfSpent); WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            if (cursor != null) {
                it.seek(cursor);
                if (it.isValid() && Arrays.equals(it.key(), cursor)) it.next();
            } else {
                it.seekToFirst();
            }
            byte[] lastProcessed = cursor;
            while (remaining > 0) {
                if (!it.isValid()) {
                    if (wrapped) break; // completed a full pass
                    // wrap to start and continue
                    it.seekToFirst();
                    wrapped = true;
                    if (!it.isValid()) break;
                }
                byte[] k = it.key();
                byte[] v = it.value();
                try {
                    Map m = (Map) CborSerializationUtil.deserializeOne(v);
                    co.nstant.in.cbor.model.DataItem d = m.get(new UnsignedInteger(1));
                    long s = d != null ? CborSerializationUtil.toLong(d) : 0L;
                    if (s > 0 && s <= spentCutoff) {
                        batch.delete(cfSpent, k);
                        remaining--;
                        deleted++;
                    }
                } catch (Exception ignore) {
                }
                lastProcessed = k;
                it.next();
            }
            if (remaining != pruneBatchSize) {
                db.write(wo, batch);
            }
            // Update cursor. If we wrapped and reached end of pass without progress, clear cursor.
            try (WriteBatch mb = new WriteBatch(); WriteOptions mwo = new WriteOptions()) {
                if (lastProcessed != null) mb.put(cfMeta, META_PRUNE_SPENT_CURSOR, lastProcessed);
                db.write(mwo, mb);
            }
        } catch (Exception ignored) {
        }
        return deleted;
    }

    private static int getInt(java.util.Map<String, Object> cfg, String key, int def) {
        Object v = cfg != null ? cfg.get(key) : null;
        if (v instanceof Number n) return n.intValue();
        if (v != null) try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception ignored) {
        }
        return def;
    }

    private static boolean getBool(java.util.Map<String, Object> cfg, String key, boolean def) {
        Object v = cfg != null ? cfg.get(key) : null;
        if (v instanceof Boolean b) return b;
        if (v != null) {
            String s = String.valueOf(v);
            if ("true".equalsIgnoreCase(s)) return true;
            if ("false".equalsIgnoreCase(s)) return false;
        }
        return def;
    }


    // ---- end prune support ----
}
