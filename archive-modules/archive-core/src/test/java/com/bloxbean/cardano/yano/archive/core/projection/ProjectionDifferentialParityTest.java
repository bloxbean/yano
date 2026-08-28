package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.Redeemer;
import com.bloxbean.cardano.yaci.core.model.RedeemerTag;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionInput;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.model.Witnesses;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.archive.ProjectionCfNames;
import com.bloxbean.cardano.yano.api.archive.ProjectionStagingWriter;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveJob;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveRangeAnchor;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.api.BlockRange;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSectionType;
import com.bloxbean.cardano.yano.archive.core.dataset.BlockSourceContext;
import com.bloxbean.cardano.yano.archive.core.dataset.StandardBlockDatasets;
import com.bloxbean.cardano.yano.archive.core.dataset.UtxoHistoryFact;
import com.bloxbean.cardano.yano.archive.core.dataset.UtxoHistoryRows;
import com.bloxbean.cardano.yano.archive.core.source.YaciBlockArchiveDecoder;
import com.bloxbean.cardano.yano.archive.core.source.YaciUtxoHistoryDecoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-039 Phase 3 exit criterion: the projection outbox must produce byte-identical
 * archive rows to the existing decode-and-derive path over the same canonical block.
 *
 * <p>The old path is the differential oracle. Both paths deliberately end in the same
 * {@code BlockArchiveDataset.derive} implementations, so any divergence is a transport
 * defect — a lossy encoding, a reordering, a dropped nullable — rather than two
 * independent interpretations of the chain drifting apart. That is precisely the class
 * of bug this test exists to catch, and it is the evidence Phase 7 needs before the
 * replay workers can be deleted.
 */
class ProjectionDifferentialParityTest {
    static { RocksDB.loadLibrary(); }

    private static final ArchiveNetworkIdentity NETWORK = new ArchiveNetworkIdentity(1, "162d29c4");
    private static final Set<ProjectionSectionType> REQUIRED =
            Set.of(ProjectionSectionType.TRANSACTION, ProjectionSectionType.UTXO_HISTORY,
                    ProjectionSectionType.ACCOUNT_EVENT);
    private static final ProjectionIdentity IDENTITY =
            new ProjectionIdentity(NETWORK, "synthetic", 1, REQUIRED);

    @TempDir Path directory;

    private RocksDB db;
    private DBOptions dbOptions;
    private List<ColumnFamilyHandle> handles;
    private ProjectionOutboxStore store;
    private CanonicalProjectionCollector collector;

