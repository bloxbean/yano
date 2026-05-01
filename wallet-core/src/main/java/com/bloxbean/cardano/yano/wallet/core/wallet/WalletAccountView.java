package com.bloxbean.cardano.yano.wallet.core.wallet;

import java.util.List;

public record WalletAccountView(
        String walletId,
        String name,
        String networkId,
        int accountIndex,
        String stakeAddress,
        String drepId,
        List<WalletAddressView> receiveAddresses) {

    public WalletAccountView {
        receiveAddresses = receiveAddresses == null ? List.of() : List.copyOf(receiveAddresses);
    }
}
