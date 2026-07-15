package com.bloxbean.cardano.yano.appchain.integration.objectstore;

import com.bloxbean.cardano.yano.appchain.integration.internal.Blake2b256;
import com.bloxbean.cardano.yano.appchain.integration.internal.ContractValidation;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Hash of the mandatory immutable provider version id.
 *
 * @param bytes the 32-byte provider-version commitment; defensively copied
 */
public record ObjectVersionFingerprint(byte[] bytes) {
    /** Frozen ASCII domain separator for provider-version commitments. */
    public static final String DOMAIN = "yano-object-version-v1";

    /** Validates and defensively snapshots a provider-version commitment. */
    public ObjectVersionFingerprint {
        bytes = ContractValidation.hash32(bytes);
    }

    /**
     * Returns a defensive copy of the commitment bytes.
     *
     * @return the 32-byte provider-version commitment
     */
    @Override public byte[] bytes() { return bytes.clone(); }

    /**
     * Computes a domain-separated commitment to an immutable provider version id.
     *
     * @param providerVersionId the provider version identifier
     * @return the provider-version commitment
     */
    public static ObjectVersionFingerprint compute(String providerVersionId) {
        ContractValidation.boundedAscii(providerVersionId, 1, 1_024);
        byte[] domain = DOMAIN.getBytes(StandardCharsets.US_ASCII);
        byte[] version = providerVersionId.getBytes(StandardCharsets.US_ASCII);
        byte[] input = new byte[domain.length + version.length];
        System.arraycopy(domain, 0, input, 0, domain.length);
        System.arraycopy(version, 0, input, domain.length, version.length);
        return new ObjectVersionFingerprint(Blake2b256.hash(input));
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ObjectVersionFingerprint fingerprint
                && Arrays.equals(bytes, fingerprint.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}
