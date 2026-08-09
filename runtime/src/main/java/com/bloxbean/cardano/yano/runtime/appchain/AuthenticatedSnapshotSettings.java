package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.snapshot.AuthenticatedSnapshotSeriesDescriptorV1;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfile;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Normalized chain configuration for the reusable authenticated-snapshot runtime. */
record AuthenticatedSnapshotSettings(
        boolean enabled,
        Set<String> selectedSeries,
        int maxOperationsPerBlock,
        long maxBytesPerBlock,
        byte[] identityDigest,
        int proofConcurrency,
        boolean retentionEnabled,
        int keepOnlineCount,
        boolean evictAfterArchive,
        long retentionIntervalSeconds,
        boolean mpfPruningEnabled,
        java.nio.file.Path archiveDirectory,
        long archiveMaxNodes,
        long archiveMaxBytes
) {
    static final String PREFIX = "capabilities.authenticated-snapshots.";
    static final String ENABLED = PREFIX + "enabled";
    static final String SERIES = PREFIX + "series";
    static final String MAX_OPERATIONS = PREFIX + "max-operations-per-block";
    static final String MAX_BYTES = PREFIX + "max-bytes-per-block";
    static final String ARCHIVE_DIRECTORY = PREFIX + "archive-directory";
    static final String PROOF_CONCURRENCY = PREFIX + "proof-service.concurrency";
    static final String RETENTION_ENABLED = PREFIX + "retention.enabled";
    static final String KEEP_ONLINE_COUNT = PREFIX + "retention.keep-online-count";
    static final String EVICT_AFTER_ARCHIVE = PREFIX + "retention.evict-after-archive";
    static final String RETENTION_INTERVAL = PREFIX + "retention.interval-seconds";
    static final String MPF_PRUNING_ENABLED = PREFIX + "storage.mpf-pruning-enabled";
    static final String ARCHIVE_MAX_NODES = PREFIX + "archive-max-nodes";
    static final String ARCHIVE_MAX_BYTES = PREFIX + "archive-max-bytes";

    AuthenticatedSnapshotSettings {
        selectedSeries = Set.copyOf(selectedSeries);
        identityDigest = identityDigest.clone();
        if (proofConcurrency <= 0 || proofConcurrency > 1024) {
            throw new IllegalArgumentException(PROOF_CONCURRENCY + " must be between 1 and 1024");
        }
        if (keepOnlineCount < 1 || retentionIntervalSeconds < 10) {
            throw new IllegalArgumentException("snapshot retention bounds are invalid");
        }
        archiveDirectory = archiveDirectory.toAbsolutePath().normalize();
    }

    @Override public byte[] identityDigest() { return identityDigest.clone(); }

    /** Bind the selected machine declarations, not only operator settings, into genesis identity. */
    byte[] capabilityIdentityDigest(List<AuthenticatedSnapshotSeriesDescriptorV1> selected) {
        if (!enabled) return new byte[32];
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.writeBytes("yano-authenticated-snapshot-capability-v1\0"
                    .getBytes(StandardCharsets.US_ASCII));
            DataOutputStream out = new DataOutputStream(bytes);
            out.write(identityDigest);
            List<AuthenticatedSnapshotSeriesDescriptorV1> ordered = selected.stream()
                    .sorted(java.util.Comparator.comparing(
                            AuthenticatedSnapshotSeriesDescriptorV1::seriesId)).toList();
            out.writeInt(ordered.size());
            for (var descriptor : ordered) {
                write(out, descriptor.seriesId());
                write(out, descriptor.schemaId());
                write(out, descriptor.trigger().name());
                write(out, descriptor.snapshotProfile());
                out.write(descriptor.formatFingerprint());
                write(out, descriptor.proofWireVersion());
                write(out, descriptor.verificationTarget().name());
                write(out, descriptor.visibility().name());
                write(out, descriptor.sourceCommitmentAlgorithm());
                write(out, descriptor.sourceCommitmentWireVersion());
                out.writeLong(descriptor.maxEntriesPerChunk());
                out.writeLong(descriptor.maxChunkBytes());
                out.writeLong(descriptor.maxKeyBytes());
                out.writeLong(descriptor.maxValueBytes());
                out.writeLong(descriptor.maxEntriesPerSnapshot());
                write(out, descriptor.recoveryCoverage().name());
            }
            out.flush();
            return Blake2bUtil.blake2bHash256(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static AuthenticatedSnapshotSettings from(AppChainConfig config) {
        Map<String, String> source = config.pluginSettings();
        boolean enabled = strictBoolean(source.getOrDefault(ENABLED, "false"), ENABLED);
        String configuredArchive = source.get(ARCHIVE_DIRECTORY);
        java.nio.file.Path archiveDirectory;
        if (configuredArchive != null) {
            archiveDirectory = java.nio.file.Path.of(configuredArchive);
        } else if (config.ledgerPath() != null && !config.ledgerPath().isBlank()) {
            archiveDirectory = java.nio.file.Path.of(config.ledgerPath())
                    .resolve("authenticated-snapshot-archives");
        } else if (enabled) {
            throw new IllegalArgumentException(ARCHIVE_DIRECTORY
                    + " is required when the app-chain ledger path is not configured");
        } else {
            archiveDirectory = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
                    "yano-disabled-snapshots", config.chainId());
        }
        long archiveMaxNodes = positiveLong(source.getOrDefault(
                ARCHIVE_MAX_NODES, "10000000"), ARCHIVE_MAX_NODES);
        long archiveMaxBytes = positiveLong(source.getOrDefault(
                ARCHIVE_MAX_BYTES, Long.toString(64L * 1024 * 1024 * 1024)), ARCHIVE_MAX_BYTES);
        int proofConcurrency = positiveInt(source.getOrDefault(PROOF_CONCURRENCY, "32"),
                PROOF_CONCURRENCY);
        if (proofConcurrency > 1024) throw new IllegalArgumentException(
                PROOF_CONCURRENCY + " must be at most 1024");
        boolean retentionEnabled = strictBoolean(source.getOrDefault(RETENTION_ENABLED, "false"),
                RETENTION_ENABLED);
        int keepOnlineCount = positiveInt(source.getOrDefault(KEEP_ONLINE_COUNT, "10"),
                KEEP_ONLINE_COUNT);
        boolean evictAfterArchive = strictBoolean(source.getOrDefault(
                EVICT_AFTER_ARCHIVE, "true"), EVICT_AFTER_ARCHIVE);
        long retentionInterval = positiveLong(source.getOrDefault(RETENTION_INTERVAL, "300"),
                RETENTION_INTERVAL);
        if (retentionInterval < 10) throw new IllegalArgumentException(
                RETENTION_INTERVAL + " must be at least 10 seconds");
        boolean mpfPruningEnabled = strictBoolean(source.getOrDefault(
                MPF_PRUNING_ENABLED, "false"), MPF_PRUNING_ENABLED);
        if (!enabled) return new AuthenticatedSnapshotSettings(false, Set.of(), 0, 0,
                new byte[32], proofConcurrency, false, keepOnlineCount, evictAfterArchive,
                retentionInterval, mpfPruningEnabled, archiveDirectory,
                archiveMaxNodes, archiveMaxBytes);
        String rawSeries = source.getOrDefault(SERIES, "all");
        Set<String> series = "all".equals(rawSeries) ? Set.of("*")
                : Arrays.stream(rawSeries.split(",", -1)).map(String::trim)
                .peek(value -> requireSeriesId(value, SERIES)).collect(java.util.stream.Collectors.toSet());
        if (series.isEmpty()) throw new IllegalArgumentException(SERIES + " must not be empty");
        int operations = positiveInt(source.getOrDefault(MAX_OPERATIONS, "32768"), MAX_OPERATIONS);
        long bytes = positiveLong(source.getOrDefault(MAX_BYTES,
                Long.toString(Math.min(config.blockMaxBytes(), 4L * 1024 * 1024))), MAX_BYTES);
        TreeMap<String, String> committed = new TreeMap<>();
        source.forEach((key, value) -> {
            if (key.startsWith(PREFIX) && !key.equals(ARCHIVE_DIRECTORY)
                    && !key.equals(ARCHIVE_MAX_NODES) && !key.equals(ARCHIVE_MAX_BYTES)
                    && !key.equals(PROOF_CONCURRENCY) && !key.equals(RETENTION_ENABLED)
                    && !key.equals(KEEP_ONLINE_COUNT) && !key.equals(EVICT_AFTER_ARCHIVE)
                    && !key.equals(RETENTION_INTERVAL) && !key.equals(MPF_PRUNING_ENABLED)) {
                committed.put(key, value);
            }
        });
        return new AuthenticatedSnapshotSettings(true, series, operations, bytes, digest(committed),
                proofConcurrency, retentionEnabled, keepOnlineCount, evictAfterArchive,
                retentionInterval, mpfPruningEnabled, archiveDirectory,
                archiveMaxNodes, archiveMaxBytes);
    }

    List<AuthenticatedSnapshotSeriesDescriptorV1> select(
            List<AuthenticatedSnapshotSeriesDescriptorV1> declarations,
            StateCommitmentProfile primaryProfile,
            boolean l1ProofRequired) {
        Map<String, AuthenticatedSnapshotSeriesDescriptorV1> unique = new LinkedHashMap<>();
        declarations.stream().sorted(java.util.Comparator.comparing(
                AuthenticatedSnapshotSeriesDescriptorV1::seriesId)).forEach(value -> {
            if (unique.putIfAbsent(value.seriesId(), value) != null) {
                throw new IllegalArgumentException("duplicate authenticated snapshot series: " + value.seriesId());
            }
        });
        List<AuthenticatedSnapshotSeriesDescriptorV1> selected = unique.values().stream()
                .filter(value -> selectedSeries.contains("*") || selectedSeries.contains(value.seriesId()))
                .toList();
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("authenticated snapshots enabled without a selected declared series");
        }
        if (!selectedSeries.contains("*")) {
            for (String id : selectedSeries) {
                if (!unique.containsKey(id)) throw new IllegalArgumentException(
                        "unknown authenticated snapshot series: " + id);
            }
        }
        for (var declaration : selected) {
            if (declaration.verificationTarget()
                    == AuthenticatedSnapshotSeriesDescriptorV1.VerificationTarget.ON_CHAIN) {
                if (primaryProfile.backendFamily() != StateCommitmentProfile.BackendFamily.MPF
                        || !declaration.snapshotProfile().startsWith("mpf-") || !l1ProofRequired) {
                    throw new IllegalArgumentException("on-chain snapshot series requires MPF primary, "
                            + "MPF secondary, and state.l1-proof-consumption-required=true: "
                            + declaration.seriesId());
                }
                if (declaration.maxKeyBytes() > 256 || declaration.maxValueBytes() > 8 * 1024) {
                    throw new IllegalArgumentException("on-chain snapshot series exceeds the released "
                            + "256-byte key / 8-KiB value envelope: " + declaration.seriesId());
                }
            }
        }
        return selected;
    }

    private static boolean strictBoolean(String raw, String name) {
        if (!"true".equals(raw) && !"false".equals(raw)) {
            throw new IllegalArgumentException(name + " must be true or false");
        }
        return Boolean.parseBoolean(raw);
    }

    private static int positiveInt(String value, String name) {
        try {
            int result = Integer.parseInt(value);
            if (result <= 0) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException(name + " must be a positive integer", malformed);
        }
    }

    private static long positiveLong(String value, String name) {
        try {
            long result = Long.parseLong(value);
            if (result <= 0) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException(name + " must be a positive integer", malformed);
        }
    }

    private static void requireSeriesId(String value, String name) {
        if (!value.matches("[a-z0-9][a-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(name + " contains an invalid series id");
        }
    }

    private static byte[] digest(Map<String, String> settings) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.writeBytes("yano-authenticated-snapshot-settings-v1\0"
                    .getBytes(StandardCharsets.US_ASCII));
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(settings.size());
            for (var entry : settings.entrySet()) {
                write(out, entry.getKey());
                write(out, entry.getValue());
            }
            out.flush();
            return Blake2bUtil.blake2bHash256(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void write(DataOutputStream out, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(encoded.length);
        out.write(encoded);
    }
}
