package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionInput;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.archive.ConsumedOutputAddresses;
import com.bloxbean.cardano.yano.api.archive.PointerCredentialSource;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.core.address.AddressKeyCodec;
import com.bloxbean.cardano.yano.archive.core.dataset.AddressSubjectRows;
import com.bloxbean.cardano.yano.archive.core.dataset.AddressTransactionSubjects;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Address-transaction projection: capture, encode, decode, derive.
 *
 * <p>Row-shape parity with the live path is guaranteed structurally rather than by comparison —
 * both call {@link AddressSubjectRows}, and both decompose addresses through the one shared
 * parser. What still needs testing is everything around that: which addresses participate in
 * which role, that the encoding round-trips, and that an unresolvable input fails loudly rather
 * than silently dropping address history.
 */
class AddressTransactionProjectionParityTest {

    private static final ArchiveNetworkIdentity NETWORK = new ArchiveNetworkIdentity(1, "fixture");
    private static final AddressKeyCodec KEYS = new AddressKeyCodec();

    private static String hex(int b, int len) {
        return String.format("%02x", b).repeat(len);
    }

    /** A Shelley address with distinct payment and stake parts, so all three subjects appear. */
    private static String address(int paymentFill, int stakeFill) {
        byte[] raw = new byte[57];
        raw[0] = 0;
        Arrays.fill(raw, 1, 29, (byte) paymentFill);
        Arrays.fill(raw, 29, 57, (byte) stakeFill);
        return HexUtil.encodeHexString(raw);
    }

    private static TransactionOutput out(String address, long lovelace) {
        return TransactionOutput.builder().address(address)
                .amounts(List.of(Amount.builder().unit("lovelace")
                        .quantity(BigInteger.valueOf(lovelace)).build()))
                .build();
    }

    private static TransactionInput in(int fill, int index) {
        return TransactionInput.builder().transactionId(hex(fill, 32)).index(index).build();
    }

    private static Block block(Era era, List<TransactionBody> txs, List<Integer> invalid) {
        return Block.builder().era(era)
                .header(BlockHeader.builder().headerBody(HeaderBody.builder()
                        .blockNumber(100).slot(2000).prevHash(hex(0x0a, 32))
                        .blockHash(hex(0x0b, 32)).build()).build())
                .transactionBodies(txs).transactionWitness(List.of()).invalidTransactions(invalid)
                .build();
    }

    private static ConsumedOutputAddresses consumed(Map<String, String> map) {
        return (txHash, index) -> map.get(txHash + '#' + index);
    }

    /** Rows the projection path produces, end to end through the encoding. */
    private List<ArchiveRow> projectedRows(Block block, ConsumedOutputAddresses consumed) {
        var fact = ProjectionAddressParticipation.resolve(block, 2000, consumed, KEYS,
                PointerCredentialSource.NONE);
        var decoded = ProjectionFactCodec.decodeAddressParticipations(
                ProjectionFactCodec.encodeAddressParticipations(fact));

        List<ArchiveRow> rows = new ArrayList<>();
        UUID jobId = UUID.nameUUIDFromBytes("fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (var tx : decoded.transactions()) {
            var accumulator = new AddressSubjectRows(AddressTransactionSubjects.all(),
                    NETWORK.networkMagic());
            for (var participation : tx.participations()) {
                accumulator.add(participation.participant(),
                        AddressSubjectRows.Role.valueOf(participation.role()));
            }
            accumulator.emit(tx.txHash(), tx.txIndex(), HexUtil.decodeHexString(hex(0x0b, 32)),
                    100, 2000, 20, 1_600_002_000L, jobId, rows::add);
        }
        return rows;
    }

    private static String render(List<ArchiveRow> rows) {
        return rows.stream().map(row -> row.table() + "(" + row.values().stream()
                .map(v -> v instanceof byte[] b ? HexUtil.encodeHexString(b) : String.valueOf(v))
                .collect(Collectors.joining(",")) + ")").collect(Collectors.joining("\n"));
    }

    // ------------------------------------------------------------------ cases

