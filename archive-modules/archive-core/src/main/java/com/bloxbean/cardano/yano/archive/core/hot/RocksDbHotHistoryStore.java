package com.bloxbean.cardano.yano.archive.core.hot;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveReceipt;
import com.bloxbean.cardano.yano.api.BlockBodyRetentionBoundary;
import com.bloxbean.cardano.yano.archive.core.source.ArchiveSourceLease;
import com.bloxbean.cardano.yano.archive.core.worker.ArchiveProgress;
import com.bloxbean.cardano.yano.archive.core.worker.ArchiveProgressStore;
import com.bloxbean.cardano.yano.archive.core.worker.ArchiveTrack;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Dedicated history RocksDB with exact per-block undo; no core-state handle is shared. */
public final class RocksDbHotHistoryStore implements ArchiveProgressStore, BlockBodyRetentionBoundary, AutoCloseable {
    static { RocksDB.loadLibrary(); }
    private static final byte[] DATA = "d/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] UNDO = "u/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CHECKPOINT = "b/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PROGRESS = "p/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] RECEIPT = "r/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LEASE = "l/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] REQUIRED = "q/".getBytes(StandardCharsets.UTF_8);

    private final Options options;
    private final WriteOptions writes = new WriteOptions().setSync(true);
    private final RocksDB database;
    private final AtomicBoolean closed = new AtomicBoolean();

    public RocksDbHotHistoryStore(Path directory) {
        try {
            Files.createDirectories(directory.toAbsolutePath().normalize());
            options = new Options().setCreateIfMissing(true);
            database = RocksDB.open(options, directory.toString());
        } catch (Exception e) {
            throw new IllegalStateException("cannot open dedicated hot-history RocksDB", e);
        }
    }

    public synchronized void applyBlock(ArchiveDatasetId dataset, HotBlockCheckpoint block,
                                        List<HotHistoryMutation> mutations, ArchiveProgress progress) {
        applyBlocks(dataset, List.of(new HotBlockUpdate(block, mutations)), progress, null);
    }

    /** Applies a complete derived range, resolver state, receipt and cursor atomically. */
    public synchronized void applyBlocks(ArchiveDatasetId dataset, List<HotBlockUpdate> blocks,
                                         ArchiveProgress progress, ArchiveReceipt receipt) {
        requireOpen();
        if (blocks.isEmpty() || progress.dataset() != dataset
                || progress.coordinate() != blocks.getLast().checkpoint().blockNumber()) {
            throw new IllegalArgumentException("progress does not match hot block range");
        }
        if (receipt != null && (receipt.dataset() != dataset
                || receipt.backendGeneration() != progress.backendGeneration())) {
            throw new IllegalArgumentException("receipt does not match hot block range");
        }
        try (WriteBatch batch = new WriteBatch()) {
            java.util.Map<Key, byte[]> pending = new java.util.HashMap<>();
            for (HotBlockUpdate block : blocks) {
                List<Undo> undo = new ArrayList<>();
                for (HotHistoryMutation mutation : block.mutations()) {
                    byte[] physicalKey = dataKey(dataset, mutation.key());
                    Key key = new Key(physicalKey);
                    byte[] previous = pending.containsKey(key) ? pending.get(key) : database.get(physicalKey);
                    byte[] selected = mutation.value();
                    if (dataset == ArchiveDatasetId.UTXO_HISTORY
                            && HotArchiveRows.isAddressDimensionKey(mutation.key())) {
                        selected = HotArchiveRows.mergeAddressDimensionValue(previous, selected);
                        if (Arrays.equals(previous, selected)) continue;
                    }
                    undo.add(new Undo(physicalKey, previous));
                    pending.put(key, selected);
                    if (selected == null) batch.delete(physicalKey);
                    else batch.put(physicalKey, selected);
                }
                batch.put(undoKey(dataset, progress.track(), block.checkpoint().blockNumber()), encodeUndo(undo));
                batch.put(checkpointKey(dataset, progress.track(), block.checkpoint().blockNumber()),
                        encodeCheckpoint(block.checkpoint()));
            }
            batch.put(progressKey(dataset, progress.track()), encodeProgress(progress));
            advanceBlockBodyRequirement(batch, dataset, progress);
            if (receipt != null) batch.put(prefix(RECEIPT, receipt.jobId().toString()),
                    receipt.orderedDigest().getBytes(StandardCharsets.UTF_8));
            database.write(writes, batch);
        } catch (RocksDBException e) {
            throw new IllegalStateException("hot-history block apply failed", e);
        }
    }

    /** Seeds immutable resolver/dictionary values before canonical replay. */
    public synchronized void seed(ArchiveDatasetId dataset, List<HotHistoryMutation> mutations) {
        requireOpen();
        try (WriteBatch batch = new WriteBatch()) {
            for (HotHistoryMutation mutation : mutations) {
                if (mutation.value() == null) throw new IllegalArgumentException("seed value is required");
                byte[] key = dataKey(dataset, mutation.key());
                byte[] existing = database.get(key);
                if (existing != null && !Arrays.equals(existing, mutation.value())) {
                    throw new IllegalStateException("resolver seed conflicts with durable state");
                }
                batch.put(key, mutation.value());
            }
            database.write(writes, batch);
        } catch (RocksDBException e) {
            throw new IllegalStateException("hot-history seed failed", e);
        }
    }

    public synchronized void rollbackTo(ArchiveDatasetId dataset, ArchiveTrack track, long commonBlock) {
        requireOpen();
        ArchiveProgress progress = load(dataset, track).orElse(null);
        if (progress == null || progress.coordinate() <= commonBlock) return;
        try (WriteBatch batch = new WriteBatch()) {
            for (long block = progress.coordinate(); block > commonBlock; block--) {
                byte[] encoded = database.get(undoKey(dataset, track, block));
                if (encoded == null) throw new IllegalStateException("missing exact undo for block " + block);
                List<Undo> undoEntries = decodeUndo(encoded);
                for (int index = undoEntries.size() - 1; index >= 0; index--) {
                    Undo undo = undoEntries.get(index);
                    if (undo.previous == null) batch.delete(undo.key);
                    else batch.put(undo.key, undo.previous);
                }
                batch.delete(undoKey(dataset, track, block));
                batch.delete(checkpointKey(dataset, track, block));
            }
            if (commonBlock < 0) {
                batch.delete(progressKey(dataset, track));
            } else {
                HotBlockCheckpoint checkpoint = readCheckpoint(dataset, track, commonBlock)
                        .orElseThrow(() -> new IllegalStateException("missing checkpoint at rollback target"));
                batch.put(progressKey(dataset, track), encodeProgress(new ArchiveProgress(dataset, track,
                        commonBlock, checkpoint.slot(), checkpoint.blockHash(), progress.backendGeneration())));
            }
            if (track == ArchiveTrack.BACKFILL && dataset.sourceKind()
                    == com.bloxbean.cardano.yano.archive.api.SourceKind.BLOCK) {
                batch.put(requirementKey(dataset), longBytes(Math.addExact(commonBlock, 1)));
            }
            database.write(writes, batch);
        } catch (RocksDBException e) {
            throw new IllegalStateException("hot-history rollback failed", e);
        }
    }

    /**
     * Removes every derived block at or after an activation anchor while
     * preserving immutable genesis seeds. This is the safe re-activation path
     * when a rollback crosses the anchor or a committed archive job.
     */
    public synchronized void resetTrackFrom(ArchiveDatasetId dataset, ArchiveTrack track, long firstBlock) {
        requireOpen();
        ArchiveProgress progress = load(dataset, track).orElse(null);
        if (progress == null || progress.coordinate() < firstBlock) return;
        try (WriteBatch batch = new WriteBatch()) {
            for (long block = progress.coordinate(); block >= firstBlock; block--) {
                byte[] encoded = database.get(undoKey(dataset, track, block));
                if (encoded == null) throw new IllegalStateException("missing exact undo for block " + block);
                List<Undo> undoEntries = decodeUndo(encoded);
                for (int index = undoEntries.size() - 1; index >= 0; index--) {
                    Undo undo = undoEntries.get(index);
                    if (undo.previous == null) batch.delete(undo.key);
                    else batch.put(undo.key, undo.previous);
                }
                batch.delete(undoKey(dataset, track, block));
                batch.delete(checkpointKey(dataset, track, block));
            }
            batch.delete(progressKey(dataset, track));
            if (track == ArchiveTrack.BACKFILL && dataset.sourceKind()
                    == com.bloxbean.cardano.yano.archive.api.SourceKind.BLOCK) {
                batch.put(requirementKey(dataset), longBytes(firstBlock));
            }
            database.write(writes, batch);
        } catch (RocksDBException e) {
            throw new IllegalStateException("hot-history activation reset failed", e);
        }
    }

    public synchronized void pruneUndoThrough(ArchiveDatasetId dataset, ArchiveTrack track, long blockInclusive) {
        requireOpen();
        try (WriteBatch batch = new WriteBatch(); var iterator = database.newIterator()) {
            byte[] prefix = prefix(UNDO, dataset.name() + "/" + track.name() + "/");
            for (iterator.seek(prefix); iterator.isValid() && startsWith(iterator.key(), prefix); iterator.next()) {
                long block = ByteBuffer.wrap(iterator.key(), prefix.length, Long.BYTES).getLong();
                if (block <= blockInclusive) batch.delete(iterator.key());
            }
            byte[] checkpoints = prefix(CHECKPOINT, dataset.name() + "/" + track.name() + "/");
            for (iterator.seek(checkpoints); iterator.isValid() && startsWith(iterator.key(), checkpoints);
                 iterator.next()) {
                long block = ByteBuffer.wrap(iterator.key(), checkpoints.length, Long.BYTES).getLong();
                if (block <= blockInclusive) batch.delete(iterator.key());
            }
            database.write(writes, batch);
        } catch (RocksDBException e) {
            throw new IllegalStateException("hot-history undo prune failed", e);
        }
    }

    public synchronized Optional<HotBlockCheckpoint> checkpoint(ArchiveDatasetId dataset,
                                                                 ArchiveTrack track, long block) {
        requireOpen();
        try { return readCheckpoint(dataset, track, block); }
        catch (RocksDBException e) { throw new IllegalStateException("hot-history checkpoint read failed", e); }
    }

    public Optional<byte[]> get(ArchiveDatasetId dataset, byte[] logicalKey) {
        requireOpen();
        try { return Optional.ofNullable(database.get(dataKey(dataset, logicalKey))); }
        catch (RocksDBException e) { throw new IllegalStateException("hot-history read failed", e); }
    }

    public List<HotHistorySnapshot.Entry> scanDataPrefix(ArchiveDatasetId dataset, byte[] logicalPrefix) {
        requireOpen();
        byte[] physicalPrefix = dataKey(dataset, logicalPrefix);
        byte[] datasetPrefix = dataKey(dataset, new byte[0]);
        List<HotHistorySnapshot.Entry> result = new ArrayList<>();
        try (var iterator = database.newIterator()) {
            for (iterator.seek(physicalPrefix); iterator.isValid() && startsWith(iterator.key(), physicalPrefix);
                 iterator.next()) {
                result.add(new HotHistorySnapshot.Entry(
                        Arrays.copyOfRange(iterator.key(), datasetPrefix.length, iterator.key().length),
                        iterator.value()));
            }
            return List.copyOf(result);
        }
    }

    /** Deletes already-promoted live rows; active RocksDB snapshots retain their old view. */
    public synchronized void deleteData(ArchiveDatasetId dataset, Collection<byte[]> logicalKeys) {
        requireOpen();
        try (WriteBatch batch = new WriteBatch()) {
            for (byte[] key : logicalKeys) batch.delete(dataKey(dataset, key));
            database.write(writes, batch);
        } catch (RocksDBException e) { throw new IllegalStateException("hot-history promotion cleanup failed", e); }
    }

    /** Deletes one private logical namespace without touching other dataset state. */
    public synchronized void deleteDataPrefix(ArchiveDatasetId dataset, byte[] logicalPrefix) {
        requireOpen();
        byte[] physicalPrefix = dataKey(dataset, logicalPrefix);
        try (WriteBatch batch = new WriteBatch(); var iterator = database.newIterator()) {
            for (iterator.seek(physicalPrefix); iterator.isValid() && startsWith(iterator.key(), physicalPrefix);
                 iterator.next()) batch.delete(iterator.key());
            database.write(writes, batch);
        } catch (RocksDBException e) { throw new IllegalStateException("hot-history prefix delete failed", e); }
    }

    /** Fail-safe live reactivation that does not depend on already-pruned undo entries. */
    public synchronized void clearTrack(ArchiveDatasetId dataset, ArchiveTrack track,
                                        Collection<byte[]> logicalDataPrefixes) {
        requireOpen();
        try (WriteBatch batch = new WriteBatch(); var iterator = database.newIterator()) {
            for (byte[] logical : logicalDataPrefixes) {
                byte[] selected = dataKey(dataset, logical);
                for (iterator.seek(selected); iterator.isValid() && startsWith(iterator.key(), selected);
                     iterator.next()) batch.delete(iterator.key());
            }
            for (byte[] selected : List.of(prefix(UNDO, dataset.name() + "/" + track.name() + "/"),
                    prefix(CHECKPOINT, dataset.name() + "/" + track.name() + "/"))) {
                for (iterator.seek(selected); iterator.isValid() && startsWith(iterator.key(), selected);
                     iterator.next()) batch.delete(iterator.key());
            }
            batch.delete(progressKey(dataset, track));
            database.write(writes, batch);
        } catch (RocksDBException e) { throw new IllegalStateException("hot-history track clear failed", e); }
    }

    public HotHistorySnapshot snapshot() {
        requireOpen();
        return new HotHistorySnapshot(database);
    }

    public ArchiveSourceLease acquireBlockBodyLease(long startBlock, long endBlock, Instant expiresAt) {
        requireOpen();
        if (startBlock < 0 || endBlock < startBlock || !expiresAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("invalid block-body lease");
        }
        UUID id = UUID.randomUUID();
        putLease(id, startBlock, endBlock, expiresAt);
        return new DurableLease(id, startBlock, endBlock, expiresAt);
    }

    /** Durable low watermark for the next block a configured backfill consumer needs. */
    public synchronized void requireBlockBodiesFrom(ArchiveDatasetId dataset, long blockNumber) {
        requireOpen();
        if (dataset.sourceKind() != com.bloxbean.cardano.yano.archive.api.SourceKind.BLOCK
                || blockNumber < 0) throw new IllegalArgumentException("invalid block-body requirement");
        try {
            database.put(writes, requirementKey(dataset), longBytes(blockNumber));
        } catch (RocksDBException e) {
            throw new IllegalStateException("block-body requirement write failed", e);
        }
    }

    public synchronized void releaseBlockBodyRequirement(ArchiveDatasetId dataset) {
        requireOpen();
        try {
            database.delete(writes, requirementKey(dataset));
        } catch (RocksDBException e) {
            throw new IllegalStateException("block-body requirement delete failed", e);
        }
    }

    @Override
    public synchronized OptionalLong oldestRequiredBlockNumber() {
        requireOpen();
        long now = Instant.now().toEpochMilli();
        long oldest = Long.MAX_VALUE;
        List<byte[]> expired = new ArrayList<>();
        try (var iterator = database.newIterator()) {
            for (iterator.seek(REQUIRED); iterator.isValid() && startsWith(iterator.key(), REQUIRED);
                 iterator.next()) {
                if (iterator.value().length == Long.BYTES) {
                    oldest = Math.min(oldest, ByteBuffer.wrap(iterator.value()).getLong());
                }
            }
            for (iterator.seek(LEASE); iterator.isValid() && startsWith(iterator.key(), LEASE); iterator.next()) {
                ByteBuffer value = ByteBuffer.wrap(iterator.value());
                long start = value.getLong();
                value.getLong();
                long expiry = value.getLong();
                if (expiry <= now) expired.add(iterator.key().clone());
                else oldest = Math.min(oldest, start);
            }
        }
        if (!expired.isEmpty()) {
            try (WriteBatch batch = new WriteBatch()) {
                expired.forEach(key -> { try { batch.delete(key); } catch (RocksDBException e) { throw new IllegalStateException(e); } });
                database.write(writes, batch);
            } catch (RocksDBException e) { throw new IllegalStateException("expired lease cleanup failed", e); }
        }
        return oldest == Long.MAX_VALUE ? OptionalLong.empty() : OptionalLong.of(oldest);
    }

    @Override
    public Optional<ArchiveProgress> load(ArchiveDatasetId dataset, ArchiveTrack track) {
        requireOpen();
        try {
            byte[] value = database.get(progressKey(dataset, track));
            return value == null ? Optional.empty() : Optional.of(decodeProgress(dataset, track, value));
        } catch (RocksDBException e) {
            throw new IllegalStateException("hot-history progress read failed", e);
        }
    }

    @Override
    public synchronized void save(ArchiveProgress progress, ArchiveReceipt receipt) {
        requireOpen();
        if (receipt.dataset() != progress.dataset() || receipt.backendGeneration() != progress.backendGeneration()) {
            throw new IllegalArgumentException("receipt does not match progress");
        }
        try (WriteBatch batch = new WriteBatch()) {
            batch.put(progressKey(progress.dataset(), progress.track()), encodeProgress(progress));
            advanceBlockBodyRequirement(batch, progress.dataset(), progress);
            batch.put(prefix(RECEIPT, receipt.jobId().toString()), receipt.orderedDigest().getBytes(StandardCharsets.UTF_8));
            database.write(writes, batch);
        } catch (RocksDBException e) {
            throw new IllegalStateException("hot-history receipt/progress save failed", e);
        }
    }

    /** Advances a backfill cursor through a range already atomically published by live promotion. */
    public synchronized void saveCoveredProgress(ArchiveProgress progress) {
        requireOpen();
        if (progress.track() != ArchiveTrack.BACKFILL
                || progress.dataset().sourceKind() != com.bloxbean.cardano.yano.archive.api.SourceKind.BLOCK) {
            throw new IllegalArgumentException("covered progress must be a block backfill cursor");
        }
        try (WriteBatch batch = new WriteBatch()) {
            batch.put(progressKey(progress.dataset(), progress.track()), encodeProgress(progress));
            advanceBlockBodyRequirement(batch, progress.dataset(), progress);
            database.write(writes, batch);
        } catch (RocksDBException e) {
            throw new IllegalStateException("hot-history covered progress save failed", e);
        }
    }

    private Optional<HotBlockCheckpoint> readCheckpoint(ArchiveDatasetId dataset, ArchiveTrack track, long block) throws RocksDBException {
        byte[] value = database.get(checkpointKey(dataset, track, block));
        return value == null ? Optional.empty() : Optional.of(decodeCheckpoint(value));
    }

    private byte[] dataKey(ArchiveDatasetId dataset, byte[] logical) { return concat(prefix(DATA, dataset.name() + "/"), logical); }
    private byte[] undoKey(ArchiveDatasetId dataset, ArchiveTrack track, long block) { return concat(prefix(UNDO, dataset.name() + "/" + track.name() + "/"), longBytes(block)); }
    private byte[] checkpointKey(ArchiveDatasetId dataset, ArchiveTrack track, long block) { return concat(prefix(CHECKPOINT, dataset.name() + "/" + track.name() + "/"), longBytes(block)); }
    private byte[] progressKey(ArchiveDatasetId dataset, ArchiveTrack track) { return prefix(PROGRESS, dataset.name() + "/" + track.name()); }
    private byte[] requirementKey(ArchiveDatasetId dataset) { return prefix(REQUIRED, dataset.name()); }

    private void advanceBlockBodyRequirement(WriteBatch batch, ArchiveDatasetId dataset,
                                             ArchiveProgress progress) throws RocksDBException {
        if (progress.track() == ArchiveTrack.BACKFILL
                && dataset.sourceKind() == com.bloxbean.cardano.yano.archive.api.SourceKind.BLOCK) {
            batch.put(requirementKey(dataset), longBytes(Math.addExact(progress.coordinate(), 1)));
        }
    }

    private synchronized void putLease(UUID id, long start, long end, Instant expiry) {
        try {
            database.put(writes, prefix(LEASE, id.toString()), ByteBuffer.allocate(Long.BYTES * 3)
                    .putLong(start).putLong(end).putLong(expiry.toEpochMilli()).array());
        } catch (RocksDBException e) { throw new IllegalStateException("block-body lease write failed", e); }
    }

    private synchronized void deleteLease(UUID id) {
        try { database.delete(writes, prefix(LEASE, id.toString())); }
        catch (RocksDBException e) { throw new IllegalStateException("block-body lease release failed", e); }
    }

    private byte[] encodeUndo(List<Undo> undo) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(undo.size());
            for (Undo item : undo) { writeBytes(out, item.key); writeBytes(out, item.previous); }
            return bytes.toByteArray();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private List<Undo> decodeUndo(byte[] encoded) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            int count = in.readInt();
            if (count < 0 || count > 1_000_000) throw new IllegalStateException("invalid undo count");
            List<Undo> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) result.add(new Undo(readBytes(in, false), readBytes(in, true)));
            if (in.available() != 0) throw new IllegalStateException("trailing undo bytes");
            return result;
        } catch (Exception e) { throw new IllegalStateException("invalid hot-history undo", e); }
    }

    private byte[] encodeCheckpoint(HotBlockCheckpoint checkpoint) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeLong(checkpoint.blockNumber()); out.writeLong(checkpoint.slot());
            writeBytes(out, checkpoint.blockHash()); writeBytes(out, checkpoint.parentHash());
            return bytes.toByteArray();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private HotBlockCheckpoint decodeCheckpoint(byte[] encoded) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            return new HotBlockCheckpoint(in.readLong(), in.readLong(), readBytes(in, false), readBytes(in, false));
        } catch (Exception e) { throw new IllegalStateException("invalid hot checkpoint", e); }
    }

    private byte[] encodeProgress(ArchiveProgress progress) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeLong(progress.coordinate()); out.writeLong(progress.slot());
            writeBytes(out, progress.blockHash()); out.writeLong(progress.backendGeneration());
            return bytes.toByteArray();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private ArchiveProgress decodeProgress(ArchiveDatasetId dataset, ArchiveTrack track, byte[] encoded) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            return new ArchiveProgress(dataset, track, in.readLong(), in.readLong(), readBytes(in, false), in.readLong());
        } catch (Exception e) { throw new IllegalStateException("invalid hot progress", e); }
    }

    private void writeBytes(DataOutputStream out, byte[] value) throws java.io.IOException {
        if (value == null) { out.writeInt(-1); return; }
        out.writeInt(value.length); out.write(value);
    }

    private byte[] readBytes(DataInputStream in, boolean nullable) throws java.io.IOException {
        int length = in.readInt();
        if (length == -1 && nullable) return null;
        if (length < 0 || length > 64 * 1024 * 1024) throw new java.io.IOException("invalid byte length");
        return in.readNBytes(length);
    }

    private byte[] prefix(byte[] type, String suffix) { return concat(type, suffix.getBytes(StandardCharsets.UTF_8)); }
    private byte[] longBytes(long value) { return ByteBuffer.allocate(Long.BYTES).putLong(value).array(); }
    private byte[] concat(byte[] left, byte[] right) { byte[] all = Arrays.copyOf(left, left.length + right.length); System.arraycopy(right, 0, all, left.length, right.length); return all; }
    private boolean startsWith(byte[] value, byte[] prefix) { return value.length >= prefix.length && Arrays.equals(value, 0, prefix.length, prefix, 0, prefix.length); }
    private void requireOpen() { if (closed.get()) throw new IllegalStateException("hot-history store is closed"); }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        database.close(); writes.close(); options.close();
    }

    private record Undo(byte[] key, byte[] previous) { }

    private record Key(byte[] bytes) {
        private Key { bytes = bytes.clone(); }
        @Override public boolean equals(Object other) { return other instanceof Key key && Arrays.equals(bytes, key.bytes); }
        @Override public int hashCode() { return Arrays.hashCode(bytes); }
    }

    private final class DurableLease implements ArchiveSourceLease {
        private final UUID id;
        private final long start;
        private final long end;
        private Instant expiry;
        private boolean released;

        private DurableLease(UUID id, long start, long end, Instant expiry) {
            this.id = id; this.start = start; this.end = end; this.expiry = expiry;
        }
        public UUID leaseId() { return id; }
        public Instant expiresAt() { return expiry; }
        public synchronized ArchiveSourceLease renew(Instant newExpiry) {
            if (released || !newExpiry.isAfter(Instant.now())) throw new IllegalStateException("invalid lease renewal");
            putLease(id, start, end, newExpiry); expiry = newExpiry; return this;
        }
        public synchronized void close() { if (!released) { released = true; deleteLease(id); } }
    }
}
