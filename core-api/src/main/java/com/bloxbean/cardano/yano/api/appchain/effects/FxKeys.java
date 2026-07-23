package com.bloxbean.cardano.yano.api.appchain.effects;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Reserved effect-system trie keys and the effectsRoot merkleizer (ADR
 * app-layer/010 F4). The {@code ~fx/} first bytes are reserved in the trie
 * keyspace — {@code AppStateWriter} rejects application writes under this
 * prefix — mirroring the {@code ~} reserved-topic convention. Exposed in
 * core-api so external verifiers and clients can construct proof keys.
 */
public final class FxKeys {

    /** Reserved trie-key prefix: {@code "~fx/"} (bytes 7E 66 78 2F). */
    public static final byte[] RESERVED_PREFIX = "~fx/".getBytes(StandardCharsets.UTF_8);

    /** Consensus cap shared by the kernel, proof builder and verifier. */
    public static final int MAX_EFFECTS_PER_BLOCK = 1_048_576;

    private static final byte[] ROOT_PREFIX = "~fx/root/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] RESULTS_PREFIX = "~fx/results/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DONE_PREFIX = "~fx/done/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LIST_ROOT_DOMAIN =
            "yano:fx:list-root:v1".getBytes(StandardCharsets.UTF_8);

    private FxKeys() {
    }

    /** {@code ~fx/root/<height 8BE>} → effectsRoot(H). Written only when the block emitted ≥ 1 effect. */
    public static byte[] effectsRootKey(long height) {
        return withHeight(ROOT_PREFIX, height);
    }

    /** {@code ~fx/results/<height 8BE>} → resultsRoot(H) ({@code outcome-commitment: per-block} mode). */
    public static byte[] resultsRootKey(long height) {
        return withHeight(RESULTS_PREFIX, height);
    }

    /** {@code ~fx/done/<idHash 32>} → blake2b-256(outcome envelope) ({@code per-effect} mode). */
    public static byte[] doneKey(EffectId effectId) {
        return doneKey(effectId.hash());
    }

    public static byte[] doneKey(byte[] effectIdHash) {
        ByteBuffer buffer = ByteBuffer.allocate(DONE_PREFIX.length + effectIdHash.length);
        buffer.put(DONE_PREFIX).put(effectIdHash);
        return buffer.array();
    }

