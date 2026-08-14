package com.bloxbean.cardano.yano.api.appchain;

import java.util.Objects;

/**
 * The newest anchor that this node has observed and confirmed on L1.
 *
 * <p>The commitment is node-reported evidence. Independent authenticity still
 * requires resolving {@code transactionHash} on Cardano and verifying the
 * expected metadata or script output.
 */
public record AppAnchorCommitment(
        String chainId,
        String mode,
        long anchoredHeight,
        byte[] stateRoot,
        byte[] blockHash,
        String transactionHash,
        long l1Slot
) {

    public AppAnchorCommitment {
        Objects.requireNonNull(chainId, "chainId");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(stateRoot, "stateRoot");
        Objects.requireNonNull(blockHash, "blockHash");
        Objects.requireNonNull(transactionHash, "transactionHash");
        if (chainId.isBlank()) {
            throw new IllegalArgumentException("chainId must not be blank");
        }
        if (mode.isBlank()) {
            throw new IllegalArgumentException("mode must not be blank");
        }
        if (anchoredHeight <= 0) {
            throw new IllegalArgumentException("anchoredHeight must be positive");
        }
        if (stateRoot.length != 32) {
            throw new IllegalArgumentException("stateRoot must contain exactly 32 bytes");
        }
        if (blockHash.length != 32) {
            throw new IllegalArgumentException("blockHash must contain exactly 32 bytes");
        }
        if (transactionHash.isBlank()) {
            throw new IllegalArgumentException("transactionHash must not be blank");
        }
        if (l1Slot < 0) {
            throw new IllegalArgumentException("l1Slot must not be negative");
        }
        stateRoot = stateRoot.clone();
        blockHash = blockHash.clone();
    }

    @Override
    public byte[] stateRoot() {
        return stateRoot.clone();
    }

    @Override
    public byte[] blockHash() {
        return blockHash.clone();
    }
}
