package com.bloxbean.cardano.yano.appchain.integration.detail;

import com.bloxbean.cardano.yano.appchain.integration.ConnectorLimits;
import com.bloxbean.cardano.yano.appchain.integration.internal.Blake2b256;
import com.bloxbean.cardano.yano.appchain.integration.internal.CanonicalCbor;
import com.bloxbean.cardano.yano.appchain.integration.internal.ContractValidation;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Domain-separated commitment to exact canonical connector detail bytes.
 *
 * @param bytes the 32-byte detail commitment; defensively copied
 */
public record ConnectorDetailHash(byte[] bytes) {
    /** Frozen ASCII domain separator for detail commitments. */
    public static final String DOMAIN = "yano-fx-detail-v1";

    /** Validates and defensively snapshots a detail commitment. */
    public ConnectorDetailHash {
        bytes = ContractValidation.hash32(bytes);
    }

    /**
     * Returns a defensive copy of the commitment bytes.
     *
     * @return the 32-byte commitment
     */
    @Override public byte[] bytes() { return bytes.clone(); }

    /**
     * Computes a commitment for a validated detail document.
     *
     * @param document the detail document
     * @return its domain-separated commitment
     */
    public static ConnectorDetailHash compute(ConnectorDetailDocumentV1 document) {
        return compute(document.encode());
    }

    /**
     * Validates canonical document bytes and computes their commitment.
     *
     * @param canonicalDocument the exact canonical detail encoding
     * @return its domain-separated commitment
     */
    public static ConnectorDetailHash compute(byte[] canonicalDocument) {
        byte[] document = CanonicalCbor.boundedSnapshot(
                canonicalDocument, ConnectorLimits.MAX_DETAIL_DOCUMENT_BYTES);
        ConnectorDetailDocumentV1.decode(document);
        byte[] domain = DOMAIN.getBytes(StandardCharsets.US_ASCII);
        byte[] input = new byte[domain.length + document.length];
        System.arraycopy(domain, 0, input, 0, domain.length);
        System.arraycopy(document, 0, input, domain.length, document.length);
        return new ConnectorDetailHash(Blake2b256.hash(input));
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ConnectorDetailHash hash
                && Arrays.equals(bytes, hash.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}
