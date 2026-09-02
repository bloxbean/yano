package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import org.rocksdb.WriteBatch;

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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Durable, bounded, node-local delivery journal for block-scoped L1 observations. */
final class L1ObservationJournal {
    static final long DEFAULT_MAX_BYTES = 512L * 1024 * 1024;
    static final int DEFAULT_MAX_ENTRIES = 100_000;
    private static final byte[] RECORD_PREFIX = new byte[]{'B'};
    private static final byte[] SOURCE_PREFIX = new byte[]{'S'};
    private static final byte[] CURSOR_PREFIX = new byte[]{'C'};
    private static final byte[] PROFILE_KEY = new byte[]{'P'};
    private static final byte[] QUARANTINE_KEY = new byte[]{'Q'};
    private static final byte[] CALLBACK_FAILURE_KEY = new byte[]{'F'};
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_SCAN = 1_000_001;

    enum State {
        SEEN_UNSTABLE,
        STABLE_PENDING,
        IN_FLIGHT,
        QC_PREPARED,
        QUARANTINED,
        FINALIZED
    }

    private record Record(State state, byte[] observationBytes) {
        private Record {
            state = Objects.requireNonNull(state, "state");
            observationBytes = observationBytes.clone();
        }
    }

    private final AppLedgerStore ledger;
    private final long maxBytes;
    private final int maxEntries;
    private final byte[] identityContext;
    private long usedBytes;

    L1ObservationJournal(AppLedgerStore ledger, long maxBytes) {
        this(ledger, maxBytes, DEFAULT_MAX_ENTRIES, new byte[32]);
    }

    L1ObservationJournal(AppLedgerStore ledger, long maxBytes, byte[] identityContext) {
        this(ledger, maxBytes, DEFAULT_MAX_ENTRIES, identityContext);
    }

