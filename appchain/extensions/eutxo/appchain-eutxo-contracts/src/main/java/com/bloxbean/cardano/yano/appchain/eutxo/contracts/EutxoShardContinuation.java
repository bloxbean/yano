package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.Objects;

/**
 * ADR-UTXO-009 SP-M6: everything a settlement/exit builder needs to emit the
 * CONTINUING nullifier-shard output paired with the shard spend: the shard
 * script address, the thread-token identity (policy + the shard's single
 * index byte), the min-ADA the thread carries, and the post-insert datum
 * ({@link EutxoShardDatum#withRoot} of {@code NullifierShardMirror.planInserts}'
 * next root). The on-chain {@code NullifierShardValidator} requires exactly
 * one continuing output holding the thread token with this datum shape.
 */
public record EutxoShardContinuation(
        String shardAddress,
        String shardThreadPolicyIdHex,
        BigInteger lovelace,
        EutxoShardDatum datum
) {
    public EutxoShardContinuation {
        shardAddress = Objects.requireNonNull(shardAddress, "shardAddress").trim();
        if (shardAddress.isEmpty()) {
            throw new IllegalArgumentException("shard address is required");
        }
        shardThreadPolicyIdHex = Objects.requireNonNull(
                shardThreadPolicyIdHex, "shardThreadPolicyIdHex")
                .trim().toLowerCase(java.util.Locale.ROOT);
        byte[] policy;
        try {
            policy = HexFormat.of().parseHex(shardThreadPolicyIdHex);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                    "shard thread policy id must be canonical hex", failure);
        }
        if (policy.length != 28) {
            throw new IllegalArgumentException(
                    "shard thread policy id must be 28 bytes");
        }
        Objects.requireNonNull(lovelace, "lovelace");
        if (lovelace.signum() <= 0) {
            throw new IllegalArgumentException(
                    "shard continuation lovelace must be positive");
        }
        Objects.requireNonNull(datum, "datum");
    }

    /** The thread token name carried by the continuing output. */
    public byte[] threadTokenName() {
        return datum.threadTokenName();
    }

    public int shardIndex() {
        return datum.shardIndex();
    }
}
