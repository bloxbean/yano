package com.bloxbean.cardano.yano.appchain.stdlib.contracts;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.model.Array;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.vds.mpf.rocksdb.RocksDbNodeStore;
import com.bloxbean.cardano.vds.mpf.rocksdb.gc.GcOptions;
import com.bloxbean.cardano.vds.mpf.rocksdb.gc.GcReport;
import com.bloxbean.cardano.vds.mpf.rocksdb.gc.strategy.OnDiskMarkSweepStrategy;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Comparator;

/** Reproducible ADR-028 M4 direct-MPF feasibility workload. */
public final class EpochStakeMpfBenchmark {
    private static final int PROOF_SAMPLE_COUNT = 3;

    private EpochStakeMpfBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) throw new IllegalArgumentException(
                "usage: REPORT ENTRY_COUNT CHUNK_ENTRIES [prune]");
        Path report = Path.of(args[0]);
        int entries = Integer.parseInt(args[1]);
        int chunkEntries = Integer.parseInt(args[2]);
        boolean prune = args.length == 4 && "prune".equals(args[3]);
        if (args.length == 4 && !prune) {
            throw new IllegalArgumentException("fourth argument must be prune");
        }
        if (entries <= 0 || chunkEntries <= 0
                || chunkEntries > EpochStakeContract.MAX_CHUNK_ENTRIES) {
            throw new IllegalArgumentException("invalid benchmark workload");
        }

        long heapBefore = usedHeap();
        long firstPassStarted = System.nanoTime();
        Generation firstPass = generate(entries, chunkEntries);
        long firstPassNanos = System.nanoTime() - firstPassStarted;
        long secondPassStarted = System.nanoTime();
        Generation secondPass = generate(entries, chunkEntries);
        long secondPassNanos = System.nanoTime() - secondPassStarted;
        if (firstPass.canonicalBytes != secondPass.canonicalBytes
                || !java.util.Arrays.equals(firstPass.datasetRoot, secondPass.datasetRoot)) {
            throw new IllegalStateException("epoch-stake generation passes disagree");
        }

        Path database = Files.createTempDirectory("yano-adr028-mpf-");
        byte[] stateRoot;
        long insertionNanos;
        long restartNanos;
        long proofNanos = 0;
        int maximumProofBytes = 0;
        int maximumProofSteps = 0;
        int maximumCombinedProofBytes = 0;
        long databaseBytesBeforePrune;
        long databaseBytesAfterDelete;
        long databaseBytes;
        long gcMarked = 0;
        long gcTotal = 0;
        long gcDeleted = 0;
        long gcMillis = 0;
        try {
            long insertionStarted = System.nanoTime();
            try (RocksDbNodeStore store = new RocksDbNodeStore(database.toString())) {
                MpfTrie trie = new MpfTrie(store);
                int chunkIndex = 0;
                for (int offset = 0; offset < entries; offset += chunkEntries) {
                    List<EpochStakeContract.Entry> chunk = entries(offset,
                            Math.min(entries, offset + chunkEntries));
                    try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
                        store.withBatch(batch, () -> {
                            for (EpochStakeContract.Entry entry : chunk) {
                                trie.put(EpochStakeContract.entryKey(
                                                500, entry.credType(), entry.credHash()),
                                        EpochStakeContract.encodeValue(entry));
                            }
                            return null;
                        });
                        store.db().write(options, batch);
                    }
                    if (++chunkIndex % 10 == 0 || chunkIndex == firstPass.chunkCount) {
                        System.out.printf("ADR-028 M4 MPF insertion: %d/%d chunks%n",
                                chunkIndex, firstPass.chunkCount);
                    }
                }
                EpochStakeContract.Manifest manifest = new EpochStakeContract.Manifest(
                        500, entries, chunkEntries, firstPass.chunkCount,
                        firstPass.datasetRoot);
                EpochStakeContract.Meta complete = new EpochStakeContract.Meta(
                        manifest, firstPass.chunkCount, true);
                try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
                    store.withBatch(batch, () -> {
                        trie.put(EpochStakeContract.metaKey(500),
                                EpochStakeContract.encodeMeta(complete));
                        return null;
                    });
                    store.db().write(options, batch);
                }
                stateRoot = trie.getRootHash();
            }
            insertionNanos = System.nanoTime() - insertionStarted;
            databaseBytesBeforePrune = directoryBytes(database);

            if (prune) {
                try (RocksDbNodeStore store = new RocksDbNodeStore(database.toString())) {
                    GcOptions options = new GcOptions();
                    options.deleteBatchSize = 20_000;
                    GcReport gc = new OnDiskMarkSweepStrategy().run(
                            store, null, ignored -> List.of(stateRoot), options);
                    gcMarked = gc.marked;
                    gcTotal = gc.total;
                    gcDeleted = gc.deleted;
                    gcMillis = gc.durationMillis;
                }
                databaseBytesAfterDelete = directoryBytes(database);
                try (RocksDbNodeStore store = new RocksDbNodeStore(database.toString())) {
                    store.db().compactRange(store.nodesHandle());
                }
            } else {
                databaseBytesAfterDelete = databaseBytesBeforePrune;
            }
            databaseBytes = directoryBytes(database);

            long restartStarted = System.nanoTime();
            try (RocksDbNodeStore store = new RocksDbNodeStore(database.toString())) {
                MpfTrie trie = new MpfTrie(store, stateRoot);
                restartNanos = System.nanoTime() - restartStarted;
                int[] samples = {0, entries / 2, entries - 1};
                byte[] completeProof = trie.getProofWire(
                        EpochStakeContract.metaKey(500)).orElseThrow();
                maximumProofSteps = Math.max(maximumProofSteps, proofSteps(completeProof));
                for (int sample : samples) {
                    EpochStakeContract.Entry entry = entry(sample);
                    byte[] key = EpochStakeContract.entryKey(500, entry.credType(), entry.credHash());
                    long started = System.nanoTime();
                    byte[] proof = trie.getProofWire(key).orElseThrow();
                    proofNanos += System.nanoTime() - started;
                    maximumProofBytes = Math.max(maximumProofBytes, proof.length);
                    maximumProofSteps = Math.max(maximumProofSteps, proofSteps(proof));
                    maximumCombinedProofBytes = Math.max(
                            maximumCombinedProofBytes, proof.length + completeProof.length);
                    if (!trie.verifyProofWire(stateRoot, key, EpochStakeContract.encodeValue(entry),
                            true, proof)) throw new IllegalStateException("inclusion proof failed");
                }
                byte[] absentKey = EpochStakeContract.entryKey(500, 1, credential(entries + 1L));
                byte[] absence = trie.getProofWire(absentKey).orElseThrow();
                if (!trie.verifyProofWire(stateRoot, absentKey, null, false, absence)) {
                    throw new IllegalStateException("absence proof failed");
                }
                maximumProofBytes = Math.max(maximumProofBytes, absence.length);
                maximumProofSteps = Math.max(maximumProofSteps, proofSteps(absence));
                maximumCombinedProofBytes = Math.max(
                        maximumCombinedProofBytes, absence.length + completeProof.length);
            }
        } finally {
            try (var paths = Files.walk(database)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); }
                    catch (java.io.IOException failure) {
                        throw new IllegalStateException("failed to remove benchmark database", failure);
                    }
                });
            }
        }
        long heapAfter = usedHeap();

        String json = "{\n"
                + "  \"profile\": \"mpf-blake2b256-v1\",\n"
                + "  \"pruningEnabled\": " + prune + ",\n"
                + "  \"entries\": " + entries + ",\n"
                + "  \"chunkEntries\": " + chunkEntries + ",\n"
                + "  \"chunkCount\": " + firstPass.chunkCount + ",\n"
                + "  \"canonicalChunkBytes\": " + firstPass.canonicalBytes + ",\n"
                + "  \"maximumChunkBytes\": " + firstPass.maximumChunkBytes + ",\n"
                + "  \"firstPassMillis\": " + millis(firstPassNanos) + ",\n"
                + "  \"secondPassMillis\": " + millis(secondPassNanos) + ",\n"
                + "  \"insertionMillis\": " + millis(insertionNanos) + ",\n"
                + "  \"restartMillis\": " + millis(restartNanos) + ",\n"
                + "  \"rocksDbBytesBeforePrune\": " + databaseBytesBeforePrune + ",\n"
                + "  \"rocksDbBytesAfterDelete\": " + databaseBytesAfterDelete + ",\n"
                + "  \"rocksDbBytes\": " + databaseBytes + ",\n"
                + "  \"gcMarkedNodes\": " + gcMarked + ",\n"
                + "  \"gcTotalNodes\": " + gcTotal + ",\n"
                + "  \"gcDeletedNodes\": " + gcDeleted + ",\n"
                + "  \"gcMillis\": " + gcMillis + ",\n"
                + "  \"sampleProofAverageMicros\": " + (proofNanos / PROOF_SAMPLE_COUNT / 1_000) + ",\n"
                + "  \"maximumProofBytes\": " + maximumProofBytes + ",\n"
                + "  \"maximumProofSteps\": " + maximumProofSteps + ",\n"
                + "  \"maximumCombinedProofBytes\": " + maximumCombinedProofBytes + ",\n"
                + "  \"heapGrowthBytes\": " + Math.max(0, heapAfter - heapBefore) + ",\n"
                + "  \"datasetRoot\": \"" + HexFormat.of().formatHex(firstPass.datasetRoot) + "\",\n"
                + "  \"stateRoot\": \"" + HexFormat.of().formatHex(stateRoot) + "\"\n"
                + "}\n";
        Files.createDirectories(report.toAbsolutePath().getParent());
        Files.writeString(report, json, StandardCharsets.UTF_8);
        System.out.println(json);
    }

    static Generation generate(int entries, int chunkEntries) {
        List<byte[]> chunkHashes = new ArrayList<>();
        long canonicalBytes = 0;
        int maximumChunkBytes = 0;
        for (int offset = 0; offset < entries; offset += chunkEntries) {
            List<EpochStakeContract.Entry> chunk = entries(offset,
                    Math.min(entries, offset + chunkEntries));
            byte[] encoded = EpochStakeContract.encodeChunk(new EpochStakeContract.Chunk(
                    500, new byte[32], chunkHashes.size(), chunk));
            canonicalBytes += encoded.length;
            maximumChunkBytes = Math.max(maximumChunkBytes, encoded.length);
            chunkHashes.add(EpochStakeContract.chunkHash(chunk));
        }
        return new Generation(canonicalBytes, maximumChunkBytes, chunkHashes.size(),
                EpochStakeContract.snapshotRoot(chunkHashes));
    }

    static long directoryBytes(Path directory) throws Exception {
        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile).mapToLong(path -> {
                try { return Files.size(path); }
                catch (java.io.IOException failure) {
                    throw new IllegalStateException("failed to measure benchmark database", failure);
                }
            }).sum();
        }
    }

    private static int proofSteps(byte[] proof) throws Exception {
        var roots = new CborDecoder(new ByteArrayInputStream(proof)).decode();
        if (roots.size() != 1 || !(roots.getFirst() instanceof Array steps)) {
            throw new IllegalStateException("MPF proof wire has the wrong shape");
        }
        return steps.getDataItems().size();
    }

    record Generation(long canonicalBytes, int maximumChunkBytes,
                      int chunkCount, byte[] datasetRoot) { }

    static List<EpochStakeContract.Entry> entries(int from, int to) {
        List<EpochStakeContract.Entry> result = new ArrayList<>(to - from);
        for (int index = from; index < to; index++) result.add(entry(index));
        return result;
    }

    static EpochStakeContract.Entry entry(long index) {
        return new EpochStakeContract.Entry(0, credential(index),
                BigInteger.valueOf(1_000_000L + index), pool(index % 1_000));
    }

    static byte[] credential(long value) {
        byte[] result = new byte[28];
        ByteBuffer.wrap(result, 20, 8).putLong(value);
        return result;
    }

    private static byte[] pool(long value) {
        byte[] result = new byte[28];
        ByteBuffer.wrap(result, 20, 8).putLong(value);
        return result;
    }

    static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    static long millis(long nanos) { return nanos / 1_000_000; }
}
