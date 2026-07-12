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

    private static final byte[] ROOT_PREFIX = "~fx/root/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] RESULTS_PREFIX = "~fx/results/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DONE_PREFIX = "~fx/done/".getBytes(StandardCharsets.UTF_8);

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
     * Binary merkle root (blake2b-256) over ordered effect hashes. Empty list
     * → 32 zero bytes; an odd node is promoted UNCHANGED to the next level
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
        return level.get(0);
    }

    private static byte[] withHeight(byte[] prefix, long height) {
        ByteBuffer buffer = ByteBuffer.allocate(prefix.length + 8);
        buffer.put(prefix).putLong(height);
        return buffer.array();
    }
}
