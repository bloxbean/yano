package com.bloxbean.cardano.yano.api.appchain.snapshot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;

/** Strict fixed-order canonical CBOR codec for the ADR-028 v1 contracts. */
public final class SnapshotCanonicalCodec {
    private static final ObjectMapper CBOR = new ObjectMapper(new CBORFactory());
    private static final TypeReference<List<Object>> LIST = new TypeReference<>() { };
    private static final int MAX_BYTES = 64 * 1024;

    private SnapshotCanonicalCodec() { }

    public static byte[] encodeDescriptor(SnapshotDescriptorV1 value) {
        return write(List.of(1, value.chainGenerationId(), value.applicationProfileDigest(),
                value.seriesId(), value.sequence(), value.snapshotId(), value.snapshotProfile(),
                value.snapshotFormatFingerprint(), value.snapshotProofWireVersion(), value.snapshotRoot(),
                value.sourceDatasetRoot(), value.sourceCommitmentAlgorithm(),
                value.sourceCommitmentWireVersion(), value.schemaId(), value.entryCount(),
                value.baseAppChainHeight(), value.completedAppChainHeight(), value.coveredFromHeight(),
                value.coveredThroughHeight(), value.previousSnapshotCommitment(),
                boundary(value.sourceBoundary()), value.recoveryCoverage().ordinal(), 1));
    }

    public static SnapshotDescriptorV1 decodeDescriptor(byte[] canonical) {
        List<Object> f = readCanonical(canonical, 23);
        requireVersion(f);
        SnapshotDescriptorV1 result = new SnapshotDescriptorV1(bytes(f, 1), bytes(f, 2), text(f, 3),
                number(f, 4), text(f, 5), text(f, 6), bytes(f, 7), text(f, 8), bytes(f, 9),
                bytes(f, 10), text(f, 11), text(f, 12), text(f, 13), number(f, 14), number(f, 15),
                number(f, 16), number(f, 17), number(f, 18), bytes(f, 19), parseBoundary(f.get(20)),
                recovery(f.get(21)), number(f, 22) == 1);
        requireRoundTrip(canonical, encodeDescriptor(result));
        return result;
    }

    public static byte[] encodeHead(SnapshotHeadV1 value) {
        return write(List.of(1, value.sequence(), value.descriptorCommitment()));
    }

    public static SnapshotHeadV1 decodeHead(byte[] canonical) {
        List<Object> f = readCanonical(canonical, 3);
        requireVersion(f);
        SnapshotHeadV1 result = new SnapshotHeadV1(number(f, 1), bytes(f, 2));
        requireRoundTrip(canonical, encodeHead(result));
        return result;
    }

    public static byte[] encodeReceipt(SnapshotBuildReceiptV1 value) {
        return write(List.of(1, value.sequence(), value.snapshotId(), boundary(value.sourceBoundary()),
                value.baseHeight(), value.coveredFromHeight(), value.coveredThroughHeight(),
                value.recoveryCoverage().ordinal(), value.descriptorDraftDigest(), value.sourceDatasetRoot(),
                value.sourceCommitmentAlgorithm(), value.sourceCommitmentWireVersion(), value.expectedChunks(),
                value.nextChunk(), value.expectedEntries(), value.receivedEntries(),
                value.lastApplicationKey(), value.sourceAccumulator(), value.partialRoot()));
    }

    public static SnapshotBuildReceiptV1 decodeReceipt(byte[] canonical) {
        List<Object> f = readCanonical(canonical, 19);
        requireVersion(f);
        SnapshotBuildReceiptV1 result = new SnapshotBuildReceiptV1(number(f, 1), text(f, 2),
                parseBoundary(f.get(3)), number(f, 4), number(f, 5), number(f, 6), recovery(f.get(7)),
                bytes(f, 8), bytes(f, 9), text(f, 10), text(f, 11), number(f, 12), number(f, 13),
                number(f, 14), number(f, 15), bytes(f, 16), bytes(f, 17), bytes(f, 18));
        requireRoundTrip(canonical, encodeReceipt(result));
        return result;
    }

