package com.bloxbean.cardano.yano.wallet.core.wallet;

import java.math.BigInteger;

public record WalletAssetBalance(
        String unit,
        BigInteger quantity) {

    public WalletAssetBalance {
        if (unit == null || unit.isBlank()) {
            throw new IllegalArgumentException("unit is required");
        }
        quantity = quantity == null ? BigInteger.ZERO : quantity;
    }
}
