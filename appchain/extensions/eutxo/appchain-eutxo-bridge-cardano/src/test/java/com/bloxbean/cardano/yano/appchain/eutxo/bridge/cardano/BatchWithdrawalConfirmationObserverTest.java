package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoBatchSettlementMarker;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoBatchWithdrawalConfirmation;
import com.bloxbean.cardano.yano.appchain.eutxo.testkit.EutxoTestWallet;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BatchWithdrawalConfirmationObserverTest {
    private static final byte[] VAULT_HASH = fill(28, 7);
    private static final String VAULT = AddressProvider.getEntAddress(
            Credential.fromScript(VAULT_HASH), Networks.testnet()).getAddress();
    private static final String BOUNTY = EutxoTestWallet.fromSeed(fill(32, 0x99)).address();
    private static final String CHAIN = "payments-eutxo";
    private static final List<String> CLAIM_IDS = List.of(
            "11".repeat(32), "22".repeat(32), "33".repeat(32));

    @Test
    void batchMarkerYieldsOneObservationCoveringEveryPositionalPayout() {
        String d0 = EutxoTestWallet.fromSeed(fill(32, 0x80)).address();
        String d1 = EutxoTestWallet.fromSeed(fill(32, 0x81)).address();
        String d2 = EutxoTestWallet.fromSeed(fill(32, 0x82)).address();
        EutxoBatchSettlementMarker marker =
                new EutxoBatchSettlementMarker(1, CLAIM_IDS);
        Block block = block(List.of(
                output(d0, 8_000_000L, null),
                output(d1, 5_000_000L, null),
                output(d2, 3_000_000L, null),
                output(VAULT, 18_000_000L, marker.encode()),
                output(BOUNTY, 6_000_000L, null)));

        List<L1Observation> observations =
                observer().observe(1_000L, fill(32, 9), block);

        assertThat(observations).singleElement().satisfies(observation -> {
            EutxoBatchWithdrawalConfirmation confirmation =
                    EutxoBatchWithdrawalConfirmation.decode(observation.claim());
            assertThat(confirmation.chainId()).isEqualTo(CHAIN);
            assertThat(confirmation.bridgeEpoch()).isEqualTo(3);
            assertThat(confirmation.settlementTransactionId())
                    .isEqualTo("11".repeat(32));
            assertThat(confirmation.continuingVaultOutpoint().index()).isEqualTo(3);
            assertThat(confirmation.continuingVaultLovelace())
                    .isEqualTo(BigInteger.valueOf(18_000_000L));
            assertThat(confirmation.count()).isEqualTo(3);
            assertThat(confirmation.entries()).extracting(
                    EutxoBatchWithdrawalConfirmation.Entry::claimId)
                    .containsExactlyElementsOf(CLAIM_IDS);
            assertThat(confirmation.entries()).extracting(
                    EutxoBatchWithdrawalConfirmation.Entry::destinationAddress)
                    .containsExactly(d0, d1, d2);
            assertThat(confirmation.entries().get(0).lovelace())
                    .isEqualTo(BigInteger.valueOf(8_000_000L));
            assertThat(confirmation.entries().get(2).payoutIndex()).isEqualTo(2);
        });
    }

    @Test
    void everyBatchConfirmationSharesTheSameFrameworkKey() {
        // A single observation per settlement tx keeps one framework key
        // (observerId/txHash/slot) — the whole reason a batch payload exists.
        EutxoBatchSettlementMarker marker =
                new EutxoBatchSettlementMarker(1, CLAIM_IDS.subList(0, 1));
        Block block = block(List.of(
                output(EutxoTestWallet.fromSeed(fill(32, 0x80)).address(),
                        8_000_000L, null),
                output(VAULT, 18_000_000L, marker.encode()),
                output(BOUNTY, 6_000_000L, null)));

        List<L1Observation> observations =
                observer().observe(1_000L, fill(32, 9), block);
        assertThat(observations).hasSize(1);
        assertThat(observations.get(0).key())
                .isEqualTo("bridge-withdrawals/" + "11".repeat(32) + "/1000");
    }

    @Test
    void structurallyInvalidMarkerTransactionsAreSkippedNotThrown() {
        EutxoBatchSettlementMarker marker =
                new EutxoBatchSettlementMarker(1, CLAIM_IDS.subList(0, 2));
        // Marker claims 2 payouts but the continuing vault sits at index 1,
        // not at the payout boundary (index 2). Anyone can craft this by
        // paying the vault address — it must be SKIPPED (a genuine
        // settlement is always well-formed), never thrown: a throw would
        // let one crafted output drop the whole block's observations.
        Block block = block(List.of(
                output(EutxoTestWallet.fromSeed(fill(32, 0x80)).address(),
                        8_000_000L, null),
                output(VAULT, 18_000_000L, marker.encode()),
                output(EutxoTestWallet.fromSeed(fill(32, 0x81)).address(),
                        5_000_000L, null)));

        assertThat(observer().observe(1_000L, fill(32, 9), block)).isEmpty();
    }

    @Test
    void aCraftedMarkerCannotSuppressAGenuineSettlementInTheSameBlock() {
        // Transaction 1: attacker's garbage marker (lone vault output).
        EutxoBatchSettlementMarker garbage =
                new EutxoBatchSettlementMarker(1, List.of("dd".repeat(32)));
        TransactionBody crafted = TransactionBody.builder()
                .txHash("22".repeat(32))
                .inputs(java.util.Set.of(
                        com.bloxbean.cardano.yaci.core.model.TransactionInput
                                .builder()
                                .transactionId("cc".repeat(32)).index(0).build()))
                .outputs(List.of(output(VAULT, 2_000_000L, garbage.encode())))
                .build();
        // Transaction 2: a genuine, well-formed settlement.
        String destination = EutxoTestWallet.fromSeed(fill(32, 0x80)).address();
        EutxoBatchSettlementMarker marker =
                new EutxoBatchSettlementMarker(1, CLAIM_IDS.subList(0, 1));
        TransactionBody genuine = TransactionBody.builder()
                .txHash("33".repeat(32))
                .inputs(java.util.Set.of(
                        com.bloxbean.cardano.yaci.core.model.TransactionInput
                                .builder()
                                .transactionId("aa".repeat(32)).index(1).build()))
                .outputs(List.of(
                        output(destination, 8_000_000L, null),
                        output(VAULT, 18_000_000L, marker.encode()),
                        output(BOUNTY, 6_000_000L, null)))
                .build();
        Block block = Block.builder()
                .transactionBodies(List.of(crafted, genuine))
                .build();

        List<L1Observation> observations =
                observer().observe(1_000L, fill(32, 9), block);
        assertThat(observations).singleElement().satisfies(observation -> {
            EutxoBatchWithdrawalConfirmation confirmation =
                    EutxoBatchWithdrawalConfirmation.decode(observation.claim());
            assertThat(confirmation.settlementTransactionId())
                    .isEqualTo("33".repeat(32));
            assertThat(confirmation.spentOutpoints()).containsExactly(
                    new com.bloxbean.cardano.yano.appchain.eutxo.contracts
                            .EutxoOutpoint("aa".repeat(32), 1));
        });
    }

    @Test
    void blocksWithoutABatchMarkerProduceNothing() {
        Block block = block(List.of(
                output(EutxoTestWallet.fromSeed(fill(32, 0x80)).address(),
                        8_000_000L, null),
                output(VAULT, 18_000_000L, null)));
        assertThat(observer().observe(1_000L, fill(32, 9), block)).isEmpty();
    }

    private static BatchWithdrawalConfirmationObserver observer() {
        return new BatchWithdrawalConfirmationObserver("bridge-withdrawals", Map.of(
                "chain-id", CHAIN,
                "bridge-epoch", "3",
                "vault-address", VAULT));
    }

    private static TransactionOutput output(String address, long lovelace, byte[] datum) {
        var builder = TransactionOutput.builder()
                .address(address)
                .amounts(List.of(Amount.builder()
                        .unit("lovelace")
                        .quantity(BigInteger.valueOf(lovelace))
                        .build()));
        if (datum != null) {
            builder.inlineDatum(HexFormat.of().formatHex(datum));
        }
        return builder.build();
    }

    private static Block block(List<TransactionOutput> outputs) {
        return Block.builder()
                .transactionBodies(List.of(TransactionBody.builder()
                        .txHash("11".repeat(32))
                        .inputs(java.util.Set.of(
                                com.bloxbean.cardano.yaci.core.model.TransactionInput
                                        .builder()
                                        .transactionId("aa".repeat(32))
                                        .index(1)
                                        .build(),
                                com.bloxbean.cardano.yaci.core.model.TransactionInput
                                        .builder()
                                        .transactionId("bb".repeat(32))
                                        .index(0)
                                        .build()))
                        .outputs(new ArrayList<>(outputs))
                        .build()))
                .build();
    }

    private static byte[] fill(int size, int value) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
