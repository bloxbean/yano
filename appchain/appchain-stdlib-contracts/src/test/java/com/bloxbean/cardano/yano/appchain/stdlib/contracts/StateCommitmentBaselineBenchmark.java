package com.bloxbean.cardano.yano.appchain.stdlib.contracts;

import com.bloxbean.cardano.vds.jmt.JellyfishMerkleTree;
import com.bloxbean.cardano.vds.jmt.JmtProfile;
import com.bloxbean.cardano.vds.jmt.store.InMemoryJmtStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.vds.mpf.internal.TestNodeStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reproducible single-process algorithm/codec baseline for identical MPF and
 * classic-JMT logical workloads. This is not a production throughput claim;
 * Phase 3 adds durable runtime/atomicity/storage measurements.
 */
public final class StateCommitmentBaselineBenchmark {
    private static final byte[] OWNER = java.util.HexFormat.of().parseHex("11".repeat(32));
    private static final int PROOF_SAMPLES = 128;

    private StateCommitmentBaselineBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("usage: REPORT KEY_COUNT BATCH_SIZE");
        }
        Path report = Path.of(args[0]);
        int keyCount = positive(args[1], "keyCount");
        int batchSize = positive(args[2], "batchSize");
        requireWorkload(keyCount, batchSize);

        BenchmarkReport result = run(keyCount, batchSize);
        Files.createDirectories(report.toAbsolutePath().getParent());
        Files.writeString(report, result.toJson(), StandardCharsets.UTF_8);
        System.out.println("ADR-025 Phase 0 baseline report: " + report.toAbsolutePath());
        System.out.println(result.summary());
    }

    static BenchmarkReport run(int keyCount, int batchSize) {
        requireWorkload(keyCount, batchSize);
        List<LogicalUpdate> inserts = new ArrayList<>(keyCount);
        List<LogicalUpdate> updates = new ArrayList<>((keyCount + 9) / 10);
        List<LogicalUpdate> revokes = new ArrayList<>((keyCount + 19) / 20);
        for (int index = 0; index < keyCount; index++) {
            byte[] key = key(index);
            AuthenticatedMapContract.Entry entry = entry(index, 1, 1);
            inserts.add(new LogicalUpdate(key, AuthenticatedMapContract.encodeEntry(entry)));
            if (index % 10 == 0) {
                updates.add(new LogicalUpdate(key, AuthenticatedMapContract.encodeEntry(
                        entry(index + keyCount, 2, 2))));
            }
            if (index % 20 == 0) {
                revokes.add(new LogicalUpdate(key, AuthenticatedMapContract.encodeEntry(
                        entry(index + keyCount, 2, 2).revoked(3))));
            }
        }
        List<LogicalUpdate> workload = new ArrayList<>(
                inserts.size() + updates.size() + revokes.size());
        workload.addAll(inserts);
        workload.addAll(updates);
        workload.addAll(revokes);

        ProfileResult mpf = runMpf(workload, inserts, batchSize);
        ProfileResult jmt = runJmt(workload, inserts, batchSize);
        return new BenchmarkReport(keyCount, workload.size(), batchSize, mpf, jmt);
    }

    private static ProfileResult runMpf(List<LogicalUpdate> workload,
                                        List<LogicalUpdate> inserts,
                                        int batchSize) {
        MpfTrie trie = new MpfTrie(new TestNodeStore());
        List<Long> latencies = new ArrayList<>();
        long started = System.nanoTime();
        applyBatches(workload, batchSize, batch -> {
            long batchStarted = System.nanoTime();
            batch.forEach(update -> trie.put(update.key(), update.value()));
            latencies.add(System.nanoTime() - batchStarted);
        });
        long elapsed = System.nanoTime() - started;
        byte[] root = requiredRoot(trie.getRootHash(), "MPF");

        List<Long> proofLatencies = new ArrayList<>();
        long proofBytes = 0;
        int stride = Math.max(1, inserts.size() / PROOF_SAMPLES);
        for (int index = 0; index < inserts.size() && proofLatencies.size() < PROOF_SAMPLES;
             index += stride) {
            LogicalUpdate sample = inserts.get(index);
            long proofStarted = System.nanoTime();
            byte[] proof = trie.getProofWire(sample.key()).orElseThrow();
            proofLatencies.add(System.nanoTime() - proofStarted);
            proofBytes += proof.length;
            byte[] currentValue = trie.get(sample.key());
            if (!trie.verifyProofWire(root, sample.key(), currentValue, true, proof)) {
                throw new IllegalStateException("MPF benchmark proof failed verification");
            }
        }
        return ProfileResult.of("mpf-blake2b256-v1", root, workload.size(), elapsed,
                latencies, proofLatencies, proofBytes);
    }

    private static ProfileResult runJmt(List<LogicalUpdate> workload,
                                        List<LogicalUpdate> inserts,
                                        int batchSize) {
        try (InMemoryJmtStore store = new InMemoryJmtStore()) {
            JellyfishMerkleTree tree = new JellyfishMerkleTree(
                    store, JmtProfile.classicBlake2b256V1());
            List<Long> latencies = new ArrayList<>();
            long[] version = {-1};
            byte[][] root = {null};
            long started = System.nanoTime();
            applyBatches(workload, batchSize, batch -> {
                Map<byte[], byte[]> updates = new LinkedHashMap<>();
                batch.forEach(update -> updates.put(update.key(), update.value()));
                long batchStarted = System.nanoTime();
                root[0] = tree.put(++version[0], updates).rootHash();
                latencies.add(System.nanoTime() - batchStarted);
            });
            long elapsed = System.nanoTime() - started;
            byte[] finalRoot = requiredRoot(root[0], "JMT");

            List<Long> proofLatencies = new ArrayList<>();
            long proofBytes = 0;
            int stride = Math.max(1, inserts.size() / PROOF_SAMPLES);
            for (int index = 0; index < inserts.size() && proofLatencies.size() < PROOF_SAMPLES;
                 index += stride) {
                LogicalUpdate sample = inserts.get(index);
                long proofStarted = System.nanoTime();
                byte[] proof = tree.getProofWire(sample.key(), version[0]).orElseThrow();
                proofLatencies.add(System.nanoTime() - proofStarted);
                proofBytes += proof.length;
                byte[] currentValue = tree.get(sample.key()).orElseThrow();
                if (!tree.verifyProofWire(finalRoot, sample.key(), currentValue, true, proof)) {
                    throw new IllegalStateException("JMT benchmark proof failed verification");
                }
            }
            return ProfileResult.of("jmt-blake2b256-v1", finalRoot, workload.size(), elapsed,
                    latencies, proofLatencies, proofBytes);
        }
    }

    private static void applyBatches(List<LogicalUpdate> workload, int batchSize,
                                     java.util.function.Consumer<List<LogicalUpdate>> consumer) {
        for (int offset = 0; offset < workload.size(); offset += batchSize) {
            consumer.accept(workload.subList(offset, Math.min(workload.size(), offset + batchSize)));
        }
    }

    private static AuthenticatedMapContract.Entry entry(int index, long revision, long height) {
        return AuthenticatedMapContract.Entry.active(revision, OWNER,
                String.format(Locale.ROOT, "value-%08d", index).getBytes(StandardCharsets.US_ASCII),
                1, height);
    }

    private static byte[] key(int index) {
        return AuthenticatedMapContract.canonicalKey("benchmark",
                String.format(Locale.ROOT, "key-%08d", index).getBytes(StandardCharsets.US_ASCII));
    }

    private static int positive(String value, String name) {
        int parsed = Integer.parseInt(value);
        if (parsed <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return parsed;
    }

    private static void requireWorkload(int keyCount, int batchSize) {
        if (keyCount <= 0 || batchSize <= 0 || batchSize > 1_000) {
            throw new IllegalArgumentException("keyCount must be positive and batchSize must be 1-1000");
        }
    }

    private static byte[] requiredRoot(byte[] root, String profile) {
        if (root == null || root.length != 32) {
            throw new IllegalStateException(profile + " produced an invalid root");
        }
        return root;
    }

    private record LogicalUpdate(byte[] key, byte[] value) {
        private LogicalUpdate {
            key = key.clone();
            value = value.clone();
        }

        @Override public byte[] key() { return key.clone(); }
        @Override public byte[] value() { return value.clone(); }
    }

    record ProfileResult(String profile, String rootHex, long operations,
                         long elapsedNanos, double operationsPerSecond,
                         long batchP50Nanos, long batchP95Nanos, long batchP99Nanos,
                         long proofP50Nanos, long proofP95Nanos, long proofP99Nanos,
                         long averageProofBytes) {
        static ProfileResult of(String profile, byte[] root, long operations, long elapsed,
                                List<Long> batches, List<Long> proofs, long proofBytes) {
            return new ProfileResult(profile, java.util.HexFormat.of().formatHex(root),
                    operations, elapsed, operations * 1_000_000_000.0 / elapsed,
                    percentile(batches, 50), percentile(batches, 95), percentile(batches, 99),
                    percentile(proofs, 50), percentile(proofs, 95), percentile(proofs, 99),
                    proofs.isEmpty() ? 0 : proofBytes / proofs.size());
        }
    }

    record BenchmarkReport(int keyCount, int operationCount, int batchSize,
                           ProfileResult mpf, ProfileResult jmt) {
        String summary() {
            return String.format(Locale.ROOT,
                    "keys=%d operations=%d batch=%d mpf=%.1f ops/s jmt=%.1f ops/s",
                    keyCount, operationCount, batchSize,
                    mpf.operationsPerSecond(), jmt.operationsPerSecond());
        }

        String toJson() {
            return """
                    {
                      "schema": "yano-adr025-phase0-baseline-v1",
                      "scope": "single-process in-memory algorithm/codec baseline; not production bounds",
                      "cclVersion": "0.8.0-pre5-dev1",
                      "keyCount": %d,
                      "operationCount": %d,
                      "mix": {"insert": %d, "update": %d, "revoke": %d},
                      "batchSize": %d,
                      "proofSamples": %d,
                      "profiles": [
                        %s,
                        %s
                      ],
                      "deferred": [{
                        "profile": "jmt-poseidon-bls12381-v1",
                        "reason": "ZeroJ persistent JMT release unavailable; deferred with ADR-025 Phase 4"
                      }]
                    }
                    """.formatted(keyCount, operationCount, keyCount,
                    (keyCount + 9) / 10, (keyCount + 19) / 20,
                    batchSize, PROOF_SAMPLES, profileJson(mpf), profileJson(jmt));
        }

        private static String profileJson(ProfileResult result) {
            return """
                    {"id":"%s","root":"%s","operations":%d,"elapsedNanos":%d,
                     "operationsPerSecond":%.3f,
                     "batchLatencyNanos":{"p50":%d,"p95":%d,"p99":%d},
                     "proofLatencyNanos":{"p50":%d,"p95":%d,"p99":%d},
                     "averageProofBytes":%d}
                    """.formatted(result.profile(), result.rootHex(), result.operations(),
                    result.elapsedNanos(), result.operationsPerSecond(),
                    result.batchP50Nanos(), result.batchP95Nanos(), result.batchP99Nanos(),
                    result.proofP50Nanos(), result.proofP95Nanos(), result.proofP99Nanos(),
                    result.averageProofBytes()).replace("\n", "");
        }
    }

    private static long percentile(List<Long> samples, int percentile) {
        if (samples.isEmpty()) {
            return 0;
        }
        long[] sorted = samples.stream().mapToLong(Long::longValue).sorted().toArray();
        int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }
}
