package com.bloxbean.cardano.yano.wallet.core.wallet;

public record WalletAddressView(
        int accountIndex,
        int addressIndex,
        String role,
        String derivationPath,
        String baseAddress,
        String enterpriseAddress) {
}
