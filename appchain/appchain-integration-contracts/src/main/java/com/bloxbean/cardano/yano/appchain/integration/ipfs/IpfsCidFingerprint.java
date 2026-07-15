package com.bloxbean.cardano.yano.appchain.integration.ipfs;

import com.bloxbean.cardano.yano.appchain.integration.internal.Blake2b256;
import com.bloxbean.cardano.yano.appchain.integration.internal.ContractValidation;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Compact domain-separated commitment to the CID already held in the effect record.
 *
 * @param bytes the 32-byte CID commitment; defensively copied
 */
public record IpfsCidFingerprint(byte[] bytes) {
    /** Frozen ASCII domain separator for CID commitments. */
    public static final String DOMAIN = "yano-ipfs-cid-v1";

    /** Validates and defensively snapshots a CID commitment. */
    public IpfsCidFingerprint {
        bytes = ContractValidation.hash32(bytes);
    }

    /**
     * Returns a defensive copy of the commitment bytes.
     *
     * @return the 32-byte commitment
     */
    @Override public byte[] bytes() { return bytes.clone(); }

    /**
     * Computes a domain-separated commitment to canonical CID bytes.
     *
     * @param cid the canonical CID
     * @return the CID commitment
     */
    public static IpfsCidFingerprint compute(CanonicalCid cid) {
        if (cid == null) {
            throw com.bloxbean.cardano.yano.appchain.integration.internal.CanonicalCbor.malformed();
        }
        byte[] domain = DOMAIN.getBytes(StandardCharsets.US_ASCII);
        byte[] cidBytes = cid.bytes();
        byte[] input = new byte[domain.length + cidBytes.length];
        System.arraycopy(domain, 0, input, 0, domain.length);
        System.arraycopy(cidBytes, 0, input, domain.length, cidBytes.length);
        return new IpfsCidFingerprint(Blake2b256.hash(input));
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof IpfsCidFingerprint fingerprint
                && Arrays.equals(bytes, fingerprint.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}
