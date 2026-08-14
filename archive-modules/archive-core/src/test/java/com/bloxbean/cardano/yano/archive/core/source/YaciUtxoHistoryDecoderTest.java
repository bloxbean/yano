package com.bloxbean.cardano.yano.archive.core.source;

import com.bloxbean.cardano.yaci.core.model.*;
import com.bloxbean.cardano.yaci.core.model.certs.StakeRegistration;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredential;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredType;
import com.bloxbean.cardano.yano.archive.api.*;
import com.bloxbean.cardano.yano.archive.core.dataset.UtxoHistoryDataset;
import com.bloxbean.cardano.yano.archive.core.hot.RocksDbHotHistoryStore;
import com.bloxbean.cardano.yano.archive.core.worker.ArchiveTrack;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class YaciUtxoHistoryDecoderTest {
    @TempDir Path temp;

    @Test
    void recordsNormalizedAssetsAndPhaseTwoCollateralSemantics() {
        byte[] address = new byte[57];
        address[0] = 0;
        Arrays.fill(address, 1, 29, (byte) 1);
        Arrays.fill(address, 29, 57, (byte) 2);
        var regular = TransactionOutput.builder().address(HexUtil.encodeHexString(address)).amounts(List.of(
                Amount.builder().unit("lovelace").quantity(BigInteger.TEN).build(),
                Amount.builder().unit("aa".repeat(28) + "01").policyId("aa".repeat(28))
                        .assetName("01").assetNameBytes(new byte[]{1}).quantity(BigInteger.valueOf(7)).build())).build();
        var invalid = TransactionBody.builder().txHash("22".repeat(32))
                .outputs(List.of(regular))
                .inputs(Set.of(TransactionInput.builder().transactionId("33".repeat(32)).index(0).build()))
                .collateralInputs(Set.of(TransactionInput.builder().transactionId("44".repeat(32)).index(1).build()))
                .referenceInputs(Set.of(TransactionInput.builder().transactionId("55".repeat(32)).index(2).build()))
                .collateralReturn(regular).build();
        Block block = Block.builder().transactionBodies(List.of(invalid)).invalidTransactions(List.of(0)).build();

        var facts = new YaciUtxoHistoryDecoder(slot -> 0, slot -> 0).derive(block);

        assertThat(facts.outputs()).singleElement().satisfies(output -> {
            assertThat(output.originType()).isEqualTo("collateral_return");
            assertThat(output.outputIndex()).isEqualTo(1);
            assertThat(output.stakeCredential()).containsOnly((byte) 2);
        });
        assertThat(facts.assets()).singleElement().satisfies(asset ->
                assertThat(asset.quantity()).isEqualTo(BigInteger.valueOf(7)));
        assertThat(facts.inputs()).extracting(i -> i.inputRole() + ':' + i.consumesOutput())
                .containsExactlyInAnyOrder("input:false", "collateral:true", "reference:false");
    }

    @Test
    void resolvesPreConwayPointerStakeCredentialFromSequentialRegistrations() {
        String stakeHash = "77".repeat(28);
        String pointerAddress = "40" + "66".repeat(28) + "0a0000";
        var registration = StakeRegistration.builder().stakeCredential(StakeCredential.builder()
                .type(StakeCredType.ADDR_KEYHASH).hash(stakeHash).build()).build();
        var tx = TransactionBody.builder().txHash("11".repeat(32))
                .certificates(List.of(registration))
                .outputs(List.of(TransactionOutput.builder().address(pointerAddress).amounts(List.of()).build()))
                .build();
        Block block = Block.builder().era(Era.Babbage).transactionBodies(List.of(tx))
                .invalidTransactions(List.of()).build();
        var facts = new YaciUtxoHistoryDecoder(slot -> 0, slot -> 0).derive(block, 10);
        var context = new com.bloxbean.cardano.yano.archive.core.dataset.BlockSourceContext<>(
                1, 10, 0, Instant.EPOCH, new byte[]{1}, new byte[0], facts);
        ArchiveJob job = ArchiveJob.deterministic(new ArchiveNetworkIdentity(1, "g"),
                ArchiveDatasetId.UTXO_HISTORY, 1, new BlockRange(1, 1),
                new ArchiveRangeAnchor(10, new byte[]{1}, 10, new byte[]{1}), "v1");

        try (var state = new RocksDbHotHistoryStore(temp.resolve("pointer"))) {
            var dataset = new UtxoHistoryDataset(state, "backfill", ArchiveTrack.BACKFILL);
            dataset.beginBatch(job, List.of(context));
            List<ArchiveRow> rows = new ArrayList<>();
            dataset.derive(job, context, rows::add);
            assertThat(rows).filteredOn(row -> row.table().equals("addresses")).singleElement()
                    .satisfies(row -> {
                        assertThat(row.values().get(7)).isEqualTo("pointer_resolved");
                        assertThat((byte[]) row.values().get(9)).containsOnly((byte) 0x77);
                    });
            assertThat(rows).filteredOn(row -> row.table().equals("transaction_outputs")).singleElement()
                    .satisfies(row -> assertThat((byte[]) row.values().get(6)).containsOnly((byte) 0x77));
            dataset.abortBatch();
        }
    }

    @Test
    void conwayPointerCoordinatesRemainQueryableButAreNotStakeEffective() {
        String pointerAddress = "40" + "66".repeat(28) + "0a0000";
        var tx = TransactionBody.builder().txHash("11".repeat(32))
                .outputs(List.of(TransactionOutput.builder().address(pointerAddress).amounts(List.of()).build()))
                .build();
        Block block = Block.builder().era(Era.Conway).transactionBodies(List.of(tx))
                .invalidTransactions(List.of()).build();
        var facts = new YaciUtxoHistoryDecoder(slot -> 0, slot -> 0).derive(block, 10);
        assertThat(facts.newAddresses()).singleElement().satisfies(address -> {
            assertThat(address.stakeReferenceType()).isEqualTo("pointer");
            assertThat(address.pointerSlot()).isEqualTo(10);
        });
        var context = new com.bloxbean.cardano.yano.archive.core.dataset.BlockSourceContext<>(
                1, 10, 0, Instant.EPOCH, new byte[]{1}, new byte[0], facts);
        ArchiveJob job = ArchiveJob.deterministic(new ArchiveNetworkIdentity(1, "g"),
                ArchiveDatasetId.UTXO_HISTORY, 1, new BlockRange(1, 1),
                new ArchiveRangeAnchor(10, new byte[]{1}, 10, new byte[]{1}), "v1");
        var dataset = new UtxoHistoryDataset();
        List<ArchiveRow> rows = new ArrayList<>();
        dataset.derive(job, context, rows::add);
        assertThat(rows).filteredOn(row -> row.table().equals("addresses")).singleElement()
                .satisfies(row -> {
                    assertThat(row.values().get(7)).isEqualTo("pointer_not_effective");
                    assertThat(row.values().get(9)).isNull();
                });
    }

    @Test
    void emitsTypedGenesisOutputOnceWithoutInventingATransaction() {
        String address = "60" + "33".repeat(28);
        var decoder = new YaciUtxoHistoryDecoder(slot -> 0, slot -> 0, ignored -> Era.Shelley,
                List.of(new YaciUtxoHistoryDecoder.GenesisOutput(
                        address, BigInteger.valueOf(42), "genesis_shelley")));
        Block block = Block.builder().era(Era.Shelley).transactionBodies(List.of())
                .invalidTransactions(List.of()).build();

        var facts = decoder.derive(block, 0, true);

        assertThat(facts.outputs()).singleElement().satisfies(output -> {
            assertThat(output.originType()).isEqualTo("genesis_shelley");
            assertThat(output.txIndex()).isEqualTo(-1);
            assertThat(output.lovelace()).isEqualTo(42);
            assertThat(output.txHash()).hasSize(32);
        });
        assertThat(facts.newAddresses()).hasSize(1);
        assertThat(facts.pointerRegistrations()).isEmpty();
    }

    @Test
    void injectsGenesisAtConfiguredFirstCanonicalBlockForByronNetworks() {
        String address = "60" + "44".repeat(28);
        var decoder = new YaciUtxoHistoryDecoder(slot -> 0, slot -> 0, ignored -> Era.Shelley,
                List.of(new YaciUtxoHistoryDecoder.GenesisOutput(
                        address, BigInteger.valueOf(84), "genesis_byron")), 1);
        Block block = Block.builder().era(Era.Shelley).transactionBodies(List.of())
                .invalidTransactions(List.of()).build();

        assertThat(decoder.includesGenesis(0)).isFalse();
        assertThat(decoder.includesGenesis(1)).isTrue();
        assertThat(decoder.derive(block, 0, false).outputs()).isEmpty();
        assertThat(decoder.derive(block, 0, true).outputs()).singleElement()
                .satisfies(output -> {
                    assertThat(output.originType()).isEqualTo("genesis_byron");
                    assertThat(output.lovelace()).isEqualTo(84);
                });
    }
}
