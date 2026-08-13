package com.bloxbean.cardano.yano.archive.core.dataset;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

public record BlockSourceContext<B>(long blockNumber, long slot, long epoch, Instant blockTime,
                                    byte[] blockHash, byte[] parentHash, B block) {
    public BlockSourceContext {
        if (blockNumber < 0 || slot < 0 || epoch < 0) throw new IllegalArgumentException("negative block coordinate");
        Objects.requireNonNull(blockTime, "blockTime");
        if (blockHash == null || blockHash.length == 0) throw new IllegalArgumentException("blockHash is required");
        if (parentHash == null) throw new IllegalArgumentException("parentHash is required");
        Objects.requireNonNull(block, "block");
        blockHash = Arrays.copyOf(blockHash, blockHash.length);
        parentHash = Arrays.copyOf(parentHash, parentHash.length);
    }

    @Override public byte[] blockHash() { return Arrays.copyOf(blockHash, blockHash.length); }
    @Override public byte[] parentHash() { return Arrays.copyOf(parentHash, parentHash.length); }
}