    @Test
    void aValidTransactionContributesItsInputsAndOutputs() {
        var tx = TransactionBody.builder().txHash(hex(0x20, 32))
                .inputs(new java.util.LinkedHashSet<>(List.of(in(0x30, 0))))
                .outputs(List.of(out(address(0x11, 0x12), 1_000_000L)))
                .fee(BigInteger.valueOf(170_000L)).build();

        var rows = projectedRows(block(Era.Babbage, List.of(tx), List.of()),
                consumed(Map.of(hex(0x30, 32) + "#0", address(0x21, 0x22))));

        // Two distinct addresses x three subject types.
        assertThat(rows).hasSize(6);
        assertThat(render(rows)).contains("address_transactions(");
        // The spent address is recorded as an input, the created one as an output.
        long inputRows = rows.stream().filter(r -> (Integer) r.values().get(11) == 1).count();
        long outputRows = rows.stream().filter(r -> (Integer) r.values().get(12) == 1).count();
        assertThat(inputRows).isEqualTo(3);
        assertThat(outputRows).isEqualTo(3);
    }

    @Test
    void oneAddressOnBothSidesIsASingleSubjectWithBothRoles() {
        // Change back to the same address: the archive must not emit it twice.
        String same = address(0x11, 0x12);
        var tx = TransactionBody.builder().txHash(hex(0x21, 32))
                .inputs(new java.util.LinkedHashSet<>(List.of(in(0x31, 0))))
                .outputs(List.of(out(same, 900_000L)))
                .fee(BigInteger.valueOf(170_000L)).build();

        var rows = projectedRows(block(Era.Babbage, List.of(tx), List.of()),
                consumed(Map.of(hex(0x31, 32) + "#0", same)));

        assertThat(rows).hasSize(3);
        for (ArchiveRow row : rows) {
            assertThat((Integer) row.values().get(11)).as("input count").isEqualTo(1);
            assertThat((Integer) row.values().get(12)).as("output count").isEqualTo(1);
        }
    }

    @Test
    void anInvalidTransactionContributesCollateralRatherThanInputsAndOutputs() {
        var tx = TransactionBody.builder().txHash(hex(0x22, 32))
                .inputs(new java.util.LinkedHashSet<>(List.of(in(0x32, 0))))
                .collateralInputs(new java.util.LinkedHashSet<>(List.of(in(0x33, 1))))
                .outputs(List.of(out(address(0x41, 0x42), 1_000_000L)))
                .collateralReturn(out(address(0x51, 0x52), 500_000L))
                .fee(BigInteger.valueOf(170_000L)).build();

        var rows = projectedRows(block(Era.Babbage, List.of(tx), List.of(0)),
                consumed(Map.of(hex(0x33, 32) + "#1", address(0x61, 0x62))));

        // Collateral input address plus collateral return address; the ordinary input and the
        // ordinary outputs take no part in a failed transaction.
        assertThat(rows).hasSize(6);
        long collateralInputs = rows.stream().filter(r -> (Integer) r.values().get(13) == 1).count();
        long collateralReturns = rows.stream().filter(r -> (Integer) r.values().get(14) == 1).count();
        assertThat(collateralInputs).isEqualTo(3);
        assertThat(collateralReturns).isEqualTo(3);
        assertThat(rows.stream().filter(r -> (Integer) r.values().get(11) == 1)).isEmpty();
        assertThat(rows.stream().filter(r -> (Integer) r.values().get(12) == 1)).isEmpty();
    }

