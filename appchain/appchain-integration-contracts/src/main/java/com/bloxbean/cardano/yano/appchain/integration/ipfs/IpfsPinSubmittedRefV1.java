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
 * Optional remote-pinning polling handle stored by ADR-010 Submitted.
 * {@code providerRequestId} must be an opaque non-secret handle. A bearer
 * token, signed URL, or other polling credential belongs only in local target
 * configuration and is never valid here.
 *
 * @param targetFingerprint the 32-byte resolved-target commitment
 * @param providerRequestId the bounded, non-secret provider polling handle
 */
public record IpfsPinSubmittedRefV1(byte[] targetFingerprint, byte[] providerRequestId) {
    /** Maximum provider polling-handle size in bytes. */
    public static final int MAX_PROVIDER_REQUEST_ID_BYTES = 88;

    /** Validates and defensively snapshots the target and polling handle. */
    public IpfsPinSubmittedRefV1 {
        targetFingerprint = ContractValidation.hash32(targetFingerprint);
        providerRequestId = ContractValidation.bytes(providerRequestId, 1,
                MAX_PROVIDER_REQUEST_ID_BYTES);
    }

    /**
     * Creates a submitted reference from a typed target commitment.
     *
     * @param targetFingerprint the resolved-target commitment
     * @param providerRequestId the bounded, non-secret provider polling handle
     */
    public IpfsPinSubmittedRefV1(ConnectorTargetFingerprint targetFingerprint,
                                 byte[] providerRequestId) {
        this(targetFingerprint.bytes(), providerRequestId);
    }

    /**
     * Returns a defensive copy of the target commitment.
     *
     * @return the 32-byte target commitment
     */
    @Override public byte[] targetFingerprint() { return targetFingerprint.clone(); }

    /**
     * Returns a defensive copy of the provider polling handle.
     *
     * @return the opaque non-secret polling-handle bytes
     */
    @Override public byte[] providerRequestId() { return providerRequestId.clone(); }

    /**
     * Encodes this submitted reference as strict canonical CBOR.
     *
     * @return a new canonical encoding
     */
    public byte[] encode() {
        Array root = new Array();
        root.add(new UnsignedInteger(1));
        root.add(new ByteString(targetFingerprint));
        root.add(new ByteString(providerRequestId));
        byte[] encoded = CanonicalCbor.encode(root);
        CanonicalCbor.requireEncodedBound(encoded, ConnectorLimits.MAX_EXTERNAL_REF_BYTES);
        return encoded;
    }

    /**
     * Decodes and validates a canonical submitted reference.
     *
     * @param bytes the bounded canonical encoding
     * @return the validated submitted reference
     */
    public static IpfsPinSubmittedRefV1 decode(byte[] bytes) {
        Array root = CanonicalCbor.decodeArray(bytes, ConnectorLimits.MAX_EXTERNAL_REF_BYTES, 3);
        List<DataItem> items = CanonicalCbor.items(root);
        CanonicalCbor.requireVersion(items.get(0));
        return new IpfsPinSubmittedRefV1(CanonicalCbor.bytes(items.get(1)),
                CanonicalCbor.bytes(items.get(2)));
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof IpfsPinSubmittedRefV1 reference
                && Arrays.equals(targetFingerprint, reference.targetFingerprint)
                && Arrays.equals(providerRequestId, reference.providerRequestId);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(targetFingerprint) + Arrays.hashCode(providerRequestId);
    }
}
