package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observer;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoDepositClaim;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoSettlementDatum;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoVaultDatum;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Observes only vault outputs carrying the accepted-deposit datum. Staging
 * outputs are intentionally outside this observer's address and can never be
 * turned into app-chain credits.
 */
final class AcceptedVaultDepositObserver implements L1Observer {
    private static final String LOVELACE = "lovelace";

    private final String observerId;
    private final String chainId;
    private final String vaultAddress;
    private final String vaultScriptHash;
    private final BigInteger maxLovelace;

    AcceptedVaultDepositObserver(String observerId, Map<String, String> settings) {
        this.observerId = required(observerId, "observer id");
        this.chainId = required(settings.get("chain-id"), "chain-id");
        this.vaultAddress = required(settings.get("vault-address"), "vault-address");
        this.vaultScriptHash = canonicalHash(
                settings.get("vault-script-hash"), "vault-script-hash");
        this.maxLovelace = positive(
                settings.getOrDefault("max-lovelace", "45000000000000000"),
                "max-lovelace");
        Address address = new Address(vaultAddress);
        if (!address.isScriptHashInPaymentPart()
                || address.getPaymentCredentialHash().isEmpty()
                || !vaultScriptHash.equals(HexFormat.of().formatHex(
                address.getPaymentCredentialHash().orElseThrow()))) {
            throw new IllegalArgumentException(
                    "vault-address payment credential must match vault-script-hash");
        }
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
            EutxoDepositClaim claim = claim(slot, blockHash, transaction);
            if (claim != null) {
                observations.add(new L1Observation(
                        observerId,
                        HexUtil.decodeHexString(transaction.getTxHash()),
                        slot,
                        blockHash,
                        claim.encode()));
            }
        }
        return List.copyOf(observations);
    }

    private EutxoDepositClaim claim(
            long slot,
            byte[] blockHash,
            TransactionBody transaction
    ) {
        if (transaction.getOutputs() == null) {
            return null;
        }
        EutxoDepositClaim found = null;
        for (int index = 0; index < transaction.getOutputs().size(); index++) {
            TransactionOutput output = transaction.getOutputs().get(index);
            if (!vaultAddress.equals(output.getAddress())) {
                continue;
            }
            if (output.getInlineDatum() == null) {
                throw new IllegalArgumentException(
                        "accepted bridge deposit requires an inline datum");
            }
            byte[] datumCbor = HexFormat.of().parseHex(output.getInlineDatum());
            EutxoVaultDatum datum;
            try {
                datum = EutxoVaultDatum.decode(datumCbor);
            } catch (IllegalArgumentException notDeposit) {
                try {
                    EutxoSettlementDatum.decode(datumCbor);
                    continue;
                } catch (IllegalArgumentException notSettlement) {
                    throw new IllegalArgumentException(
                            "vault output contains an unsupported bridge datum",
                            notDeposit);
                }
            }
            BigInteger lovelace = exactLovelace(output);
            if (lovelace.signum() <= 0 || lovelace.compareTo(maxLovelace) > 0) {
                throw new IllegalArgumentException(
                        "accepted bridge deposit is outside the configured lovelace bound");
            }
            if (found != null) {
                throw new IllegalArgumentException(
                        "one bridge acceptance transaction may create only one deposit vault output");
            }
            if (!chainId.equals(datum.chainId())) {
                throw new IllegalArgumentException(
                        "accepted bridge deposit targets a different app chain");
            }
            try {
                com.bloxbean.cardano.client.transaction.spec.TransactionOutput accepted =
                        com.bloxbean.cardano.client.transaction.spec.TransactionOutput.builder()
                                .address(vaultAddress)
                                .value(Value.fromCoin(lovelace))
                                .inlineDatum(PlutusData.deserialize(datumCbor))
                                .build();
                com.bloxbean.cardano.client.transaction.spec.TransactionOutput mirrored =
                        com.bloxbean.cardano.client.transaction.spec.TransactionOutput.builder()
                                .address(datum.l2Address())
                                .value(Value.fromCoin(lovelace))
                                .build();
                found = new EutxoDepositClaim(
                        EutxoDepositClaim.ABI_VERSION,
                        chainId,
                        new EutxoOutpoint(transaction.getTxHash(), index),
                        slot,
                        blockHash,
                        vaultAddress,
                        vaultScriptHash,
                        CborSerializationUtil.serialize(accepted.serialize()),
                        datum.l2Address(),
                        CborSerializationUtil.serialize(mirrored.serialize()),
                        datum.depositNonce(),
                        datum.stagingOutpoint(),
                        datum.refundDeadline());
            } catch (Exception failure) {
                throw new IllegalArgumentException(
                        "accepted bridge deposit cannot be canonically encoded", failure);
            }
        }
        return found;
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
                        "the initial EUTxO bridge accepts lovelace only");
            }
        }
        return lovelace;
    }

    @Override
    public Map<String, Object> status() {
        return Map.of(
                "type", CardanoBridgeObserverProvider.TYPE,
                "chainId", chainId,
                "vaultAddress", vaultAddress,
                "vaultScriptHash", vaultScriptHash,
                "assetProfile", LOVELACE);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String canonicalHash(String value, String field) {
        String hash = required(value, field);
        if (hash.length() != 56
                || !hash.equals(hash.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException(field + " must be 28-byte lowercase hex");
        }
        try {
            HexFormat.of().parseHex(hash);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    field + " must be 28-byte lowercase hex", failure);
        }
        return hash;
    }

    private static BigInteger positive(String value, String field) {
        try {
            BigInteger parsed = new BigInteger(required(value, field));
            if (parsed.signum() <= 0) {
                throw new IllegalArgumentException(field + " must be positive");
            }
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(field + " must be an integer", failure);
        }
    }
}
