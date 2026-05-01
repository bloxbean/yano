package com.bloxbean.cardano.yano.wallet.core.wallet;

import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.CardanoConstants;
import com.bloxbean.cardano.hdwallet.Wallet;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class WalletBalanceService {
    private static final int DEFAULT_GAP_LIMIT = 20;
    private static final int DEFAULT_MAX_ADDRESSES = 100;

    public WalletBalance scan(Wallet wallet, UtxoSupplier utxoSupplier) {
        return scan(wallet, utxoSupplier, DEFAULT_GAP_LIMIT, DEFAULT_MAX_ADDRESSES);
    }

    public WalletBalance scan(Wallet wallet, UtxoSupplier utxoSupplier, int gapLimit, int maxAddresses) {
        Objects.requireNonNull(wallet, "wallet is required");
        Objects.requireNonNull(utxoSupplier, "utxoSupplier is required");
        if (gapLimit <= 0) {
            throw new IllegalArgumentException("gapLimit must be positive");
        }
        if (maxAddresses <= 0) {
            throw new IllegalArgumentException("maxAddresses must be positive");
        }

        BigInteger total = BigInteger.ZERO;
        int unusedStreak = 0;
        int scannedAddresses = 0;
        List<WalletUtxoView> walletUtxos = new ArrayList<>();
        Map<String, BigInteger> assets = new LinkedHashMap<>();

        for (int index = 0; index < maxAddresses && unusedStreak < gapLimit; index++) {
            String address = wallet.getBaseAddressString(index);
            List<Utxo> utxos = utxoSupplier.getAll(address);
            scannedAddresses++;

            if (utxos == null || utxos.isEmpty()) {
                unusedStreak++;
                continue;
            }

            unusedStreak = 0;
            for (Utxo utxo : utxos) {
                BigInteger lovelace = lovelace(utxo);
                total = total.add(lovelace);
                aggregateAssets(utxo, assets);
                walletUtxos.add(new WalletUtxoView(
                        address,
                        utxo.getTxHash(),
                        utxo.getOutputIndex(),
                        lovelace,
                        assetCount(utxo),
                        utxo.getDataHash() != null || utxo.getInlineDatum() != null,
                        utxo.getReferenceScriptHash() != null));
            }
        }

        List<WalletAssetBalance> assetBalances = assets.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(entry -> new WalletAssetBalance(entry.getKey(), entry.getValue()))
                .toList();
        return new WalletBalance(total, scannedAddresses, walletUtxos.size(), walletUtxos, assetBalances);
    }

    private BigInteger lovelace(Utxo utxo) {
        if (utxo == null || utxo.getAmount() == null) {
            return BigInteger.ZERO;
        }
        return utxo.getAmount().stream()
                .filter(amount -> CardanoConstants.LOVELACE.equals(amount.getUnit()))
                .map(amount -> amount.getQuantity() == null ? BigInteger.ZERO : amount.getQuantity())
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    private int assetCount(Utxo utxo) {
        if (utxo == null || utxo.getAmount() == null) {
            return 0;
        }
        return (int) utxo.getAmount().stream()
                .filter(amount -> !CardanoConstants.LOVELACE.equals(amount.getUnit()))
                .count();
    }

    private void aggregateAssets(Utxo utxo, Map<String, BigInteger> assets) {
        if (utxo == null || utxo.getAmount() == null) {
            return;
        }
        utxo.getAmount().stream()
                .filter(amount -> amount.getUnit() != null)
                .filter(amount -> !CardanoConstants.LOVELACE.equals(amount.getUnit()))
                .forEach(amount -> assets.merge(
                        amount.getUnit(),
                        amount.getQuantity() == null ? BigInteger.ZERO : amount.getQuantity(),
                        BigInteger::add));
    }
}
