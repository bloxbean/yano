package com.bloxbean.cardano.yano.api.appchain.effects;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Deterministic effect identity: the effect's position in chain history
 * (ADR app-layer/010 F2). Never a UUID, random, wall clock or sequence — every
 * node derives the identical id, across retries, restarts and executor
 * failover, which is what makes it usable as the universal idempotency key.
 *
 * @param chainId the app chain
 * @param height  block height the effect was emitted at
 * @param ordinal zero-based emission ordinal within the block
 */
public record EffectId(String chainId, long height, int ordinal) {

    private static final byte[] DOMAIN = "yano-fx-v1".getBytes(StandardCharsets.UTF_8);

    public EffectId {
        Objects.requireNonNull(chainId, "chainId");
        if (height <= 0) {
            throw new IllegalArgumentException("height must be positive");
        }
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must be >= 0");
        }
    }

    /** Human/URL-friendly rendering: {@code <chainId>/<height>/<ordinal>}. */
    public String canonical() {
        return chainId + "/" + height + "/" + ordinal;
    }

    /**
     * Fixed-width external key: {@code blake2b-256("yano-fx-v1" || chainId ||
     * be64(height) || be32(ordinal))}. Handed to external systems as the
     * idempotency key on EVERY attempt (HTTP {@code Idempotency-Key}, Cardano
     * tx metadata, DB unique constraint).
     */
    public byte[] hash() {
        byte[] chain = chainId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(DOMAIN.length + chain.length + 8 + 4);
        buffer.put(DOMAIN).put(chain).putLong(height).putInt(ordinal);
        return Blake2bUtil.blake2bHash256(buffer.array());
    }

    public String hashHex() {
        return HexUtil.encodeHexString(hash());
    }

    /** Parse the {@link #canonical()} rendering. */
    public static EffectId parse(String canonical) {
        int last = canonical.lastIndexOf('/');
        int mid = canonical.lastIndexOf('/', last - 1);
        if (mid <= 0 || last <= mid + 1) {
            throw new IllegalArgumentException("Not a canonical effect id: " + canonical);
        }
        return new EffectId(canonical.substring(0, mid),
                Long.parseLong(canonical.substring(mid + 1, last)),
                Integer.parseInt(canonical.substring(last + 1)));
    }
}
