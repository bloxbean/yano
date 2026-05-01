package com.bloxbean.cardano.yano.wallet.core.tx;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.common.CardanoConstants;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record QuickTxPayment(
        String receiverAddress,
        BigInteger lovelace,
        List<Amount> nativeAssets) {

    public QuickTxPayment {
        if (receiverAddress == null || receiverAddress.isBlank()) {
            throw new IllegalArgumentException("receiverAddress is required");
        }
        lovelace = lovelace == null ? BigInteger.ZERO : lovelace;
        if (lovelace.signum() < 0) {
            throw new IllegalArgumentException("lovelace must not be negative");
        }
        nativeAssets = nativeAssets == null
                ? List.of()
                : nativeAssets.stream()
                .filter(Objects::nonNull)
                .map(QuickTxPayment::validatedAsset)
                .toList();
        if (lovelace.signum() == 0 && nativeAssets.isEmpty()) {
            throw new IllegalArgumentException("payment must include ADA or at least one native asset");
        }
    }

    public List<Amount> amounts() {
        List<Amount> amounts = new ArrayList<>();
        if (lovelace.signum() > 0) {
            amounts.add(Amount.lovelace(lovelace));
        }
        amounts.addAll(nativeAssets);
        return List.copyOf(amounts);
    }

    private static Amount validatedAsset(Amount amount) {
        if (amount.getUnit() == null || amount.getUnit().isBlank()) {
            throw new IllegalArgumentException("asset unit is required");
        }
        if (CardanoConstants.LOVELACE.equals(amount.getUnit())) {
            throw new IllegalArgumentException("native asset list must not contain lovelace");
        }
        if (amount.getQuantity() == null || amount.getQuantity().signum() <= 0) {
            throw new IllegalArgumentException("asset quantity must be positive");
        }
        return Amount.asset(amount.getUnit().trim(), amount.getQuantity());
    }
}
