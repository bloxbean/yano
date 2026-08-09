package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yano.api.appchain.l1view.EpochObservationManifest;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochBoundary;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Durable, byte-bounded ADR-028 epoch-observation spool and local outbox. */
final class EpochObservationSpool {
    static final long DEFAULT_MAX_BYTES = 512L * 1024 * 1024;
    private static final int MAX_JOBS = 4_096;
    private static final int MAX_RECORDS_PER_SCAN = 1_000_001;
    private static final int FORMAT_VERSION = 1;
    private static final byte[] JOB_PREFIX = new byte[]{'j'};
    private static final byte[] DIGEST_PREFIX = new byte[]{'d'};
    private static final byte[] RECORD_PREFIX = new byte[]{'r'};
    private static final byte[] VERIFY_PREFIX = new byte[]{'v'};

    enum State {
        GENERATING,
        READY,
        OFFERED,
        FINALIZED
    }

    record Offered(L1Observation observation, byte[] jobDigest, int index) {
        Offered {
            jobDigest = jobDigest.clone();
        }

        @Override public byte[] jobDigest() { return jobDigest.clone(); }
    }

    private final AppLedgerStore ledger;
    private final long maxBytes;
    private long usedBytes;

    EpochObservationSpool(AppLedgerStore ledger, long maxBytes) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("epoch observation spool max bytes must be positive");
        }
        this.maxBytes = maxBytes;
        this.usedBytes = calculateUsedBytes();
        if (usedBytes > maxBytes) {
            throw new IllegalStateException("Epoch-observation spool exceeds its configured byte bound");
        }
    }

    synchronized void begin(L1EpochBoundary boundary, EpochObservationManifest manifest) {
        validateIdentity(boundary, manifest);
        byte[] key = jobKey(manifest.observerId(), manifest.newEpoch(), manifest.snapshotRoot());
        byte[] digest = digest(key);
        removeJobIfPresent(key, digest);
        Job job = new Job(State.GENERATING, boundary, manifest, 0, 0, 0);
        writeChecked(List.of(
                AppLedgerStore.EpochSpoolMutation.put(key, encodeJob(job)),
                AppLedgerStore.EpochSpoolMutation.put(digestKey(digest), key)));
    }

    synchronized void append(L1EpochBoundary boundary,
                             EpochObservationManifest manifest,
                             int index,
                             byte[] claim) {
        Objects.requireNonNull(claim, "claim");
        byte[] key = jobKey(manifest.observerId(), manifest.newEpoch(), manifest.snapshotRoot());
        Job job = requireJob(key);
        if (job.state() != State.GENERATING || index != job.nextIndex()
                || index < 0 || index >= manifest.observationCount()) {
            throw new IllegalStateException("Epoch observations must be generated exactly once in order");
        }
        validateIdentity(boundary, manifest);
        if (!job.boundary().equals(boundary) || !job.manifest().equals(manifest)) {
            throw new IllegalStateException("Epoch observation generation identity changed between passes");
        }
        L1Observation observation = L1Observation.epoch(
                manifest.observerId(), boundary.newEpoch(), boundary.boundarySlot(),
                boundary.boundaryBlockHash(), claim);
        byte[] observationBytes = observation.encode();
        if (observationBytes.length > AppChainConfig.MAX_MESSAGE_BYTES) {
            throw new IllegalStateException(
                    "Epoch observation exceeds the released per-message byte bound");
        }
        byte[] digest = digest(key);
        byte[] recordKey = recordKey(digest, index);
        byte[] verifyKey = verifyKey(digest(observationBytes));
        if (ledger.epochSpoolGet(recordKey) != null || ledger.epochSpoolGet(verifyKey) != null) {
            throw new IllegalStateException("Duplicate epoch observation record identity");
        }
        Record record = new Record(State.READY, observationBytes);
        Job advanced = job.withProgress(index + 1,
                Math.addExact(job.payloadBytes(), observationBytes.length), job.finalizedCount());
        writeChecked(List.of(
                AppLedgerStore.EpochSpoolMutation.put(recordKey, encodeRecord(record)),
                AppLedgerStore.EpochSpoolMutation.put(verifyKey, verifyValue(digest, index)),
                AppLedgerStore.EpochSpoolMutation.put(key, encodeJob(advanced))));
    }

    synchronized void complete(EpochObservationManifest manifest) {
        byte[] key = jobKey(manifest.observerId(), manifest.newEpoch(), manifest.snapshotRoot());
        Job job = requireJob(key);
        if (job.state() != State.GENERATING
                || job.nextIndex() != manifest.observationCount()) {
            throw new IllegalStateException("Epoch observation generation ended before all records were written");
        }
        replace(key, encodeJob(job.withState(State.READY)));
    }

    synchronized List<Offered> offer(long maximumBoundaryBlockNumber,
                                    int maxMessages,
                                    long maxPayloadBytes) {
        if (maxMessages <= 0 || maxPayloadBytes <= 0) {
            return List.of();
        }
        List<JobEntry> jobs = jobs().stream()
                .filter(entry -> entry.job().state() == State.READY
                        || entry.job().state() == State.OFFERED)
                .filter(entry -> entry.job().boundary().boundaryBlockNumber()
                        <= maximumBoundaryBlockNumber)
                .sorted(Comparator.comparingLong((JobEntry value) ->
                                value.job().boundary().boundaryBlockNumber())
                        .thenComparing(value -> value.job().manifest().observerId()))
                .toList();
        List<Offered> offered = new ArrayList<>();
        long bytes = 0;
        for (JobEntry entry : jobs) {
            byte[] jobDigest = digest(entry.key());
            List<AppLedgerStore.EpochSpoolEntry> records = ledger.epochSpoolScan(
                    recordPrefix(jobDigest), MAX_RECORDS_PER_SCAN);
            boolean outstanding = records.stream()
                    .map(encoded -> decodeRecord(encoded.value()))
                    .anyMatch(record -> record.state() == State.OFFERED);
            if (outstanding) {
                continue;
            }
            for (AppLedgerStore.EpochSpoolEntry encoded : records) {
                Record record = decodeRecord(encoded.value());
                if (record.state() != State.READY) {
                    continue;
                }
                int nextBytes = record.observationBytes().length;
                if (offered.size() == maxMessages || bytes > maxPayloadBytes - nextBytes) {
                    return List.copyOf(offered);
                }
                int index = recordIndex(encoded.key());
                Record claimed = record.withState(State.OFFERED);
                replace(encoded.key(), encodeRecord(claimed));
                offered.add(new Offered(requireObservation(record.observationBytes()),
                        jobDigest, index));
                bytes += nextBytes;
            }
        }
        return List.copyOf(offered);
    }

    synchronized void offerFailed(Offered offered) {
        byte[] key = recordKey(offered.jobDigest(), offered.index());
        byte[] encoded = ledger.epochSpoolGet(key);
        if (encoded == null) {
            return;
        }
        Record record = decodeRecord(encoded);
        if (record.state() == State.OFFERED) {
            replace(key, encodeRecord(record.withState(State.READY)));
        }
    }

    /** Reset process-local offers after restart or loss of proposership. */
    synchronized void releaseOffers() {
        for (JobEntry entry : jobs()) {
            byte[] jobDigest = digest(entry.key());
            boolean changed = false;
            for (AppLedgerStore.EpochSpoolEntry encoded : ledger.epochSpoolScan(
                    recordPrefix(jobDigest), MAX_RECORDS_PER_SCAN)) {
                Record record = decodeRecord(encoded.value());
                if (record.state() == State.OFFERED) {
                    replace(encoded.key(), encodeRecord(record.withState(State.READY)));
                    changed = true;
                }
            }
            if (changed && entry.job().state() == State.OFFERED) {
                replace(entry.key(), encodeJob(entry.job().withState(State.READY)));
            }
        }
    }

    synchronized boolean acknowledge(L1Observation observation) {
        byte[] observationBytes = observation.encode();
        byte[] location = ledger.epochSpoolGet(verifyKey(digest(observationBytes)));
        if (location == null) {
            return false;
        }
        Location resolved = decodeLocation(location);
        byte[] jobKey = ledger.epochSpoolGet(digestKey(resolved.jobDigest()));
        if (jobKey == null) {
            return false;
        }
        Job job = requireJob(jobKey);
        byte[] recordKey = recordKey(resolved.jobDigest(), resolved.index());
        byte[] encoded = ledger.epochSpoolGet(recordKey);
        if (encoded == null) {
            return false;
        }
        Record record = decodeRecord(encoded);
        if (!Arrays.equals(record.observationBytes(), observationBytes)) {
            throw new IllegalStateException("Epoch-observation verification index is corrupt");
        }
        if (record.state() == State.FINALIZED) {
            return true;
        }
        int finalized = Math.addExact(job.finalizedCount(), 1);
        State jobState = finalized == job.manifest().observationCount()
                ? State.FINALIZED : State.OFFERED;
        writeChecked(List.of(
                AppLedgerStore.EpochSpoolMutation.put(recordKey,
                        encodeRecord(record.withState(State.FINALIZED))),
                AppLedgerStore.EpochSpoolMutation.put(jobKey,
                        encodeJob(job.withProgress(job.nextIndex(), job.payloadBytes(), finalized)
                                .withState(jobState)))));
        return true;
    }

    synchronized AppChainEngine.L1RefVerdict verify(L1Observation observation,
                                                    boolean historicalCatchUp) {
        byte[] observationBytes = observation.encode();
        byte[] location = ledger.epochSpoolGet(verifyKey(digest(observationBytes)));
        if (location == null) {
            return observation.epochAnchor().newEpoch() > latestPreparedEpoch()
                    ? AppChainEngine.L1RefVerdict.AHEAD
                    : AppChainEngine.L1RefVerdict.MISMATCH;
        }
        Location resolved = decodeLocation(location);
        byte[] jobKey = ledger.epochSpoolGet(digestKey(resolved.jobDigest()));
        if (jobKey == null) {
            return AppChainEngine.L1RefVerdict.MISMATCH;
        }
        Job job = requireJob(jobKey);
        if (job.state() == State.GENERATING) {
            return AppChainEngine.L1RefVerdict.AHEAD;
        }
        byte[] encoded = ledger.epochSpoolGet(
                recordKey(resolved.jobDigest(), resolved.index()));
        if (encoded == null) {
            return AppChainEngine.L1RefVerdict.MISMATCH;
        }
        Record record = decodeRecord(encoded);
        if (!Arrays.equals(record.observationBytes(), observationBytes)) {
            return AppChainEngine.L1RefVerdict.MISMATCH;
        }
        return !historicalCatchUp && record.state() == State.FINALIZED
                ? AppChainEngine.L1RefVerdict.MISMATCH
                : AppChainEngine.L1RefVerdict.OK;
    }

    synchronized void rollback(long rollbackToSlot) {
        boolean finalizedBoundaryCrossed = jobs().stream().anyMatch(entry ->
                entry.job().state() == State.FINALIZED
                        && entry.job().boundary().boundarySlot() > rollbackToSlot);
        if (finalizedBoundaryCrossed) {
            throw new IllegalStateException(
                    "DEEP_ROLLBACK_BELOW_FINALIZED_EPOCH_ATTESTATION");
        }
        for (JobEntry entry : jobs()) {
            if (entry.job().boundary().boundarySlot() > rollbackToSlot
                    && entry.job().state() != State.FINALIZED) {
                removeJobIfPresent(entry.key(), digest(entry.key()));
            }
        }
    }

    synchronized List<L1EpochBoundary> boundaries() {
        return jobs().stream().map(entry -> entry.job().boundary()).distinct().toList();
    }

    synchronized boolean prepared(String observerId, long newEpoch) {
        return jobs().stream().anyMatch(entry ->
                entry.job().manifest().observerId().equals(observerId)
                        && entry.job().manifest().newEpoch() == newEpoch
                        && entry.job().state() != State.GENERATING);
    }

    synchronized Map<String, Object> status() {
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, Long> recordCounts = new LinkedHashMap<>();
        for (State state : State.values()) {
            counts.put(state.name().toLowerCase(java.util.Locale.ROOT), 0L);
            recordCounts.put(state.name().toLowerCase(java.util.Locale.ROOT), 0L);
        }
        for (JobEntry entry : jobs()) {
            counts.compute(entry.job().state().name().toLowerCase(java.util.Locale.ROOT),
                    (ignored, value) -> value + 1);
            for (AppLedgerStore.EpochSpoolEntry encoded : ledger.epochSpoolScan(
                    recordPrefix(digest(entry.key())), MAX_RECORDS_PER_SCAN)) {
                Record record = decodeRecord(encoded.value());
                recordCounts.compute(record.state().name().toLowerCase(java.util.Locale.ROOT),
                        (ignored, value) -> value + 1);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobs", counts);
        result.put("records", recordCounts);
        result.put("usedBytes", usedBytes);
        result.put("maxBytes", maxBytes);
        result.put("latestPreparedEpoch", latestPreparedEpoch());
        return Map.copyOf(result);
    }

    private long latestPreparedEpoch() {
        return jobs().stream().mapToLong(entry -> entry.job().manifest().newEpoch())
                .max().orElse(-1);
    }

    private List<JobEntry> jobs() {
        List<JobEntry> result = new ArrayList<>();
        for (AppLedgerStore.EpochSpoolEntry encoded : ledger.epochSpoolScan(
                JOB_PREFIX, MAX_JOBS + 1)) {
            result.add(new JobEntry(encoded.key(), decodeJob(encoded.value())));
        }
        if (result.size() > MAX_JOBS) {
            throw new IllegalStateException("Epoch-observation spool job bound exceeded");
        }
        return List.copyOf(result);
    }

    private void removeJobIfPresent(byte[] key, byte[] digest) {
        byte[] encodedJob = ledger.epochSpoolGet(key);
        if (encodedJob == null) {
            return;
        }
        List<AppLedgerStore.EpochSpoolMutation> mutations = new ArrayList<>();
        for (AppLedgerStore.EpochSpoolEntry encoded : ledger.epochSpoolScan(
                recordPrefix(digest), MAX_RECORDS_PER_SCAN)) {
            Record record = decodeRecord(encoded.value());
            mutations.add(AppLedgerStore.EpochSpoolMutation.delete(encoded.key()));
            mutations.add(AppLedgerStore.EpochSpoolMutation.delete(
                    verifyKey(digest(record.observationBytes()))));
        }
        mutations.add(AppLedgerStore.EpochSpoolMutation.delete(digestKey(digest)));
        mutations.add(AppLedgerStore.EpochSpoolMutation.delete(key));
        writeChecked(mutations);
    }

    private long calculateUsedBytes() {
        long total = 0;
        for (byte prefix : new byte[]{'j', 'd', 'r', 'v'}) {
            for (AppLedgerStore.EpochSpoolEntry entry : ledger.epochSpoolScan(
                    new byte[]{prefix}, Integer.MAX_VALUE)) {
                total = Math.addExact(total, entry.key().length + entry.value().length);
            }
        }
        return total;
    }

    private void replace(byte[] key, byte[] value) {
        writeChecked(List.of(AppLedgerStore.EpochSpoolMutation.put(key, value)));
    }

    private void writeChecked(List<AppLedgerStore.EpochSpoolMutation> mutations) {
        long delta = 0;
        for (AppLedgerStore.EpochSpoolMutation mutation : mutations) {
            byte[] key = mutation.key();
            byte[] before = ledger.epochSpoolGet(key);
            byte[] after = mutation.value();
            delta = Math.addExact(delta,
                    (after != null ? key.length + after.length : 0)
                            - (before != null ? key.length + before.length : 0));
        }
        if (delta > 0 && usedBytes > maxBytes - delta) {
            throw new IllegalStateException("Epoch-observation spool byte bound exceeded");
        }
        ledger.epochSpoolWrite(mutations);
        usedBytes = Math.addExact(usedBytes, delta);
    }

    private Job requireJob(byte[] key) {
        byte[] value = ledger.epochSpoolGet(key);
        if (value == null) {
            throw new IllegalStateException("Epoch-observation job is missing");
        }
        return decodeJob(value);
    }

    private static void validateIdentity(L1EpochBoundary boundary,
                                         EpochObservationManifest manifest) {
        if (boundary.previousEpoch() != manifest.previousEpoch()
                || boundary.newEpoch() != manifest.newEpoch()) {
            throw new IllegalArgumentException("Manifest epochs do not match the L1 boundary");
        }
    }

    private static byte[] jobKey(String observerId, long newEpoch, byte[] root) {
        byte[] observer = observerId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(1 + 2 + observer.length + 8 + 32);
        return buffer.put((byte) 'j').putShort((short) observer.length).put(observer)
                .putLong(newEpoch).put(root).array();
    }

    private static byte[] digestKey(byte[] digest) {
        return ByteBuffer.allocate(33).put((byte) 'd').put(digest).array();
    }

    private static byte[] recordPrefix(byte[] digest) {
        return ByteBuffer.allocate(33).put((byte) 'r').put(digest).array();
    }

    private static byte[] recordKey(byte[] digest, int index) {
        return ByteBuffer.allocate(37).put((byte) 'r').put(digest).putInt(index).array();
    }

    private static int recordIndex(byte[] key) {
        if (key.length != 37 || key[0] != 'r') {
            throw new IllegalStateException("Malformed epoch-observation record key");
        }
        return ByteBuffer.wrap(key, 33, 4).getInt();
    }

    private static byte[] verifyKey(byte[] digest) {
        return ByteBuffer.allocate(33).put((byte) 'v').put(digest).array();
    }

    private static byte[] verifyValue(byte[] jobDigest, int index) {
        return ByteBuffer.allocate(36).put(jobDigest).putInt(index).array();
    }

    private static Location decodeLocation(byte[] value) {
        if (value.length != 36) {
            throw new IllegalStateException("Malformed epoch-observation verification index");
        }
        ByteBuffer buffer = ByteBuffer.wrap(value);
        byte[] digest = new byte[32];
        buffer.get(digest);
        return new Location(digest, buffer.getInt());
    }

    private static byte[] digest(byte[] value) {
        return Blake2bUtil.blake2bHash256(value);
    }

    private static byte[] encodeJob(Job job) {
        return encode(output -> {
            output.writeByte(FORMAT_VERSION);
            output.writeByte(job.state().ordinal());
            L1EpochBoundary boundary = job.boundary();
            EpochObservationManifest manifest = job.manifest();
            output.writeLong(boundary.previousEpoch());
            output.writeLong(boundary.newEpoch());
            output.writeLong(boundary.boundarySlot());
            output.write(boundary.boundaryBlockHash());
            output.writeLong(boundary.boundaryBlockNumber());
            writeText(output, manifest.observerId());
            output.writeLong(manifest.datasetEpoch());
            output.writeLong(manifest.totalEntries());
            output.writeInt(manifest.chunkEntries());
            output.writeInt(manifest.chunkCount());
            output.write(manifest.snapshotRoot());
            output.writeInt(job.nextIndex());
            output.writeLong(job.payloadBytes());
            output.writeInt(job.finalizedCount());
        });
    }

    private static Job decodeJob(byte[] value) {
        return decode(value, input -> {
            requireVersion(input);
            State state = state(input.readUnsignedByte());
            long previousEpoch = input.readLong();
            long newEpoch = input.readLong();
            long slot = input.readLong();
            byte[] blockHash = input.readNBytes(32);
            long blockNumber = input.readLong();
            String observerId = readText(input);
            long datasetEpoch = input.readLong();
            long totalEntries = input.readLong();
            int chunkEntries = input.readInt();
            int chunkCount = input.readInt();
            byte[] root = input.readNBytes(32);
            int nextIndex = input.readInt();
            long payloadBytes = input.readLong();
            int finalizedCount = input.readInt();
            L1EpochBoundary boundary = new L1EpochBoundary(previousEpoch, newEpoch,
                    slot, blockHash, blockNumber);
            EpochObservationManifest manifest = new EpochObservationManifest(
                    EpochObservationManifest.VERSION, observerId, previousEpoch, newEpoch,
                    datasetEpoch, totalEntries, chunkEntries, chunkCount, root);
            return new Job(state, boundary, manifest, nextIndex, payloadBytes, finalizedCount);
        });
    }

    private static byte[] encodeRecord(Record record) {
        return encode(output -> {
            output.writeByte(FORMAT_VERSION);
            output.writeByte(record.state().ordinal());
            output.writeInt(record.observationBytes().length);
            output.write(record.observationBytes());
        });
    }

    private static Record decodeRecord(byte[] value) {
        return decode(value, input -> {
            requireVersion(input);
            State state = state(input.readUnsignedByte());
            int length = input.readInt();
            if (length < 0 || length > com.bloxbean.cardano.yano.api.appchain.AppChainConfig
                    .MAX_MESSAGE_BYTES || length != input.available()) {
                throw new IllegalStateException("Malformed epoch-observation record length");
            }
            return new Record(state, input.readNBytes(length));
        });
    }

    private static L1Observation requireObservation(byte[] value) {
        L1Observation observation = L1Observation.decode(value);
        if (observation == null || !(observation.anchor() instanceof L1Observation.EpochAnchor)) {
            throw new IllegalStateException("Epoch-observation spool contains invalid wire bytes");
        }
        return observation;
    }

    private static State state(int ordinal) {
        if (ordinal < 0 || ordinal >= State.values().length) {
            throw new IllegalStateException("Malformed epoch-observation state");
        }
        return State.values()[ordinal];
    }

    private static void requireVersion(DataInputStream input) throws IOException {
        if (input.readUnsignedByte() != FORMAT_VERSION) {
            throw new IllegalStateException("Unsupported epoch-observation spool format");
        }
    }

    private static void writeText(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 128) {
            throw new IllegalArgumentException("Epoch observer id exceeds 128 UTF-8 bytes");
        }
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static String readText(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        if (length == 0 || length > 128) {
            throw new IllegalStateException("Malformed epoch observer id length");
        }
        return new String(input.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static byte[] encode(IoWriter writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writer.write(output);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    private static <T> T decode(byte[] value, IoReader<T> reader) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(value))) {
            T result = reader.read(input);
            if (input.available() != 0) {
                throw new IllegalStateException("Trailing epoch-observation spool bytes");
            }
            return result;
        } catch (IOException failure) {
            throw new IllegalStateException("Malformed epoch-observation spool bytes", failure);
        }
    }

    private record Job(State state,
                       L1EpochBoundary boundary,
                       EpochObservationManifest manifest,
                       int nextIndex,
                       long payloadBytes,
                       int finalizedCount) {
        private Job withState(State value) {
            return new Job(value, boundary, manifest, nextIndex, payloadBytes, finalizedCount);
        }

        private Job withProgress(int next, long bytes, int finalized) {
            return new Job(state, boundary, manifest, next, bytes, finalized);
        }
    }

    private record Record(State state, byte[] observationBytes) {
        private Record {
            observationBytes = observationBytes.clone();
        }

        @Override public byte[] observationBytes() { return observationBytes.clone(); }

        private Record withState(State value) {
            return new Record(value, observationBytes);
        }
    }

    private record JobEntry(byte[] key, Job job) {
        private JobEntry {
            key = key.clone();
        }

        @Override public byte[] key() { return key.clone(); }
    }

    private record Location(byte[] jobDigest, int index) {
        private Location {
            jobDigest = jobDigest.clone();
        }

        @Override public byte[] jobDigest() { return jobDigest.clone(); }
    }

    @FunctionalInterface
    private interface IoWriter {
        void write(DataOutputStream output) throws IOException;
    }

    @FunctionalInterface
    private interface IoReader<T> {
        T read(DataInputStream input) throws IOException;
    }
}
