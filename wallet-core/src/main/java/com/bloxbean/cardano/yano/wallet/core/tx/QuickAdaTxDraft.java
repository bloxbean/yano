package com.bloxbean.cardano.yano.wallet.core.tx;

import java.math.BigInteger;

public record QuickAdaTxDraft(
        String txHash,
        String cborHex,
        String fromAddress,
        String toAddress,
        BigInteger lovelace,
        BigInteger fee,
        Long ttlSlot,
        String assetSummary,
        String metadataSummary,
        int inputCount,
        int outputCount) {
}
