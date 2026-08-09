package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yano.api.appchain.l1view.EpochObservationManifest;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochBoundary;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochObservationSink;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochObserver;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochState;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochStakeContract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Two-pass, bounded canonical end-of-epoch stake observer. */
public final class EpochStakeObserver implements L1EpochObserver {
    private final String observerId;
    private final int chunkEntries;

    public EpochStakeObserver(String observerId, int chunkEntries) {
        if (observerId == null || observerId.isBlank()) {
            throw new IllegalArgumentException("epoch-stake observer id is required");
        }
        if (chunkEntries <= 0 || chunkEntries > EpochStakeContract.MAX_CHUNK_ENTRIES) {
            throw new IllegalArgumentException("epoch-stake chunk-entries must be between 1 and 25000");
        }
        this.observerId = observerId;
        this.chunkEntries = chunkEntries;
    }

    @Override public String observerId() { return observerId; }

    @Override
    public EpochObservationManifest prepare(L1EpochBoundary boundary, L1EpochState state) {
        long epoch = boundary.previousEpoch();
        requireSnapshot(state, epoch);
        ChunkAccumulator accumulator = new ChunkAccumulator(chunkEntries, null, null);
        state.forEachStakeEntry(epoch, accumulator::add);
        accumulator.finish();
        return new EpochObservationManifest(EpochObservationManifest.VERSION, observerId,
                boundary.previousEpoch(), boundary.newEpoch(), epoch,
                accumulator.totalEntries, chunkEntries, accumulator.chunkHashes.size(),
                EpochStakeContract.snapshotRoot(accumulator.chunkHashes));
    }

    @Override
    public void writeObservations(EpochObservationManifest manifest,
                                  L1EpochState state,
                                  L1EpochObservationSink sink) {
        requireSnapshot(state, manifest.datasetEpoch());
        EpochStakeContract.Manifest claimManifest = new EpochStakeContract.Manifest(
                manifest.datasetEpoch(), manifest.totalEntries(), manifest.chunkEntries(),
                manifest.chunkCount(), manifest.snapshotRoot());
        sink.write(0, EpochStakeContract.encodeManifest(claimManifest));
        ChunkAccumulator accumulator = new ChunkAccumulator(chunkEntries, claimManifest, sink);
        state.forEachStakeEntry(manifest.datasetEpoch(), accumulator::add);
        accumulator.finish();
        byte[] root = EpochStakeContract.snapshotRoot(accumulator.chunkHashes);
        if (accumulator.totalEntries != manifest.totalEntries()
                || accumulator.chunkHashes.size() != manifest.chunkCount()
                || !Arrays.equals(root, manifest.snapshotRoot())) {
            throw new IllegalStateException("Epoch stake changed between observer passes");
        }
    }

    @Override public Map<String, Object> status() {
        return Map.of("dataset", "epoch-stake", "wireVersion", 1,
                "snapshotSemantics", "end-of-epoch", "chunkEntries", chunkEntries);
    }

    private static void requireSnapshot(L1EpochState state, long epoch) {
        if (state.previousEpoch() != epoch || !state.hasStakeSnapshot(epoch)) {
            throw new IllegalStateException("End-of-epoch stake snapshot is unavailable for epoch " + epoch);
        }
    }

    private static final class ChunkAccumulator {
        private final int chunkEntries;
        private final EpochStakeContract.Manifest manifest;
        private final L1EpochObservationSink sink;
        private final List<EpochStakeContract.Entry> entries;
        private final List<byte[]> chunkHashes = new ArrayList<>();
        private EpochStakeContract.Entry previous;
        private long totalEntries;

        private ChunkAccumulator(int chunkEntries, EpochStakeContract.Manifest manifest,
                                 L1EpochObservationSink sink) {
            this.chunkEntries = chunkEntries;
            this.manifest = manifest;
            this.sink = sink;
            this.entries = new ArrayList<>(chunkEntries);
        }

        private void add(int credType, byte[] credHash, java.math.BigInteger coin, byte[] poolHash) {
            EpochStakeContract.Entry entry = new EpochStakeContract.Entry(
                    credType, credHash, coin, poolHash);
            if (previous != null && EpochStakeContract.compare(previous, entry) >= 0) {
                throw new IllegalStateException("Host stake iterator is not in canonical order");
            }
            previous = entry;
            entries.add(entry);
            totalEntries++;
            if (entries.size() == chunkEntries) flush();
        }

        private void finish() {
            if (!entries.isEmpty()) flush();
        }

        private void flush() {
            List<EpochStakeContract.Entry> chunk = List.copyOf(entries);
            chunkHashes.add(EpochStakeContract.chunkHash(chunk));
            if (sink != null) {
                int index = chunkHashes.size() - 1;
                sink.write(index + 1, EpochStakeContract.encodeChunk(
                        new EpochStakeContract.Chunk(manifest.epoch(),
                                manifest.snapshotRoot(), index, chunk)));
            }
            entries.clear();
        }
    }
}
