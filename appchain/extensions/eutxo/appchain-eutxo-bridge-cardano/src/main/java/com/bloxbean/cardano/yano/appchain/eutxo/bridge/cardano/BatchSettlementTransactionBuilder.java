package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoBatchSettlementMarker;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalClaim;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Builds the unsigned A2 batch settlement transaction (ADR-UTXO-009 §7.2)
 * that the SP-M2 {@code SettlementVaultValidator} Settle path accepts:
 * <ul>
 *   <li>one positional payout output per claim (output[i] = claim[i].payout
 *       to claim[i].destination),
 *   <li>a continuing vault output at index {@code count} holding
 *       {@code ΣvaultInputs − Σ(payout+bounty)} under the batch marker
 *       {@code Constr0[1, count, concat(claimIds)]},
 *   <li>the total committed bounty paid to the executor's own output,
 *   <li>the L1 fee funded from the executor's own inputs — never the vault.
 * </ul>
 * The root thread is attached as a reference input and the nullifier shard
 * as a spend input through {@link ExecutionInputs}; the threshold witness is
 * added later by the co-sign round.
 */
public final class BatchSettlementTransactionBuilder {
    private BatchSettlementTransactionBuilder() {
    }

    public static Plan build(
            List<EutxoWithdrawalClaim> claims,
            List<VaultInput> vaultInventory,
            String vaultAddress,
            String bountyAddress,
            BigInteger fee,
            BigInteger minimumContinuingLovelace,
            long currentSlot,
            long ttlSlots,
            ExecutionInputs execution
    ) {
        return build(claims, vaultInventory, vaultAddress, bountyAddress, fee,
                minimumContinuingLovelace, currentSlot, ttlSlots, execution, null);
    }

    /**
     * Full SP-M6 form: additionally emits the CONTINUING nullifier-shard
     * output the {@code NullifierShardValidator} requires — the thread token
     * plus the post-insert datum (root advanced by the batch's
     * {@code planInserts} proof chain). When {@code shardContinuation} is
     * null the body carries the shard spend only (SP-M3 compatibility for
     * tests; a real settlement always continues the shard).
     */
    public static Plan build(
            List<EutxoWithdrawalClaim> claims,
            List<VaultInput> vaultInventory,
            String vaultAddress,
            String bountyAddress,
            BigInteger fee,
            BigInteger minimumContinuingLovelace,
            long currentSlot,
            long ttlSlots,
            ExecutionInputs execution,
            com.bloxbean.cardano.yano.appchain.eutxo.contracts
                    .EutxoShardContinuation shardContinuation
    ) {
        Objects.requireNonNull(claims, "claims");
        Objects.requireNonNull(vaultInventory, "vaultInventory");
        Objects.requireNonNull(execution, "execution");
        if (claims.isEmpty()) {
            throw new IllegalArgumentException("batch must carry at least one claim");
        }
        String normalizedVault = required(vaultAddress, "vault address");
        String normalizedBounty = required(bountyAddress, "bounty address");
        BigInteger checkedFee = nonNegative(fee, "fee");
        BigInteger minimum = positive(
                minimumContinuingLovelace, "minimum continuing lovelace");
        if (currentSlot < 0 || ttlSlots <= 0
                || currentSlot > Long.MAX_VALUE - ttlSlots) {
            throw new IllegalArgumentException("invalid settlement validity interval");
        }

        BigInteger payoutTotal = BigInteger.ZERO;
        BigInteger bountyTotal = BigInteger.ZERO;
        long epoch = claims.getFirst().bridgeEpoch();
        for (EutxoWithdrawalClaim claim : claims) {
            if (claim.bridgeEpoch() != epoch) {
                throw new IllegalArgumentException(
                        "a settlement batch cannot mix bridge epochs");
            }
            payoutTotal = payoutTotal.add(claim.lovelace());
            bountyTotal = bountyTotal.add(claim.bounty());
        }
        BigInteger vaultOutflow = payoutTotal.add(bountyTotal);
        if (shardContinuation != null) {
            // Every claim in a single-shard batch must belong to the
            // continued shard (a shard only validates its own nibble).
            for (EutxoWithdrawalClaim claim : claims) {
                byte[] id = java.util.HexFormat.of().parseHex(claim.claimId());
                if ((id[31] & 0x0F) != shardContinuation.shardIndex()) {
                    throw new IllegalArgumentException(
                            "claim does not belong to the continued shard "
                                    + shardContinuation.shardIndex());
                }
            }
        }

        // Vault inputs must cover the outflow plus the continuing minimum. Fee
        // and bounty come from the executor's own inputs, so the vault side
        // is exactly outflow + minimum.
        List<VaultInput> ordered = vaultInventory.stream()
                .sorted(Comparator.comparing(VaultInput::outpoint))
                .toList();
        List<VaultInput> selected = new ArrayList<>();
        BigInteger gathered = BigInteger.ZERO;
        BigInteger vaultNeeded = vaultOutflow.add(minimum);
        for (VaultInput input : ordered) {
            selected.add(input);
            gathered = gathered.add(input.lovelace());
            if (gathered.compareTo(vaultNeeded) >= 0) {
                break;
            }
        }
        if (gathered.compareTo(vaultNeeded) < 0) {
            throw new IllegalArgumentException(
                    "vault inputs cannot fund the batch outflow and continuation");
        }
        BigInteger continuing = gathered.subtract(vaultOutflow);

        List<TransactionOutput> outputs = new ArrayList<>();
        for (EutxoWithdrawalClaim claim : claims) {
            outputs.add(TransactionOutput.builder()
                    .address(claim.destinationAddress())
                    .value(Value.fromCoin(claim.lovelace()))
                    .build());
        }
        EutxoBatchSettlementMarker marker = new EutxoBatchSettlementMarker(
                EutxoBatchSettlementMarker.VERSION,
                EutxoBatchSettlementMarker.orderedFrom(claims));
        try {
            outputs.add(TransactionOutput.builder()
                    .address(normalizedVault)
                    .value(Value.fromCoin(continuing))
                    .inlineDatum(PlutusData.deserialize(marker.encode()))
                    .build());
            if (bountyTotal.signum() > 0) {
                outputs.add(TransactionOutput.builder()
                        .address(normalizedBounty)
                        .value(Value.fromCoin(bountyTotal))
                        .build());
            }
            if (shardContinuation != null) {
                outputs.add(TransactionOutput.builder()
                        .address(shardContinuation.shardAddress())
                        .value(new Value(
                                shardContinuation.lovelace(),
                                List.of(com.bloxbean.cardano.client.transaction
                                        .spec.MultiAsset.builder()
                                        .policyId(shardContinuation
                                                .shardThreadPolicyIdHex())
                                        .assets(List.of(
                                                com.bloxbean.cardano.client
                                                        .transaction.spec.Asset
                                                        .builder()
                                                        .name("0x" + java.util
                                                                .HexFormat.of()
                                                                .formatHex(
                                                                shardContinuation
                                                                .threadTokenName()))
                                                        .value(BigInteger.ONE)
                                                        .build()))
                                        .build())))
                        .inlineDatum(PlutusData.deserialize(
                                shardContinuation.datum().encode()))
                        .build());
            }
            List<TransactionInput> inputs = new ArrayList<>();
            for (VaultInput vault : selected) {
                inputs.add(new TransactionInput(
                        vault.outpoint().transactionId(), vault.outpoint().index()));
            }
            inputs.add(new TransactionInput(
                    execution.shardInput().transactionId(),
                    execution.shardInput().index()));
            for (EutxoOutpoint feeInput : execution.feeInputs()) {
                inputs.add(new TransactionInput(
                        feeInput.transactionId(), feeInput.index()));
            }
            var body = TransactionBody.builder()
                    .inputs(inputs)
                    .outputs(outputs)
                    .fee(checkedFee)
                    .ttl(currentSlot + ttlSlots)
                    .validityStartInterval(currentSlot)
                    .networkId(execution.networkId())
                    .referenceInputs(List.of(new TransactionInput(
                            execution.rootReferenceInput().transactionId(),
                            execution.rootReferenceInput().index())))
                    .requiredSigners(execution.requiredSignerHashes());
            TransactionBody prepared = body.build();
            return new Plan(
                    CborSerializationUtil.serialize(prepared.serialize()),
                    List.copyOf(selected),
                    continuing,
                    bountyTotal,
                    marker.claimIds(),
                    currentSlot + ttlSlots);
        } catch (Exception failure) {
            throw new IllegalArgumentException(
                    "cannot build the batch settlement body", failure);
        }
    }

