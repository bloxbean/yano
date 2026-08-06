package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observer;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoBatchSettlementMarker;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoBatchWithdrawalConfirmation;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ADR-UTXO-009 SP-M3: the v3 (batch) counterpart to
 * {@link WithdrawalConfirmationObserver}. An A2 settlement transaction pays
 * every claim in the batch positionally and carries an
 * {@link EutxoBatchSettlementMarker} on its continuing vault output. This
 * observer decodes that marker, reads each positional payout, and emits ONE
 * observation whose claim covers the whole batch — see
 * {@link EutxoBatchWithdrawalConfirmation} for why a single observation
 * (rather than one per claim) is required by the framework's
 * observation-keying.
 *
 * <p>The transaction shape is the one the on-chain
 * {@code SettlementVaultValidator} enforces and
 * {@code BatchSettlementTransactionBuilder} produces: outputs {@code [0,
 * count)} are the positional payouts, output {@code count} is the continuing
 * vault carrying the marker, and output {@code count + 1} is the bounty.
 */
final class BatchWithdrawalConfirmationObserver implements L1Observer {
    private static final String LOVELACE = "lovelace";

    private final String observerId;
    private final String chainId;
    private final long bridgeEpoch;
    private final String vaultAddress;

    BatchWithdrawalConfirmationObserver(String observerId, Map<String, String> settings) {
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
            EutxoBatchWithdrawalConfirmation confirmation =
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

    private EutxoBatchWithdrawalConfirmation confirmation(
            long slot,
            byte[] blockHash,
            TransactionBody transaction
    ) {
        if (transaction.getOutputs() == null) {
            return null;
        }
        List<TransactionOutput> outputs = transaction.getOutputs();
        EutxoBatchSettlementMarker marker = null;
        int continuingVaultIndex = -1;
        BigInteger continuingVaultLovelace = BigInteger.ZERO;
        for (int index = 0; index < outputs.size(); index++) {
            TransactionOutput output = outputs.get(index);
            if (!vaultAddress.equals(output.getAddress())
                    || output.getInlineDatum() == null) {
                continue;
            }
            EutxoBatchSettlementMarker candidate;
            try {
                candidate = EutxoBatchSettlementMarker.decode(
                        HexUtil.decodeHexString(output.getInlineDatum()));
            } catch (RuntimeException notBatchMarker) {
                continue;
            }
            if (marker != null) {
                throw new IllegalArgumentException(
                        "one transaction may carry only one batch settlement marker");
            }
            marker = candidate;
            continuingVaultIndex = index;
            continuingVaultLovelace = exactLovelace(output);
            if (continuingVaultLovelace.signum() <= 0) {
                throw new IllegalArgumentException(
                        "settlement requires a positive continuing vault output");
            }
        }
        if (marker == null) {
            return null;
        }
        int count = marker.count();
        // The continuing vault sits immediately after the dense positional
        // payouts; anything else is not a settlement this observer can trust.
        if (continuingVaultIndex != count) {
            throw new IllegalArgumentException(
                    "batch settlement continuing vault is not at the payout boundary");
        }
        if (outputs.size() < count + 1) {
            throw new IllegalArgumentException(
                    "batch settlement is missing positional payout outputs");
        }
        List<EutxoBatchWithdrawalConfirmation.Entry> entries = new ArrayList<>(count);
        List<String> claimIds = marker.claimIds();
        for (int index = 0; index < count; index++) {
            TransactionOutput payout = outputs.get(index);
            BigInteger lovelace = exactLovelace(payout);
            if (lovelace.signum() <= 0) {
                throw new IllegalArgumentException(
                        "batch settlement payout must be a positive lovelace output");
            }
            entries.add(new EutxoBatchWithdrawalConfirmation.Entry(
                    claimIds.get(index), index, payout.getAddress(), lovelace));
        }
        return new EutxoBatchWithdrawalConfirmation(
                EutxoBatchWithdrawalConfirmation.ABI_VERSION,
                chainId,
                bridgeEpoch,
                transaction.getTxHash(),
                new EutxoOutpoint(transaction.getTxHash(), continuingVaultIndex),
                continuingVaultLovelace,
                slot,
                blockHash,
                entries);
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
                        "EUTxO batch settlement accepts lovelace only");
            }
        }
        return lovelace;
    }

    @Override
    public Map<String, Object> status() {
        return Map.of(
                "type", CardanoBatchWithdrawalConfirmationObserverProvider.TYPE,
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
