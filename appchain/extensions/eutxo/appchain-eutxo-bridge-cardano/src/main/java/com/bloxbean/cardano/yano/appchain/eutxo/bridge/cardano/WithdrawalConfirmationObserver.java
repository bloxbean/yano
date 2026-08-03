package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observer;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoSettlementDatum;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalConfirmation;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Emits one confirmation only when a settlement marker and exact payout agree. */
final class WithdrawalConfirmationObserver implements L1Observer {
    private static final String LOVELACE = "lovelace";

    private final String observerId;
    private final String chainId;
    private final long bridgeEpoch;
    private final String vaultAddress;

    WithdrawalConfirmationObserver(String observerId, Map<String, String> settings) {
        this.observerId = required(observerId, "observer id");
        this.chainId = required(settings.get("chain-id"), "chain-id");
        this.bridgeEpoch = nonNegativeLong(
                settings.getOrDefault("bridge-epoch", "0"), "bridge epoch");
        this.vaultAddress = required(settings.get("vault-address"), "vault-address");
    }

    @Override
    public String observerId() {
        return observerId;
    }

    @Override
    public List<L1Observation> observe(long slot, byte[] blockHash, Block block) {
        if (block == null || block.getTransactionBodies() == null) {
            return List.of();
        }
        List<L1Observation> observations = new ArrayList<>();
        for (TransactionBody transaction : block.getTransactionBodies()) {
            EutxoWithdrawalConfirmation confirmation =
                    confirmation(slot, blockHash, transaction);
            if (confirmation != null) {
                observations.add(new L1Observation(
                        observerId,
                        HexUtil.decodeHexString(transaction.getTxHash()),
                        slot,
                        blockHash,
                        confirmation.encode()));
            }
        }
        return List.copyOf(observations);
    }

    private EutxoWithdrawalConfirmation confirmation(
            long slot,
            byte[] blockHash,
            TransactionBody transaction
    ) {
        if (transaction.getOutputs() == null) {
            return null;
        }
        EutxoSettlementDatum settlement = null;
        int continuingVaultIndex = -1;
        BigInteger continuingVaultLovelace = BigInteger.ZERO;
        for (int index = 0; index < transaction.getOutputs().size(); index++) {
            TransactionOutput output = transaction.getOutputs().get(index);
            if (!vaultAddress.equals(output.getAddress())
                    || output.getInlineDatum() == null) {
                continue;
            }
            EutxoSettlementDatum candidate;
            try {
                candidate = EutxoSettlementDatum.decode(
                        java.util.HexFormat.of().parseHex(output.getInlineDatum()));
            } catch (IllegalArgumentException notSettlement) {
                continue;
            }
            if (settlement != null) {
                throw new IllegalArgumentException(
                        "one transaction may confirm only one EUTxO withdrawal");
            }
            if (!chainId.equals(candidate.chainId())
                    || bridgeEpoch != candidate.bridgeEpoch()) {
                throw new IllegalArgumentException(
                        "settlement marker targets another chain or bridge epoch");
            }
            settlement = candidate;
            continuingVaultIndex = index;
            continuingVaultLovelace = exactLovelace(output);
            if (continuingVaultLovelace.signum() <= 0) {
                throw new IllegalArgumentException(
                        "settlement requires a positive continuing vault output");
            }
        }
        if (settlement == null) {
            return null;
        }
        int payoutIndex = -1;
        for (int index = 0; index < transaction.getOutputs().size(); index++) {
            TransactionOutput output = transaction.getOutputs().get(index);
            if (settlement.matchesDestination(output.getAddress())
                    && settlement.lovelace().equals(exactLovelace(output))) {
                if (payoutIndex >= 0) {
                    throw new IllegalArgumentException(
                            "settlement has more than one matching payout output");
                }
                payoutIndex = index;
            }
        }
        if (payoutIndex < 0) {
            throw new IllegalArgumentException(
                    "settlement marker has no exact payout output");
        }
        return new EutxoWithdrawalConfirmation(
                EutxoWithdrawalConfirmation.ABI_VERSION,
                chainId,
                bridgeEpoch,
                settlement.claimId(),
                transaction.getTxHash(),
                payoutIndex,
                transaction.getOutputs().get(payoutIndex).getAddress(),
                settlement.lovelace(),
                new EutxoOutpoint(transaction.getTxHash(), continuingVaultIndex),
                continuingVaultLovelace,
                slot,
                blockHash);
    }

    private static BigInteger exactLovelace(TransactionOutput output) {
        BigInteger lovelace = BigInteger.ZERO;
        if (output.getAmounts() == null) {
            return lovelace;
        }
        for (Amount amount : output.getAmounts()) {
            BigInteger quantity = amount.getQuantity() == null
                    ? BigInteger.ZERO : amount.getQuantity();
            if (LOVELACE.equals(amount.getUnit())) {
                lovelace = lovelace.add(quantity);
            } else if (quantity.signum() != 0) {
                throw new IllegalArgumentException(
                        "EUTxO withdrawal settlement accepts lovelace only");
            }
        }
        return lovelace;
    }

    @Override
    public Map<String, Object> status() {
        return Map.of(
                "type", CardanoWithdrawalConfirmationObserverProvider.TYPE,
                "chainId", chainId,
                "bridgeEpoch", bridgeEpoch,
                "vaultAddress", vaultAddress,
                "assetProfile", LOVELACE);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static long nonNegativeLong(String value, String field) {
        try {
            long parsed = Long.parseLong(required(value, field));
            if (parsed < 0) {
                throw new IllegalArgumentException(field + " cannot be negative");
            }
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(field + " must be an integer", failure);
        }
    }
}
