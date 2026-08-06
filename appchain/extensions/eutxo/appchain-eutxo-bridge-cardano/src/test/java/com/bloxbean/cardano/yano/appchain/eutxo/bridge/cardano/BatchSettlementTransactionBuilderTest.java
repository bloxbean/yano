package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoBatchSettlementMarker;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalClaim;
import com.bloxbean.cardano.yano.appchain.eutxo.testkit.EutxoTestWallet;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-UTXO-009 SP-M3: the batch settlement builder produces a transaction
 * shaped exactly as the SP-M2 vault Settle path requires.
 */
class BatchSettlementTransactionBuilderTest {
    private static final String VAULT =
            EutxoTestWallet.fromSeed(fill(32, 0x54)).address();
    private static final String BOUNTY =
            EutxoTestWallet.fromSeed(fill(32, 0x99)).address();

    @Test
    void positionalPayoutsMarkerBountyAndRemainderAreExact() throws Exception {
        List<EutxoWithdrawalClaim> claims = List.of(
                claim(0, 8_000_000L, 2_000_000L),
                claim(1, 5_000_000L, 2_000_000L),
                claim(2, 3_000_000L, 2_000_000L));
        List<BatchSettlementTransactionBuilder.VaultInput> inventory = List.of(
                new BatchSettlementTransactionBuilder.VaultInput(
                        outpoint(0x11), BigInteger.valueOf(20_000_000L)),
                new BatchSettlementTransactionBuilder.VaultInput(
                        outpoint(0x12), BigInteger.valueOf(20_000_000L)));

        BatchSettlementTransactionBuilder.Plan plan =
                BatchSettlementTransactionBuilder.build(
                        claims, inventory, VAULT, BOUNTY,
                        BigInteger.valueOf(300_000L),
                        BigInteger.valueOf(2_000_000L),
                        1_000L, 7_200L, execution());

        TransactionBody body = TransactionBody.deserialize(
                (co.nstant.in.cbor.model.Map) CborSerializationUtil.deserialize(
                        plan.unsignedBodyCbor()));

        // Payout total 16 ADA + bounty total 6 ADA = 22 ADA vault outflow.
        // Selected vault inputs = 40 ADA -> continuing = 18 ADA.
        assertThat(plan.continuingVaultLovelace())
                .isEqualTo(BigInteger.valueOf(18_000_000L));
        assertThat(plan.bountyLovelace()).isEqualTo(BigInteger.valueOf(6_000_000L));

        var outputs = body.getOutputs();
        // 3 positional payouts, then continuing vault, then bounty.
        assertThat(outputs).hasSize(5);
        for (int i = 0; i < 3; i++) {
            assertThat(outputs.get(i).getAddress())
                    .isEqualTo(claims.get(i).destinationAddress());
            assertThat(outputs.get(i).getValue().getCoin())
                    .isEqualTo(claims.get(i).lovelace());
        }
        assertThat(outputs.get(3).getAddress()).isEqualTo(VAULT);
        assertThat(outputs.get(3).getValue().getCoin())
                .isEqualTo(BigInteger.valueOf(18_000_000L));
        assertThat(outputs.get(4).getAddress()).isEqualTo(BOUNTY);
        assertThat(outputs.get(4).getValue().getCoin())
                .isEqualTo(BigInteger.valueOf(6_000_000L));

        // Batch marker on the continuing output = ordered claim ids.
        EutxoBatchSettlementMarker marker = EutxoBatchSettlementMarker.decode(
                outputs.get(3).getInlineDatum().serializeToBytes());
        assertThat(marker.claimIds())
                .containsExactly(claims.get(0).claimId(),
                        claims.get(1).claimId(), claims.get(2).claimId());

        // Fee comes from an executor input, never the vault; root is a ref
        // input; the shard is spent.
        assertThat(body.getFee()).isEqualTo(BigInteger.valueOf(300_000L));
        assertThat(body.getReferenceInputs()).hasSize(1);
        // 2 vault inputs + 1 shard + 1 fee input.
        assertThat(body.getInputs()).hasSize(4);
    }

