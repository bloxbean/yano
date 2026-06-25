package com.bloxbean.cardano.yano.testkit.devnet;

import com.bloxbean.cardano.yano.api.utxo.model.Utxo;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/**
 * Assertions for wallet UTXO balance checks.
 */
public final class YanoWalletAssertions {
    private static final int PAGE_SIZE = 100;

    private final YanoQueries queries;
    private final TestWallet wallet;

    YanoWalletAssertions(YanoQueries queries, TestWallet wallet) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.wallet = Objects.requireNonNull(wallet, "wallet");
    }

    /**
     * Asserts that the wallet has at least the requested lovelace on its default
     * address.
     *
     * @param lovelace minimum lovelace
     * @return this helper
     */
    public YanoWalletAssertions hasAtLeast(long lovelace) {
        requireNonNegative(lovelace);
        BigInteger actual = balance();
        BigInteger expected = BigInteger.valueOf(lovelace);
        if (actual.compareTo(expected) < 0) {
            throw new AssertionError("Expected wallet " + wallet.label() + " to have at least "
                    + lovelace + " lovelace, got " + actual);
        }
        return this;
    }

    /**
     * Asserts that the wallet has exactly the requested lovelace on its default
     * address.
     *
     * @param lovelace expected lovelace
     * @return this helper
     */
    public YanoWalletAssertions hasExactly(long lovelace) {
        requireNonNegative(lovelace);
        BigInteger actual = balance();
        BigInteger expected = BigInteger.valueOf(lovelace);
        if (!actual.equals(expected)) {
            throw new AssertionError("Expected wallet " + wallet.label() + " to have exactly "
                    + lovelace + " lovelace, got " + actual);
        }
        return this;
    }

    /**
     * Returns current wallet balance for the default address.
     *
     * @return lovelace balance
     */
    public BigInteger balance() {
        UtxoState utxoState = queries.utxoState();
        if (utxoState == null || !utxoState.isEnabled()) {
            throw new AssertionError("Wallet balance assertions require enabled UTXO state");
        }

        BigInteger total = BigInteger.ZERO;
        int page = 1;
        while (true) {
            List<Utxo> utxos = utxoState.getUtxosByAddress(wallet.address(), page, PAGE_SIZE);
            if (utxos == null || utxos.isEmpty()) {
                return total;
            }
            for (Utxo utxo : utxos) {
                if (utxo != null && utxo.lovelace() != null) {
                    total = total.add(utxo.lovelace());
                }
            }
            if (utxos.size() < PAGE_SIZE) {
                return total;
            }
            page++;
        }
    }

    private static void requireNonNegative(long lovelace) {
        if (lovelace < 0) {
            throw new IllegalArgumentException("lovelace must be non-negative");
        }
    }
}
