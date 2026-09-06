package com.bloxbean.cardano.yano.api.wallet;

import java.util.Locale;

/** Exact canonical coordinate; block -1 denotes origin, independently of slot zero. */
public record WalletChainPoint(long blockNumber, long slot, String blockHash) {
    public static final WalletChainPoint ORIGIN = new WalletChainPoint(-1, 0, "00".repeat(32));

    public WalletChainPoint {
        if (blockNumber < -1 || slot < 0 || !WalletHex.valid(blockHash, 32)) {
            throw new IllegalArgumentException("Invalid wallet chain point");
        }
        blockHash = blockHash.toLowerCase(Locale.ROOT);
        if (blockNumber == -1 && (slot != 0 || !blockHash.equals("00".repeat(32)))) {
            throw new IllegalArgumentException("Invalid origin point");
        }
    }
}
