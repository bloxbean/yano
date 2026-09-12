package org.yanoproject.runtime.wallet;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.yanoproject.api.chain.ChainPoint;
import org.yanoproject.api.utxo.index.IndexUnavailableException;
import org.yanoproject.api.wallet.AddressFirstSeen;
import org.yanoproject.api.wallet.WalletIndexCoverage;
import org.yanoproject.api.wallet.WalletIndexUnavailableException;
import org.yanoproject.api.wallet.WalletScan;
import org.yanoproject.api.wallet.WalletScanRequest;
import org.yanoproject.api.wallet.WalletScanRollbackException;
import org.yanoproject.api.utxo.model.Utxo;
import org.yanoproject.runtime.chain.ArchiveChainStateCapabilities;
import org.yanoproject.runtime.db.RocksDbSupplier;
import org.rocksdb.RocksDBException;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.LongSupplier;

/** Wallet read coordination under short owner-lock scopes; streaming never holds the lock. */
public final class WalletQueryService {
    private final Object lock;
    private final RocksDbSupplier supplier;
    private final Supplier<WalletIndexStore> indexes;
    private final Supplier<ChainPoint> applied;
    private final LongSupplier generation;
    private final Runnable availability;
    private final Runnable readAccess;

    public WalletQueryService(Object lock, RocksDbSupplier supplier, Supplier<WalletIndexStore> indexes,
                              Supplier<ChainPoint> applied, LongSupplier generation, Runnable readAccess, Runnable availability) {
        this.lock = lock;
        this.supplier = supplier;
        this.indexes = indexes;
        this.applied = applied;
        this.generation = generation;
        this.availability = availability;
        this.readAccess = readAccess;
    }

    public AddressFirstSeen firstSeen(String address) {
        synchronized (lock) { return firstSeenLocked(address); }
    }

    private void requireAvailable(byte feature) throws RocksDBException {
        try { readAccess.run(); }
        catch (IndexUnavailableException unavailable) {
            // Do not touch native handles while storage replacement is in progress.
            throw new WalletIndexUnavailableException(new WalletIndexCoverage(true, false, null, null, null, unavailable.getMessage()));
        }
        try { availability.run(); }
        catch (IndexUnavailableException unavailable) {
            WalletIndexCoverage coverage = indexes.get().coverage(feature, applied.get());
            throw new WalletIndexUnavailableException(new WalletIndexCoverage(coverage.enabled(), false,
                    coverage.from(), coverage.indexedThrough(), coverage.identity(), unavailable.getMessage()));
        }
    }

    public WalletScan scan(WalletScanRequest request) {
        synchronized (lock) { return scanLocked(request); }
    }

    private AddressFirstSeen firstSeenLocked(String address) {
        try {
            requireAvailable(WalletIndexStore.FIRST_SEEN);
            ChainPoint applied = this.applied.get();
            if (!(supplier instanceof ArchiveChainStateCapabilities canonical)) {
                throw new IllegalStateException("Canonical point reader unavailable for first-seen");
            }
            requireCanonicalPoint(canonical, applied);
            AddressFirstSeen result = indexes.get().firstSeen(address, applied);
            requireCanonicalPoint(canonical, applied);
            return result;
        } catch (RocksDBException failure) {
            throw new IllegalStateException("Failed to read first-seen index", failure);
        }
    }

    private WalletScan scanLocked(WalletScanRequest request) {
        if (!(supplier instanceof ChainState chainState)
                || !(supplier instanceof ArchiveChainStateCapabilities capabilities)) {
            throw new IllegalStateException("Canonical block reader unavailable for wallet scan");
        }
        try {
            requireAvailable(WalletIndexStore.FILTERS);
            WalletIndexCoverage coverage = indexes.get().coverage(WalletIndexStore.FILTERS, applied.get());
            if (!coverage.available()) throw new WalletIndexUnavailableException(coverage);
            ChainPoint end = request.to() == null ? coverage.indexedThrough() : request.to();
            requireCanonicalPoint(capabilities, request.after());
            if (request.after().blockNumber() < coverage.from().blockNumber() - 1
                    || request.after().blockNumber() == -1 && !coverage.completeFromOrigin()
                    || end.blockNumber() > coverage.indexedThrough().blockNumber()
                    || end.blockNumber() < request.after().blockNumber()) {
                throw new IllegalArgumentException("Requested range is outside complete filter coverage");
            }
            requireCanonicalPoint(capabilities, end);
            long capturedGeneration = generation.getAsLong();
            return new WalletScanner(new WalletScanner.Backend() {
                @Override public void validate() {
                    synchronized (lock) {
                        if (capturedGeneration != generation.getAsLong()) throw new WalletScanRollbackException("Scan invalidated by rollback or restore; restart from a canonical cursor");
                        requireCanonicalPoint(capabilities, end);
                        var floor = capabilities.getEarliestRetainedBodyBlockNumber();
                        if (end.blockNumber() >= 0 && (floor.isEmpty()
                                || floor.getAsLong() > Math.max(1, request.after().blockNumber() + 1))) {
                            throw new IllegalStateException("Requested scan bodies are no longer retained");
                        }
                    }
                }

                @Override public List<WalletIndexStore.FilterRecord> filters(long after, long to, int limit) {
                    synchronized (lock) {
                        validate();
                        try { return indexes.get().readFilters(after, to, limit); }
                        catch (RocksDBException failure) { throw new IllegalStateException("Filter read failed", failure); }
                    }
                }

                @Override public Block block(ChainPoint point) {
                    synchronized (lock) {
                        validate();
                        requireCanonicalPoint(capabilities, point);
                        byte[] body = chainState.getBlock(HexUtil.decodeHexString(point.blockHash()));
                        if (body == null) throw new IllegalStateException("Candidate block body unavailable");
                        Block block = BlockSerializer.INSTANCE.deserialize(body);
                        if (!point.blockHash().equals(block.getHeader().getHeaderBody().getBlockHash())) {
                            throw new IllegalStateException("Candidate block hash mismatch");
                        }
                        return block;
                    }
                }

                @Override public List<Utxo> genesis() {
                    synchronized (lock) {
                        validate();
                        try { return indexes.get().scanGenesis(); }
                        catch (RocksDBException failure) { throw new IllegalStateException("Scan genesis read failed", failure); }
                    }
                }
            }, request, coverage, end);
        } catch (RocksDBException failure) {
            throw new IllegalStateException("Wallet scan unavailable", failure);
        }
    }

    public static void requireCanonicalPoint(ArchiveChainStateCapabilities chain, ChainPoint point) {
        if (point.blockNumber() == -1) return;
        if (chain == null) throw new IllegalStateException("Canonical block reader unavailable");
        var canonical = chain.getCanonicalBlockReference(point.blockNumber());
        if (canonical.isEmpty() || canonical.get().slot() != point.slot()
                || !Arrays.equals(canonical.get().blockHash(), HexUtil.decodeHexString(point.blockHash()))) {
            throw new WalletScanRollbackException("Scan cursor is no longer canonical; rollback and resume required");
        }
    }

}
