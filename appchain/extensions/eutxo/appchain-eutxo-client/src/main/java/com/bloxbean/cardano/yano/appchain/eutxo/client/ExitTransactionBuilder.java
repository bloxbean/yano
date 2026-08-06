package com.bloxbean.cardano.yano.appchain.eutxo.client;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoBatchSettlementMarker;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalClaim;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * ADR-UTXO-009 §7.3 / SP-M5: builds the unsigned permissionless A3 Exit
 * transaction the SP-M2 {@code SettlementVaultValidator} Exit path accepts.
 * A cranker drains one nullifier shard's queue once the accepted-root thread
 * has gone stale beyond its governed {@code fallbackDelaySlots}; the tx needs
 * no federation signature — arming (a validity lower bound past
 * {@code updatedAtSlot + fallbackDelaySlots}) plus per-claim MPF inclusion in
 * the accepted state root authorizes it.
 *
 * <p>Body shape (identical to Settle, minus required signers):
 * <ul>
 *   <li>one positional payout output per claim (output[i] = claim[i].payout to
 *       claim[i].destination),
 *   <li>a continuing vault output at index {@code count} holding
 *       {@code ΣvaultInputs − Σ(payout+bounty)} under the batch marker
 *       {@code Constr0[1, count, concat(claimIds)]},
 *   <li>the total committed bounty paid to the <em>cranker</em>'s own output —
 *       {@code Σbounty} "floats free" of the vault, the cranker's reward,
 *   <li>the L1 fee funded from the cranker's own inputs — never the vault.
 * </ul>
 * All claims must share one nullifier shard (a cranker drains a single shard);
 * the shard is spent (its {@code InsertBatch} nullifies each claim), and the
 * root thread is a reference input.
 */
public final class ExitTransactionBuilder {
    private ExitTransactionBuilder() {
    }

    public static Plan build(
            List<EutxoWithdrawalClaim> claims,
            List<VaultInput> vaultInventory,
            String vaultAddress,
            String crankerAddress,
            BigInteger fee,
            BigInteger minimumContinuingLovelace,
            long rootUpdatedAtSlot,
            long fallbackDelaySlots,
            long currentSlot,
            long ttlSlots,
            int maxExitBatch,
            ExecutionInputs execution
    ) {
        Objects.requireNonNull(claims, "claims");
        Objects.requireNonNull(vaultInventory, "vaultInventory");
        Objects.requireNonNull(execution, "execution");
        if (claims.isEmpty()) {
            throw new IllegalArgumentException("an exit must carry at least one claim");
        }
        if (maxExitBatch <= 0 || claims.size() > maxExitBatch) {
            throw new IllegalArgumentException(
                    "exit batch exceeds the governed maximum of " + maxExitBatch);
        }
        String normalizedVault = required(vaultAddress, "vault address");
        String normalizedCranker = required(crankerAddress, "cranker address");
        BigInteger checkedFee = nonNegative(fee, "fee");
        BigInteger minimum = positive(
                minimumContinuingLovelace, "minimum continuing lovelace");
        if (rootUpdatedAtSlot < 0 || fallbackDelaySlots <= 0) {
            throw new IllegalArgumentException("invalid root staleness parameters");
        }
        if (currentSlot < 0 || ttlSlots <= 0
                || currentSlot > Long.MAX_VALUE - ttlSlots) {
            throw new IllegalArgumentException("invalid exit validity interval");
        }
        // Arming: the validity LOWER bound the vault reads must be strictly
        // past updatedAtSlot + fallbackDelaySlots, or the Exit path is closed.
        if (currentSlot - rootUpdatedAtSlot <= fallbackDelaySlots) {
            throw new IllegalArgumentException(
                    "exit is not armed: the accepted root is not stale beyond "
                            + "its fallback delay");
        }

        int shard = shardOfBatch(claims);

        BigInteger payoutTotal = BigInteger.ZERO;
        BigInteger bountyTotal = BigInteger.ZERO;
        long epoch = claims.getFirst().bridgeEpoch();
        for (EutxoWithdrawalClaim claim : claims) {
            if (claim.bridgeEpoch() != epoch) {
                throw new IllegalArgumentException("an exit cannot mix bridge epochs");
            }
            payoutTotal = payoutTotal.add(claim.lovelace());
            bountyTotal = bountyTotal.add(claim.bounty());
        }
        BigInteger vaultOutflow = payoutTotal.add(bountyTotal);

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
                    "vault inputs cannot fund the exit outflow and continuation");
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
                // Σbounty floats free of the vault — the cranker's reward.
                outputs.add(TransactionOutput.builder()
                        .address(normalizedCranker)
                        .value(Value.fromCoin(bountyTotal))
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
                            execution.rootReferenceInput().index())));
            // Permissionless: NO required signers.
            TransactionBody prepared = body.build();
            return new Plan(
                    CborSerializationUtil.serialize(prepared.serialize()),
                    List.copyOf(selected),
                    continuing,
                    bountyTotal,
                    marker.claimIds(),
                    shard,
                    currentSlot + ttlSlots);
        } catch (Exception failure) {
            throw new IllegalArgumentException("cannot build the exit body", failure);
        }
    }

    private static int shardOfBatch(List<EutxoWithdrawalClaim> claims) {
        int shard = shardOf(claims.getFirst());
        for (EutxoWithdrawalClaim claim : claims) {
            if (shardOf(claim) != shard) {
                throw new IllegalArgumentException(
                        "an exit batch must drain a single nullifier shard");
            }
        }
        return shard;
    }

    private static int shardOf(EutxoWithdrawalClaim claim) {
        byte[] id = HexFormat.of().parseHex(claim.claimId());
        if (id.length != 32) {
            throw new IllegalArgumentException("claim id must be 32 bytes");
        }
        return id[31] & 0x0F;
    }

    public record VaultInput(EutxoOutpoint outpoint, BigInteger lovelace) {
        public VaultInput {
            Objects.requireNonNull(outpoint, "outpoint");
            lovelace = positive(lovelace, "vault input lovelace");
        }
    }

    /** Root reference input, spent nullifier shard, fee inputs (no signers). */
    public record ExecutionInputs(
            NetworkId networkId,
            EutxoOutpoint rootReferenceInput,
            EutxoOutpoint shardInput,
            List<EutxoOutpoint> feeInputs
    ) {
        public ExecutionInputs {
            Objects.requireNonNull(networkId, "networkId");
            Objects.requireNonNull(rootReferenceInput, "rootReferenceInput");
            Objects.requireNonNull(shardInput, "shardInput");
            feeInputs = List.copyOf(Objects.requireNonNull(feeInputs, "feeInputs"));
            if (feeInputs.isEmpty()) {
                throw new IllegalArgumentException(
                        "an exit needs at least one cranker fee input");
            }
        }
    }

    public record Plan(
            byte[] unsignedBodyCbor,
            List<VaultInput> selectedVaultInputs,
            BigInteger continuingVaultLovelace,
            BigInteger bountyLovelace,
            List<String> orderedClaimIds,
            int shard,
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
