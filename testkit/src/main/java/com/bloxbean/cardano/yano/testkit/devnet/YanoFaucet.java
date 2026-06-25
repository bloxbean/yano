package com.bloxbean.cardano.yano.testkit.devnet;

import com.bloxbean.cardano.yano.api.DevnetControl;
import com.bloxbean.cardano.yano.api.model.FundResult;
import com.bloxbean.cardano.yano.api.model.FundingRequest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Faucet helpers backed by {@link DevnetControl}.
 */
public final class YanoFaucet {
    private final DevnetControl devnet;

    YanoFaucet(DevnetControl devnet) {
        this.devnet = Objects.requireNonNull(devnet, "devnet");
    }

    /**
     * Funds an address with a synthetic devnet UTXO.
     *
     * @param address target Cardano address
     * @param lovelace amount in lovelace
     * @return funding result
     */
    public FundResult fund(String address, long lovelace) {
        requireAddress(address);
        requirePositiveLovelace(lovelace);
        return devnet.fundAddress(address, lovelace);
    }

    /**
     * Funds a wallet's default address with a synthetic devnet UTXO.
     *
     * @param wallet target wallet
     * @param lovelace amount in lovelace
     * @return funding result
     */
    public FundResult fund(TestWallet wallet, long lovelace) {
        Objects.requireNonNull(wallet, "wallet");
        return fund(wallet.address(), lovelace);
    }

    /**
     * Funds a wallet's enterprise address with a synthetic devnet UTXO.
     *
     * @param wallet target wallet
     * @param lovelace amount in lovelace
     * @return funding result
     */
    public FundResult fundEnterprise(TestWallet wallet, long lovelace) {
        Objects.requireNonNull(wallet, "wallet");
        return fund(wallet.enterpriseAddress(), lovelace);
    }

    /**
     * Funds a sequence of addresses. Runtime funding is sequential and
     * non-atomic.
     *
     * @param requests funding requests
     * @return funding results in request order
     */
    public List<FundResult> fundAll(Collection<FundingRequest> requests) {
        Objects.requireNonNull(requests, "requests");
        List<FundResult> results = new ArrayList<>(requests.size());
        for (FundingRequest request : requests) {
            Objects.requireNonNull(request, "request");
            results.add(fund(request.address(), request.lovelace()));
        }
        return List.copyOf(results);
    }

    /**
     * Funds multiple wallets with the same amount. Runtime funding is
     * sequential and non-atomic.
     *
     * @param wallets target wallets
     * @param lovelace amount in lovelace per wallet
     * @return funding results in wallet order
     */
    public List<FundResult> fundWallets(Collection<TestWallet> wallets, long lovelace) {
        Objects.requireNonNull(wallets, "wallets");
        requirePositiveLovelace(lovelace);
        List<FundResult> results = new ArrayList<>(wallets.size());
        for (TestWallet wallet : wallets) {
            results.add(fund(wallet, lovelace));
        }
        return List.copyOf(results);
    }

    private static void requireAddress(String address) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("address must not be blank");
        }
    }

    private static void requirePositiveLovelace(long lovelace) {
        if (lovelace <= 0) {
            throw new IllegalArgumentException("lovelace must be positive");
        }
    }
}