    @Test
    void anUnresolvableInputFailsLoudlyRatherThanDroppingAddressHistory() {
        // A skipped input would silently lose a real spend from address history, and no later
        // pass could detect it. Failing closed is the only safe behaviour.
        var tx = TransactionBody.builder().txHash(hex(0x23, 32))
                .inputs(new java.util.LinkedHashSet<>(List.of(in(0x34, 0))))
                .outputs(List.of(out(address(0x11, 0x12), 1_000_000L)))
                .fee(BigInteger.valueOf(170_000L)).build();

        assertThatThrownBy(() -> projectedRows(block(Era.Babbage, List.of(tx), List.of()),
                ConsumedOutputAddresses.NONE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not resolve")
                .hasMessageContaining(hex(0x34, 32));
    }

    @Test
    void theEncodingRoundTripsEveryParticipationField() {
        var tx = TransactionBody.builder().txHash(hex(0x24, 32))
                .inputs(new java.util.LinkedHashSet<>(List.of(in(0x35, 7))))
                .outputs(List.of(out(address(0x71, 0x72), 1_000_000L),
                        out(address(0x73, 0x74), 2_000_000L)))
                .fee(BigInteger.valueOf(170_000L)).build();
        var original = ProjectionAddressParticipation.resolve(
                block(Era.Babbage, List.of(tx), List.of()), 2000,
                consumed(Map.of(hex(0x35, 32) + "#7", address(0x75, 0x76))), KEYS,
                PointerCredentialSource.NONE);

        var encoded = ProjectionFactCodec.encodeAddressParticipations(original);
        var decoded = ProjectionFactCodec.decodeAddressParticipations(encoded);

        assertThat(decoded.transactions()).hasSize(1);
        var before = original.transactions().get(0);
        var after = decoded.transactions().get(0);
        assertThat(after.txIndex()).isEqualTo(before.txIndex());
        assertThat(after.txHash()).isEqualTo(before.txHash());
        assertThat(after.participations()).hasSameSizeAs(before.participations());
        for (int i = 0; i < before.participations().size(); i++) {
            var b = before.participations().get(i);
            var a = after.participations().get(i);
            assertThat(a.role()).isEqualTo(b.role());
            assertThat(a.participant().addressKey()).isEqualTo(b.participant().addressKey());
            assertThat(a.participant().address()).isEqualTo(b.participant().address());
            assertThat(a.participant().paymentCredential()).isEqualTo(b.participant().paymentCredential());
            assertThat(a.participant().stakeCredentialType()).isEqualTo(b.participant().stakeCredentialType());
            assertThat(a.participant().stakeCredential()).isEqualTo(b.participant().stakeCredential());
        }
    }

    @Test
    void streamingYieldsTheSameTransactionsAsDecodingWhole() {
        var txs = new ArrayList<TransactionBody>();
        var map = new LinkedHashMap<String, String>();
        for (int i = 0; i < 40; i++) {
            txs.add(TransactionBody.builder().txHash(hex(0x80 + (i % 60), 32))
                    .inputs(new java.util.LinkedHashSet<>(List.of(in(0x90, i))))
                    .outputs(List.of(out(address(0xa0, 0xa1), 1_000_000L + i)))
                    .fee(BigInteger.valueOf(170_000L)).build());
            map.put(hex(0x90, 32) + '#' + i, address(0xb0, 0xb1));
        }
        var fact = ProjectionAddressParticipation.resolve(block(Era.Babbage, txs, List.of()), 2000,
                consumed(map), KEYS, PointerCredentialSource.NONE);
        byte[] encoded = ProjectionFactCodec.encodeAddressParticipations(fact);

        // Split small, so transactions straddle chunk boundaries.
        var chunks = ProjectionChunking.split(encoded, 64);
        assertThat(chunks.size()).isGreaterThan(1);
        List<Integer> streamed = new ArrayList<>();
        long participations = ProjectionFactCodec.streamAddressParticipations(chunks,
                tx -> streamed.add(tx.txIndex()));

        assertThat(streamed).hasSize(40);
        assertThat(streamed).isEqualTo(fact.transactions().stream()
                .map(t -> t.txIndex()).collect(Collectors.toList()));
        assertThat(participations).isEqualTo(fact.transactions().stream()
                .mapToLong(t -> t.participations().size()).sum());
    }

    // ------------------------------- side by side against the live dataset

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path temp;

    /**
     * Both paths, same two blocks, same rows.
     *
     * <p>Block 1 creates outputs; block 2 spends them. The live dataset resolves those inputs
     * through its own resolver, populated by block 1. The projection resolves them from the
     * addresses the UTXO subsystem would have captured while deleting the same outputs. Row
     * shape is already identical by construction — both call {@link AddressSubjectRows} — so
     * what this actually tests is that the two <em>resolutions</em> agree, which sharing code
     * cannot guarantee.
     */
    @Test
    void theProjectionAndTheLiveDatasetAgreeOnTheSameSpend() throws Exception {
        String fundedA = address(0xc1, 0xc2);
        String fundedB = address(0xc3, 0xc4);
        String createdA = address(0xd1, 0xd2);

        var funding = TransactionBody.builder().txHash(hex(0xe0, 32))
                .inputs(new java.util.LinkedHashSet<>())
                .outputs(List.of(out(fundedA, 5_000_000L), out(fundedB, 6_000_000L)))
                .fee(BigInteger.valueOf(170_000L)).build();
        var spending = TransactionBody.builder().txHash(hex(0xe1, 32))
                .inputs(new java.util.LinkedHashSet<>(List.of(
                        TransactionInput.builder().transactionId(hex(0xe0, 32)).index(0).build(),
                        TransactionInput.builder().transactionId(hex(0xe0, 32)).index(1).build())))
                .outputs(List.of(out(createdA, 10_000_000L), out(fundedA, 500_000L)))
                .fee(BigInteger.valueOf(170_000L)).build();

        var blockOne = Block.builder().era(Era.Babbage)
                .header(BlockHeader.builder().headerBody(HeaderBody.builder()
                        .blockNumber(99).slot(1980).prevHash(hex(0x09, 32))
                        .blockHash(hex(0x0a, 32)).build()).build())
                .transactionBodies(List.of(funding)).transactionWitness(List.of())
                .invalidTransactions(List.of()).build();
        var blockTwo = block(Era.Babbage, List.of(spending), List.of());

        // --- the live path -------------------------------------------------
        List<ArchiveRow> live = new ArrayList<>();
        UUID jobId;
        try (var state = new com.bloxbean.cardano.yano.archive.core.hot.RocksDbHotHistoryStore(
                temp.resolve("side-by-side"))) {
            var dataset = new com.bloxbean.cardano.yano.archive.core.dataset.AddressTransactionDataset(
                    state, KEYS);
            dataset.seedGenesis(List.of());

            var contextOne = new com.bloxbean.cardano.yano.archive.core.dataset.BlockSourceContext<>(
                    99L, 1980L, 19L, java.time.Instant.ofEpochSecond(1_600_001_980L),
                    HexUtil.decodeHexString(hex(0x0a, 32)), HexUtil.decodeHexString(hex(0x09, 32)),
                    blockOne);
            var contextTwo = new com.bloxbean.cardano.yano.archive.core.dataset.BlockSourceContext<>(
                    100L, 2000L, 20L, java.time.Instant.ofEpochSecond(1_600_002_000L),
                    HexUtil.decodeHexString(hex(0x0b, 32)), HexUtil.decodeHexString(hex(0x0a, 32)),
                    blockTwo);

            var job = com.bloxbean.cardano.yano.archive.api.ArchiveJob.deterministic(NETWORK,
                    com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.ADDRESS_TRANSACTION, 3,
                    new com.bloxbean.cardano.yano.archive.api.BlockRange(99, 100),
                    new com.bloxbean.cardano.yano.archive.api.ArchiveRangeAnchor(1980,
                            HexUtil.decodeHexString(hex(0x0a, 32)), 2000,
                            HexUtil.decodeHexString(hex(0x0b, 32))), "v3");
            jobId = job.jobId();
            dataset.beginBatch(job, List.of(contextOne, contextTwo));
            dataset.derive(job, contextOne, row -> { });      // block 1 only populates the resolver
            dataset.derive(job, contextTwo, live::add);
            dataset.abortBatch();
        }

        // --- the projection path -------------------------------------------
        var consumedFromBlockOne = new LinkedHashMap<String, String>();
        consumedFromBlockOne.put(hex(0xe0, 32) + "#0", fundedA);
        consumedFromBlockOne.put(hex(0xe0, 32) + "#1", fundedB);

        var fact = ProjectionAddressParticipation.resolve(blockTwo, 2000,
                consumed(consumedFromBlockOne), KEYS, PointerCredentialSource.NONE);
        var decoded = ProjectionFactCodec.decodeAddressParticipations(
                ProjectionFactCodec.encodeAddressParticipations(fact));
        List<ArchiveRow> projected = new ArrayList<>();
        for (var tx : decoded.transactions()) {
            var accumulator = new AddressSubjectRows(AddressTransactionSubjects.all(),
                    NETWORK.networkMagic());
            for (var participation : tx.participations()) {
                accumulator.add(participation.participant(),
                        AddressSubjectRows.Role.valueOf(participation.role()));
            }
            accumulator.emit(tx.txHash(), tx.txIndex(), HexUtil.decodeHexString(hex(0x0b, 32)),
                    100, 2000, 20, 1_600_002_000L, jobId, projected::add);
        }

        assertThat(projected).isNotEmpty();
        assertThat(render(projected))
                .as("the projection must reproduce the live path's rows exactly")
                .isEqualTo(render(live));
    }

    @Test
    void theSectionsEncodedCostIsMeasuredRatherThanAssumed() {
        // The address-transaction section carries output addresses that utxo-history already
        // carries, so enabling all four datasets costs more than the sum of their row counts
        // suggests. Measure it rather than assume it is small.
        var txs = new ArrayList<TransactionBody>();
        var map = new LinkedHashMap<String, String>();
        for (int i = 0; i < 30; i++) {
            var inputs = new java.util.LinkedHashSet<TransactionInput>();
            for (int k = 0; k < 2; k++) {
                inputs.add(in(0xf0, i * 2 + k));
                map.put(hex(0xf0, 32) + '#' + (i * 2 + k), address(0x90 + (i % 40), 0x91));
            }
            var outputs = new ArrayList<TransactionOutput>();
            for (int k = 0; k < 3; k++) outputs.add(out(address(0xa0 + (i % 40), 0xa1 + k), 1_000_000L + k));
            txs.add(TransactionBody.builder().txHash(hex(0x10 + (i % 200), 32))
                    .inputs(inputs).outputs(outputs).fee(BigInteger.valueOf(170_000L)).build());
        }
        var blk = block(Era.Babbage, txs, List.of());

        var fact = ProjectionAddressParticipation.resolve(blk, 2000, consumed(map), KEYS,
                PointerCredentialSource.NONE);
        byte[] encoded = ProjectionFactCodec.encodeAddressParticipations(fact);
        long participations = fact.transactions().stream()
                .mapToLong(t -> t.participations().size()).sum();

        System.out.printf("ADR-039 address-transaction section: %d txs, %d participations,"
                        + " %d bytes (%d B/participation)%n",
                fact.transactions().size(), participations, encoded.length,
                encoded.length / Math.max(1, participations));

        // 30 transactions x (2 inputs + 3 outputs).
        assertThat(participations).isEqualTo(150);
        // A guard rather than a target: the encoding carries an address key, a display address
        // and two credentials per participation, so a few hundred bytes each is expected. A
        // large jump means a field was added without anyone noticing the size cost.
        assertThat(encoded.length / participations).isLessThan(400L);
    }

    @Test
    void anOutputCreatedAndSpentInsideOneBlockResolves() {
        // Preprod block 1,809,762: an invalid transaction's collateral return (output #1) is
        // consumed later in the same block. The consuming transaction's input never appears on
        // the UTXO store's spend path as a stored read - the output was created in the same
        // uncommitted batch - so capturing only on spend leaves it unresolvable and the
        // fail-closed guard halts the node. Addresses are therefore captured on creation too.
        String collateralReturnAddress = address(0xb1, 0xb2);
        String laterOutput = address(0xb3, 0xb4);

        var invalidTx = TransactionBody.builder().txHash(hex(0x50, 32))
                .inputs(new java.util.LinkedHashSet<>(List.of(in(0x51, 0))))
                .collateralInputs(new java.util.LinkedHashSet<>(List.of(in(0x52, 0))))
                .outputs(List.of(out(address(0xb5, 0xb6), 1_000_000L)))
                .collateralReturn(out(collateralReturnAddress, 7_000_000L))
                .fee(BigInteger.valueOf(170_000L)).build();
        // Spends the collateral return created by the transaction above, in this same block.
        var spender = TransactionBody.builder().txHash(hex(0x53, 32))
                .inputs(new java.util.LinkedHashSet<>(List.of(
                        TransactionInput.builder().transactionId(hex(0x50, 32)).index(1).build())))
                .outputs(List.of(out(laterOutput, 6_500_000L)))
                .fee(BigInteger.valueOf(170_000L)).build();

        // What the UTXO subsystem captures: the spent collateral input, plus every output it
        // creates — including the collateral return, which is the entry that matters here.
        var captured = new LinkedHashMap<String, String>();
        captured.put(hex(0x52, 32) + "#0", address(0xb7, 0xb8));   // collateral input, spend path
        captured.put(hex(0x50, 32) + "#1", collateralReturnAddress); // collateral return, creation path

        var rows = projectedRows(block(Era.Babbage, List.of(invalidTx, spender), List.of(0)),
                consumed(captured));

        assertThat(rows).isNotEmpty();
        String rendered = render(rows);
        assertThat(rendered)
                .as("the collateral return must appear as the spender's input")
                .contains(HexUtil.encodeHexString(
                        KEYS.key(HexUtil.decodeHexString(collateralReturnAddress))));
    }
}
