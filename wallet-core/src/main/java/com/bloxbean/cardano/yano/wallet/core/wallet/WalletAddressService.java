package com.bloxbean.cardano.yano.wallet.core.wallet;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.hdwallet.Wallet;

import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

public class WalletAddressService {
    public WalletAccountView accountView(StoredWallet profile, Wallet wallet, int receiveAddressCount) {
        Objects.requireNonNull(profile, "profile is required");
        Objects.requireNonNull(wallet, "wallet is required");
        if (receiveAddressCount <= 0) {
            throw new IllegalArgumentException("receiveAddressCount must be positive");
        }

        int accountIndex = profile.accountIndex();
        Account accountZero = wallet.getAccount(accountIndex, 0);
        List<WalletAddressView> receiveAddresses = IntStream.range(0, receiveAddressCount)
                .mapToObj(index -> receiveAddress(wallet, accountIndex, index))
                .toList();

        return new WalletAccountView(
                profile.id(),
                profile.name(),
                profile.networkId(),
                accountIndex,
                accountZero.stakeAddress(),
                accountZero.drepId(),
                receiveAddresses);
    }

    private WalletAddressView receiveAddress(Wallet wallet, int accountIndex, int addressIndex) {
        Account account = wallet.getAccount(accountIndex, addressIndex);
        return new WalletAddressView(
                accountIndex,
                addressIndex,
                "receive",
                receiveDerivationPath(accountIndex, addressIndex),
                account.baseAddress(),
                account.enterpriseAddress());
    }

    private String receiveDerivationPath(int accountIndex, int addressIndex) {
        return "m/1852'/1815'/" + accountIndex + "'/0/" + addressIndex;
    }
}
