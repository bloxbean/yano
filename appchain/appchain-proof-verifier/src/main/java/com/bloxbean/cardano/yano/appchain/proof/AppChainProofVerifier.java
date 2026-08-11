package com.bloxbean.cardano.yano.appchain.proof;

import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.jmt.JmtProfile;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;

/**
 * Product-neutral, release-matched verification of native app-chain proofs.
 */
public final class AppChainProofVerifier {
    public static final String MPF_BLAKE2B256_V1 =
            StateCommitmentProfiles.MPF_BLAKE2B256_V1;
    public static final String JMT_BLAKE2B256_V1 =
            StateCommitmentProfiles.JMT_BLAKE2B256_V1;
    public static final String JMT_POSEIDON_BLS12381_V1 =
            StateCommitmentProfiles.JMT_POSEIDON_BLS12381_V1;

    private static final int HASH_BYTES = 32;
    private static final int MAX_KEY_BYTES = 256;
    private static final int MAX_VALUE_BYTES = 1024 * 1024;
    private static final int MAX_PROOF_WIRE_BYTES = 1024 * 1024;

    private AppChainProofVerifier() {
    }

    public enum Presence {
        PRESENT,
        TOMBSTONED,
        ABSENT
    }

    /** Profile-dispatched verification against an independently trusted root. */
    public static boolean verify(
            String profile,
            Presence presence,
            byte[] expectedRoot,
            byte[] key,
            byte[] value,
            byte[] proofWire
    ) {
        if (profile == null || presence == null
                || (presence == Presence.ABSENT) != (value == null)) {
            return false;
        }
        boolean inclusion = presence != Presence.ABSENT;
        return switch (profile) {
            case MPF_BLAKE2B256_V1 -> inclusion
                    ? verifyMpfInclusion(expectedRoot, key, value, proofWire)
                    : verifyMpfExclusion(expectedRoot, key, proofWire);
            case JMT_BLAKE2B256_V1 -> verifyClassicJmt(
                    expectedRoot, key, value, inclusion, proofWire);
            case JMT_POSEIDON_BLS12381_V1 -> false;
            default -> false;
        };
    }

    public static boolean verifyMpfInclusion(
            byte[] expectedRoot,
            byte[] key,
            byte[] value,
            byte[] proofWire
    ) {
        if (!validInputs(expectedRoot, key, value, proofWire)
                || !MpfProofWirePreflight.accepts(proofWire)) {
            return false;
        }
        try {
            return new MpfTrie(NoOpNodeStore.INSTANCE)
                    .verifyProofWire(expectedRoot, key, value, true, proofWire);
        } catch (Exception | StackOverflowError malformed) {
            return false;
        }
    }

    public static boolean verifyMpfExclusion(
            byte[] expectedRoot,
            byte[] key,
            byte[] proofWire
    ) {
        if (!validInputs(expectedRoot, key, null, proofWire)
                || !MpfProofWirePreflight.accepts(proofWire)) {
            return false;
        }
        try {
            return new MpfTrie(NoOpNodeStore.INSTANCE)
                    .verifyProofWire(expectedRoot, key, null, false, proofWire);
        } catch (Exception | StackOverflowError malformed) {
            return false;
        }
    }

    public static boolean verifyClassicJmt(
            byte[] expectedRoot,
            byte[] key,
            byte[] value,
            boolean inclusion,
            byte[] proofWire
    ) {
        if (!validInputs(expectedRoot, key, inclusion ? value : null, proofWire)
                || inclusion != (value != null)) {
            return false;
        }
        try {
            JmtProfile profile = JmtProfile.classicBlake2b256V1();
            return profile.proofCodec().verify(expectedRoot, key, value, inclusion,
                    proofWire, profile.hashFunction(), profile.commitmentScheme());
        } catch (Exception | StackOverflowError malformed) {
            return false;
        }
    }

    private static boolean validInputs(
            byte[] expectedRoot,
            byte[] key,
            byte[] value,
            byte[] proofWire
    ) {
        return expectedRoot != null && expectedRoot.length == HASH_BYTES
                && key != null && key.length > 0 && key.length <= MAX_KEY_BYTES
                && (value == null || value.length <= MAX_VALUE_BYTES)
                && proofWire != null && proofWire.length > 0
                && proofWire.length <= MAX_PROOF_WIRE_BYTES;
    }

    private enum NoOpNodeStore implements NodeStore {
        INSTANCE;

        @Override public byte[] get(byte[] hash) { return null; }
        @Override public void put(byte[] hash, byte[] nodeBytes) { }
        @Override public void delete(byte[] hash) { }
    }
}
