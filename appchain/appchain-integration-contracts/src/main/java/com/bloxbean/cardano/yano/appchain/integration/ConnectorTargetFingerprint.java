package com.bloxbean.cardano.yano.appchain.integration;

import com.bloxbean.cardano.yano.appchain.integration.internal.Blake2b256;
import com.bloxbean.cardano.yano.appchain.integration.internal.CanonicalCbor;
import com.bloxbean.cardano.yano.appchain.integration.internal.ContractValidation;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Credential-free commitment to the resolved connector destination and
 * policy. The descriptor must be canonical CBOR and must never contain a
 * secret. Aliases must not be repointed while effects using them remain open.
 *
 * @param bytes the 32-byte BLAKE2b-256 commitment; defensively copied
 */
public record ConnectorTargetFingerprint(byte[] bytes) {
    private static final int MAX_DESCRIPTOR_BYTES = 4_096;

    /** Validates and defensively snapshots a target commitment. */
    public ConnectorTargetFingerprint {
        bytes = ContractValidation.hash32(bytes);
    }

    /**
     * Returns a defensive copy of the commitment bytes.
     *
     * @return the 32-byte commitment
     */
    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    /**
     * Computes a commitment over a domain separator and canonical public descriptor.
     *
     * @param domain the connector-specific fingerprint domain
     * @param canonicalPublicDescriptor canonical CBOR containing no credentials or secrets
     * @return the computed target commitment
     */
    public static ConnectorTargetFingerprint compute(ConnectorFingerprintDomain domain,
                                                     byte[] canonicalPublicDescriptor) {
        if (domain == null) {
            throw new ConnectorContractException(ConnectorErrorCode.INVALID_PAYLOAD);
        }
        byte[] descriptor = CanonicalCbor.boundedSnapshot(
                canonicalPublicDescriptor, MAX_DESCRIPTOR_BYTES);
        if (!CanonicalCbor.isCanonicalValue(descriptor, MAX_DESCRIPTOR_BYTES)) {
            throw new ConnectorContractException(ConnectorErrorCode.INVALID_PAYLOAD);
        }
        byte[] prefix = domain.value().getBytes(StandardCharsets.US_ASCII);
        byte[] input = new byte[prefix.length + descriptor.length];
        System.arraycopy(prefix, 0, input, 0, prefix.length);
        System.arraycopy(descriptor, 0, input, prefix.length, descriptor.length);
        return new ConnectorTargetFingerprint(Blake2b256.hash(input));
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ConnectorTargetFingerprint fingerprint
                && Arrays.equals(bytes, fingerprint.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}
