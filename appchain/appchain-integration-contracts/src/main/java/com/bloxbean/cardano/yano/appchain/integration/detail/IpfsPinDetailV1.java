package com.bloxbean.cardano.yano.appchain.integration.detail;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.integration.internal.CanonicalCbor;
import com.bloxbean.cardano.yano.appchain.integration.internal.ContractValidation;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.CanonicalCid;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsV1Policy;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Allowlisted stable IPFS pin detail.
 *
 * @param targetFingerprint the 32-byte resolved-target commitment
 * @param cid the canonical CID that was pinned
 * @param recursive whether the pin covers the reachable DAG
 * @param providerReference an optional non-secret provider reference
 */
public record IpfsPinDetailV1(byte[] targetFingerprint,
                              CanonicalCid cid,
                              boolean recursive,
                              String providerReference) implements ConnectorDetailData {
    /** Validates the stable fields and defensively snapshots the fingerprint. */
    public IpfsPinDetailV1 {
        targetFingerprint = ContractValidation.hash32(targetFingerprint);
        cid = IpfsV1Policy.requireAllowed(cid);
        providerReference = providerReference == null ? null
                : ContractValidation.boundedAscii(providerReference, 1, 256);
    }

    /**
     * Returns a defensive copy of the target commitment.
     *
     * @return the 32-byte target commitment
     */
    @Override public byte[] targetFingerprint() { return targetFingerprint.clone(); }

    /** {@inheritDoc} */
    @Override public ConnectorAction action() { return ConnectorAction.IPFS_PIN; }

    @Override
    public byte[] encode() {
        Array data = new Array();
        data.add(new UnsignedInteger(1));
        data.add(new ByteString(targetFingerprint));
        data.add(new ByteString(cid.bytes()));
        data.add(CanonicalCbor.boolValue(recursive));
        data.add(CanonicalCbor.nullable(providerReference));
        return CanonicalCbor.encode(data);
    }

    /**
     * Decodes and validates canonical IPFS detail data.
     *
     * @param bytes the canonical detail encoding
     * @return the validated detail data
     */
    public static IpfsPinDetailV1 decode(byte[] bytes) {
        Array data = CanonicalCbor.decodeArray(bytes, 512, 5);
        List<DataItem> items = CanonicalCbor.items(data);
        CanonicalCbor.requireVersion(items.get(0));
        return new IpfsPinDetailV1(CanonicalCbor.bytes(items.get(1)),
                CanonicalCid.fromBytes(CanonicalCbor.bytes(items.get(2))),
                CanonicalCbor.bool(items.get(3)), CanonicalCbor.nullableText(items.get(4)));
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof IpfsPinDetailV1 detail
                && recursive == detail.recursive
                && Arrays.equals(targetFingerprint, detail.targetFingerprint)
                && cid.equals(detail.cid)
                && Objects.equals(providerReference, detail.providerReference);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(cid, recursive, providerReference);
        return 31 * result + Arrays.hashCode(targetFingerprint);
    }
}
