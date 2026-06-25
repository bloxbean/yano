package com.bloxbean.cardano.yano.testkit.devnet;

import com.bloxbean.cardano.client.account.Account;

import java.util.Objects;

/**
 * Test wallet fixture backed by Cardano Client Lib's {@link Account}.
 */
public final class TestWallet {
    private final Account account;
    private final String label;

    TestWallet(Account account, String label) {
        this.account = Objects.requireNonNull(account, "account");
        this.label = label != null && !label.isBlank() ? label : "wallet";
    }

    /**
     * Returns the underlying CCL account for transaction builders and signing.
     *
     * @return CCL account
     */
    public Account account() {
        return account;
    }

    /**
     * Returns a short test label for diagnostics.
     *
     * @return label
     */
    public String label() {
        return label;
    }

    /**
     * Returns the default wallet address used by funding and balance helpers.
     *
     * @return base address
     */
    public String address() {
        return baseAddress();
    }

    /**
     * Returns the wallet base address.
     *
     * @return base address
     */
    public String baseAddress() {
        return account.baseAddress();
    }

    /**
     * Returns the wallet enterprise address.
     *
     * @return enterprise address
     */
    public String enterpriseAddress() {
        return account.enterpriseAddress();
    }

    /**
     * Returns the wallet stake address.
     *
     * @return stake address
     */
    public String stakeAddress() {
        return account.stakeAddress();
    }

    /**
     * Returns the mnemonic when the account was created from one.
     *
     * @return mnemonic, or null for key-backed accounts
     */
    public String mnemonic() {
        return account.mnemonic();
    }
}
