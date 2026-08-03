package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoSettlementDatum;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalClaim;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Deterministically selects vault inputs and builds one claim-bound payout. */
public final class VaultWithdrawalTransactionBuilder {
    private VaultWithdrawalTransactionBuilder() {
    }

    public static Plan build(
            EutxoWithdrawalClaim claim,
            List<VaultInput> inventory,
            String vaultAddress,
            BigInteger fee,
            BigInteger minimumContinuingLovelace,
            long currentSlot,
            long ttlSlots
    ) {
        return build(
                claim, inventory, vaultAddress, fee,
                minimumContinuingLovelace, currentSlot, ttlSlots,
                ExecutionPolicy.templateOnly());
    }

    public static Plan build(
            EutxoWithdrawalClaim claim,
            List<VaultInput> inventory,
            String vaultAddress,
            BigInteger fee,
            BigInteger minimumContinuingLovelace,
            long currentSlot,
            long ttlSlots,
            ExecutionPolicy execution
    ) {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(execution, "execution");
        String normalizedVault = required(vaultAddress, "vault address");
        BigInteger checkedFee = nonNegative(fee, "fee");
        BigInteger minimum = positive(
                minimumContinuingLovelace, "minimum continuing lovelace");
        if (currentSlot < 0 || ttlSlots <= 0
                || currentSlot > Long.MAX_VALUE - ttlSlots) {
            throw new IllegalArgumentException("invalid settlement validity interval");
        }
        List<VaultInput> ordered = inventory.stream()
                .sorted(Comparator.comparing(VaultInput::outpoint))
                .toList();
        BigInteger needed = claim.lovelace().add(checkedFee).add(minimum);
        VaultInput selectedInput = ordered.stream()
                .filter(input -> input.lovelace().compareTo(needed) >= 0)
                .findFirst()
                .orElse(null);
        if (selectedInput == null) {
            throw new IllegalArgumentException(
                    "one stable vault input must fund the withdrawal and continuation");
        }
        List<VaultInput> inputs = List.of(selectedInput);
        BigInteger selected = selectedInput.lovelace();
        BigInteger continuing = selected.subtract(claim.lovelace()).subtract(checkedFee);
        if (execution.complete()) {
            java.util.Set<EutxoOutpoint> spending = inputs.stream()
                    .map(VaultInput::outpoint)
                    .collect(java.util.stream.Collectors.toSet());
            if (execution.collateralInputs().stream().anyMatch(spending::contains)
                    || execution.referenceInputs().stream().anyMatch(spending::contains)) {
                throw new IllegalArgumentException(
                        "settlement execution inputs cannot overlap vault spends");
            }
        }
        EutxoSettlementDatum datum = EutxoSettlementDatum.forAddress(
                EutxoSettlementDatum.ABI_VERSION,
                claim.chainId(),
                claim.bridgeEpoch(),
                claim.claimId(),
                claim.destinationAddress(),
                claim.lovelace());
        try {
            var body = TransactionBody.builder()
                    .inputs(inputs.stream()
                            .map(input -> new TransactionInput(
                                    input.outpoint().transactionId(),
                                    input.outpoint().index()))
                            .toList())
                    .outputs(List.of(
                            TransactionOutput.builder()
                                    .address(claim.destinationAddress())
                                    .value(Value.fromCoin(claim.lovelace()))
                                    .build(),
                            TransactionOutput.builder()
                                    .address(normalizedVault)
                                    .value(Value.fromCoin(continuing))
                                    .inlineDatum(PlutusData.deserialize(datum.encode()))
                                    .build()))
                    .fee(checkedFee)
                    .ttl(currentSlot + ttlSlots)
                    .validityStartInterval(currentSlot);
            if (execution.complete()) {
                body.networkId(execution.networkId())
                        .collateral(inputs(execution.collateralInputs()))
                        .totalCollateral(execution.totalCollateral())
                        .collateralReturn(execution.collateralReturn())
                        .referenceInputs(inputs(execution.referenceInputs()))
                        .scriptDataHash(execution.scriptDataHash())
                        .requiredSigners(execution.requiredSignerHashes());
            }
            TransactionBody prepared = body.build();
            return new Plan(
                    CborSerializationUtil.serialize(prepared.serialize()),
                    List.copyOf(inputs),
                    continuing,
                    currentSlot + ttlSlots,
                    execution.complete());
        } catch (Exception failure) {
            throw new IllegalArgumentException(
                    "cannot build the withdrawal settlement body", failure);
        }
    }