    /** True when an application-supplied key trespasses on the reserved namespace. */
    public static boolean isReserved(byte[] key) {
        if (key == null || key.length < RESERVED_PREFIX.length) {
            return false;
        }
        for (int i = 0; i < RESERVED_PREFIX.length; i++) {
            if (key[i] != RESERVED_PREFIX[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Count-bound binary Merkle commitment (blake2b-256) over ordered effect
     * hashes. Empty list → 32 zero bytes; an odd node is promoted UNCHANGED
     * to the next level
     * (pass-through) — never duplicated, so lists differing only by a
     * repeated trailing leaf produce different roots (the CVE-2012-2459
     * malleability class). This deliberately differs from the legacy
     * duplicate-promotion in {@code AppBlockCodec.messagesRoot}, which is
     * frozen by the shipped block format (ADR-010 F4).
     */
    public static byte[] effectsRoot(List<byte[]> effectHashes) {
        if (effectHashes == null || effectHashes.isEmpty()) {
            return new byte[32];
        }
        if (effectHashes.size() > MAX_EFFECTS_PER_BLOCK) {
            throw new IllegalArgumentException("Too many effect hashes");
        }
        for (byte[] hash : effectHashes) {
            if (hash == null || hash.length != 32) {
                throw new IllegalArgumentException("effect hashes must be 32 bytes");
            }
        }
        List<byte[]> level = new java.util.ArrayList<>(effectHashes);
        while (level.size() > 1) {
            List<byte[]> next = new java.util.ArrayList<>((level.size() + 1) / 2);
            for (int i = 0; i + 1 < level.size(); i += 2) {
                byte[] left = level.get(i);
                byte[] right = level.get(i + 1);
                byte[] combined = new byte[left.length + right.length];
                System.arraycopy(left, 0, combined, 0, left.length);
                System.arraycopy(right, 0, combined, left.length, right.length);
                next.add(Blake2bUtil.blake2bHash256(combined));
            }
            if ((level.size() & 1) == 1) {
                next.add(level.get(level.size() - 1)); // pass through, no duplication
            }
            level = next;
        }
        return bindListCount(effectHashes.size(), level.get(0));
    }

    /**
     * Build the ordered Merkle path for one effect hash. The path has exactly
     * one step per tree level, including explicit pass-through steps for an
     * unpaired final node. This makes the proof shape unambiguous to clients.
     */
    public static List<EffectProofStep> effectsProof(List<byte[]> effectHashes, int index) {
        if (effectHashes == null || effectHashes.isEmpty()) {
            throw new IllegalArgumentException("effectHashes must not be empty");
        }
        if (index < 0 || index >= effectHashes.size()) {
            throw new IllegalArgumentException("effect index out of range");
        }
        if (effectHashes.size() > MAX_EFFECTS_PER_BLOCK) {
            throw new IllegalArgumentException("Too many effect hashes");
        }
        for (byte[] hash : effectHashes) {
            if (hash == null || hash.length != 32) {
                throw new IllegalArgumentException("effect hashes must be 32 bytes");
            }
        }

        List<EffectProofStep> path = new java.util.ArrayList<>();
        List<byte[]> level = new java.util.ArrayList<>(effectHashes);
        int position = index;
        while (level.size() > 1) {
            if ((position & 1) == 1) {
                path.add(new EffectProofStep(EffectProofSide.LEFT, level.get(position - 1)));
            } else if (position + 1 < level.size()) {
                path.add(new EffectProofStep(EffectProofSide.RIGHT, level.get(position + 1)));
            } else {
                path.add(new EffectProofStep(EffectProofSide.PASS_THROUGH, new byte[0]));
            }

            List<byte[]> next = new java.util.ArrayList<>((level.size() + 1) / 2);
            for (int i = 0; i + 1 < level.size(); i += 2) {
                next.add(hashPair(level.get(i), level.get(i + 1)));
            }
            if ((level.size() & 1) == 1) {
                next.add(level.get(level.size() - 1));
            }
            level = next;
            position /= 2;
        }
        return List.copyOf(path);
    }

    /** Verify an ordered effect-list proof and its exact pass-through shape. */
    public static boolean verifyEffectsProof(byte[] effectHash, int index, int count,
                                             List<EffectProofStep> path, byte[] expectedRoot) {
        if (effectHash == null || effectHash.length != 32 || expectedRoot == null
                || expectedRoot.length != 32 || count <= 0
                || count > MAX_EFFECTS_PER_BLOCK || index < 0 || index >= count
                || path == null) {
            return false;
        }
        byte[] value = effectHash;
        int position = index;
        int width = count;
        int stepIndex = 0;
        try {
            while (width > 1) {
                if (stepIndex >= path.size()) {
                    return false;
                }
                EffectProofStep step = path.get(stepIndex++);
                EffectProofSide expectedSide;
                if ((position & 1) == 1) {
                    expectedSide = EffectProofSide.LEFT;
                } else if (position + 1 < width) {
                    expectedSide = EffectProofSide.RIGHT;
                } else {
                    expectedSide = EffectProofSide.PASS_THROUGH;
                }
                if (step == null || step.side() != expectedSide) {
                    return false;
                }
                value = switch (expectedSide) {
                    case LEFT -> hashPair(step.siblingHash(), value);
                    case RIGHT -> hashPair(value, step.siblingHash());
                    case PASS_THROUGH -> value;
                };
                position /= 2;
                width = width / 2 + width % 2;
            }
            return stepIndex == path.size()
                    && java.util.Arrays.equals(bindListCount(count, value), expectedRoot);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] hashPair(byte[] left, byte[] right) {
        if (left == null || right == null || left.length != 32 || right.length != 32) {
            throw new IllegalArgumentException("Merkle nodes must be 32 bytes");
        }
        byte[] combined = new byte[64];
        System.arraycopy(left, 0, combined, 0, 32);
        System.arraycopy(right, 0, combined, 32, 32);
        return Blake2bUtil.blake2bHash256(combined);
    }

    private static byte[] bindListCount(int count, byte[] rawRoot) {
        ByteBuffer commitment = ByteBuffer.allocate(
                LIST_ROOT_DOMAIN.length + Integer.BYTES + rawRoot.length);
        commitment.put(LIST_ROOT_DOMAIN).putInt(count).put(rawRoot);
        return Blake2bUtil.blake2bHash256(commitment.array());
    }

    private static byte[] withHeight(byte[] prefix, long height) {
        ByteBuffer buffer = ByteBuffer.allocate(prefix.length + 8);
        buffer.put(prefix).putLong(height);
        return buffer.array();
    }
}
