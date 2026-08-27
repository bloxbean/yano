package com.bloxbean.cardano.yano.archive.core.address;

public record Outpoint(byte[] txHash, int outputIndex) {
    public Outpoint {
        if (txHash == null || txHash.length == 0 || outputIndex < 0) throw new IllegalArgumentException("invalid outpoint");
        txHash = txHash.clone();
    }
    @Override public byte[] txHash() { return txHash.clone(); }
    @Override public boolean equals(Object other) {
        return other instanceof Outpoint that && outputIndex == that.outputIndex
                && java.util.Arrays.equals(txHash, that.txHash);
    }
    @Override public int hashCode() { return 31 * java.util.Arrays.hashCode(txHash) + outputIndex; }
}