    public record VaultInput(EutxoOutpoint outpoint, BigInteger lovelace) {
        public VaultInput {
            Objects.requireNonNull(outpoint, "outpoint");
            lovelace = positive(lovelace, "vault input lovelace");
        }
    }

    public record Plan(
            byte[] unsignedBodyCbor,
            List<VaultInput> selectedInputs,
            BigInteger continuingVaultLovelace,
            long ttl,
            boolean submitReady
    ) {
        public Plan {
            unsignedBodyCbor = Objects.requireNonNull(
                    unsignedBodyCbor, "unsignedBodyCbor").clone();
            selectedInputs = List.copyOf(
                    Objects.requireNonNull(selectedInputs, "selectedInputs"));
            Objects.requireNonNull(continuingVaultLovelace, "continuingVaultLovelace");
        }

        @Override
        public byte[] unsignedBodyCbor() {
            return unsignedBodyCbor.clone();
        }
    }

    public record ExecutionPolicy(
            NetworkId networkId,
            List<EutxoOutpoint> collateralInputs,
            BigInteger totalCollateral,
            TransactionOutput collateralReturn,
            List<EutxoOutpoint> referenceInputs,
            byte[] scriptDataHash,
            List<byte[]> requiredSignerHashes,
            boolean complete
    ) {
        public ExecutionPolicy {
            collateralInputs = List.copyOf(
                    Objects.requireNonNull(collateralInputs, "collateralInputs"));
            referenceInputs = List.copyOf(
                    Objects.requireNonNull(referenceInputs, "referenceInputs"));
            scriptDataHash = Objects.requireNonNull(
                    scriptDataHash, "scriptDataHash").clone();
            requiredSignerHashes = Objects.requireNonNull(
                            requiredSignerHashes, "requiredSignerHashes").stream()
                    .map(byte[]::clone)
                    .toList();
            if (complete) {
                Objects.requireNonNull(networkId, "networkId");
                positive(totalCollateral, "total collateral");
                Objects.requireNonNull(collateralReturn, "collateralReturn");
                if (collateralInputs.isEmpty()
                        || scriptDataHash.length != 32
                        || requiredSignerHashes.isEmpty()
                        || requiredSignerHashes.stream().anyMatch(hash -> hash.length != 28)) {
                    throw new IllegalArgumentException(
                            "complete settlement policy requires collateral, a 32-byte "
                                    + "script-data hash, and 28-byte required signers");
                }
                java.util.Set<EutxoOutpoint> unique = new java.util.HashSet<>(
                        collateralInputs);
                if (unique.size() != collateralInputs.size()) {
                    throw new IllegalArgumentException(
                            "settlement collateral inputs must be unique");
                }
            }
        }

        public static ExecutionPolicy templateOnly() {
            return new ExecutionPolicy(
                    null, List.of(), BigInteger.ZERO, null,
                    List.of(), new byte[0], List.of(), false);
        }

        public static ExecutionPolicy plutusV3(
                NetworkId networkId,
                List<EutxoOutpoint> collateralInputs,
                BigInteger totalCollateral,
                TransactionOutput collateralReturn,
                List<EutxoOutpoint> referenceInputs,
                byte[] scriptDataHash,
                List<byte[]> requiredSignerHashes
        ) {
            return new ExecutionPolicy(
                    networkId, collateralInputs, totalCollateral,
                    collateralReturn, referenceInputs, scriptDataHash,
                    requiredSignerHashes, true);
        }

        @Override
        public byte[] scriptDataHash() {
            return scriptDataHash.clone();
        }

        @Override
        public List<byte[]> requiredSignerHashes() {
            return requiredSignerHashes.stream().map(byte[]::clone).toList();
        }
    }

    private static List<TransactionInput> inputs(List<EutxoOutpoint> outpoints) {
        return outpoints.stream()
                .sorted()
                .map(outpoint -> new TransactionInput(
                        outpoint.transactionId(), outpoint.index()))
                .toList();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static BigInteger positive(BigInteger value, String field) {
        BigInteger checked = nonNegative(value, field);
        if (checked.signum() == 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return checked;
    }

    private static BigInteger nonNegative(BigInteger value, String field) {
        BigInteger checked = Objects.requireNonNull(value, field);
        if (checked.signum() < 0) {
            throw new IllegalArgumentException(field + " cannot be negative");
        }
        return checked;
    }
}
