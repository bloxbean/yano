package com.bloxbean.cardano.yano.appchain.integration.detail;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.integration.internal.CanonicalCbor;
import com.bloxbean.cardano.yano.appchain.integration.internal.ContractValidation;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutCommandV1;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Allowlisted stable object-store version and retention detail.
 *
 * @param destinationFingerprint the 32-byte resolved-destination commitment
 * @param providerVersionId the immutable version identifier returned by the provider
 * @param etag an optional non-secret provider entity tag
 * @param verifiedSha256 the 32-byte digest verified after the write
 * @param size the verified object size in bytes
 * @param retentionMode the effective provider retention mode
 * @param retainUntilEpochMillis the retention deadline for locked modes, otherwise {@code null}
 */
public record ObjectPutDetailV1(byte[] destinationFingerprint,
                                String providerVersionId,
                                String etag,
                                byte[] verifiedSha256,
                                long size,
                                ObjectRetentionMode retentionMode,
                                Long retainUntilEpochMillis) implements ConnectorDetailData {
    /** Validates stable receipt fields and retention invariants. */
    public ObjectPutDetailV1 {
        destinationFingerprint = ContractValidation.hash32(destinationFingerprint);
        providerVersionId = ContractValidation.boundedAscii(providerVersionId, 1, 1_024);
        etag = etag == null ? null : ContractValidation.boundedAscii(etag, 1, 256);
        verifiedSha256 = ContractValidation.hash32(verifiedSha256);
        size = ContractValidation.bounded(size, 0, ObjectPutCommandV1.MAX_OBJECT_BYTES);
        if (retentionMode == null
                || (retentionMode.retainUntilRequired() && (retainUntilEpochMillis == null
                || retainUntilEpochMillis < 0))
                || (!retentionMode.retainUntilRequired() && retainUntilEpochMillis != null)) {
            throw CanonicalCbor.malformed();
        }
    }

    /**
     * Returns a defensive copy of the destination commitment.
     *
     * @return the 32-byte destination commitment
     */
    @Override public byte[] destinationFingerprint() { return destinationFingerprint.clone(); }

    /**
     * Returns a defensive copy of the verified content digest.
     *
     * @return the 32-byte SHA-256 digest
     */
    @Override public byte[] verifiedSha256() { return verifiedSha256.clone(); }

    /** {@inheritDoc} */
    @Override public ConnectorAction action() { return ConnectorAction.OBJECT_PUT; }

    @Override
    public byte[] encode() {
        Array data = new Array();
        data.add(new UnsignedInteger(1));
        data.add(new ByteString(destinationFingerprint));
        data.add(new UnicodeString(providerVersionId));
        data.add(CanonicalCbor.nullable(etag));
        data.add(new ByteString(verifiedSha256));
        data.add(new UnsignedInteger(size));
        data.add(new UnsignedInteger(retentionMode.code()));
        data.add(CanonicalCbor.nullable(retainUntilEpochMillis));
        return CanonicalCbor.encode(data);
    }

    /**
     * Decodes and validates canonical object-store detail data.
     *
     * @param bytes the canonical detail encoding
     * @return the validated detail data
     */
    public static ObjectPutDetailV1 decode(byte[] bytes) {
        Array data = CanonicalCbor.decodeArray(bytes, 2_048, 8);
        List<DataItem> items = CanonicalCbor.items(data);
        CanonicalCbor.requireVersion(items.get(0));
        return new ObjectPutDetailV1(CanonicalCbor.bytes(items.get(1)),
                CanonicalCbor.text(items.get(2)), CanonicalCbor.nullableText(items.get(3)),
                CanonicalCbor.bytes(items.get(4)), CanonicalCbor.uint(items.get(5)),
                ObjectRetentionMode.fromCode(CanonicalCbor.uint(items.get(6))),
                CanonicalCbor.nullableUint(items.get(7)));
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ObjectPutDetailV1 detail
                && size == detail.size
                && retentionMode == detail.retentionMode
                && Arrays.equals(destinationFingerprint, detail.destinationFingerprint)
                && providerVersionId.equals(detail.providerVersionId)
                && Objects.equals(etag, detail.etag)
                && Arrays.equals(verifiedSha256, detail.verifiedSha256)
                && Objects.equals(retainUntilEpochMillis, detail.retainUntilEpochMillis);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(providerVersionId, etag, size, retentionMode,
                retainUntilEpochMillis);
        result = 31 * result + Arrays.hashCode(destinationFingerprint);
        return 31 * result + Arrays.hashCode(verifiedSha256);
    }
}
