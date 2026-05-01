package com.bloxbean.cardano.yano.wallet.core.wallet;

import java.math.BigInteger;

public record WalletUtxoView(
        String address,
        String txHash,
        int outputIndex,
        BigInteger lovelace,
        int assetCount,
        boolean hasDatum,
        boolean hasReferenceScript) {
}
