package com.bloxbean.cardano.yano.appchain.eutxo.client;

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
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-UTXO-009 SP-M5: the permissionless A3 exit body — Settle's shape minus
 * required signers, armed only past the fallback delay, single-shard batches,
 * bounty to the cranker.
 */
class ExitTransactionBuilderTest {
    private static final String VAULT =
            EutxoTestWallet.fromSeed(fill(32, 0x54)).address();
    private static final String CRANKER =
            EutxoTestWallet.fromSeed(fill(32, 0xC4)).address();
    private static final long ROOT_UPDATED = 1_000L;
    private static final long DELAY = 21_600L;
    /** Strictly past updatedAtSlot + fallbackDelaySlots. */
    private static final long ARMED_SLOT = ROOT_UPDATED + DELAY + 1_000L;

    @Test
    void armedExitPaysPositionallyMarksTheBatchAndTipsTheCranker() throws Exception {
        List<EutxoWithdrawalClaim> claims = sameShardClaims(3);
        int shard = shardOf(claims.getFirst());

        ExitTransactionBuilder.Plan plan = ExitTransactionBuilder.build(
                claims,
                List.of(new ExitTransactionBuilder.VaultInput(
                                outpoint(0x11), BigInteger.valueOf(20_000_000L)),
                        new ExitTransactionBuilder.VaultInput(
                                outpoint(0x12), BigInteger.valueOf(20_000_000L))),
                VAULT, CRANKER,
                BigInteger.valueOf(300_000L), BigInteger.valueOf(2_000_000L),
                ROOT_UPDATED, DELAY, ARMED_SLOT, 7_200L, 6, execution());

        assertThat(plan.shard()).isEqualTo(shard);
        TransactionBody body = TransactionBody.deserialize(
                (co.nstant.in.cbor.model.Map) CborSerializationUtil.deserialize(
                        plan.unsignedBodyCbor()));

        // Positional payouts, continuing vault, cranker bounty.
        var outputs = body.getOutputs();
        assertThat(outputs).hasSize(5);
        BigInteger payoutTotal = BigInteger.ZERO;
        BigInteger bountyTotal = BigInteger.ZERO;
        for (int i = 0; i < 3; i++) {
            assertThat(outputs.get(i).getAddress())
                    .isEqualTo(claims.get(i).destinationAddress());
            assertThat(outputs.get(i).getValue().getCoin())
                    .isEqualTo(claims.get(i).lovelace());
            payoutTotal = payoutTotal.add(claims.get(i).lovelace());
            bountyTotal = bountyTotal.add(claims.get(i).bounty());
        }
        assertThat(outputs.get(3).getAddress()).isEqualTo(VAULT);
        assertThat(outputs.get(3).getValue().getCoin())
                .isEqualTo(BigInteger.valueOf(40_000_000L)
                        .subtract(payoutTotal).subtract(bountyTotal));
        EutxoBatchSettlementMarker marker = EutxoBatchSettlementMarker.decode(
                outputs.get(3).getInlineDatum().serializeToBytes());
        assertThat(marker.claimIds()).containsExactlyElementsOf(
                claims.stream().map(EutxoWithdrawalClaim::claimId).toList());
        assertThat(outputs.get(4).getAddress()).isEqualTo(CRANKER);
        assertThat(outputs.get(4).getValue().getCoin()).isEqualTo(bountyTotal);
        assertThat(plan.bountyLovelace()).isEqualTo(bountyTotal);

        // Permissionless: no required signers; arming interval encoded.
        assertThat(body.getRequiredSigners()).isNullOrEmpty();
        assertThat(body.getValidityStartInterval()).isEqualTo(ARMED_SLOT);
        assertThat(body.getTtl()).isEqualTo(ARMED_SLOT + 7_200L);
        // 2 vault + 1 shard + 1 fee input; root as reference.
        assertThat(body.getInputs()).hasSize(4);
        assertThat(body.getReferenceInputs()).hasSize(1);
    }

