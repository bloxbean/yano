package com.bloxbean.cardano.yano.appchain.integration.ipfs;

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
 * Compact confirmed pin receipt; the full CID remains in the originating effect record.
 *
 * @param targetFingerprint the 32-byte resolved-target commitment
 * @param cidFingerprint the 32-byte canonical-CID commitment
 */
public record IpfsPinReceiptV1(byte[] targetFingerprint, byte[] cidFingerprint) {
    /** Validates and defensively snapshots both commitments. */
    public IpfsPinReceiptV1 {
        targetFingerprint = ContractValidation.hash32(targetFingerprint);
        cidFingerprint = ContractValidation.hash32(cidFingerprint);
    }

    /**
     * Creates a receipt from typed commitments.
     *
     * @param targetFingerprint the resolved-target commitment
     * @param cidFingerprint the canonical-CID commitment
     */
    public IpfsPinReceiptV1(ConnectorTargetFingerprint targetFingerprint,
                            IpfsCidFingerprint cidFingerprint) {
        this(targetFingerprint.bytes(), cidFingerprint.bytes());
    }

    /**
     * Returns a defensive copy of the target commitment.
     *
     * @return the 32-byte target commitment
     */
    @Override public byte[] targetFingerprint() { return targetFingerprint.clone(); }

    /**
     * Returns a defensive copy of the CID commitment.
     *
     * @return the 32-byte CID commitment
     */
    @Override public byte[] cidFingerprint() { return cidFingerprint.clone(); }

    /**
     * Encodes this receipt as strict canonical CBOR.
     *
     * @return a new canonical encoding
     */
    public byte[] encode() {
        Array root = new Array();
        root.add(new UnsignedInteger(1));
        root.add(new ByteString(targetFingerprint));
        root.add(new ByteString(cidFingerprint));
        byte[] encoded = CanonicalCbor.encode(root);
        CanonicalCbor.requireEncodedBound(encoded, ConnectorLimits.MAX_EXTERNAL_REF_BYTES);
        return encoded;
    }

    /**
     * Decodes and validates a canonical confirmed-pin receipt.
     *
     * @param bytes the bounded canonical encoding
     * @return the validated receipt
     */
    public static IpfsPinReceiptV1 decode(byte[] bytes) {
        Array root = CanonicalCbor.decodeArray(bytes, ConnectorLimits.MAX_EXTERNAL_REF_BYTES, 3);
        List<DataItem> items = CanonicalCbor.items(root);
        CanonicalCbor.requireVersion(items.get(0));
        return new IpfsPinReceiptV1(CanonicalCbor.bytes(items.get(1)),
                CanonicalCbor.bytes(items.get(2)));
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof IpfsPinReceiptV1 receipt
                && Arrays.equals(targetFingerprint, receipt.targetFingerprint)
                && Arrays.equals(cidFingerprint, receipt.cidFingerprint);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(targetFingerprint) + Arrays.hashCode(cidFingerprint);
    }
}
