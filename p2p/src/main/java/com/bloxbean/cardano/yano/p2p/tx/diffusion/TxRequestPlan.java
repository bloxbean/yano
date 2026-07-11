package com.bloxbean.cardano.yano.p2p.tx.diffusion;

import java.util.List;

public record TxRequestPlan(
        List<TxIdAndSize> requested,
        int ignored,
        int rejected,
        long requestedBytes
) {
    public TxRequestPlan {
        requested = requested != null ? List.copyOf(requested) : List.of();
        ignored = Math.max(0, ignored);
        rejected = Math.max(0, rejected);
        requestedBytes = Math.max(0L, requestedBytes);
    }

    public static TxRequestPlan empty() {
        return new TxRequestPlan(List.of(), 0, 0, 0);
    }
}
