package org.yanoproject.api.chain;

import java.util.Locale;
import java.util.regex.Pattern;

/** Exact canonical coordinate. Origin is distinct from a real slot-zero block. */
public record ChainPoint(long blockNumber, long slot, String blockHash) {
    private static final Pattern HASH = Pattern.compile("[0-9a-fA-F]{64}");
    public static final ChainPoint ORIGIN = new ChainPoint(-1, 0, "00".repeat(32));

    public ChainPoint {
        if (blockNumber < -1 || slot < 0 || blockHash == null || !HASH.matcher(blockHash).matches()) {
            throw new IllegalArgumentException("Invalid chain point");
        }
        blockHash = blockHash.toLowerCase(Locale.ROOT);
        if (blockNumber == -1 && (slot != 0 || !blockHash.equals("00".repeat(32)))) {
            throw new IllegalArgumentException("Invalid origin point");
        }
    }
}
