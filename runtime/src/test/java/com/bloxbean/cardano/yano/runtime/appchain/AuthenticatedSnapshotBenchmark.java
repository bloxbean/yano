package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.jmt.JellyfishMerkleTree;
import com.bloxbean.cardano.vds.jmt.JmtProfile;
import com.bloxbean.cardano.vds.jmt.store.InMemoryJmtStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.snapshot.AuthenticatedSnapshotSeriesDescriptorV1;
import com.bloxbean.cardano.yano.api.appchain.snapshot.AuthenticatedSnapshotSourceCommitmentV1;
import com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotDescriptorDraftV1;
import com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotCanonicalCodec;
import com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotDescriptorV1;
import com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotEntry;
import com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotSourceBoundary;
import com.bloxbean.cardano.yano.api.appchain.state.CandidateState;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;
import com.bloxbean.cardano.yano.api.appchain.state.StateProof;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.rocksdb.WriteBatch;
import org.slf4j.helpers.NOPLogger;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Reproducible ADR-028 M8 logical-snapshot/archive/parallel-proof benchmark. */
public final class AuthenticatedSnapshotBenchmark {
    private static final int CHUNK_ENTRIES = 25_000;
    private static final StateCommitmentIdentity PRIMARY = StateCommitmentIdentity.explicit(
            StateCommitmentProfiles.MPF, filled(0x28, 32));
    private static final ObjectMapper JSON = new ObjectMapper();

