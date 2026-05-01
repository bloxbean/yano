package com.bloxbean.cardano.yano.wallet.core.wallet;

import com.bloxbean.cardano.hdwallet.Wallet;

public record UnlockedWallet(
        StoredWallet profile,
        Wallet wallet) {
}
