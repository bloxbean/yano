package com.bloxbean.cardano.yano.appchain.client;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Offline verifier for ADR-010's composed effect proof:
 * canonical record bytes -&gt; ordered effects Merkle root -&gt; historical MPF
 * state root. The caller should prefer {@link #verifyFor} with a state root
 * obtained independently from a certified block or L1 anchor.
 */
public final class EffectProofVerifier {

    private static final int PROOF_VERSION = 1;
    private static final int EFFECT_RECORD_VERSION = 1;
    private static final int EFFECT_RECORD_FIELDS = 11;
    private static final int HASH_BYTES = 32;
    private static final int MAX_EFFECTS_PER_BLOCK = 1_048_576;
    private static final int MAX_RECORD_CBOR_BYTES = 16 * 1024 * 1024;
    private static final int MAX_STATE_PROOF_WIRE_BYTES = 1024 * 1024;
    private static final byte[] ROOT_PREFIX = "~fx/root/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LIST_ROOT_DOMAIN =
            "yano:fx:list-root:v1".getBytes(StandardCharsets.UTF_8);
    private static final ObjectMapper CBOR = new ObjectMapper(new CBORFactory());

    private EffectProofVerifier() {
    }

    /** Verify against the historical state root carried by the proof. */
    public static boolean verify(AppChainClient.EffectProof proof) {
        return verifyInternal(proof, null, null, null, null);
    }

    /** Verify against an independently obtained state root. */
    public static boolean verify(AppChainClient.EffectProof proof, String expectedStateRootHex) {
        return verifyInternal(proof, expectedStateRootHex, null, null, null);
    }

    /**
     * Verify the proof and bind it to the effect identity the caller asked
     * for, rather than accepting only the identity asserted by the server.
     */
    public static boolean verifyFor(AppChainClient.EffectProof proof,
                                    String expectedChainId,
                                    long expectedHeight,
                                    int expectedOrdinal) {
        return verifyInternal(proof, null, expectedChainId, expectedHeight, expectedOrdinal);
    }

    /** Verify against both an independently trusted root and requested identity. */
    public static boolean verifyFor(AppChainClient.EffectProof proof,
                                    String expectedStateRootHex,
                                    String expectedChainId,
                                    long expectedHeight,
                                    int expectedOrdinal) {
        return verifyInternal(proof, expectedStateRootHex, expectedChainId,
                expectedHeight, expectedOrdinal);
    }

    private static boolean verifyInternal(AppChainClient.EffectProof proof,
                                          String expectedStateRootHex,
                                          String expectedChainId,
                                          Long expectedHeight,
                                          Integer expectedOrdinal) {
        try {
            if (proof == null || proof.version() != PROOF_VERSION
                    || proof.chainId() == null || proof.chainId().isBlank()
                    || proof.height() <= 0 || proof.ordinal() < 0
                    || proof.effectCount() <= 0
                    || proof.effectCount() > MAX_EFFECTS_PER_BLOCK
                    || proof.ordinal() >= proof.effectCount()) {
                return false;
            }
            if (expectedChainId != null && !expectedChainId.equals(proof.chainId())) {
                return false;
            }
            if (expectedHeight != null && expectedHeight != proof.height()) {
                return false;
            }
            if (expectedOrdinal != null && expectedOrdinal != proof.ordinal()) {
                return false;
            }

            byte[] recordCbor = decodeHexBounded(
                    proof.recordCborHex(), MAX_RECORD_CBOR_BYTES, "record CBOR");
            byte[] claimedEffectHash = decodeHash(proof.effectHashHex());
            byte[] effectHash = Blake2bUtil.blake2bHash256(recordCbor);
            if (!Arrays.equals(effectHash, claimedEffectHash)
                    || !recordIdentityMatches(recordCbor, proof)) {
                return false;
            }

            byte[] effectsRoot = decodeHash(proof.effectsRootHex());
            if (!verifyListPath(effectHash, proof.ordinal(), proof.effectCount(),
                    proof.merklePath(), effectsRoot)) {
                return false;
            }

            byte[] stateKey = decodeHexBounded(proof.stateKeyHex(), 64, "state key");
            if (!Arrays.equals(stateKey, effectsRootKey(proof.height()))) {
                return false;
            }
            byte[] servedStateRoot = decodeHash(proof.stateRootHex());
            byte[] expectedStateRoot = expectedStateRootHex != null
                    ? decodeHash(expectedStateRootHex) : servedStateRoot;
            if (!Arrays.equals(servedStateRoot, expectedStateRoot)) {
                return false;
            }
            byte[] proofWire = decodeHexBounded(
                    proof.stateProofWireHex(), MAX_STATE_PROOF_WIRE_BYTES, "state proof");
            if (proofWire.length == 0) {
                return false;
            }
            return ProofVerifier.verifyInclusion(expectedStateRoot, stateKey,
                    effectsRoot, proofWire);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean recordIdentityMatches(byte[] recordCbor,
                                                 AppChainClient.EffectProof proof) {
        try {
            JsonNode record = CBOR.readTree(recordCbor);
            return record != null && record.isArray() && record.size() == EFFECT_RECORD_FIELDS
                    && record.get(0).isIntegralNumber()
                    && record.get(0).asInt() == EFFECT_RECORD_VERSION
                    && record.get(1).isTextual()
                    && proof.chainId().equals(record.get(1).asText())
                    && record.get(2).isIntegralNumber()
                    && record.get(2).canConvertToLong()
                    && record.get(2).asLong() == proof.height()
                    && record.get(3).isIntegralNumber()
                    && record.get(3).canConvertToInt()
                    && record.get(3).asInt() == proof.ordinal();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean verifyListPath(byte[] leaf, int ordinal, int count,
                                          List<AppChainClient.EffectMerkleStep> path,
                                          byte[] expectedRoot) {
        if (path == null || path.size() != levels(count)) {
            return false;
        }
        byte[] current = leaf;
        int index = ordinal;
        int width = count;
        int level = 0;
        while (width > 1) {
            AppChainClient.EffectMerkleStep step = path.get(level++);
            if (step == null || step.side() == null) {
                return false;
            }
            if ((index & 1) == 1) {
                if (step.side() != AppChainClient.EffectMerkleSide.LEFT) {
                    return false;
                }
                current = hashPair(decodeHash(step.siblingHashHex()), current);
            } else if (index + 1 < width) {
                if (step.side() != AppChainClient.EffectMerkleSide.RIGHT) {
                    return false;
                }
                current = hashPair(current, decodeHash(step.siblingHashHex()));
            } else {
                if (step.side() != AppChainClient.EffectMerkleSide.PASS_THROUGH
                        || (step.siblingHashHex() != null && !step.siblingHashHex().isBlank())) {
                    return false;
                }
                // Odd trailing nodes are promoted unchanged — no hash here.
            }
            index /= 2;
            width = width / 2 + width % 2;
        }
        return Arrays.equals(bindListCount(count, current), expectedRoot);
    }

    private static int levels(int count) {
        int levels = 0;
        for (int width = count; width > 1; width = width / 2 + width % 2) {
            levels++;
        }
        return levels;
    }

    private static byte[] effectsRootKey(long height) {
        return ByteBuffer.allocate(ROOT_PREFIX.length + Long.BYTES)
                .put(ROOT_PREFIX).putLong(height).array();
    }

    private static byte[] decodeHash(String hex) {
        if (hex == null || hex.length() != HASH_BYTES * 2) {
            throw new IllegalArgumentException("Expected a 32-byte hash");
        }
        byte[] value = Hex.decode(hex);
        if (value.length != HASH_BYTES) {
            throw new IllegalArgumentException("Expected a 32-byte hash");
        }
        return value;
    }

    private static byte[] decodeHexBounded(String hex, int maxBytes, String label) {
        if (hex == null || (hex.length() & 1) != 0 || hex.length() > maxBytes * 2) {
            throw new IllegalArgumentException(label + " exceeds its encoded size bound");
        }
        return Hex.decode(hex);
    }

    private static byte[] hashPair(byte[] left, byte[] right) {
        byte[] pair = new byte[HASH_BYTES * 2];
        System.arraycopy(left, 0, pair, 0, HASH_BYTES);
        System.arraycopy(right, 0, pair, HASH_BYTES, HASH_BYTES);
        return Blake2bUtil.blake2bHash256(pair);
    }

    private static byte[] bindListCount(int count, byte[] rawRoot) {
        return Blake2bUtil.blake2bHash256(ByteBuffer.allocate(
                        LIST_ROOT_DOMAIN.length + Integer.BYTES + rawRoot.length)
                .put(LIST_ROOT_DOMAIN).putInt(count).put(rawRoot).array());
    }
}