    private AuthenticatedSnapshotBenchmark() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException(
                    "Expected report path, profile, entries, proof requests, and clients");
        }
        Path report = Path.of(args[0]);
        String profile = StateCommitmentProfiles.require(args[1]).id();
        int entries = positive(args[2], "entries");
        int proofRequests = positive(args[3], "proof requests");
        int clients = positive(args[4], "clients");
        Path workspace = Files.createTempDirectory("adr028-snapshots-");
        try {
            run(report, profile, entries, proofRequests, clients, workspace);
        } finally {
            deleteRecursively(workspace);
        }
    }

    private static void run(Path report, String profile, int entries, int proofRequests,
                            int clients, Path workspace) throws Exception {
        Path ledgerPath = workspace.resolve("ledger");
        Path archives = workspace.resolve("archives");
        var series = series(profile, entries);
        int chunks = (entries + CHUNK_ENTRIES - 1) / CHUNK_ENTRIES;
        byte[] sourceDatasetRoot = sourceDatasetRoot(entries, chunks);
        byte[] previousHash = AppBlock.GENESIS_PREV_HASH;
        long ingestStarted = System.nanoTime();
        SnapshotDescriptorV1 descriptor;
        com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotBuildTokenV1 token = null;
        try (AppLedgerStore ledger = ledger(ledgerPath)) {
            AuthenticatedSnapshotRuntime runtime = runtime(ledger, archives, series);
            for (int chunk = 0; chunk < chunks; chunk++) {
                long height = chunk + 1L;
                CandidateState candidate = ledger.stateBackend().beginCandidate(
                        ledger.tipHeight(), normalized(ledger.stateRoot()), height);
                if (height == 1) new StateCommitmentGuard(PRIMARY).apply(height, candidate);
                var session = runtime.beginBlock(candidate);
                var handle = session.writer().capabilities().snapshotSeries("epoch-stake")
                        .orElseThrow();
                if (chunk == 0) {
                    token = handle.begin(0, "epoch-stake-500",
                            new SnapshotSourceBoundary.L1Epoch(500, 501, 500, 1, filled(0x44, 32)),
                            0, 0, 1, sourceDatasetRoot, chunks, entries);
                }
                int from = chunk * CHUNK_ENTRIES;
                int through = Math.min(entries, from + CHUNK_ENTRIES);
                List<SnapshotEntry> values = new ArrayList<>(through - from);
                for (int index = from; index < through; index++) {
                    values.add(new SnapshotEntry(key(index), value(index)));
                }
                handle.appendChunk(token, chunk, values);
                if (chunk + 1 == chunks) handle.seal(token);
                try (WriteBatch batch = new WriteBatch()) {
                    session.execute(batch, height);
                    try (StagedStateCommit prepared = (StagedStateCommit) candidate.prepare()) {
                        AppBlock block = block(height, previousHash, prepared.stateRoot());
                        ledger.commitBlock(block, AppBlockCodec.blockHash(block), prepared, batch, List.of());
                        previousHash = AppBlockCodec.blockHash(block);
                    }
                }
            }
            descriptor = SnapshotCanonicalCodec.decodeDescriptor(ledger.stateBackend().get(
                    chunks, AuthenticatedSnapshotRuntime.descriptorKey("epoch-stake", 0))
                    .orElseThrow());
        }
        long ingestNanos = System.nanoTime() - ingestStarted;

        long restartStarted = System.nanoTime();
        try (AppLedgerStore ledger = ledger(ledgerPath)) {
            AuthenticatedSnapshotRuntime runtime = runtime(ledger, archives, series);
            long restartNanos = System.nanoTime() - restartStarted;
            byte[] sampleKey = key(entries / 2);
            StateProof before = runtime.stateProof(descriptor, sampleKey).orElseThrow();
            require(verify(before), "initial proof verification failed");

            long onlineBytes = directoryBytes(ledgerPath);
            long archiveStarted = System.nanoTime();
            Path archive = runtime.archive(descriptor, null);
            long archiveNanos = System.nanoTime() - archiveStarted;
            require(runtime.stateProof(descriptor, sampleKey).isPresent(),
                    "archiving made an online snapshot unavailable");

            long evictStarted = System.nanoTime();
            int evictedNodes = runtime.evict(descriptor);
            long evictNanos = System.nanoTime() - evictStarted;
            long bytesAfterEvict = directoryBytes(ledgerPath);
            require(runtime.stateProof(descriptor, sampleKey).isEmpty(),
                    "evicted snapshot still serves proofs");

            long restoreStarted = System.nanoTime();
            runtime.restore(descriptor, null);
            long restoreNanos = System.nanoTime() - restoreStarted;
            StateProof restored = runtime.stateProof(descriptor, sampleKey).orElseThrow();
            require(Arrays.equals(before.nativeProof(), restored.nativeProof()) && verify(restored),
                    "restored proof differs or does not verify");

            ProofRun proofRun = parallelProofs(runtime, descriptor, entries,
                    proofRequests, clients);
            List<Long> latencies = proofRun.latencies();
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("schemaVersion", 1);
            output.put("recordedAtEpochMillis", System.currentTimeMillis());
            output.put("profile", profile);
            output.put("verificationTarget", profile.startsWith("mpf-")
                    ? "off-chain-and-on-chain" : "off-chain-only");
            output.put("entries", entries);
            output.put("chunkEntries", CHUNK_ENTRIES);
            output.put("chunks", chunks);
            output.put("ingestSeconds", seconds(ingestNanos));
            output.put("restartMillis", millis(restartNanos));
            output.put("onlineLedgerBytes", onlineBytes);
            output.put("archiveBytes", Files.size(archive));
            output.put("archiveSeconds", seconds(archiveNanos));
            output.put("evictedNodeRecords", evictedNodes);
            output.put("evictAndCompactSeconds", seconds(evictNanos));
            output.put("ledgerBytesAfterEvict", bytesAfterEvict);
            output.put("restoreSeconds", seconds(restoreNanos));
            output.put("restoredProofByteIdentical", true);
            output.put("proofClients", clients);
            output.put("proofRequests", proofRequests);
            output.put("proofWallSeconds", seconds(proofRun.wallNanos()));
            output.put("proofQps", proofRequests / seconds(proofRun.wallNanos()));
            output.put("proofMillis", percentiles(latencies));
            output.put("openFileDescriptors", openFileDescriptors());
            output.put("javaVersion", System.getProperty("java.version"));
            output.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
            output.put("availableProcessors", Runtime.getRuntime().availableProcessors());
            output.put("os", System.getProperty("os.name") + " "
                    + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
            FileStore fileStore = Files.getFileStore(ledgerPath);
            output.put("fileStore", fileStore.name() + " (" + fileStore.type() + ")");
            output.put("snapshotRootHex", java.util.HexFormat.of().formatHex(descriptor.snapshotRoot()));
            output.put("sourceDatasetRootHex",
                    java.util.HexFormat.of().formatHex(descriptor.sourceDatasetRoot()));
            output.put("command", "./gradlew :runtime:benchmarkAdr028AuthenticatedSnapshot"
                    + (profile.startsWith("jmt-") ? "Jmt" : "Mpf"));
            Files.createDirectories(report.toAbsolutePath().getParent());
            byte[] encoded = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(output);
            Files.write(report, encoded);
            System.out.println(new String(encoded, java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static ProofRun parallelProofs(AuthenticatedSnapshotRuntime runtime,
                                           SnapshotDescriptorV1 descriptor, int entries,
                                           int requests, int clients) throws Exception {
        runtime.stateProof(descriptor, key(0)).orElseThrow();
        var executor = Executors.newFixedThreadPool(clients);
        try {
            List<Callable<Long>> tasks = new ArrayList<>(requests);
            for (int request = 0; request < requests; request++) {
                int index = (int) (((long) request * entries) / requests);
                tasks.add(() -> {
                    long started = System.nanoTime();
                    StateProof proof = runtime.stateProof(descriptor, key(index)).orElseThrow();
                    require(verify(proof), "parallel proof verification failed");
                    return System.nanoTime() - started;
                });
            }
            long wallStarted = System.nanoTime();
            var futures = executor.invokeAll(tasks);
            long wallNanos = System.nanoTime() - wallStarted;
            List<Long> result = new ArrayList<>(futures.size());
            for (var future : futures) result.add(future.get());
            return new ProofRun(List.copyOf(result), wallNanos);
        } finally {
            executor.shutdown();
            executor.awaitTermination(1, TimeUnit.MINUTES);
        }
    }

    private static boolean verify(StateProof proof) {
        if (proof.snapshot().identity().profile().id().startsWith("mpf-")) {
            MpfTrie verifier = new MpfTrie(new NodeStore() {
                @Override public byte[] get(byte[] key) { return null; }
                @Override public void put(byte[] key, byte[] value) { }
                @Override public void delete(byte[] key) { }
            });
            return verifier.verifyProofWire(proof.snapshot().stateRoot(), proof.canonicalKey(),
                    proof.value(), proof.value() != null, proof.nativeProof());
        }
        try (InMemoryJmtStore store = new InMemoryJmtStore()) {
            JellyfishMerkleTree verifier = new JellyfishMerkleTree(
                    store, JmtProfile.classicBlake2b256V1());
            return verifier.verifyProofWire(proof.snapshot().stateRoot(), proof.canonicalKey(),
                    proof.value(), proof.value() != null, proof.nativeProof());
        }
    }

    private record ProofRun(List<Long> latencies, long wallNanos) { }

    private static AuthenticatedSnapshotRuntime runtime(AppLedgerStore ledger, Path archives,
                                                        AuthenticatedSnapshotSeriesDescriptorV1 series) {
        var settings = new AuthenticatedSnapshotSettings(true, Set.of("epoch-stake"),
                32_768, 4L * 1024 * 1024, filled(9, 32),
                32, false, 10, true, 300,
                StateCommitmentProfiles.MPF.id().equals(series.snapshotProfile()), archives,
                10_000_000, 64L * 1024 * 1024 * 1024);
        return AuthenticatedSnapshotRuntime.create(ledger, PRIMARY, settings,
                List.of(series), List.of(source(series)), true, "benchmark-chain").orElseThrow();
    }

    private static AuthenticatedSnapshotSourceCommitmentV1 source(
            AuthenticatedSnapshotSeriesDescriptorV1 series) {
        return new BenchmarkSourceCommitment(series.seriesId());
    }

    private static AuthenticatedSnapshotSeriesDescriptorV1 series(String profileId, int entries) {
        var profile = StateCommitmentProfiles.require(profileId);
        return new AuthenticatedSnapshotSeriesDescriptorV1("epoch-stake", "epoch-stake-v1",
                AuthenticatedSnapshotSeriesDescriptorV1.Trigger.L1_EPOCH_BOUNDARY,
                profile.id(), profile.formatFingerprint(), profile.proofEncodingId(),
                profile.equals(StateCommitmentProfiles.MPF)
                        ? AuthenticatedSnapshotSeriesDescriptorV1.VerificationTarget.ON_CHAIN
                        : AuthenticatedSnapshotSeriesDescriptorV1.VerificationTarget.OFF_CHAIN,
                AuthenticatedSnapshotSeriesDescriptorV1.Visibility.PUBLIC,
                "blake2b256", "epoch-stake-source-v1", CHUNK_ENTRIES,
                4 * 1024 * 1024, 256, 8 * 1024, Math.max(entries, 1),
                AuthenticatedSnapshotSeriesDescriptorV1.RecoveryCoverage.DATASET);
    }

    private static AppLedgerStore ledger(Path path) {
        return new AppLedgerStore(path.toString(), NOPLogger.NOP_LOGGER, PRIMARY);
    }

    private static AppBlock block(long height, byte[] previousHash, byte[] root) {
        return new AppBlock(AppBlock.BLOCK_VERSION, "adr028-snapshot-benchmark", height,
                previousHash, height, new byte[0], 1_700_000_000_000L + height,
                AppBlockCodec.messagesRoot(List.of()), root, List.of(), new byte[32],
                FinalityCert.empty());
    }

    private static byte[] key(int index) {
        return ByteBuffer.allocate(29).put((byte) 0).put(credentialHash(index)).array();
    }

    private static byte[] value(int index) {
        return ByteBuffer.allocate(36)
                .putLong(1_000_000L + index)
                .put(filled(index, 28))
                .array();
    }

    private static byte[] credentialHash(int index) {
        return ByteBuffer.allocate(28).position(20).putLong(index).array();
    }

    private static byte[] sourceDatasetRoot(int entries, int chunks) {
        byte[] accumulator = BenchmarkSourceCommitment.initial();
        for (int chunk = 0; chunk < chunks; chunk++) {
            int from = chunk * CHUNK_ENTRIES;
            int through = Math.min(entries, from + CHUNK_ENTRIES);
            List<SnapshotEntry> values = new ArrayList<>(through - from);
            for (int index = from; index < through; index++) {
                values.add(new SnapshotEntry(key(index), value(index)));
            }
            accumulator = BenchmarkSourceCommitment.advance(accumulator, chunk, values);
        }
        return accumulator;
    }

    /** Core-only deterministic source used to keep the runtime benchmark product-neutral. */
    private record BenchmarkSourceCommitment(String seriesId)
            implements AuthenticatedSnapshotSourceCommitmentV1 {
        private static final byte[] DOMAIN =
                "yano-core-snapshot-benchmark-v1".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

        @Override public String algorithm() { return "blake2b256"; }
        @Override public String wireVersion() { return "core-benchmark-source-v1"; }
        @Override public byte[] initial(SnapshotDescriptorDraftV1 draft) { return initial(); }
        @Override public byte[] append(byte[] accumulator, long chunkIndex,
                                       List<SnapshotEntry> entries) {
            return advance(accumulator, chunkIndex, entries);
        }
        @Override public byte[] finish(byte[] accumulator, long chunks, long entries) {
            return accumulator.clone();
        }

        private static byte[] initial() {
            return Blake2bUtil.blake2bHash256(DOMAIN);
        }

        private static byte[] advance(
                byte[] accumulator,
                long chunkIndex,
                List<SnapshotEntry> entries
        ) {
            int bytes = entries.stream()
                    .mapToInt(entry -> 8 + entry.key().length + entry.value().length)
                    .sum();
            ByteBuffer canonical = ByteBuffer.allocate(32 + 8 + 4 + bytes)
                    .put(accumulator).putLong(chunkIndex).putInt(entries.size());
            for (SnapshotEntry entry : entries) {
                canonical.putInt(entry.key().length).put(entry.key());
                canonical.putInt(entry.value().length).put(entry.value());
            }
            return Blake2bUtil.blake2bHash256(canonical.array());
        }
    }

    private static Map<String, Double> percentiles(List<Long> nanos) {
        List<Long> sorted = new ArrayList<>(nanos);
        sorted.sort(Long::compareTo);
        return Map.of("p50", millis(at(sorted, .50)), "p95", millis(at(sorted, .95)),
                "p99", millis(at(sorted, .99)), "max", millis(sorted.getLast()));
    }

    private static long at(List<Long> values, double percentile) {
        int index = Math.max(0, Math.min(values.size() - 1,
                (int) Math.ceil(values.size() * percentile) - 1));
        return values.get(index);
    }

    private static long openFileDescriptors() {
        var bean = ManagementFactory.getOperatingSystemMXBean();
        return bean instanceof com.sun.management.UnixOperatingSystemMXBean unix
                ? unix.getOpenFileDescriptorCount() : -1;
    }

    private static long directoryBytes(Path path) throws Exception {
        try (var files = Files.walk(path)) {
            return files.filter(Files::isRegularFile).mapToLong(file -> {
                try { return Files.size(file); }
                catch (Exception failure) { throw new RuntimeException(failure); }
            }).sum();
        }
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (var files = Files.walk(path)) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    private static int positive(String value, String name) {
        int parsed = Integer.parseInt(value);
        if (parsed <= 0) throw new IllegalArgumentException(name + " must be positive");
        return parsed;
    }

    private static byte[] normalized(byte[] value) { return value != null ? value : new byte[32]; }
    private static double seconds(long nanos) { return nanos / 1_000_000_000.0; }
    private static double millis(long nanos) { return nanos / 1_000_000.0; }
    private static byte[] filled(int value, int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
