package com.bloxbean.cardano.yano.appchain.stdlib.contracts;

import com.bloxbean.cardano.vds.jmt.JellyfishMerkleTree;
import com.bloxbean.cardano.vds.jmt.JmtProfile;
import com.bloxbean.cardano.vds.jmt.rocksdb.RocksDbJmtStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reproducible ADR-028 classic-JMT off-chain feasibility workload. */
public final class EpochStakeJmtBenchmark {
    private static final int PROOF_SAMPLE_COUNT = 3;

    private EpochStakeJmtBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("usage: REPORT ENTRY_COUNT CHUNK_ENTRIES");
        }
        Path report = Path.of(args[0]);
        int entries = Integer.parseInt(args[1]);
        int chunkEntries = Integer.parseInt(args[2]);
        if (entries <= 0 || chunkEntries <= 0
                || chunkEntries > EpochStakeContract.MAX_CHUNK_ENTRIES) {
            throw new IllegalArgumentException("invalid benchmark workload");
        }

        long heapBefore = EpochStakeMpfBenchmark.usedHeap();
        long firstPassStarted = System.nanoTime();
        EpochStakeMpfBenchmark.Generation firstPass =
                EpochStakeMpfBenchmark.generate(entries, chunkEntries);
        long firstPassNanos = System.nanoTime() - firstPassStarted;
        long secondPassStarted = System.nanoTime();
        EpochStakeMpfBenchmark.Generation secondPass =
                EpochStakeMpfBenchmark.generate(entries, chunkEntries);
        long secondPassNanos = System.nanoTime() - secondPassStarted;
        if (firstPass.canonicalBytes() != secondPass.canonicalBytes()
                || !java.util.Arrays.equals(firstPass.datasetRoot(), secondPass.datasetRoot())) {
            throw new IllegalStateException("epoch-stake generation passes disagree");
        }

        Path database = Files.createTempDirectory("yano-adr028-jmt-");
        byte[] stateRoot;
        long finalVersion;
        long insertionNanos;
        long restartNanos;
        long proofNanos = 0;
        int maximumProofBytes = 0;
        int maximumCombinedProofBytes = 0;
        long databaseBytes;
        try {
            long insertionStarted = System.nanoTime();
            RocksDbJmtStore.Options options = RocksDbJmtStore.Options.builder()
                    .disableWalForBatches(false)
                    .syncOnCommit(false)
                    .syncOnPrune(false)
                    .syncOnTruncate(false)
                    .build();
            try (RocksDbJmtStore store = RocksDbJmtStore.open(database.toString(), options)) {
                JellyfishMerkleTree tree = new JellyfishMerkleTree(
                        store, JmtProfile.classicBlake2b256V1());
                long version = 0;
                byte[] root = new byte[32];
                int chunkIndex = 0;
                for (int offset = 0; offset < entries; offset += chunkEntries) {
                    Map<byte[], byte[]> updates = new LinkedHashMap<>();
                    for (EpochStakeContract.Entry entry : EpochStakeMpfBenchmark.entries(
                            offset, Math.min(entries, offset + chunkEntries))) {
                        updates.put(EpochStakeContract.entryKey(
                                        500, entry.credType(), entry.credHash()),
                                EpochStakeContract.encodeValue(entry));
                    }
                    root = tree.put(++version, updates).rootHash();
                    if (++chunkIndex % 10 == 0 || chunkIndex == firstPass.chunkCount()) {
                        System.out.printf("ADR-028 JMT insertion: %d/%d chunks%n",
                                chunkIndex, firstPass.chunkCount());
                    }
                }
                EpochStakeContract.Manifest manifest = new EpochStakeContract.Manifest(
                        500, entries, chunkEntries, firstPass.chunkCount(),
                        firstPass.datasetRoot());
                EpochStakeContract.Meta complete = new EpochStakeContract.Meta(
                        manifest, firstPass.chunkCount(), true);
                root = tree.put(++version, Map.of(
                        EpochStakeContract.metaKey(500),
                        EpochStakeContract.encodeMeta(complete))).rootHash();
                stateRoot = root;
                finalVersion = version;
            }
            insertionNanos = System.nanoTime() - insertionStarted;
            databaseBytes = EpochStakeMpfBenchmark.directoryBytes(database);

            long restartStarted = System.nanoTime();
            try (RocksDbJmtStore store = RocksDbJmtStore.open(database.toString())) {
                JellyfishMerkleTree tree = new JellyfishMerkleTree(
                        store, JmtProfile.classicBlake2b256V1());
                byte[] reopenedRoot = store.rootHash(finalVersion).orElseThrow();
                restartNanos = System.nanoTime() - restartStarted;
                if (!java.util.Arrays.equals(stateRoot, reopenedRoot)) {
                    throw new IllegalStateException("JMT root changed after restart");
                }
                int[] samples = {0, entries / 2, entries - 1};
                byte[] completeKey = EpochStakeContract.metaKey(500);
                byte[] completeValue = EpochStakeContract.encodeMeta(new EpochStakeContract.Meta(
                        new EpochStakeContract.Manifest(500, entries, chunkEntries,
                                firstPass.chunkCount(), firstPass.datasetRoot()),
                        firstPass.chunkCount(), true));
                byte[] completeProof = tree.getProofWire(completeKey, finalVersion).orElseThrow();
                if (!tree.verifyProofWire(stateRoot, completeKey, completeValue,
                        true, completeProof)) {
                    throw new IllegalStateException("completeness proof failed");
                }
                for (int sample : samples) {
                    EpochStakeContract.Entry entry = EpochStakeMpfBenchmark.entry(sample);
                    byte[] key = EpochStakeContract.entryKey(
                            500, entry.credType(), entry.credHash());
                    long started = System.nanoTime();
                    byte[] proof = tree.getProofWire(key, finalVersion).orElseThrow();
                    proofNanos += System.nanoTime() - started;
                    maximumProofBytes = Math.max(maximumProofBytes, proof.length);
                    maximumCombinedProofBytes = Math.max(
                            maximumCombinedProofBytes, proof.length + completeProof.length);
                    if (!tree.verifyProofWire(stateRoot, key,
                            EpochStakeContract.encodeValue(entry), true, proof)) {
                        throw new IllegalStateException("inclusion proof failed");
                    }
                }
                byte[] absentKey = EpochStakeContract.entryKey(
                        500, 1, EpochStakeMpfBenchmark.credential(entries + 1L));
                byte[] absence = tree.getProofWire(absentKey, finalVersion).orElseThrow();
                if (!tree.verifyProofWire(stateRoot, absentKey, null, false, absence)) {
                    throw new IllegalStateException("absence proof failed");
                }
                maximumProofBytes = Math.max(maximumProofBytes, absence.length);
                maximumCombinedProofBytes = Math.max(
                        maximumCombinedProofBytes, absence.length + completeProof.length);
            }
        } finally {
            try (var paths = Files.walk(database)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (java.io.IOException failure) {
                        throw new IllegalStateException(
                                "failed to remove benchmark database", failure);
                    }
                });
            }
        }
        long heapAfter = EpochStakeMpfBenchmark.usedHeap();

        String json = "{\n"
                + "  \"profile\": \"jmt-blake2b256-v1\",\n"
                + "  \"verificationTarget\": \"off-chain-only\",\n"
                + "  \"entries\": " + entries + ",\n"
                + "  \"chunkEntries\": " + chunkEntries + ",\n"
                + "  \"chunkCount\": " + firstPass.chunkCount() + ",\n"
                + "  \"treeVersions\": " + finalVersion + ",\n"
                + "  \"canonicalChunkBytes\": " + firstPass.canonicalBytes() + ",\n"
                + "  \"maximumChunkBytes\": " + firstPass.maximumChunkBytes() + ",\n"
                + "  \"firstPassMillis\": " + EpochStakeMpfBenchmark.millis(firstPassNanos) + ",\n"
                + "  \"secondPassMillis\": " + EpochStakeMpfBenchmark.millis(secondPassNanos) + ",\n"
                + "  \"insertionMillis\": " + EpochStakeMpfBenchmark.millis(insertionNanos) + ",\n"
                + "  \"restartMillis\": " + EpochStakeMpfBenchmark.millis(restartNanos) + ",\n"
                + "  \"rocksDbBytes\": " + databaseBytes + ",\n"
                + "  \"sampleProofAverageMicros\": "
                + (proofNanos / PROOF_SAMPLE_COUNT / 1_000) + ",\n"
                + "  \"maximumProofBytes\": " + maximumProofBytes + ",\n"
                + "  \"maximumCombinedProofBytes\": " + maximumCombinedProofBytes + ",\n"
                + "  \"heapGrowthBytes\": " + Math.max(0, heapAfter - heapBefore) + ",\n"
                + "  \"datasetRoot\": \""
                + HexFormat.of().formatHex(firstPass.datasetRoot()) + "\",\n"
                + "  \"stateRoot\": \"" + HexFormat.of().formatHex(stateRoot) + "\"\n"
                + "}\n";
        Files.createDirectories(report.toAbsolutePath().getParent());
        Files.writeString(report, json, StandardCharsets.UTF_8);
        System.out.println(json);
    }
}
