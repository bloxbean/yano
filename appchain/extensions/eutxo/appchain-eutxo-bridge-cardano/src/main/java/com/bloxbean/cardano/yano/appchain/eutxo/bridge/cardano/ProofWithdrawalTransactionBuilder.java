package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoNullifierState;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProofWithdrawal;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoSettlementDatum;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Builds a complete proof-gated settlement body. The relayer supplies only
 * fees/collateral and its own required signer; no custody signer is required.
 */
public final class ProofWithdrawalTransactionBuilder {
    private ProofWithdrawalTransactionBuilder() {
    }

    public static Plan build(Request request) {
        Objects.requireNonNull(request, "request");
        var withdrawal = request.withdrawal();
        var claim = withdrawal.commitment();
        var root = request.acceptedRoot();
        var nullifier = request.nullifierState();
        if (!claim.matchesDestination(request.destinationAddress())) {
            throw new IllegalArgumentException(
                    "payout address does not match the committed destination");
        }
        if (!root.accepts(withdrawal)) {
            throw new IllegalArgumentException(
                    "withdrawal proof is not fixed to the accepted root");
        }
        if (!root.chainId().equals(nullifier.chainId())
                || root.bridgeEpoch() != nullifier.bridgeEpoch()
                || root.generation() != nullifier.generation()
                || claim.settlementSequence()
                != nullifier.nextSettlementSequence()) {
            throw new IllegalArgumentException(
                    "accepted root, claim, and nullifier cursor are inconsistent");
        }
        EutxoNullifierState next =
                nullifier.advance(claim.settlementSequence());
        BigInteger fee = nonNegative(request.fee(), "fee");
        BigInteger minimum = positive(
                request.minimumContinuingLovelace(),
                "minimum continuing lovelace");
        if (request.currentSlot() < 0 || request.ttlSlots() <= 0
                || request.currentSlot() > Long.MAX_VALUE - request.ttlSlots()) {
            throw new IllegalArgumentException(
                    "invalid proof-settlement validity interval");
        }
        BigInteger needed = claim.lovelace().add(minimum);
        VaultWithdrawalTransactionBuilder.VaultInput vault =
                request.vaultInventory().stream()
                        .sorted(Comparator.comparing(
                                VaultWithdrawalTransactionBuilder
                                        .VaultInput::outpoint))
                        .filter(input ->
                                input.lovelace().compareTo(needed) >= 0)
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "one stable vault input must fund the proof withdrawal"));
        BigInteger continuing = vault.lovelace()
                .subtract(claim.lovelace());
        Execution execution = request.execution();
        BigInteger feeFunding = execution.feeInputs().stream()
                .map(FundingInput::lovelace)
                .reduce(BigInteger.ZERO, BigInteger::add);
        BigInteger feeChange = exactAda(
                execution.feeChangeOutput(),
                "fee change output");
        if (!feeFunding.equals(fee.add(feeChange))) {
            throw new IllegalArgumentException(
                    "relayer fee inputs must equal the fee plus ADA-only change");
        }
        requireDisjoint(
                vault.outpoint(),
                request.nullifierInput().outpoint(),
                request.rootReferenceInput(),
                execution);

        EutxoSettlementDatum settlement =
                EutxoSettlementDatum.forAddress(
                EutxoSettlementDatum.ABI_VERSION,
                root.chainId(),
                root.bridgeEpoch(),
                java.util.HexFormat.of().formatHex(claim.claimId()),
                request.destinationAddress(),
                claim.lovelace());
        try {
            List<TransactionInput> references = new ArrayList<>();
            references.add(input(request.rootReferenceInput()));
            execution.referenceScriptInputs().stream()
                    .sorted()
                    .map(ProofWithdrawalTransactionBuilder::input)
                    .forEach(references::add);
            List<TransactionInput> spending = new ArrayList<>();
            spending.add(input(vault.outpoint()));
            spending.add(input(request.nullifierInput().outpoint()));
            execution.feeInputs().stream()
                    .map(FundingInput::outpoint)
                    .sorted()
                    .map(ProofWithdrawalTransactionBuilder::input)
                    .forEach(spending::add);
            TransactionBody body = TransactionBody.builder()
                    .inputs(List.copyOf(spending))
                    .outputs(List.of(
                            TransactionOutput.builder()
                                    .address(request.destinationAddress())
                                    .value(Value.fromCoin(claim.lovelace()))
                                    .build(),
                            TransactionOutput.builder()
                                    .address(request.vaultAddress().trim())
                                    .value(Value.fromCoin(continuing))
                                    .inlineDatum(PlutusData.deserialize(
                                            settlement.encode()))
                                    .build(),
                            TransactionOutput.builder()
                                    .address(request.nullifierInput().address())
                                    .value(request.nullifierInput().value())
                                    .inlineDatum(PlutusData.deserialize(
                                            next.encode()))
                                    .build(),
                            execution.feeChangeOutput()))
                    .fee(fee)
                    .ttl(request.currentSlot() + request.ttlSlots())
                    .validityStartInterval(request.currentSlot())
                    .networkId(execution.networkId())
                    .collateral(inputs(execution.collateralInputs()))
                    .totalCollateral(execution.totalCollateral())
                    .collateralReturn(execution.collateralReturn())
                    .referenceInputs(List.copyOf(references))
                    .scriptDataHash(execution.scriptDataHash())
                    .requiredSigners(execution.relayerSignerHashes())
                    .build();
            return new Plan(
                    CborSerializationUtil.serialize(body.serialize()),
                    withdrawal.encode(),
                    vault,
                    continuing,
                    next,
                    request.currentSlot() + request.ttlSlots());
        } catch (Exception failure) {
            throw new IllegalArgumentException(
                    "cannot build the proof withdrawal body", failure);
        }
    }

    private static void requireDisjoint(
            EutxoOutpoint vault,
            EutxoOutpoint nullifier,
            EutxoOutpoint rootReference,
            Execution execution
    ) {
        if (vault.equals(nullifier)) {
            throw new IllegalArgumentException(
                    "proof settlement inputs must be distinct");
        }
        List<EutxoOutpoint> spending = new ArrayList<>();
        spending.add(vault);
        spending.add(nullifier);
        execution.feeInputs().stream()
                .map(FundingInput::outpoint)
                .forEach(spending::add);
        Set<EutxoOutpoint> uniqueSpending = new HashSet<>(spending);
        if (uniqueSpending.size() != spending.size()) {
            throw new IllegalArgumentException(
                    "proof settlement spending inputs must be unique");
        }
        if (spending.contains(rootReference)
                || execution.collateralInputs().stream().anyMatch(
                uniqueSpending::contains)
                || execution.referenceScriptInputs().stream().anyMatch(
                uniqueSpending::contains)) {
            throw new IllegalArgumentException(
                    "proof settlement inputs, references, and collateral "
                            + "must be disjoint");
        }
        Set<EutxoOutpoint> uniqueCollateral =
                new HashSet<>(execution.collateralInputs());
        Set<EutxoOutpoint> uniqueReferences =
                new HashSet<>(execution.referenceScriptInputs());
        uniqueReferences.add(rootReference);
        if (uniqueCollateral.size() != execution.collateralInputs().size()
                || uniqueReferences.size()
                != execution.referenceScriptInputs().size() + 1
                || execution.collateralInputs().stream()
                .anyMatch(uniqueReferences::contains)) {
            throw new IllegalArgumentException(
                    "proof settlement execution inputs must be unique");
        }
    }

    private static TransactionInput input(EutxoOutpoint outpoint) {
        return new TransactionInput(
                outpoint.transactionId(), outpoint.index());
    }

    private static List<TransactionInput> inputs(
            List<EutxoOutpoint> outpoints
    ) {
        return outpoints.stream()
                .sorted()
                .map(ProofWithdrawalTransactionBuilder::input)
                .toList();
    }

    public record Request(
            EutxoProofWithdrawal withdrawal,
            com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoFederatedRoot
                    acceptedRoot,
            EutxoNullifierState nullifierState,
            List<VaultWithdrawalTransactionBuilder.VaultInput> vaultInventory,
            String destinationAddress,
            String vaultAddress,
            ThreadStateInput nullifierInput,
            EutxoOutpoint rootReferenceInput,
            BigInteger fee,
            BigInteger minimumContinuingLovelace,
            long currentSlot,
            long ttlSlots,
            Execution execution
    ) {
        public Request {
            Objects.requireNonNull(withdrawal, "withdrawal");
            Objects.requireNonNull(acceptedRoot, "acceptedRoot");
            Objects.requireNonNull(nullifierState, "nullifierState");
            vaultInventory = List.copyOf(
                    Objects.requireNonNull(vaultInventory, "vaultInventory"));
            if (vaultInventory.isEmpty()) {
                throw new IllegalArgumentException(
                        "vault inventory cannot be empty");
            }
            if (destinationAddress == null || destinationAddress.isBlank()) {
                throw new IllegalArgumentException(
                        "destination address is required");
            }
            if (vaultAddress == null || vaultAddress.isBlank()) {
                throw new IllegalArgumentException(
                        "vault address is required");
            }
            Objects.requireNonNull(nullifierInput, "nullifierInput");
            Objects.requireNonNull(rootReferenceInput, "rootReferenceInput");
            Objects.requireNonNull(execution, "execution");
        }
    }

    public record ThreadStateInput(
            EutxoOutpoint outpoint,
            String address,
            Value value,
            byte[] threadPolicyId,
            byte[] threadAssetName
    ) {
        public ThreadStateInput {
            Objects.requireNonNull(outpoint, "outpoint");
            if (address == null || address.isBlank()) {
                throw new IllegalArgumentException(
                        "thread-state address is required");
            }
            Objects.requireNonNull(value, "value");
            if (!value.isPositive()) {
                throw new IllegalArgumentException(
                        "thread-state value must be positive");
            }
            threadPolicyId = Objects.requireNonNull(
                    threadPolicyId, "threadPolicyId").clone();
            threadAssetName = Objects.requireNonNull(
                    threadAssetName, "threadAssetName").clone();
            if (threadPolicyId.length != 28
                    || threadAssetName.length > 32
                    || !value.amountOf(
                    java.util.HexFormat.of().formatHex(threadPolicyId),
                    java.util.HexFormat.of().formatHex(threadAssetName))
                    .equals(BigInteger.ONE)) {
                throw new IllegalArgumentException(
                        "thread-state input must contain exactly one "
                                + "configured thread token");
            }
        }

        @Override
        public byte[] threadPolicyId() {
            return threadPolicyId.clone();
        }

        @Override
        public byte[] threadAssetName() {
            return threadAssetName.clone();
        }
    }

    public record Execution(
            NetworkId networkId,
            List<FundingInput> feeInputs,
            TransactionOutput feeChangeOutput,
            List<EutxoOutpoint> collateralInputs,
            BigInteger totalCollateral,
            TransactionOutput collateralReturn,
            List<EutxoOutpoint> referenceScriptInputs,
            byte[] scriptDataHash,
            List<byte[]> relayerSignerHashes
    ) {
        public Execution {
            Objects.requireNonNull(networkId, "networkId");
            feeInputs = List.copyOf(
                    Objects.requireNonNull(feeInputs, "feeInputs"));
            Objects.requireNonNull(feeChangeOutput, "feeChangeOutput");
            collateralInputs = List.copyOf(
                    Objects.requireNonNull(
                            collateralInputs, "collateralInputs"));
            totalCollateral = positive(
                    totalCollateral, "total collateral");
            Objects.requireNonNull(collateralReturn, "collateralReturn");
            referenceScriptInputs = List.copyOf(
                    Objects.requireNonNull(
                            referenceScriptInputs,
                            "referenceScriptInputs"));
            scriptDataHash = Objects.requireNonNull(
                    scriptDataHash, "scriptDataHash").clone();
            relayerSignerHashes = Objects.requireNonNull(
                            relayerSignerHashes, "relayerSignerHashes")
                    .stream()
                    .map(byte[]::clone)
                    .toList();
            if (feeInputs.isEmpty()
                    || collateralInputs.isEmpty()
                    || scriptDataHash.length != 32
                    || relayerSignerHashes.isEmpty()
                    || relayerSignerHashes.stream()
                    .anyMatch(hash -> hash.length != 28)) {
                throw new IllegalArgumentException(
                        "proof settlement requires fee inputs, collateral, "
                                + "a 32-byte script-data hash, and 28-byte "
                                + "relayer signers");
            }
        }

        @Override
        public byte[] scriptDataHash() {
            return scriptDataHash.clone();
        }

        @Override
        public List<byte[]> relayerSignerHashes() {
            return relayerSignerHashes.stream().map(byte[]::clone).toList();
        }
    }

    public record FundingInput(
            EutxoOutpoint outpoint,
            BigInteger lovelace
    ) {
        public FundingInput {
            Objects.requireNonNull(outpoint, "outpoint");
            lovelace = positive(lovelace, "funding-input lovelace");
        }
    }

    public record Plan(
            byte[] unsignedBodyCbor,
            byte[] proofRedeemerCbor,
            VaultWithdrawalTransactionBuilder.VaultInput selectedVaultInput,
            BigInteger continuingVaultLovelace,
            EutxoNullifierState nextNullifierState,
            long ttl
    ) {
        public Plan {
            unsignedBodyCbor = Objects.requireNonNull(
                    unsignedBodyCbor, "unsignedBodyCbor").clone();
            proofRedeemerCbor = Objects.requireNonNull(
                    proofRedeemerCbor, "proofRedeemerCbor").clone();
            Objects.requireNonNull(selectedVaultInput, "selectedVaultInput");
            Objects.requireNonNull(
                    continuingVaultLovelace,
                    "continuingVaultLovelace");
            Objects.requireNonNull(
                    nextNullifierState, "nextNullifierState");
        }

        @Override
        public byte[] unsignedBodyCbor() {
            return unsignedBodyCbor.clone();
        }

        @Override
        public byte[] proofRedeemerCbor() {
            return proofRedeemerCbor.clone();
        }
    }

    private static BigInteger positive(
            BigInteger value,
            String field
    ) {
        BigInteger checked = nonNegative(value, field);
        if (checked.signum() == 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return checked;
    }

    private static BigInteger nonNegative(
            BigInteger value,
            String field
    ) {
        BigInteger checked = Objects.requireNonNull(value, field);
        if (checked.signum() < 0) {
            throw new IllegalArgumentException(field + " cannot be negative");
        }
        return checked;
    }

    private static BigInteger exactAda(
            TransactionOutput output,
            String field
    ) {
        Objects.requireNonNull(output, field);
        Value value = Objects.requireNonNull(output.getValue(), field + " value");
        if (value.getCoin() == null
                || value.getCoin().signum() <= 0
                || (value.getMultiAssets() != null
                && !value.getMultiAssets().isEmpty())) {
            throw new IllegalArgumentException(
                    field + " must contain positive ADA only");
        }
        return value.getCoin();
    }
}
