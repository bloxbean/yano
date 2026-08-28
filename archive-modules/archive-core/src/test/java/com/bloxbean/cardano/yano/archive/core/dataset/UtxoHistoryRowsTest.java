package com.bloxbean.cardano.yano.archive.core.dataset;

import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.archive.PointerCredentialSource;
import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveJob;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveRangeAnchor;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.api.BlockRange;
import com.bloxbean.cardano.yano.archive.core.projection.ProjectionPointerResolution;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class UtxoHistoryRowsTest {

    private static final ArchiveNetworkIdentity NETWORK =
            new ArchiveNetworkIdentity(1, "fixture-genesis");
    private static final String CREDENTIAL = "77".repeat(28);

    @Test
    void normalizesOutputAssetsAndKeepsStakeCredentialQueryable() {
        byte[] hash = {1};
        byte[] address = {2};
        byte[] stake = new byte[28];
        Arrays.fill(stake, (byte) 3);
        ArchiveJob job = job(1, 10, hash);
        var facts = new UtxoHistoryFact(Era.Conway.getValue(), List.of(), List.of(),
                List.of(new UtxoHistoryFact.Address(address, new byte[]{4}, "addr", 0, "base", "key",
                        new byte[28], "credential", "key", stake, null, null, null)),
                List.of(new UtxoHistoryFact.Output(hash, 0, 0, "regular", address, new byte[28], stake,
                        10, "none", null, null, null, null, null, false)),
                List.of(new UtxoHistoryFact.Asset(hash, 0, new byte[28], new byte[]{6},
                        new BigInteger("18446744073709551615"))), List.of(), List.of(), List.of());
        List<ArchiveRow> rows = new ArrayList<>();

        UtxoHistoryRows.emit(job, context(facts, 1, 10, hash), rows::add);

        assertThat(rows).extracting(ArchiveRow::table)
                .containsExactly("transaction_outputs", "transaction_output_assets");
        assertThat(rows.getFirst().values().get(4)).isEqualTo("addr");
        assertThat(rows.getFirst().values().get(11)).isEqualTo(stake);
        assertThat(rows.get(1).values().get(4)).isEqualTo(new BigInteger("18446744073709551615"));
    }

    @Test
    void preservesCollateralDatumReferenceScriptAndRedeemerRows() {
        byte[] txHash = {11};
        byte[] addressKey = {12};
        byte[] datumHash = {13};
        byte[] datumCbor = {14};
        byte[] scriptHash = {15};
        byte[] scriptCbor = {16};
        var address = new UtxoHistoryFact.Address(addressKey, new byte[]{17}, "addr_test_fixture",
                0, "enterprise", "key", new byte[]{18}, "none", null, null,
                null, null, null);
        var output = new UtxoHistoryFact.Output(txHash, 1, 2, "collateral_return", addressKey,
                new byte[]{18}, null, 42, "inline", datumHash, datumCbor, scriptHash,
                "plutus_v2", scriptCbor, true);
        var input = new UtxoHistoryFact.Input(txHash, 2, 3, "collateral",
                new byte[]{19}, 4, true);
        var datum = new UtxoHistoryFact.TransactionDatum(txHash, 2, datumHash, datumCbor);
        var redeemer = new UtxoHistoryFact.TransactionRedeemer(txHash, 2, "spend", 5,
                new byte[]{20}, new byte[]{21}, BigInteger.valueOf(22), BigInteger.valueOf(23));
        var fact = new UtxoHistoryFact(Era.Babbage.getValue(), List.of(), List.of(),
                List.of(address), List.of(output), List.of(), List.of(input),
                List.of(datum), List.of(redeemer));
        List<ArchiveRow> rows = new ArrayList<>();

        UtxoHistoryRows.emit(job(8, 80, new byte[]{8}),
                context(fact, 8, 80, new byte[]{8}), rows::add);

        assertThat(rows).extracting(ArchiveRow::table).containsExactly(
                "transaction_outputs", "transaction_inputs",
                "transaction_datums", "transaction_redeemers");
        assertThat(rows.getFirst().values().get(3)).isEqualTo("collateral_return");
        assertThat(rows.getFirst().values().get(13)).isEqualTo("inline");
        assertThat(rows.getFirst().values().get(15)).isEqualTo(datumCbor);
        assertThat(rows.getFirst().values().get(17)).isEqualTo("plutus_v2");
        assertThat(rows.getFirst().values().get(18)).isEqualTo(scriptCbor);
        assertThat(rows.getFirst().values().get(19)).isEqualTo(true);
        assertThat(rows.get(1).values().get(3)).isEqualTo("collateral");
        assertThat(rows.get(1).values().get(6)).isEqualTo(true);
        assertThat(rows.get(2).values().get(3)).isEqualTo(datumCbor);
        assertThat(rows.get(3).values().get(2)).isEqualTo("spend");
        assertThat(rows.get(3).values().get(6)).isEqualTo(BigInteger.valueOf(22));
        assertThat(rows.get(3).values().get(7)).isEqualTo(BigInteger.valueOf(23));
    }

    /** Inline expected row for a resolved pre-Conway pointer. */
    @Test
    void resolvedPointerMatchesSequentialResolverGoldenOutput() {
        var source = new PointerCredentialSource() {
            @Override
            public Optional<PointerCredential> registrationAt(PointerCoordinate coordinate) {
                return coordinate.equals(new PointerCoordinate(50, 0, 0))
                        ? Optional.of(new PointerCredential(0, CREDENTIAL))
                        : Optional.empty();
            }

            @Override
            public boolean deregisteredWithin(PointerCredential credential,
                                              PointerCoordinate after,
                                              PointerCoordinate through) {
                return false;
            }

            @Override
            public IndexCompleteness completeness() {
                return IndexCompleteness.COMPLETE;
            }
        };
        UtxoHistoryFact resolved = ProjectionPointerResolution.resolve(
                pointerFact(Era.Babbage.getValue()), 60, source);

        assertThat(render(resolved, 2, 60)).containsExactly(
                "11,0,0,output,addr_test_pointer,0,ptr,key,09,"
                        + "stake_test1upmhwamhwamhwamhwamhwamhwamhwamhwamhwamhwamhwacumjjy2,"
                        + "key," + CREDENTIAL
                        + ",1000000,none,null,null,null,null,null,false,02,2,60,0,0,"
                        + "34333c87-aece-3f40-ad3d-eb21d6d8208d");
    }

    /** Inline expected row for an unresolved pre-Conway pointer. */
    @Test
    void unresolvedPointerMatchesSequentialResolverGoldenOutput() {
        UtxoHistoryFact resolved = ProjectionPointerResolution.resolve(
                pointerFact(Era.Babbage.getValue()), 100, PointerCredentialSource.NONE);

        assertThat(render(resolved, 3, 100)).containsExactly(
                "11,0,0,output,addr_test_pointer,0,ptr,key,09,null,null,null,"
                        + "1000000,none,null,null,null,null,null,false,03,3,100,0,0,"
                        + "34333c87-aece-3f40-ad3d-eb21d6d8208d");
    }

    /** Inline expected row for a Conway pointer, where pointer stake is not effective. */
    @Test
    void conwayPointerMatchesSequentialResolverGoldenOutput() {
        UtxoHistoryFact resolved = ProjectionPointerResolution.resolve(
                pointerFact(Era.Conway.getValue()), 100, PointerCredentialSource.NONE);

        assertThat(render(resolved, 1, 100)).containsExactly(
                "11,0,0,output,addr_test_pointer,0,ptr,key,09,null,null,null,"
                        + "1000000,none,null,null,null,null,null,false,01,1,100,0,0,"
                        + "34333c87-aece-3f40-ad3d-eb21d6d8208d");
    }

    private static UtxoHistoryFact pointerFact(int era) {
        byte[] addressKey = {1, 2, 3};
        var address = new UtxoHistoryFact.Address(addressKey, new byte[]{4}, "addr_test_pointer",
                0, "ptr", "key", new byte[]{9}, "pointer", null, null, 50L, 0, 0);
        var output = new UtxoHistoryFact.Output(new byte[]{0x11}, 0, 0, "output", addressKey,
                new byte[]{9}, null, 1_000_000L, "none", null, null, null, null, null, false);
        return new UtxoHistoryFact(era, List.of(), List.of(), List.of(address), List.of(output),
                List.of(), List.of(), List.of(), List.of());
    }

    private static List<String> render(UtxoHistoryFact fact, long block, long slot) {
        List<ArchiveRow> rows = new ArrayList<>();
        UtxoHistoryRows.emit(job(block, slot, new byte[]{(byte) block}),
                context(fact, block, slot, new byte[]{(byte) block}), rows::add);
        return rows.stream().filter(row -> row.table().equals("transaction_outputs"))
                .map(row -> row.values().stream()
                        .map(value -> value instanceof byte[] bytes
                                ? HexUtil.encodeHexString(bytes) : String.valueOf(value))
                        .collect(Collectors.joining(",")))
                .toList();
    }

    private static ArchiveJob job(long block, long slot, byte[] hash) {
        return ArchiveJob.deterministic(NETWORK, ArchiveDatasetId.UTXO_HISTORY, 5,
                new BlockRange(1, 1), new ArchiveRangeAnchor(100, new byte[]{1},
                        100, new byte[]{1}), "v1");
    }

    private static BlockSourceContext<UtxoHistoryFact> context(
            UtxoHistoryFact fact, long block, long slot, byte[] hash) {
        return new BlockSourceContext<>(block, slot, 0, Instant.EPOCH, hash,
                new byte[]{(byte) (block - 1)}, fact);
    }
}
