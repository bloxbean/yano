package com.bloxbean.cardano.yano.appchain.eutxo.indexer;

import java.util.Objects;

public record IndexHealth(
        Status status,
        IndexCheckpoint checkpoint,
        long finalizedHeight,
        long lagBlocks,
        String diagnostic
) {
    public enum Status {
        READY,
        CATCHING_UP,
        REBUILDING,
        FAILED,
        IDENTITY_MISMATCH
    }

    public IndexHealth {
        status = Objects.requireNonNull(status, "status");
        checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
        diagnostic = Objects.requireNonNullElse(diagnostic, "");
        if (finalizedHeight < 0 || lagBlocks < 0 || diagnostic.length() > 512) {
            throw new IllegalArgumentException("invalid index health");
        }
    }
}
