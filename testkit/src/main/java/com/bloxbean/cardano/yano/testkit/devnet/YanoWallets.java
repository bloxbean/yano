package com.bloxbean.cardano.yano.testkit.devnet;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.yano.api.NodeLifecycle;
import com.bloxbean.cardano.yano.api.config.NodeConfig;

import java.util.Objects;

/**
 * Wallet and address fixtures for devnet tests.
 */
public final class YanoWallets {
    public static final String DEFAULT_DETERMINISTIC_MNEMONIC =
            "damp wish scrub sentence vibrant gauge tumble raven game extend winner acid "
                    + "side amused vote edge affair buzz hospital slogan patient drum day vital";

    private final Network network;

    YanoWallets(NodeLifecycle lifecycle) {
        this.network = networkFrom(lifecycle);
    }

    /**
     * Returns the CCL network used by generated accounts.
     *
     * @return CCL network
     */
    public Network network() {
        return network;
    }

    /**
     * Creates a random wallet at address index 0.
     *
     * @return test wallet
     */
    public TestWallet newWallet() {
        return newWallet(0);
    }

    /**
     * Creates a random wallet at the supplied address index.
     *
     * @param index address index
     * @return test wallet
     */
    public TestWallet newWallet(int index) {
        requireNonNegative(index, "index");
        return new TestWallet(new Account(network, index), "wallet-" + index);
    }

    /**
     * Creates a deterministic wallet from the default test mnemonic.
     *
     * @param index address index
     * @return deterministic test wallet
     */
    public TestWallet deterministicWallet(int index) {
        return fromMnemonic(DEFAULT_DETERMINISTIC_MNEMONIC, 0, index);
    }

    /**
     * Creates a deterministic wallet from a mnemonic at account 0, index 0.
     *
     * @param mnemonic mnemonic phrase
     * @return deterministic test wallet
     */
    public TestWallet fromMnemonic(String mnemonic) {
        return fromMnemonic(mnemonic, 0, 0);
    }

    /**
     * Creates a deterministic wallet from a mnemonic, account, and address
     * index.
     *
     * @param mnemonic mnemonic phrase
     * @param account account number
     * @param index address index
     * @return deterministic test wallet
     */
    public TestWallet fromMnemonic(String mnemonic, int account, int index) {
        if (mnemonic == null || mnemonic.isBlank()) {
            throw new IllegalArgumentException("mnemonic must not be blank");
        }
        requireNonNegative(account, "account");
        requireNonNegative(index, "index");
        return new TestWallet(Account.createFromMnemonic(network, mnemonic, account, index),
                "wallet-" + account + "-" + index);
    }

    /**
     * Wraps an existing CCL account as a test wallet.
     *
     * @param account CCL account
     * @return test wallet
     */
    public TestWallet fromAccount(Account account) {
        return new TestWallet(Objects.requireNonNull(account, "account"), "wallet");
    }

    private static Network networkFrom(NodeLifecycle lifecycle) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        NodeConfig config = lifecycle.getConfig();
        long protocolMagic = config != null ? config.getProtocolMagic() : 42L;
        return new Network(0, protocolMagic);
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
