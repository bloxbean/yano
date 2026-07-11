package com.bloxbean.cardano.yano.p2p.tx.diffusion;

public record TxBodyIngressResult(
        int accepted,
        int rejected,
        int ignored,
        long acceptedBytes
) {
    public TxBodyIngressResult {
        accepted = Math.max(0, accepted);
        rejected = Math.max(0, rejected);
        ignored = Math.max(0, ignored);
        acceptedBytes = Math.max(0L, acceptedBytes);
    }

    public static TxBodyIngressResult empty() {
        return new TxBodyIngressResult(0, 0, 0, 0);
    }
}