    L1ObservationJournal(AppLedgerStore ledger, long maxBytes, int maxEntries,
                         byte[] identityContext) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("observation journal max bytes must be positive");
        }
        this.maxBytes = maxBytes;
        if (maxEntries <= 0 || maxEntries >= MAX_SCAN) {
            throw new IllegalArgumentException(
                    "observation journal max entries must be in [1, "
                            + (MAX_SCAN - 1) + "]");
        }
        this.maxEntries = maxEntries;
        this.identityContext = Objects.requireNonNull(identityContext, "identityContext").clone();
        if (this.identityContext.length != 32) {
            throw new IllegalArgumentException("observation identity context must be 32 bytes");
        }
        byte[] retainedContext = ledger.epochSpoolGet(PROFILE_KEY);
        if (retainedContext == null) {
            ledger.epochSpoolWrite(List.of(
                    AppLedgerStore.EpochSpoolMutation.put(PROFILE_KEY, this.identityContext)));
        } else if (!Arrays.equals(retainedContext, this.identityContext)) {
            if (ledger.epochSpoolScan(RECORD_PREFIX, 1).isEmpty()
                    && ledger.epochSpoolScan(SOURCE_PREFIX, 1).isEmpty()) {
                // Startup can fail after creating an empty journal. With no
                // observed facts there is no identity-bearing state to retain,
                // so a later corrected configuration may safely reuse the path.
                ledger.epochSpoolWrite(List.of(
                        AppLedgerStore.EpochSpoolMutation.put(
                                PROFILE_KEY, this.identityContext)));
            } else {
                throw new IllegalStateException(
                        "L1 observation journal profile differs from configuration");
            }
        }
        this.usedBytes = calculateUsedBytes();
        if (usedBytes > maxBytes) {
            throw new IllegalStateException("L1 observation journal exceeds configured capacity");
        }
        if (recordCount() > maxEntries) {
            throw new IllegalStateException("L1 observation journal exceeds configured entry limit");
        }
    }

    synchronized void observe(List<L1Observation> observations) {
        List<AppLedgerStore.EpochSpoolMutation> mutations = new ArrayList<>();
        long addedBytes = 0;
        int addedEntries = 0;
        Map<String, byte[]> stagedSources = new LinkedHashMap<>();
        Map<String, byte[]> stagedRecords = new LinkedHashMap<>();
        for (L1Observation observation : observations) {
            byte[] encoded = observation.encode();
            byte[] observationId = observationId(observation);
            byte[] recordKey = recordKey(observation, observationId);
            byte[] sourceKey = sourceKey(observation);
            String sourceHex = HexFormat.of().formatHex(sourceKey);
            byte[] existingSource = stagedSources.get(sourceHex);
            if (existingSource == null) {
                existingSource = ledger.epochSpoolGet(sourceKey);
            }
            if (existingSource != null && !Arrays.equals(existingSource, observationId)) {
                throw new IllegalStateException("CONFLICTING_L1_OBSERVATION_SOURCE");
            }
            String recordHex = HexFormat.of().formatHex(recordKey);
            byte[] existing = stagedRecords.get(recordHex);
            if (existing == null) {
                existing = ledger.epochSpoolGet(recordKey);
            }
            if (existing == null && existingSource != null) {
                // The source mapping is the compact finalized tombstone. It
                // preserves deduplication without retaining the claim bytes.
                continue;
            }
            if (existing != null) {
                if (!Arrays.equals(decodeRecord(existing).observationBytes(), encoded)) {
                    throw new IllegalStateException("L1 observation journal identity collision");
                }
                continue;
            }
            byte[] value = encodeRecord(new Record(State.SEEN_UNSTABLE, encoded));
            stagedSources.put(sourceHex, observationId);
            stagedRecords.put(recordHex, value);
            mutations.add(AppLedgerStore.EpochSpoolMutation.put(sourceKey, observationId));
            mutations.add(AppLedgerStore.EpochSpoolMutation.put(recordKey, value));
            addedEntries++;
            addedBytes = Math.addExact(addedBytes,
                    sourceKey.length + observationId.length + recordKey.length + value.length);
        }
        if (usedBytes > maxBytes - addedBytes) {
            throw new IllegalStateException("L1_OBSERVATION_JOURNAL_CAPACITY");
        }
        if (recordCount() > maxEntries - addedEntries) {
            throw new IllegalStateException("L1_OBSERVATION_JOURNAL_ENTRY_CAPACITY");
        }
        ledger.epochSpoolWrite(mutations);
        usedBytes += addedBytes;
    }

    synchronized List<L1Observation> pending(long stableSlot, int maxCount, long maxBytes) {
        if (!healthy() || maxCount <= 0 || maxBytes <= 0) {
            return List.of();
        }
        List<L1Observation> pending = new ArrayList<>();
        List<AppLedgerStore.EpochSpoolMutation> stableMutations = new ArrayList<>();
        long selectedBytes = 0;
        for (AppLedgerStore.EpochSpoolEntry entry : ledger.epochSpoolScan(
                RECORD_PREFIX, MAX_SCAN)) {
            Record record = decodeRecord(entry.value());
            L1Observation observation = requireObservation(record.observationBytes());
            if (observation.slot() > stableSlot) {
                break;
            }
            if (record.state() == State.FINALIZED
                    || record.state() == State.QUARANTINED) {
                continue;
            }
            if (record.state() == State.SEEN_UNSTABLE) {
                stableMutations.add(AppLedgerStore.EpochSpoolMutation.put(entry.key(),
                        encodeRecord(new Record(State.STABLE_PENDING,
                                record.observationBytes()))));
            }
            int bytes = record.observationBytes().length;
            if (pending.size() == maxCount || selectedBytes > maxBytes - bytes) {
                break;
            }
            pending.add(observation);
            selectedBytes += bytes;
        }
        ledger.epochSpoolWrite(stableMutations);
        return List.copyOf(pending);
    }

    synchronized boolean acknowledge(L1Observation observation) {
        byte[] encoded = observation.encode();
        byte[] key = recordKey(observation, observationId(observation));
        byte[] existing = ledger.epochSpoolGet(key);
        if (existing == null) {
            return false;
        }
        Record record = decodeRecord(existing);
        if (!Arrays.equals(record.observationBytes(), encoded)) {
            throw new IllegalStateException("L1 observation journal acknowledgement mismatch");
        }
        if (record.state() == State.FINALIZED
                && hasFinalizedCursor(observation, key)) {
            ledger.epochSpoolWrite(List.of(
                    AppLedgerStore.EpochSpoolMutation.delete(key)));
        } else if (record.state() != State.FINALIZED) {
            ledger.epochSpoolWrite(List.of(AppLedgerStore.EpochSpoolMutation.put(key,
                    encodeRecord(new Record(State.FINALIZED, encoded)))));
        }
        usedBytes = calculateUsedBytes();
        return true;
    }

    synchronized void stageFinalized(AppBlock block, WriteBatch batch) {
        List<AppLedgerStore.EpochSpoolMutation> mutations = new ArrayList<>();
        for (var sequenced : AppBlockExecutionContext.fromValidatedBlock(block).l1Observations()) {
            L1Observation observation = sequenced.observation();
            if (observation.anchor() instanceof L1Observation.EpochAnchor) {
                continue;
            }
            byte[] encoded = observation.encode();
            byte[] key = recordKey(observation, observationId(observation));
            byte[] existing = ledger.epochSpoolGet(key);
            if (existing == null) {
                continue;
            }
            if (!Arrays.equals(decodeRecord(existing).observationBytes(), encoded)) {
                throw new IllegalStateException(
                        "Finalized block observation conflicts with the durable journal");
            }
            mutations.add(AppLedgerStore.EpochSpoolMutation.put(key,
                    encodeRecord(new Record(State.FINALIZED, encoded))));
            mutations.add(AppLedgerStore.EpochSpoolMutation.put(
                    cursorKey(observation.observerId()), key));
        }
        ledger.stageEpochSpoolMutations(batch, mutations);
    }

    synchronized void markInFlight(AppBlock block) {
        transitionBlockObservations(block, State.IN_FLIGHT, false);
    }

    synchronized void markPrepared(AppBlock block) {
        transitionBlockObservations(block, State.QC_PREPARED, true);
    }

    private void transitionBlockObservations(AppBlock block, State target,
                                             boolean requirePresent) {
        List<AppLedgerStore.EpochSpoolMutation> mutations = new ArrayList<>();
        for (var sequenced : AppBlockExecutionContext.fromValidatedBlock(block).l1Observations()) {
            L1Observation observation = sequenced.observation();
            if (observation.anchor() instanceof L1Observation.EpochAnchor) {
                continue;
            }
            byte[] key = recordKey(observation, observationId(observation));
            byte[] existing = ledger.epochSpoolGet(key);
            if (existing == null) {
                if (requirePresent) {
                    throw new IllegalStateException(
                            "Prepared L1 observation is absent from the durable journal");
                }
                continue;
            }
            Record record = decodeRecord(existing);
            if (!Arrays.equals(record.observationBytes(), observation.encode())) {
                throw new IllegalStateException("L1 observation journal transition mismatch");
            }
            if (record.state() == State.FINALIZED || record.state() == State.QC_PREPARED) {
                continue;
            }
            mutations.add(AppLedgerStore.EpochSpoolMutation.put(key,
                    encodeRecord(new Record(target, record.observationBytes()))));
        }
        ledger.epochSpoolWrite(mutations);
    }

    synchronized boolean contains(L1Observation observation) {
        byte[] encoded = observation.encode();
        byte[] existing = ledger.epochSpoolGet(
                recordKey(observation, observationId(observation)));
        return existing != null && Arrays.equals(decodeRecord(existing).observationBytes(), encoded);
    }

    synchronized void quarantineObservation(L1Observation observation, String reason) {
        byte[] key = recordKey(observation, observationId(observation));
        byte[] existing = ledger.epochSpoolGet(key);
        if (existing == null) {
            throw new IllegalStateException("Cannot quarantine an absent L1 observation");
        }
        Record record = decodeRecord(existing);
        if (!Arrays.equals(record.observationBytes(), observation.encode())) {
            throw new IllegalStateException("L1 observation quarantine identity mismatch");
        }
        ledger.epochSpoolWrite(List.of(
                AppLedgerStore.EpochSpoolMutation.put(key,
                        encodeRecord(new Record(State.QUARANTINED,
                                record.observationBytes()))),
                AppLedgerStore.EpochSpoolMutation.put(QUARANTINE_KEY,
                        reason.getBytes(StandardCharsets.UTF_8))));
    }

    synchronized void rollback(long rollbackToSlot) {
        for (AppLedgerStore.EpochSpoolEntry entry : ledger.epochSpoolScan(
                CURSOR_PREFIX, MAX_SCAN)) {
            byte[] finalizedRecordKey = cursorRecordKey(entry.value());
            long finalizedSlot = ByteBuffer.wrap(finalizedRecordKey, 1, Long.BYTES).getLong();
            if (finalizedSlot > rollbackToSlot) {
                quarantine("DEEP_L1_ROLLBACK_BELOW_FINALIZED_OBSERVATION");
                throw new IllegalStateException(
                        "DEEP_L1_ROLLBACK_BELOW_FINALIZED_OBSERVATION");
            }
        }
        List<AppLedgerStore.EpochSpoolMutation> mutations = new ArrayList<>();
        boolean invalidatedPreparedValue = false;
        for (AppLedgerStore.EpochSpoolEntry entry : ledger.epochSpoolScan(
                RECORD_PREFIX, MAX_SCAN)) {
            Record record = decodeRecord(entry.value());
            L1Observation observation = requireObservation(record.observationBytes());
            if (observation.slot() <= rollbackToSlot) {
                continue;
            }
            if (record.state() == State.FINALIZED) {
                quarantine("DEEP_L1_ROLLBACK_BELOW_FINALIZED_OBSERVATION");
                throw new IllegalStateException("DEEP_L1_ROLLBACK_BELOW_FINALIZED_OBSERVATION");
            }
            if (record.state() == State.QC_PREPARED) {
                mutations.add(AppLedgerStore.EpochSpoolMutation.put(entry.key(),
                        encodeRecord(new Record(State.QUARANTINED,
                                record.observationBytes()))));
                invalidatedPreparedValue = true;
                continue;
            }
            mutations.add(AppLedgerStore.EpochSpoolMutation.delete(entry.key()));
            mutations.add(AppLedgerStore.EpochSpoolMutation.delete(sourceKey(observation)));
        }
        if (invalidatedPreparedValue) {
            mutations.add(AppLedgerStore.EpochSpoolMutation.put(QUARANTINE_KEY,
                    "L1_INVALIDATED_PREPARED_VALUE".getBytes(StandardCharsets.UTF_8)));
        }
        ledger.epochSpoolWrite(mutations);
        usedBytes = calculateUsedBytes();
        if (invalidatedPreparedValue) {
            throw new IllegalStateException("L1_INVALIDATED_PREPARED_VALUE");
        }
    }

    synchronized boolean healthy() {
        if (ledger.epochSpoolGet(QUARANTINE_KEY) != null
                || ledger.epochSpoolGet(CALLBACK_FAILURE_KEY) != null) {
            return false;
        }
        return ledger.epochSpoolScan(RECORD_PREFIX, MAX_SCAN).stream()
                .map(AppLedgerStore.EpochSpoolEntry::value)
                .map(L1ObservationJournal::decodeRecord)
                .noneMatch(record -> record.state() == State.QUARANTINED);
    }

    synchronized long callbackFailureSlot() {
        byte[] encoded = ledger.epochSpoolGet(CALLBACK_FAILURE_KEY);
        return encoded == null ? -1 : ByteBuffer.wrap(encoded).getLong();
    }

    synchronized void markCallbackFailure(long slot) {
        ledger.epochSpoolWrite(List.of(AppLedgerStore.EpochSpoolMutation.put(
                CALLBACK_FAILURE_KEY, ByteBuffer.allocate(Long.BYTES).putLong(slot).array())));
    }

    synchronized void clearCallbackFailure(long slot) {
        if (callbackFailureSlot() == slot) {
            ledger.epochSpoolWrite(List.of(
                    AppLedgerStore.EpochSpoolMutation.delete(CALLBACK_FAILURE_KEY)));
        }
    }

    private void quarantine(String reason) {
        ledger.epochSpoolWrite(List.of(AppLedgerStore.EpochSpoolMutation.put(
                QUARANTINE_KEY, reason.getBytes(StandardCharsets.UTF_8))));
    }

    synchronized Map<String, Object> status() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (State state : State.values()) {
            counts.put(state.name(), 0L);
        }
        for (AppLedgerStore.EpochSpoolEntry entry : ledger.epochSpoolScan(
                RECORD_PREFIX, MAX_SCAN)) {
            State state = decodeRecord(entry.value()).state();
            counts.compute(state.name(), (ignored, count) -> count + 1);
        }
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("states", Map.copyOf(counts));
        status.put("usedBytes", usedBytes);
        status.put("maxBytes", maxBytes);
        status.put("entries", counts.values().stream().mapToLong(Long::longValue).sum());
        status.put("maxEntries", maxEntries);
        status.put("cursors", ledger.epochSpoolScan(CURSOR_PREFIX, MAX_SCAN).size());
        status.put("cursorDigest", HexFormat.of().formatHex(cursorDigest()));
        status.put("healthy", healthy());
        byte[] quarantine = ledger.epochSpoolGet(QUARANTINE_KEY);
        if (quarantine != null) {
            status.put("quarantineReason", new String(quarantine, StandardCharsets.UTF_8));
        }
        long callbackFailureSlot = callbackFailureSlot();
        if (callbackFailureSlot >= 0) {
            status.put("callbackFailureSlot", callbackFailureSlot);
        }
        return Map.copyOf(status);
    }

    static void commitAuthenticatedCursors(AppBlock block, AppStateWriter state,
                                           byte[] identityContext) {
        Objects.requireNonNull(state, "state");
        byte[] context = Objects.requireNonNull(
                identityContext, "identityContext").clone();
        if (context.length != 32) {
            throw new IllegalArgumentException("observation identity context must be 32 bytes");
        }
        for (var sequenced : AppBlockExecutionContext.fromValidatedBlock(block).l1Observations()) {
            L1Observation observation = sequenced.observation();
            state.put(authenticatedCursorKey(observation.observerId(), context),
                    cursorValue(observation, context));
        }
    }

    private int recordCount() {
        return ledger.epochSpoolScan(RECORD_PREFIX, MAX_SCAN).size();
    }

    private byte[] cursorDigest() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (AppLedgerStore.EpochSpoolEntry entry : ledger.epochSpoolScan(
                    CURSOR_PREFIX, MAX_SCAN)) {
                bytes.write(entry.key());
                bytes.write(entry.value());
            }
            return digest(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    private long calculateUsedBytes() {
        long total = 0;
        for (byte[] prefix : List.of(RECORD_PREFIX, SOURCE_PREFIX, CURSOR_PREFIX)) {
            for (AppLedgerStore.EpochSpoolEntry entry : ledger.epochSpoolScan(prefix, MAX_SCAN)) {
                total = Math.addExact(total, entry.key().length + entry.value().length);
            }
        }
        return total;
    }

    private byte[] cursorKey(String observerId) {
        byte[] digest = digest(authenticatedCursorKey(observerId, identityContext));
        return ByteBuffer.allocate(1 + digest.length)
                .put(CURSOR_PREFIX[0]).put(digest).array();
    }

    private boolean hasFinalizedCursor(L1Observation observation, byte[] recordKey) {
        byte[] cursor = ledger.epochSpoolGet(cursorKey(observation.observerId()));
        return cursor != null && Arrays.equals(cursorRecordKey(cursor), recordKey);
    }

    private static byte[] cursorRecordKey(byte[] cursor) {
        if (cursor.length < Integer.BYTES) {
            throw new IllegalStateException("Invalid L1 observation cursor");
        }
        ByteBuffer bytes = ByteBuffer.wrap(cursor);
        int keyLength = bytes.getInt();
        if (keyLength < 1 + Long.BYTES || keyLength > bytes.remaining() - 32) {
            throw new IllegalStateException("Invalid L1 observation cursor");
        }
        byte[] key = new byte[keyLength];
        bytes.get(key);
        if (key[0] != RECORD_PREFIX[0] || bytes.remaining() != 32) {
            throw new IllegalStateException("Invalid L1 observation cursor");
        }
        return key;
    }

    private static byte[] authenticatedCursorKey(String observerId, byte[] identityContext) {
        byte[] domain = "yano-system-l1-cursor-v1\0".getBytes(StandardCharsets.US_ASCII);
        byte[] observer = observerId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer key = ByteBuffer.allocate(domain.length + identityContext.length
                + Integer.BYTES + observer.length);
        key.put(domain).put(identityContext).putInt(observer.length).put(observer);
        return key.array();
    }

    private static byte[] cursorValue(L1Observation observation, byte[] identityContext) {
        byte[] id = observationId(observation, identityContext);
        byte[] orderingKey = recordKey(observation, id);
        return ByteBuffer.allocate(Integer.BYTES + orderingKey.length + id.length)
                .putInt(orderingKey.length).put(orderingKey).put(id).array();
    }

    private static byte[] recordKey(L1Observation observation, byte[] observationId) {
        byte[] observer = observation.observerId().getBytes(StandardCharsets.UTF_8);
        byte[] anchor;
        int anchorTag;
        if (observation.anchor() instanceof L1Observation.TransactionAnchor transaction) {
            anchorTag = L1Observation.TRANSACTION_ANCHOR_TAG;
            anchor = transaction.transactionHash();
        } else if (observation.anchor() instanceof L1Observation.EpochAnchor epoch) {
            anchorTag = L1Observation.EPOCH_ANCHOR_TAG;
            anchor = ByteBuffer.allocate(Long.BYTES).putLong(epoch.newEpoch()).array();
        } else {
            throw new IllegalArgumentException("Unsupported L1 observation anchor");
        }
        ByteBuffer key = ByteBuffer.allocate(1 + Long.BYTES + 32 + observer.length + 1
                + 1 + anchor.length + Long.BYTES + 32);
        key.put(RECORD_PREFIX[0])
                .putLong(observation.slot())
                .put(observation.blockHash())
                .put(observer).put((byte) 0)
                .put((byte) anchorTag).put(anchor)
                .putLong(observation.eventOrdinal())
                .put(observationId);
        return key.array();
    }

    private byte[] sourceKey(L1Observation observation) {
        return sourceKey(observation, identityContext);
    }

    private static byte[] sourceKey(L1Observation observation, byte[] identityContext) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.write("yano-l1-source-v1\0".getBytes(StandardCharsets.US_ASCII));
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.write(identityContext);
                writeBytes(out, observation.observerId().getBytes(StandardCharsets.UTF_8));
                writeBytes(out, observation.anchor().key().getBytes(StandardCharsets.UTF_8));
                out.writeLong(observation.eventOrdinal());
                out.writeLong(observation.slot());
                out.write(observation.blockHash());
            }
            byte[] digest = digest(bytes.toByteArray());
            ByteBuffer key = ByteBuffer.allocate(1 + digest.length);
            return key.put(SOURCE_PREFIX[0]).put(digest).array();
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    private byte[] observationId(L1Observation observation) {
        return observationId(observation, identityContext);
    }

    private static byte[] observationId(L1Observation observation, byte[] identityContext) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.write("yano-l1-observation-v1\0".getBytes(StandardCharsets.US_ASCII));
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.write(sourceKey(observation, identityContext));
                out.write(digest(observation.claim()));
            }
            return digest(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    private static byte[] encodeRecord(Record record) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(FORMAT_VERSION);
                out.writeByte(record.state().ordinal());
                writeBytes(out, record.observationBytes());
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    private static Record decodeRecord(byte[] encoded) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (in.readInt() != FORMAT_VERSION) {
                throw new IllegalStateException("Unsupported L1 observation journal format");
            }
            int state = in.readUnsignedByte();
            byte[] observation = readBytes(in);
            if (state >= State.values().length || in.available() != 0) {
                throw new IllegalStateException("Invalid L1 observation journal record");
            }
            return new Record(State.values()[state], observation);
        } catch (IOException failure) {
            throw new IllegalStateException("Invalid L1 observation journal record", failure);
        }
    }

    private static L1Observation requireObservation(byte[] encoded) {
        L1Observation observation = L1Observation.decode(encoded);
        if (observation == null) {
            throw new IllegalStateException("Invalid persisted L1 observation");
        }
        return observation;
    }

    private static byte[] digest(byte[] value) {
        return Blake2bUtil.blake2bHash256(value);
    }

    private static void writeBytes(DataOutputStream out, byte[] value) throws IOException {
        out.writeInt(value.length);
        out.write(value);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > 16 * 1024 * 1024 || length > in.available()) {
            throw new IOException("Invalid bounded byte string");
        }
        return in.readNBytes(length);
    }
}
