package com.bloxbean.cardano.yano.appchain.integration.objectstore;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorLimits;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorTargetFingerprint;
import com.bloxbean.cardano.yano.appchain.integration.internal.CanonicalCbor;
import com.bloxbean.cardano.yano.appchain.integration.internal.ContractValidation;

import java.util.Arrays;
import java.util.List;

/**
 * Compact authenticated object-store acknowledgement.
 *
 * @param destinationFingerprint the 32-byte resolved-destination commitment
 * @param objectVersionFingerprint the 32-byte provider-version commitment
 * @param verifiedSha256 the 32-byte content digest verified after the write
 * @param size the verified object size in bytes
 */
public record ObjectPutReceiptV1(byte[] destinationFingerprint,
                                 byte[] objectVersionFingerprint,
                                 byte[] verifiedSha256,
                                 long size) {
    /** Validates and defensively snapshots all commitments. */
    public ObjectPutReceiptV1 {
        destinationFingerprint = ContractValidation.hash32(destinationFingerprint);
        objectVersionFingerprint = ContractValidation.hash32(objectVersionFingerprint);
        verifiedSha256 = ContractValidation.hash32(verifiedSha256);
        size = ContractValidation.bounded(size, 0, ObjectPutCommandV1.MAX_OBJECT_BYTES);
    }

    /**
     * Creates a receipt from typed destination and version commitments.
     *
     * @param destinationFingerprint the resolved-destination commitment
     * @param objectVersionFingerprint the provider-version commitment
     * @param verifiedSha256 the content digest verified after the write
     * @param size the verified object size in bytes
     */
    public ObjectPutReceiptV1(ConnectorTargetFingerprint destinationFingerprint,
                              ObjectVersionFingerprint objectVersionFingerprint,
                              byte[] verifiedSha256,
                              long size) {
        this(destinationFingerprint.bytes(), objectVersionFingerprint.bytes(), verifiedSha256, size);
    }

    /**
     * Returns a defensive copy of the destination commitment.
     *
     * @return the 32-byte destination commitment
     */
    @Override public byte[] destinationFingerprint() { return destinationFingerprint.clone(); }

    /**
     * Returns a defensive copy of the provider-version commitment.
     *
     * @return the 32-byte provider-version commitment
     */
    @Override public byte[] objectVersionFingerprint() { return objectVersionFingerprint.clone(); }

    /**
     * Returns a defensive copy of the verified content digest.
     *
     * @return the 32-byte SHA-256 digest
     */
    @Override public byte[] verifiedSha256() { return verifiedSha256.clone(); }

    /**
     * Encodes this receipt as strict canonical CBOR.
     *
     * @return a new canonical encoding
     */
    public byte[] encode() {
        Array root = new Array();
        root.add(new UnsignedInteger(1));
        root.add(new ByteString(destinationFingerprint));
        root.add(new ByteString(objectVersionFingerprint));
        root.add(new ByteString(verifiedSha256));
        root.add(new UnsignedInteger(size));
        byte[] encoded = CanonicalCbor.encode(root);
        CanonicalCbor.requireEncodedBound(encoded, ConnectorLimits.MAX_EXTERNAL_REF_BYTES);
        return encoded;
    }

    /**
     * Decodes and validates a canonical object-store acknowledgement.
     *
     * @param bytes the bounded canonical encoding
     * @return the validated receipt
     */
    public static ObjectPutReceiptV1 decode(byte[] bytes) {
        Array root = CanonicalCbor.decodeArray(bytes, ConnectorLimits.MAX_EXTERNAL_REF_BYTES, 5);
        List<DataItem> items = CanonicalCbor.items(root);
        CanonicalCbor.requireVersion(items.get(0));
        return new ObjectPutReceiptV1(
                CanonicalCbor.bytes(items.get(1)),
                CanonicalCbor.bytes(items.get(2)),
                CanonicalCbor.bytes(items.get(3)),
                CanonicalCbor.uint(items.get(4)));
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ObjectPutReceiptV1 receipt
                && size == receipt.size
                && Arrays.equals(destinationFingerprint, receipt.destinationFingerprint)
                && Arrays.equals(objectVersionFingerprint, receipt.objectVersionFingerprint)
                && Arrays.equals(verifiedSha256, receipt.verifiedSha256);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(destinationFingerprint);
        result = 31 * result + Arrays.hashCode(objectVersionFingerprint);
        result = 31 * result + Arrays.hashCode(verifiedSha256);
        return 31 * result + Long.hashCode(size);
    }
}