    @BeforeEach
    void setUp() throws Exception {
        dbOptions = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
        List<ColumnFamilyDescriptor> descriptors = new ArrayList<>();
        descriptors.add(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY));
        for (String name : ProjectionCfNames.ALL) {
            descriptors.add(new ColumnFamilyDescriptor(name.getBytes(StandardCharsets.UTF_8)));
        }
        handles = new ArrayList<>();
        db = RocksDB.open(dbOptions, directory.resolve("db").toString(), descriptors, handles);
        store = new ProjectionOutboxStore(db, handles.get(1), handles.get(2), handles.get(3), handles.get(4));
        collector = new CanonicalProjectionCollector(store, IDENTITY, slot -> slot / 100, slot -> 1_600_000_000L + slot);
    }

    @AfterEach
    void tearDown() {
        handles.forEach(ColumnFamilyHandle::close);
        db.close();
        dbOptions.close();
    }

    // ---------------------------------------------------------------- fixtures

    private static String hex(int b, int len) {
        return String.format("%02x", b).repeat(len);
    }

    private static TransactionOutput output(String address, long lovelace, Amount... extra) {
        List<Amount> amounts = new ArrayList<>();
        amounts.add(Amount.builder().unit("lovelace").quantity(BigInteger.valueOf(lovelace)).build());
        amounts.addAll(Arrays.asList(extra));
        return TransactionOutput.builder().address(address).amounts(amounts).build();
    }

    private static String shelleyAddress(int paymentFill, int stakeFill) {
        byte[] address = new byte[57];
        address[0] = 0;
        Arrays.fill(address, 1, 29, (byte) paymentFill);
        Arrays.fill(address, 29, 57, (byte) stakeFill);
        return HexUtil.encodeHexString(address);
    }

    private static Block block(Era era, List<TransactionBody> txs, List<Integer> invalid, List<Witnesses> witnesses) {
        return Block.builder().era(era)
                .header(BlockHeader.builder().headerBody(HeaderBody.builder()
                        .blockNumber(100).slot(2000).prevHash(hex(0x0a, 32)).blockHash(hex(0x0b, 32)).build()).build())
                .transactionBodies(txs)
                .transactionWitness(witnesses == null ? List.of() : witnesses)
                .invalidTransactions(invalid)
                .build();
    }

    // ------------------------------------------------------- parity machinery

    private static String render(List<ArchiveRow> rows) {
        return rows.stream().map(row -> row.table() + "(" + row.values().stream()
                .map(value -> value instanceof byte[] bytes ? HexUtil.encodeHexString(bytes) : String.valueOf(value))
                .collect(Collectors.joining(",")) + ")").collect(Collectors.joining("\n"));
    }

    private static ArchiveJob job(ArchiveDatasetId dataset) {
        return ArchiveJob.deterministic(NETWORK, dataset, 1, new BlockRange(100, 100),
                new ArchiveRangeAnchor(2000, HexUtil.decodeHexString(hex(0x0b, 32)), 2000,
                        HexUtil.decodeHexString(hex(0x0b, 32))), "v1");
    }

    private BlockSourceContext<Block> context(Block block) {
        return new BlockSourceContext<>(100, 2000, 20, Instant.ofEpochSecond(1_600_002_000L),
                HexUtil.decodeHexString(hex(0x0b, 32)), HexUtil.decodeHexString(hex(0x0a, 32)), block);
    }

    /** Rows the existing path produces: decode the block, derive directly. */
    private List<ArchiveRow> oracleRows(Block block) {
        var ctx = context(block);
        List<ArchiveRow> rows = new ArrayList<>();
        var txFacts = new YaciBlockArchiveDecoder(slot -> slot / 100, slot -> 1_600_000_000L + slot).project(ctx);
        StandardBlockDatasets.transactions().derive(job(ArchiveDatasetId.TRANSACTION), txFacts, rows::add);

        StandardBlockDatasets.accountEvents().derive(job(ArchiveDatasetId.ACCOUNT_EVENT), txFacts, rows::add);

        var utxoFacts = new YaciUtxoHistoryDecoder(slot -> slot / 100, slot -> 1_600_000_000L + slot).project(ctx);
        UtxoHistoryRows.emit(job(ArchiveDatasetId.UTXO_HISTORY), utxoFacts, rows::add);
        return rows;
    }

    /** Rows the ADR-039 path produces: collect during apply, read back, derive from carried facts. */
    private List<ArchiveRow> outboxRows(Block block) {
        var event = new BlockAppliedEvent(block.getEra(), 2000, 100, hex(0x0b, 32), block);
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            ProjectionStagingWriter writer = ProjectionOutboxStore.batchWriter(batch, store.handles());
            collector.contributeBlock(event, writer);
            db.write(options, batch);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        var envelope = store.readEnvelope(100, REQUIRED).orElseThrow();
        List<ArchiveRow> rows = new ArrayList<>();

        var txSection = envelope.section(ProjectionSectionType.TRANSACTION).orElseThrow();
        var txFacts = ProjectionFactCodec.decodeTransactions(ProjectionChunking.join(txSection.chunks()));
        StandardBlockDatasets.transactions().derive(job(ArchiveDatasetId.TRANSACTION),
                new BlockSourceContext<>(100, 2000, 20, Instant.ofEpochSecond(1_600_002_000L),
                        HexUtil.decodeHexString(hex(0x0b, 32)), HexUtil.decodeHexString(hex(0x0a, 32)),
                        new com.bloxbean.cardano.yano.archive.core.dataset.ArchiveBlockFacts(txFacts, List.of())),
                rows::add);

        var eventSection = envelope.section(ProjectionSectionType.ACCOUNT_EVENT).orElseThrow();
        var eventFacts = ProjectionFactCodec.decodeAccountEvents(ProjectionChunking.join(eventSection.chunks()));
        StandardBlockDatasets.accountEvents().derive(job(ArchiveDatasetId.ACCOUNT_EVENT),
                new BlockSourceContext<>(100, 2000, 20, Instant.ofEpochSecond(1_600_002_000L),
                        HexUtil.decodeHexString(hex(0x0b, 32)), HexUtil.decodeHexString(hex(0x0a, 32)),
                        new com.bloxbean.cardano.yano.archive.core.dataset.ArchiveBlockFacts(
                                List.of(), eventFacts)),
                rows::add);

        var utxoSection = envelope.section(ProjectionSectionType.UTXO_HISTORY).orElseThrow();
        UtxoHistoryFact utxoFact =
                ProjectionFactCodec.decodeUtxoHistory(ProjectionChunking.join(utxoSection.chunks()));
        UtxoHistoryRows.emit(job(ArchiveDatasetId.UTXO_HISTORY),
                new BlockSourceContext<>(100, 2000, 20, Instant.ofEpochSecond(1_600_002_000L),
                        HexUtil.decodeHexString(hex(0x0b, 32)), HexUtil.decodeHexString(hex(0x0a, 32)), utxoFact),
                rows::add);
        return rows;
    }

    private void assertParity(Block block) {
        String oracle = render(oracleRows(block));
        String outbox = render(outboxRows(block));
        assertThat(outbox).isEqualTo(oracle);
    }

    /**
     * Parity, plus proof that the fixture actually exercises the dataset.
     *
     * <p>Equality between two empty row sets is not evidence of anything. A certificate fixture
     * that silently produced no account events would pass {@link #assertParity} while testing
     * nothing at all, so the expected row count is stated rather than assumed.
     */
    private void assertAccountEventParity(Block block, int expectedEvents) {
        List<ArchiveRow> oracle = oracleRows(block);
        long events = oracle.stream().filter(row -> row.table().equals("account_events")).count();
        assertThat(events)
                .as("the fixture must actually produce account events, or parity proves nothing")
                .isEqualTo(expectedEvents);
        assertThat(render(outboxRows(block))).isEqualTo(render(oracle));
    }

    // -------------------------------------------------------------- the cases

    @Test
    void emptyBlock() {
        assertParity(block(Era.Babbage, List.of(), List.of(), List.of()));
    }

    @Test
    void simpleValidTransaction() {
        var tx = TransactionBody.builder().txHash(hex(0x11, 32)).fee(BigInteger.valueOf(170_000))
                .inputs(new LinkedHashSet<>(List.of(
                        TransactionInput.builder().transactionId(hex(0x22, 32)).index(0).build())))
                .outputs(List.of(output(shelleyAddress(1, 2), 5_000_000)))
                .build();
        assertParity(block(Era.Babbage, List.of(tx), List.of(), List.of()));
    }

    @Test
    void invalidTransactionWithCollateralAndCollateralReturn() {
        var out = output(shelleyAddress(3, 4), 1_000_000);
        var tx = TransactionBody.builder().txHash(hex(0x33, 32)).fee(BigInteger.valueOf(200_000))
                .inputs(new LinkedHashSet<>(List.of(
                        TransactionInput.builder().transactionId(hex(0x44, 32)).index(0).build())))
                .collateralInputs(new LinkedHashSet<>(List.of(
                        TransactionInput.builder().transactionId(hex(0x55, 32)).index(1).build())))
                .referenceInputs(new LinkedHashSet<>(List.of(
                        TransactionInput.builder().transactionId(hex(0x66, 32)).index(2).build())))
                .outputs(List.of(out))
                .collateralReturn(out)
                .build();
        assertParity(block(Era.Babbage, List.of(tx), List.of(0), List.of()));
    }

    @Test
    void sameBlockParentAndChildPreserveCanonicalOrder() {
        var parent = TransactionBody.builder().txHash(hex(0x77, 32)).fee(BigInteger.valueOf(100))
                .inputs(new LinkedHashSet<>(List.of(
                        TransactionInput.builder().transactionId(hex(0x88, 32)).index(0).build())))
                .outputs(List.of(output(shelleyAddress(5, 6), 9_000_000)))
                .build();
        // Child spends the parent's output created earlier in this same block.
        var child = TransactionBody.builder().txHash(hex(0x99, 32)).fee(BigInteger.valueOf(120))
                .inputs(new LinkedHashSet<>(List.of(
                        TransactionInput.builder().transactionId(hex(0x77, 32)).index(0).build())))
                .outputs(List.of(output(shelleyAddress(7, 8), 8_000_000)))
                .build();
        assertParity(block(Era.Babbage, List.of(parent, child), List.of(), List.of()));
    }

    @Test
    void multiAssetOutputs() {
        var multi = output(shelleyAddress(9, 10), 2_000_000,
                Amount.builder().unit(hex(0xaa, 28) + "01").policyId(hex(0xaa, 28))
                        .assetName("01").assetNameBytes(new byte[]{1}).quantity(BigInteger.valueOf(42)).build(),
                Amount.builder().unit(hex(0xbb, 28) + "02").policyId(hex(0xbb, 28))
                        .assetName("02").assetNameBytes(new byte[]{2}).quantity(BigInteger.valueOf(7)).build());
        var tx = TransactionBody.builder().txHash(hex(0xab, 32)).fee(BigInteger.valueOf(180_000))
                .inputs(new LinkedHashSet<>(List.of(
                        TransactionInput.builder().transactionId(hex(0xac, 32)).index(0).build())))
                .outputs(List.of(multi)).build();
        assertParity(block(Era.Babbage, List.of(tx), List.of(), List.of()));
    }

    @Test
    void datumHashInlineDatumAndReferenceScript() {
        var withDatumHash = TransactionOutput.builder().address(shelleyAddress(11, 12))
                .amounts(List.of(Amount.builder().unit("lovelace").quantity(BigInteger.valueOf(3_000_000)).build()))
                .datumHash(hex(0xcc, 32)).build();
        var withInline = TransactionOutput.builder().address(shelleyAddress(13, 14))
                .amounts(List.of(Amount.builder().unit("lovelace").quantity(BigInteger.valueOf(4_000_000)).build()))
                .inlineDatum(hex(0xdd, 16)).build();
        var tx = TransactionBody.builder().txHash(hex(0xae, 32)).fee(BigInteger.valueOf(190_000))
                .inputs(new LinkedHashSet<>(List.of(
                        TransactionInput.builder().transactionId(hex(0xaf, 32)).index(0).build())))
                .outputs(List.of(withDatumHash, withInline)).build();
        assertParity(block(Era.Babbage, List.of(tx), List.of(), List.of()));
    }

    @Test
    void redeemersAreCarriedIntact() {
        var redeemer = Redeemer.builder().tag(RedeemerTag.Spend).index(0)
                .data(com.bloxbean.cardano.yaci.core.model.Datum.builder().cbor(hex(0xef, 8)).build())
                .exUnits(com.bloxbean.cardano.yaci.core.model.ExUnits.builder()
                        .mem(BigInteger.valueOf(1000)).steps(BigInteger.valueOf(2000)).build())
                .cbor(hex(0xee, 8)).build();
        var witnesses = Witnesses.builder().redeemers(List.of(redeemer)).build();
        var tx = TransactionBody.builder().txHash(hex(0xba, 32)).fee(BigInteger.valueOf(210_000))
                .inputs(new LinkedHashSet<>(List.of(
                        TransactionInput.builder().transactionId(hex(0xbc, 32)).index(0).build())))
                .outputs(List.of(output(shelleyAddress(15, 16), 1_500_000))).build();
        assertParity(block(Era.Babbage, List.of(tx), List.of(), List.of(witnesses)));
    }

    @Test
    void denseBlockWithManyTransactions() {
        List<TransactionBody> txs = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            txs.add(TransactionBody.builder().txHash(hex(i % 200, 32)).fee(BigInteger.valueOf(150_000 + i))
                    .inputs(new LinkedHashSet<>(List.of(
                            TransactionInput.builder().transactionId(hex((i + 7) % 200, 32)).index(i % 3).build())))
                    .outputs(List.of(output(shelleyAddress(i % 20 + 1, i % 15 + 1), 1_000_000L + i)))
                    .build());
        }
        assertParity(block(Era.Babbage, txs, List.of(3, 17, 41), List.of()));
    }

    @Test
    void byronStyleBlockWithNullFeeAndBase58Addresses() {
        // Byron normalization yields no fee and raw base58 addresses; both must survive.
        var tx = TransactionBody.builder().txHash(hex(0xca, 32))
                .inputs(new LinkedHashSet<>(List.of(
                        TransactionInput.builder().transactionId(hex(0xcb, 32)).index(0).build())))
                .outputs(List.of(output("Ae2tdPwUPEZFRbyhz3cpfC2CumGzNkFBN2L42rcUc2yjQpEkxDbkPodpMAi", 10_000_000)))
                .build();
        Block byron = Block.builder().era(Era.Byron)
                .header(BlockHeader.builder().headerBody(HeaderBody.builder()
                        .blockNumber(100).slot(2000).prevHash(hex(0x0a, 32)).blockHash(hex(0x0b, 32)).build()).build())
                .transactionBodies(List.of(tx)).transactionWitness(List.of()).invalidTransactions(List.of()).build();

        assertParity(byron);

        // The projection semantic the ADR calls out explicitly: fee is NULL for Byron.
        assertThat(render(outboxRows(byron))).contains("null");
    }

    @Test
    void chunkingDoesNotChangeDerivedRows() {
        // Force multi-chunk sections; the reassembled payload must derive identical rows.
        var small = new CanonicalProjectionCollector(store, IDENTITY, slot -> slot / 100,
                slot -> 1_600_000_000L + slot, 64, true);
        List<TransactionBody> txs = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            txs.add(TransactionBody.builder().txHash(hex(i % 200, 32)).fee(BigInteger.valueOf(1000 + i))
                    .inputs(new LinkedHashSet<>(List.of(
                            TransactionInput.builder().transactionId(hex((i + 3) % 200, 32)).index(0).build())))
                    .outputs(List.of(output(shelleyAddress(i % 10 + 1, 2), 2_000_000L + i))).build());
        }
        Block dense = block(Era.Babbage, txs, List.of(), List.of());

        var event = new BlockAppliedEvent(dense.getEra(), 2000, 100, hex(0x0b, 32), dense);
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            small.contributeBlock(event, ProjectionOutboxStore.batchWriter(batch, store.handles()));
            db.write(options, batch);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        var envelope = store.readEnvelope(100, REQUIRED).orElseThrow();
        assertThat(envelope.section(ProjectionSectionType.TRANSACTION).orElseThrow().chunks().size())
                .isGreaterThan(1);

        var txFacts = ProjectionFactCodec.decodeTransactions(ProjectionChunking.join(
                envelope.section(ProjectionSectionType.TRANSACTION).orElseThrow().chunks()));
        List<ArchiveRow> rows = new ArrayList<>();
        StandardBlockDatasets.transactions().derive(job(ArchiveDatasetId.TRANSACTION),
                new BlockSourceContext<>(100, 2000, 20, Instant.ofEpochSecond(1_600_002_000L),
                        HexUtil.decodeHexString(hex(0x0b, 32)), HexUtil.decodeHexString(hex(0x0a, 32)),
                        new com.bloxbean.cardano.yano.archive.core.dataset.ArchiveBlockFacts(txFacts, List.of())),
                rows::add);

        List<ArchiveRow> oracle = oracleRows(dense).stream()
                .filter(r -> r.table().equals("chain_transaction")).toList();
        assertThat(render(rows)).isEqualTo(render(oracle));
    }

    // ------------------------------------------------------- pointer addresses

    /**
     * Pre-Conway pointer addresses used to throw here, because row derivation ran without a
     * resolver. They are now resolved at <em>capture</em> time against the authoritative
     * account-state mapping, so the sink needs no resolver state and an unresolvable pointer
     * is recorded as {@code pointer_unresolved} rather than failing the batch.
     *
     * <p>This collector is constructed with {@code PointerCredentialSource.NONE}, so every
     * pointer is genuinely unresolvable — which is precisely the case that used to crash.
     * Full agreement with the shipped sequential resolver is covered by
     * {@code PointerResolutionDifferentialTest}, which uses that resolver as its oracle
     * instead of comparing two resolver-less paths.
     */
    @Test
    void preConwayPointerAddressesResolveToUnresolvedInsteadOfThrowing() {
        String pointerAddress = "40" + "66".repeat(28) + "0a0000";
        var tx = TransactionBody.builder().txHash(hex(0xd1, 32)).fee(BigInteger.valueOf(100))
                .inputs(new LinkedHashSet<>(List.of(
                        TransactionInput.builder().transactionId(hex(0xd2, 32)).index(0).build())))
                .outputs(List.of(TransactionOutput.builder().address(pointerAddress)
                        .amounts(List.of(Amount.builder().unit("lovelace")
                                .quantity(BigInteger.valueOf(1_000_000)).build())).build()))
                .build();
        Block preConway = block(Era.Babbage, List.of(tx), List.of(), List.of());

        List<ArchiveRow> rows = outboxRows(preConway);
        assertThat(rows).isNotEmpty();
        assertThat(render(rows)).contains("transaction_outputs");
    }

    @Test
    void conwayPointerAddressesAreProjectedWithoutAResolver() {
        // From Conway on, a pointer stake reference is recorded as not effective, so the
        // projection path needs no resolver and parity holds.
        String pointerAddress = "40" + "66".repeat(28) + "0a0000";
        var tx = TransactionBody.builder().txHash(hex(0xd3, 32)).fee(BigInteger.valueOf(100))
                .inputs(new LinkedHashSet<>(List.of(
                        TransactionInput.builder().transactionId(hex(0xd4, 32)).index(0).build())))
                .outputs(List.of(TransactionOutput.builder().address(pointerAddress)
                        .amounts(List.of(Amount.builder().unit("lovelace")
                                .quantity(BigInteger.valueOf(1_000_000)).build())).build()))
                .build();
        assertParity(block(Era.Conway, List.of(tx), List.of(), List.of()));
    }

    // ------------------------------------------------- account event fixtures

    private static com.bloxbean.cardano.yaci.core.model.certs.StakeCredential stakeKey(int fill) {
        return com.bloxbean.cardano.yaci.core.model.certs.StakeCredential.fromKeyHash(
                HexUtil.decodeHexString(hex(fill, 28)));
    }

    private static com.bloxbean.cardano.yaci.core.model.certs.StakeCredential stakeScript(int fill) {
        return com.bloxbean.cardano.yaci.core.model.certs.StakeCredential.fromScriptHash(
                HexUtil.decodeHexString(hex(fill, 28)));
    }

    private static TransactionBody txWithCertificates(int fill,
            List<com.bloxbean.cardano.yaci.core.model.certs.Certificate> certificates) {
        return TransactionBody.builder().txHash(hex(fill, 32))
                .inputs(java.util.Set.of()).outputs(List.of(output(shelleyAddress(0x11, 0x22), 1_000_000L)))
                .fee(BigInteger.valueOf(170_000L))
                .certificates(certificates)
                .build();
    }

    @Test
    void stakeRegistrationAndDeregistrationProjectIdentically() {
        var tx = txWithCertificates(0x40, List.of(
                com.bloxbean.cardano.yaci.core.model.certs.StakeRegistration.builder()
                        .stakeCredential(stakeKey(0x51)).build(),
                com.bloxbean.cardano.yaci.core.model.certs.StakeDeregistration.builder()
                        .stakeCredential(stakeKey(0x52)).build()));
        assertAccountEventParity(block(Era.Babbage, List.of(tx), List.of(), List.of()), 2);
    }

    @Test
    void scriptStakeCredentialsKeepTheirCredentialType() {
        // The credential type reaches the stake-address encoder, so getting it wrong produces
        // a valid-looking but wrong bech32 address rather than an error.
        var tx = txWithCertificates(0x41, List.of(
                com.bloxbean.cardano.yaci.core.model.certs.StakeRegistration.builder()
                        .stakeCredential(stakeScript(0x53)).build()));
        assertAccountEventParity(block(Era.Babbage, List.of(tx), List.of(), List.of()), 1);
    }

    @Test
    void stakeDelegationCarriesThePoolHash() {
        var tx = txWithCertificates(0x42, List.of(
                com.bloxbean.cardano.yaci.core.model.certs.StakeDelegation.builder()
                        .stakeCredential(stakeKey(0x54))
                        .stakePoolId(com.bloxbean.cardano.yaci.core.model.certs.StakePoolId.builder()
                                .poolKeyHash(hex(0x55, 28)).build())
                        .build()));
        assertAccountEventParity(block(Era.Babbage, List.of(tx), List.of(), List.of()), 1);
    }

    @Test
    void aConwayCertificateThatExpandsIntoSeveralEventsKeepsTheirOrderAndIndices() {
        // StakeVoteRegDelegCert becomes three events at index<<32, +1 and +2. Their order and
        // their derived indices are part of the archive contract, not an implementation detail.
        var tx = txWithCertificates(0x43, List.of(
                com.bloxbean.cardano.yaci.core.model.certs.StakeVoteRegDelegCert.builder()
                        .stakeCredential(stakeKey(0x56))
                        .poolKeyHash(hex(0x57, 28))
                        .drep(com.bloxbean.cardano.yaci.core.model.governance.Drep
                                .addrKeyHash(hex(0x58, 28)))
                        .coin(BigInteger.valueOf(2_000_000L))
                        .build()));
        assertAccountEventParity(block(Era.Babbage, List.of(tx), List.of(), List.of()), 3);
    }

    @Test
    void withdrawalsProjectAsAccountEvents() {
        var tx = TransactionBody.builder().txHash(hex(0x44, 32))
                .inputs(java.util.Set.of()).outputs(List.of(output(shelleyAddress(0x11, 0x22), 1_000_000L)))
                .fee(BigInteger.valueOf(170_000L))
                .withdrawals(new java.util.LinkedHashMap<>(java.util.Map.of(
                        "e1" + hex(0x59, 28), BigInteger.valueOf(4_500_000L))))
                .build();
        assertAccountEventParity(block(Era.Babbage, List.of(tx), List.of(), List.of()), 1);
    }

    @Test
    void certificatesInAnInvalidTransactionFollowTheSameRuleAsTheExistingPath() {
        // Whatever the existing archive does with certificates in a failed transaction, the
        // projection must do the same. Pinning it here means a change in that rule shows up as
        // a parity failure rather than as a silent divergence between the two paths.
        var tx = txWithCertificates(0x45, List.of(
                com.bloxbean.cardano.yaci.core.model.certs.StakeRegistration.builder()
                        .stakeCredential(stakeKey(0x5a)).build()));
        // The existing path derives account events only from valid transactions, so the
        // expected count is zero. Stating it makes the rule explicit rather than incidental.
        assertAccountEventParity(block(Era.Babbage, List.of(tx), List.of(0), List.of()), 0);
    }

    @Test
    void certificatesAcrossSeveralTransactionsKeepBlockOrder() {
        var first = txWithCertificates(0x46, List.of(
                com.bloxbean.cardano.yaci.core.model.certs.StakeRegistration.builder()
                        .stakeCredential(stakeKey(0x5b)).build()));
        var second = txWithCertificates(0x47, List.of(
                com.bloxbean.cardano.yaci.core.model.certs.StakeDeregistration.builder()
                        .stakeCredential(stakeKey(0x5c)).build()));
        assertAccountEventParity(block(Era.Babbage, List.of(first, second), List.of(), List.of()), 2);
    }
}