    @Test
    void unarmedExitIsRefused() {
        List<EutxoWithdrawalClaim> claims = sameShardClaims(1);
        long tooEarly = ROOT_UPDATED + DELAY; // boundary: NOT strictly past
        assertThatThrownBy(() -> ExitTransactionBuilder.build(
                claims,
                List.of(new ExitTransactionBuilder.VaultInput(
                        outpoint(0x11), BigInteger.valueOf(40_000_000L))),
                VAULT, CRANKER,
                BigInteger.valueOf(300_000L), BigInteger.valueOf(2_000_000L),
                ROOT_UPDATED, DELAY, tooEarly, 7_200L, 6, execution()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not armed");
    }

    @Test
    void mixedShardBatchesAndOversizeBatchesAreRefused() {
        List<EutxoWithdrawalClaim> mixed = mixedShardClaims();
        assertThatThrownBy(() -> ExitTransactionBuilder.build(
                mixed,
                List.of(new ExitTransactionBuilder.VaultInput(
                        outpoint(0x11), BigInteger.valueOf(400_000_000L))),
                VAULT, CRANKER,
                BigInteger.valueOf(300_000L), BigInteger.valueOf(2_000_000L),
                ROOT_UPDATED, DELAY, ARMED_SLOT, 7_200L, 6, execution()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("single nullifier shard");

        List<EutxoWithdrawalClaim> seven = sameShardClaims(7);
        assertThatThrownBy(() -> ExitTransactionBuilder.build(
                seven,
                List.of(new ExitTransactionBuilder.VaultInput(
                        outpoint(0x11), BigInteger.valueOf(400_000_000L))),
                VAULT, CRANKER,
                BigInteger.valueOf(300_000L), BigInteger.valueOf(2_000_000L),
                ROOT_UPDATED, DELAY, ARMED_SLOT, 7_200L, 6, execution()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("governed maximum");
    }

    @Test
    void unfundedVaultIsRefused() {
        List<EutxoWithdrawalClaim> claims = sameShardClaims(1);
        assertThatThrownBy(() -> ExitTransactionBuilder.build(
                claims,
                List.of(new ExitTransactionBuilder.VaultInput(
                        outpoint(0x11), BigInteger.valueOf(1_000_000L))),
                VAULT, CRANKER,
                BigInteger.valueOf(300_000L), BigInteger.valueOf(2_000_000L),
                ROOT_UPDATED, DELAY, ARMED_SLOT, 7_200L, 6, execution()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot fund");
    }

    // --- fixtures ---------------------------------------------------------

    static int shardOf(EutxoWithdrawalClaim claim) {
        byte[] id = HexFormat.of().parseHex(claim.claimId());
        return id[31] & 0x0F;
    }

    /** Generate claims (varying nonce) until {@code count} share one shard. */
    static List<EutxoWithdrawalClaim> sameShardClaims(int count) {
        List<List<EutxoWithdrawalClaim>> byShard = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            byShard.add(new ArrayList<>());
        }
        for (int seed = 0; seed < 512; seed++) {
            EutxoWithdrawalClaim claim = claim(seed);
            List<EutxoWithdrawalClaim> bucket = byShard.get(shardOf(claim));
            bucket.add(claim);
            if (bucket.size() == count) {
                return List.copyOf(bucket);
            }
        }
        throw new IllegalStateException("could not gather same-shard claims");
    }

    static List<EutxoWithdrawalClaim> mixedShardClaims() {
        EutxoWithdrawalClaim first = claim(0);
        for (int seed = 1; seed < 512; seed++) {
            EutxoWithdrawalClaim other = claim(seed);
            if (shardOf(other) != shardOf(first)) {
                return List.of(first, other);
            }
        }
        throw new IllegalStateException("could not gather mixed-shard claims");
    }

    static EutxoWithdrawalClaim claim(int seed) {
        return new EutxoWithdrawalClaim(
                EutxoWithdrawalClaim.ABI_VERSION_V2,
                "payments", 7,
                outpoint(0x40 + (seed % 64)),
                EutxoTestWallet.fromSeed(fill(32, 0x80 + (seed % 32))).address(),
                BigInteger.valueOf(5_000_000L + seed),
                fill(32, seed % 251),
                seed, 42, BigInteger.valueOf(2_000_000L));
    }

    static ExitTransactionBuilder.ExecutionInputs execution() {
        return new ExitTransactionBuilder.ExecutionInputs(
                NetworkId.TESTNET, outpoint(0x63), outpoint(0x62),
                List.of(outpoint(0x70)));
    }

    static EutxoOutpoint outpoint(int value) {
        return new EutxoOutpoint("%02x".formatted(value & 0xFF).repeat(32), 0);
    }

    static byte[] fill(int size, int value) {
        byte[] bytes = new byte[size];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
