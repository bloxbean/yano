package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.vds.jmt.JellyfishMerkleTree;
import com.bloxbean.cardano.vds.jmt.JmtProfile;
import com.bloxbean.cardano.vds.jmt.store.InMemoryJmtStore;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.state.CandidateState;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;
import com.bloxbean.cardano.yano.api.appchain.state.StateProof;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.rocksdb.WriteBatch;
import org.slf4j.helpers.NOPLogger;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reproducible, non-gating ADR-025 Phase 3 durable-runtime measurement. */
public final class ClassicJmtRuntimeBenchmark {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final StateCommitmentIdentity IDENTITY =
            StateCommitmentIdentity.explicit(
                    StateCommitmentProfiles.CLASSIC_JMT, repeated(0x33, 32));

    private ClassicJmtRuntimeBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "Expected report path, key count, batch size, and proof samples");
        }
        Path reportPath = Path.of(args[0]);
        int keyCount = positive(args[1], "key count");
        int batchSize = positive(args[2], "batch size");
        int proofSamples = positive(args[3], "proof samples");
        Path workspace = Files.createTempDirectory("adr025-classic-jmt-runtime-");
        try {
            benchmark(reportPath, keyCount, batchSize, proofSamples, workspace);
        } finally {
            deleteRecursively(workspace);
        }
    }

    private static void benchmark(
            Path reportPath,
            int keyCount,
            int batchSize,
            int proofSamples,
            Path workspace
    ) throws Exception {
        Path ledgerPath = workspace.resolve("ledger");
        Path snapshotPath = workspace.resolve("snapshot");

        List<Long> commitNanos = new ArrayList<>();
        byte[] previousHash = AppBlock.GENESIS_PREV_HASH;
        long totalStart = System.nanoTime();
        long finalHeight;
        byte[] finalRoot;
        try (AppLedgerStore ledger = open(ledgerPath)) {
            int nextKey = 0;
            long height = 0;
            while (nextKey < keyCount) {
                height++;
                long commitStart = System.nanoTime();
                CandidateState candidate = ledger.stateBackend().beginCandidate(
                        ledger.tipHeight(), normalizedRoot(ledger.stateRoot()), height);
                if (height == 1) {
                    new StateCommitmentGuard(IDENTITY).apply(height, candidate);
                }
                int end = Math.min(keyCount, nextKey + batchSize);
                while (nextKey < end) {
                    candidate.put(key(nextKey), value(nextKey));
                    nextKey++;
                }
                try (StagedStateCommit prepared = (StagedStateCommit) candidate.prepare()) {
                    AppBlock block = block(height, previousHash, prepared.stateRoot());
                    try (WriteBatch batch = new WriteBatch()) {
                        ledger.commitBlock(block, AppBlockCodec.blockHash(block),
                                prepared, batch, List.of());
                    }
                    previousHash = AppBlockCodec.blockHash(block);
                }
                commitNanos.add(System.nanoTime() - commitStart);
            }
            finalHeight = ledger.tipHeight();
            finalRoot = ledger.stateRoot();
        }
        long ingestNanos = System.nanoTime() - totalStart;

        long restartStart = System.nanoTime();
        AppLedgerStore reopened = open(ledgerPath);
        long restartNanos = System.nanoTime() - restartStart;
        try {
            require(reopened.tipHeight() == finalHeight, "restart height mismatch");
            require(Arrays.equals(reopened.stateRoot(), finalRoot), "restart root mismatch");

            List<Long> proofNanos = new ArrayList<>();
            long proofBytes = 0;
            try (InMemoryJmtStore verifierStore = new InMemoryJmtStore()) {
                JellyfishMerkleTree verifier = new JellyfishMerkleTree(
                        verifierStore, JmtProfile.classicBlake2b256V1());
                for (int sample = 0; sample < proofSamples; sample++) {
                    int index = (int) (((long) sample * keyCount) / proofSamples);
                    long proofStart = System.nanoTime();
                    StateProof proof = reopened.stateBackend()
                            .prove(finalHeight, key(index)).orElseThrow();
                    proofNanos.add(System.nanoTime() - proofStart);
                    proofBytes += proof.nativeProof().length;
                    require(verifier.verifyProofWire(finalRoot, key(index), value(index),
                            true, proof.nativeProof()), "proof verification failed");
                }
            }

            long integrityStart = System.nanoTime();
            require(reopened.stateBackend().verifyIntegrity().valid(),
                    "full integrity check failed");
            long integrityNanos = System.nanoTime() - integrityStart;

            long snapshotStart = System.nanoTime();
            reopened.createSnapshot(snapshotPath.toString());
            long snapshotNanos = System.nanoTime() - snapshotStart;
            long ledgerBytesBeforePrune = directoryBytes(ledgerPath);
            long snapshotBytes = directoryBytes(snapshotPath);

            long retainFrom = Math.max(1, finalHeight / 2);
            long pruneStart = System.nanoTime();
            int prunedRecords = reopened.pruneStateProofsBefore(retainFrom);
            long pruneNanos = System.nanoTime() - pruneStart;
            require(Arrays.equals(reopened.stateRoot(), finalRoot),
                    "pruning changed the finalized root");
            long ledgerBytesAfterPrune = directoryBytes(ledgerPath);
            require(reopened.stateBackend().prove(retainFrom, key(0)).isPresent(),
                    "retained-horizon proof is unavailable");
            if (retainFrom > 1) {
                require(reopened.stateBackend().prove(retainFrom - 1, key(0)).isEmpty(),
                        "pruned proof remains available");
            }

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("schemaVersion", 1);
            report.put("profile", StateCommitmentProfiles.CLASSIC_JMT.id());
            report.put("dependencyDescriptor",
                    StateCommitmentProfiles.CLASSIC_JMT.dependencyDescriptor());
            report.put("cclVersion", "0.8.0-pre5-dev1");
            report.put("durability", "RocksDB WAL + WriteOptions.sync(true), one shared batch");
            report.put("javaVersion", System.getProperty("java.version"));
            report.put("os", System.getProperty("os.name") + " "
                    + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
            report.put("availableProcessors", Runtime.getRuntime().availableProcessors());
            report.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
            report.put("keys", keyCount);
            report.put("versions", finalHeight);
            report.put("keysPerVersion", batchSize);
            report.put("proofSamples", proofSamples);
            report.put("ingestSeconds", seconds(ingestNanos));
            report.put("keysPerSecond", keyCount / seconds(ingestNanos));
            report.put("commitMillis", percentiles(commitNanos));
            report.put("proofMillis", percentiles(proofNanos));
            report.put("meanProofBytes", proofBytes / (double) proofSamples);
            report.put("restartMillis", millis(restartNanos));
            report.put("fullIntegrityMillis", millis(integrityNanos));
            report.put("snapshotMillis", millis(snapshotNanos));
            report.put("ledgerBytesBeforePrune", ledgerBytesBeforePrune);
            report.put("ledgerBytesAfterPruneBeforeCompaction", ledgerBytesAfterPrune);
            report.put("snapshotBytes", snapshotBytes);
            report.put("pruneRetainFromHeight", retainFrom);
            report.put("prunedRecords", prunedRecords);
            report.put("pruneMillis", millis(pruneNanos));
            report.put("oldestProvableHeight",
                    reopened.stateBackend().oldestProvableHeight());

            Files.createDirectories(reportPath.toAbsolutePath().getParent());
            byte[] encoded = MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(report);
            Files.write(reportPath, encoded);
            System.out.println(new String(encoded, java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            reopened.close();
        }
    }

    private static void deleteRecursively(Path directory) throws Exception {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static Map<String, Double> percentiles(List<Long> nanos) {
        List<Long> sorted = new ArrayList<>(nanos);
        sorted.sort(Long::compareTo);
        Map<String, Double> values = new LinkedHashMap<>();
        values.put("p50", millis(at(sorted, 0.50)));
        values.put("p95", millis(at(sorted, 0.95)));
        values.put("p99", millis(at(sorted, 0.99)));
        values.put("max", millis(sorted.get(sorted.size() - 1)));
        return values;
    }

    private static long at(List<Long> sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static long directoryBytes(Path directory) throws Exception {
        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            }).sum();
        }
    }

    private static AppLedgerStore open(Path path) {
        return new AppLedgerStore(path.toString(),
                NOPLogger.NOP_LOGGER, IDENTITY);
    }

    private static AppBlock block(long height, byte[] previousHash, byte[] root) {
        return new AppBlock(
                AppBlock.BLOCK_VERSION, "adr025-benchmark", height, previousHash,
                height, new byte[0], 1_700_000_000_000L + height,
                AppBlockCodec.messagesRoot(List.of()), root, List.of(),
                new byte[32], FinalityCert.empty());
    }

    private static byte[] key(int index) {
        return ByteBuffer.allocate(16).putLong(0x59414e4f4a4d5401L).putLong(index).array();
    }

    private static byte[] value(int index) {
        byte[] value = new byte[128];
        ByteBuffer.wrap(value).putLong(index).putLong(~(long) index);
        for (int offset = 16; offset < value.length; offset++) {
            value[offset] = (byte) (index * 31 + offset);
        }
        return value;
    }

    private static byte[] normalizedRoot(byte[] root) {
        return root != null ? root : new byte[32];
    }

    private static int positive(String value, String name) {
        int parsed = Integer.parseInt(value);
        if (parsed <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return parsed;
    }

    private static double seconds(long nanos) {
        return nanos / 1_000_000_000.0;
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static byte[] repeated(int value, int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