    public record VaultInput(EutxoOutpoint outpoint, BigInteger lovelace) {
        public VaultInput {
            Objects.requireNonNull(outpoint, "outpoint");
            lovelace = positive(lovelace, "vault input lovelace");
        }
    }

    /** Root reference input, spent nullifier shard, fee inputs, signers. */
    public record ExecutionInputs(
            NetworkId networkId,
            EutxoOutpoint rootReferenceInput,
            EutxoOutpoint shardInput,
            List<EutxoOutpoint> feeInputs,
            List<byte[]> requiredSignerHashes
    ) {
        public ExecutionInputs {
            Objects.requireNonNull(networkId, "networkId");
            Objects.requireNonNull(rootReferenceInput, "rootReferenceInput");
            Objects.requireNonNull(shardInput, "shardInput");
            feeInputs = List.copyOf(Objects.requireNonNull(feeInputs, "feeInputs"));
            if (feeInputs.isEmpty()) {
                throw new IllegalArgumentException(
                        "batch settlement needs at least one executor fee input");
            }
            List<byte[]> signers = new ArrayList<>();
            for (byte[] hash : requiredSignerHashes) {
                signers.add(hash.clone());
            }
            requiredSignerHashes = List.copyOf(signers);
        }
    }

    public record Plan(
            byte[] unsignedBodyCbor,
            List<VaultInput> selectedVaultInputs,
            BigInteger continuingVaultLovelace,
            BigInteger bountyLovelace,
            List<String> orderedClaimIds,
            long ttl
    ) {
        public Plan {
            unsignedBodyCbor = Objects.requireNonNull(
                    unsignedBodyCbor, "unsignedBodyCbor").clone();
            selectedVaultInputs = List.copyOf(selectedVaultInputs);
            orderedClaimIds = List.copyOf(orderedClaimIds);
        }

        @Override
        public byte[] unsignedBodyCbor() {
            return unsignedBodyCbor.clone();
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static BigInteger nonNegative(BigInteger value, String field) {
        Objects.requireNonNull(value, field);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(field + " cannot be negative");
        }
        return value;
    }

    private static BigInteger positive(BigInteger value, String field) {
        Objects.requireNonNull(value, field);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