    public static byte[] encodeDraft(SnapshotDescriptorDraftV1 value) {
        AuthenticatedSnapshotSeriesDescriptorV1 series = value.series();
        return write(List.of(1, series.seriesId(), series.schemaId(), series.trigger().ordinal(),
                series.snapshotProfile(), series.formatFingerprint(), series.proofWireVersion(),
                series.verificationTarget().ordinal(), series.visibility().ordinal(),
                series.sourceCommitmentAlgorithm(), series.sourceCommitmentWireVersion(),
                series.maxEntriesPerChunk(), series.maxChunkBytes(), series.maxKeyBytes(),
                series.maxValueBytes(), series.maxEntriesPerSnapshot(),
                series.recoveryCoverage().ordinal(), value.sequence(), value.snapshotId(),
                boundary(value.sourceBoundary()), value.baseAppChainHeight(),
                value.coveredFromHeight(), value.coveredThroughHeight(), value.sourceDatasetRoot(),
                value.expectedChunks(), value.expectedEntries()));
    }

    private static List<Object> boundary(SnapshotSourceBoundary boundary) {
        if (boundary instanceof SnapshotSourceBoundary.AppHeight app) {
            return List.of(0, app.appHeight());
        }
        SnapshotSourceBoundary.L1Epoch epoch = (SnapshotSourceBoundary.L1Epoch) boundary;
        return List.of(1, epoch.previousEpoch(), epoch.newEpoch(), epoch.datasetEpoch(),
                epoch.boundarySlot(), epoch.blockHash());
    }

    private static SnapshotSourceBoundary parseBoundary(Object raw) {
        if (!(raw instanceof List<?> values) || values.isEmpty()) {
            throw new IllegalArgumentException("invalid snapshot source boundary");
        }
        long tag = number(values.get(0));
        if (tag == 0 && values.size() == 2) return new SnapshotSourceBoundary.AppHeight(number(values.get(1)));
        if (tag == 1 && values.size() == 6) return new SnapshotSourceBoundary.L1Epoch(number(values.get(1)),
                number(values.get(2)), number(values.get(3)), number(values.get(4)), bytes(values.get(5)));
        throw new IllegalArgumentException("invalid snapshot source boundary variant");
    }

    private static AuthenticatedSnapshotSeriesDescriptorV1.RecoveryCoverage recovery(Object raw) {
        long value = number(raw);
        if (value != 0) throw new IllegalArgumentException("FULL_STATE snapshots are not released in v1");
        return AuthenticatedSnapshotSeriesDescriptorV1.RecoveryCoverage.DATASET;
    }

    private static byte[] write(List<?> fields) {
        try { return CBOR.writeValueAsBytes(fields); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static List<Object> readCanonical(byte[] bytes, int fields) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("invalid bounded snapshot CBOR");
        }
        try {
            List<Object> decoded = CBOR.readValue(bytes, LIST);
            if (decoded.size() != fields) throw new IllegalArgumentException("invalid snapshot CBOR arity");
            return decoded;
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid snapshot CBOR", e);
        }
    }

    private static void requireVersion(List<Object> values) {
        if (number(values, 0) != 1) throw new IllegalArgumentException("unsupported snapshot wire version");
    }

    private static void requireRoundTrip(byte[] supplied, byte[] canonical) {
        if (!Arrays.equals(supplied, canonical)) throw new IllegalArgumentException("non-canonical snapshot CBOR");
    }

    private static long number(List<?> values, int index) { return number(values.get(index)); }
    private static long number(Object value) {
        if (!(value instanceof Number n) || n.longValue() < 0) throw new IllegalArgumentException("expected uint");
        return n.longValue();
    }
    private static String text(List<?> values, int index) {
        Object value = values.get(index);
        if (!(value instanceof String text)) throw new IllegalArgumentException("expected text");
        return text;
    }
    private static byte[] bytes(List<?> values, int index) { return bytes(values.get(index)); }
    private static byte[] bytes(Object value) {
        if (!(value instanceof byte[] bytes)) throw new IllegalArgumentException("expected bytes");
        return bytes;
    }
}