    @Test
    void shardContinuationEmitsTheThreadTokenOutputWithThePostInsertDatum()
            throws Exception {
        // Gather claims that share one shard (claim ids are hash-derived).
        List<EutxoWithdrawalClaim> sameShard = new java.util.ArrayList<>();
        int shard = -1;
        for (int seed = 0; seed < 512 && sameShard.size() < 2; seed++) {
            EutxoWithdrawalClaim candidate = claim(seed % 64,
                    5_000_000L + seed, 2_000_000L);
            int nibble = java.util.HexFormat.of()
                    .parseHex(candidate.claimId())[31] & 0x0F;
            if (shard < 0) {
                shard = nibble;
                sameShard.add(candidate);
            } else if (nibble == shard) {
                sameShard.add(candidate);
            }
        }
        assertThat(sameShard).hasSize(2);
        final int shardIndex = shard;

        byte[] nextRoot = fill(32, 0x5A);
        com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoShardDatum datum =
                new com.bloxbean.cardano.yano.appchain.eutxo.contracts
                        .EutxoShardDatum(1, "payments", 7, shard, nextRoot);
        com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoShardContinuation
                continuation = new com.bloxbean.cardano.yano.appchain.eutxo
                .contracts.EutxoShardContinuation(
                VAULT /* stand-in shard address */,
                java.util.HexFormat.of().formatHex(fill(28, 0x71)),
                BigInteger.valueOf(2_000_000L),
                datum);

        BatchSettlementTransactionBuilder.Plan plan =
                BatchSettlementTransactionBuilder.build(
                        sameShard,
                        List.of(new BatchSettlementTransactionBuilder.VaultInput(
                                outpoint(0x11), BigInteger.valueOf(40_000_000L))),
                        VAULT, BOUNTY, BigInteger.valueOf(300_000L),
                        BigInteger.valueOf(2_000_000L), 1_000L, 7_200L,
                        execution(), continuation);

        TransactionBody body = TransactionBody.deserialize(
                (co.nstant.in.cbor.model.Map) CborSerializationUtil.deserialize(
                        plan.unsignedBodyCbor()));
        // 2 payouts + continuing vault + bounty + continuing shard.
        assertThat(body.getOutputs()).hasSize(5);
        var shardOut = body.getOutputs().get(4);
        assertThat(shardOut.getValue().getCoin())
                .isEqualTo(BigInteger.valueOf(2_000_000L));
        assertThat(shardOut.getValue().getMultiAssets()).hasSize(1);
        var multiAsset = shardOut.getValue().getMultiAssets().getFirst();
        assertThat(multiAsset.getPolicyId())
                .isEqualTo(java.util.HexFormat.of().formatHex(fill(28, 0x71)));
        assertThat(multiAsset.getAssets()).singleElement().satisfies(asset -> {
            assertThat(asset.getNameAsBytes())
                    .containsExactly((byte) shardIndex);
            assertThat(asset.getValue()).isEqualTo(BigInteger.ONE);
        });
        assertThat(com.bloxbean.cardano.yano.appchain.eutxo.contracts
                .EutxoShardDatum.decode(
                        shardOut.getInlineDatum().serializeToBytes()))
                .isEqualTo(datum);

        // A claim outside the continued shard is refused.
        com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoShardContinuation
                wrongShard = new com.bloxbean.cardano.yano.appchain.eutxo
                .contracts.EutxoShardContinuation(
                VAULT, java.util.HexFormat.of().formatHex(fill(28, 0x71)),
                BigInteger.valueOf(2_000_000L),
                new com.bloxbean.cardano.yano.appchain.eutxo.contracts
                        .EutxoShardDatum(1, "payments", 7,
                        (shard + 1) % 16, nextRoot));
        assertThatThrownBy(() -> BatchSettlementTransactionBuilder.build(
                sameShard,
                List.of(new BatchSettlementTransactionBuilder.VaultInput(
                        outpoint(0x11), BigInteger.valueOf(40_000_000L))),
                VAULT, BOUNTY, BigInteger.valueOf(300_000L),
                BigInteger.valueOf(2_000_000L), 1_000L, 7_200L,
                execution(), wrongShard))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to the continued shard");
    }

    @Test
    void rejectsUnfundedVaultAndMixedEpochs() {
        List<EutxoWithdrawalClaim> claims = List.of(
                claim(0, 8_000_000L, 2_000_000L));
        List<BatchSettlementTransactionBuilder.VaultInput> thin = List.of(
                new BatchSettlementTransactionBuilder.VaultInput(
                        outpoint(0x11), BigInteger.valueOf(1_000_000L)));
        assertThatThrownBy(() -> BatchSettlementTransactionBuilder.build(
                claims, thin, VAULT, BOUNTY, BigInteger.valueOf(300_000L),
                BigInteger.valueOf(2_000_000L), 1_000L, 7_200L, execution()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot fund");

        List<EutxoWithdrawalClaim> mixed = new ArrayList<>();
        mixed.add(claim(0, 8_000_000L, 2_000_000L));
        mixed.add(new EutxoWithdrawalClaim(
                EutxoWithdrawalClaim.ABI_VERSION_V2, "payments", 8,
                outpoint(1), EutxoTestWallet.fromSeed(fill(32, 1)).address(),
                BigInteger.valueOf(5_000_000L), fill(32, 1), 0,
                42, BigInteger.valueOf(2_000_000L)));
        assertThatThrownBy(() -> BatchSettlementTransactionBuilder.build(
                mixed,
                List.of(new BatchSettlementTransactionBuilder.VaultInput(
                        outpoint(0x11), BigInteger.valueOf(40_000_000L))),
                VAULT, BOUNTY, BigInteger.valueOf(300_000L),
                BigInteger.valueOf(2_000_000L), 1_000L, 7_200L, execution()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mix bridge epochs");
    }

    private static BatchSettlementTransactionBuilder.ExecutionInputs execution() {
        return new BatchSettlementTransactionBuilder.ExecutionInputs(
                NetworkId.TESTNET,
                outpoint(0x63),
                outpoint(0x62),
                List.of(outpoint(0x70)),
                List.of(fill(28, 0xaa)));
    }

    private static EutxoWithdrawalClaim claim(int index, long payout, long bounty) {
        return new EutxoWithdrawalClaim(
                EutxoWithdrawalClaim.ABI_VERSION_V2,
                "payments", 7, outpoint(0x40 + index),
                EutxoTestWallet.fromSeed(fill(32, 0x80 + index)).address(),
                BigInteger.valueOf(payout), fill(32, 0x30 + index),
                index, 42, BigInteger.valueOf(bounty));
    }

    private static EutxoOutpoint outpoint(int value) {
        return new EutxoOutpoint("%02x".formatted(value & 0xFF).repeat(32), 0);
    }

    private static byte[] fill(int size, int value) {
        byte[] bytes = new byte[size];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
