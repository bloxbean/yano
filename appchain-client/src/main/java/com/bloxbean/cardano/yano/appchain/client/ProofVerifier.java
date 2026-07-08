package com.bloxbean.cardano.yano.appchain.client;

import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;

/**
 * Client-side MPF proof verification ("don't trust, verify",
 * ADR app-layer/006 E1.1): checks an inclusion proof served by a node against
 * a state root — ideally one read from a Cardano L1 anchor transaction rather
 * than the node's own claim. Pure Java, no node access required.
 */
public final class ProofVerifier {

    private ProofVerifier() {
    }

    /**
     * No-op store: {@code verifyProofWire} walks the serialized proof only and
     * never touches the store. Avoids depending on the MPF library's internal
     * test fixtures (no compatibility guarantee, may be stripped from releases).
     */
    private static final class NoOpNodeStore implements NodeStore {
        @Override
        public byte[] get(byte[] hash) {
            return null;
        }

        @Override
        public void put(byte[] hash, byte[] nodeBytes) {
        }

        @Override
        public void delete(byte[] hash) {
        }
    }

    /** Verify the proof against the state root it was served with. */
    public static boolean verify(AppChainClient.Proof proof) {
        return verify(proof, proof.stateRootHex());
    }

    /**
     * Verify the proof against an independently obtained state root —
     * e.g. the {@code state_root} from an anchor transaction's metadata.
     */
    public static boolean verify(AppChainClient.Proof proof, String expectedStateRootHex) {
        if (proof == null || proof.valueHex() == null) {
            return false;
        }
        return verifyInclusion(
                Hex.decode(expectedStateRootHex),
                Hex.decode(proof.keyHex()),
                Hex.decode(proof.valueHex()),
                Hex.decode(proof.proofWireHex()));
    }

    /** Raw form: does {@code root} commit to {@code key → value} per this wire proof? */
    public static boolean verifyInclusion(byte[] expectedRoot, byte[] key, byte[] value, byte[] proofWire) {
        try {
            // Verification only walks the serialized proof — the store is unused
            MpfTrie trie = new MpfTrie(new NoOpNodeStore());
            return trie.verifyProofWire(expectedRoot, key, value, true, proofWire);
        } catch (Exception e) {
            return false;
        }
    }
}
