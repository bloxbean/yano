package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yaci.core.model.Epoch;
import com.bloxbean.cardano.yaci.core.model.byron.ByronAddress;
import com.bloxbean.cardano.yaci.core.model.byron.ByronBlockBody;
import com.bloxbean.cardano.yaci.core.model.byron.ByronBlockCons;
import com.bloxbean.cardano.yaci.core.model.byron.ByronBlockHead;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlockCons;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbHead;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronTx;
import com.bloxbean.cardano.yaci.core.model.byron.ByronTxIn;
import com.bloxbean.cardano.yaci.core.model.byron.ByronTxOut;
import com.bloxbean.cardano.yaci.core.model.byron.payload.ByronTxPayload;
import com.bloxbean.cardano.yano.api.archive.ProjectionCfNames;
import com.bloxbean.cardano.yano.api.events.ByronBlockProjectionEvent;
import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveJob;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveRangeAnchor;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.api.BlockRange;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionBlockKind;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSectionType;
import com.bloxbean.cardano.yano.archive.core.dataset.ArchiveBlockFacts;
import com.bloxbean.cardano.yano.archive.core.dataset.BlockSourceContext;
import com.bloxbean.cardano.yano.archive.core.dataset.StandardBlockDatasets;
import com.bloxbean.cardano.yano.archive.core.source.ByronBlockNormalizer;
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
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives genuine {@link ByronMainBlock} and {@link ByronEbBlock} values through the
 * dedicated ADR-039 projection carrier.
 *
 * <p>Byron is roughly a third of mainnet and has no live-path source at all: the UTXO
 * store returns immediately on the {@code block() == null} sentinel and never applies a
 * Byron transaction. So this path is the only way those blocks become projectable, and
 * the EBB case is what keeps the archive's contiguous coordinate from stopping dead at
 * the first epoch boundary.
 */
class ByronProjectionCarrierTest {
    static { RocksDB.loadLibrary(); }

    private static final ArchiveNetworkIdentity NETWORK = new ArchiveNetworkIdentity(764824073, "5f20df93");
    private static final Set<ProjectionSectionType> REQUIRED =
            Set.of(ProjectionSectionType.TRANSACTION, ProjectionSectionType.UTXO_HISTORY);
    private static final ProjectionIdentity IDENTITY =
            new ProjectionIdentity(NETWORK, "synthetic", 1, REQUIRED);

    private static final String BASE58 = "Ae2tdPwUPEZFRbyhz3cpfC2CumGzNkFBN2L42rcUc2yjQpEkxDbkPodpMAi";

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
        store = new ProjectionOutboxStore(db, handles.get(1), handles.get(2), handles.get(3), handles.get(4), handles.get(5));
        collector = new CanonicalProjectionCollector(store, IDENTITY, slot -> slot / 21600,
                slot -> 1_506_203_091L + slot * 20);
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

    private static ByronMainBlock mainBlock(long slot, String prevBlock, List<ByronTx> transactions) {
        var payloads = transactions.stream()
                .map(tx -> ByronTxPayload.builder().transaction(tx).witnesses(List.of()).build())
                .toList();
        return ByronMainBlock.builder()
                .header(ByronBlockHead.builder()
                        .protocolMagic(764824073L)
                        .prevBlock(prevBlock)
                        .consensusData(ByronBlockCons.builder()
                                .slotId(Epoch.builder().epoch(slot / 21600).slot(slot % 21600).build())
                                .difficulty(BigInteger.ONE)
                                .build())
                        .build())
                .body(ByronBlockBody.builder().txPayload(payloads).build())
                .build();
    }

    private static ByronTx transaction(String txHash, String inputTxId, int inputIndex, long amount) {
        return ByronTx.builder()
                .txHash(txHash)
                .inputs(List.of(ByronTxIn.builder().txId(inputTxId).index(inputIndex).build()))
                .outputs(List.of(ByronTxOut.builder()
                        .address(ByronAddress.builder().base58Raw(BASE58).build())
                        .amount(BigInteger.valueOf(amount)).build()))
                .build();
    }

    private static ByronEbBlock epochBoundaryBlock(long slot, String prevBlock) {
        return ByronEbBlock.builder()
                .header(ByronEbHead.builder()
                        .protocolMagic(764824073L)
                        .prevBlock(prevBlock)
                        .consensusData(ByronEbBlockCons.builder()
                                .epoch(slot / 21600).difficulty(BigInteger.ONE).build())
                        .build())
                .build();
    }

    private void contribute(ByronBlockProjectionEvent event) {
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            collector.contributeByronBlock(event, ProjectionOutboxStore.batchWriter(batch, store.handles()));
            db.write(options, batch);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ----------------------------------------------------------------- the cases

    @Test
    void aGenuineByronMainBlockProducesAProjectableEnvelope() {
        var byron = mainBlock(43200, hex(0x0a, 32),
                List.of(transaction(hex(0x11, 32), hex(0x22, 32), 0, 1_000_000)));
        contribute(ByronBlockProjectionEvent.main(43200, 500, hex(0x0b, 32), hex(0x0a, 32), byron));

        var envelope = store.readEnvelope(500, REQUIRED).orElseThrow();
        assertThat(envelope.header().blockKind()).isEqualTo(ProjectionBlockKind.BYRON_MAIN);
        assertThat(envelope.sections()).hasSize(2);

        var txFacts = ProjectionFactCodec.decodeTransactions(ProjectionChunking.join(
                envelope.section(ProjectionSectionType.TRANSACTION).orElseThrow().chunks()));
        assertThat(txFacts).hasSize(1);
        assertThat(txFacts.get(0).txIndex()).isZero();
        assertThat(txFacts.get(0).valid()).isTrue();
    }

    @Test
    void byronTransactionFeeIsNullBecauseByronCarriesNone() {
        var byron = mainBlock(43200, hex(0x0a, 32),
                List.of(transaction(hex(0x11, 32), hex(0x22, 32), 0, 1_000_000)));
        contribute(ByronBlockProjectionEvent.main(43200, 500, hex(0x0b, 32), hex(0x0a, 32), byron));

        var envelope = store.readEnvelope(500, REQUIRED).orElseThrow();
        var facts = ProjectionFactCodec.decodeTransactions(ProjectionChunking.join(
                envelope.section(ProjectionSectionType.TRANSACTION).orElseThrow().chunks()));
        assertThat(facts.get(0).fee()).isNull();

        // And the fee reaches chain_transaction as a genuine SQL NULL, not a zero.
        List<ArchiveRow> rows = new ArrayList<>();
        StandardBlockDatasets.transactions().derive(
                ArchiveJob.deterministic(NETWORK, ArchiveDatasetId.TRANSACTION, 1, new BlockRange(500, 500),
                        new ArchiveRangeAnchor(43200, new byte[]{1}, 43200, new byte[]{1}), "v1"),
                new BlockSourceContext<>(500, 43200, 2, Instant.EPOCH, new byte[]{1}, new byte[]{0},
                        new ArchiveBlockFacts(facts, List.of())),
                rows::add);
        assertThat(rows).singleElement().satisfies(row -> assertThat(row.values().get(8)).isNull());
    }

    @Test
    void byronOutputsKeepTheirRawBase58Address() {
        var byron = mainBlock(43200, hex(0x0a, 32),
                List.of(transaction(hex(0x11, 32), hex(0x22, 32), 0, 1_000_000)));
        contribute(ByronBlockProjectionEvent.main(43200, 500, hex(0x0b, 32), hex(0x0a, 32), byron));

        var envelope = store.readEnvelope(500, REQUIRED).orElseThrow();
        var fact = ProjectionFactCodec.decodeUtxoHistory(ProjectionChunking.join(
                envelope.section(ProjectionSectionType.UTXO_HISTORY).orElseThrow().chunks()));
        assertThat(fact.outputs()).hasSize(1);
        assertThat(fact.newAddresses()).anySatisfy(address ->
                assertThat(address.displayAddress()).isEqualTo(BASE58));
    }

    @Test
    void byronHasNoCollateralReferenceInputsDatumsOrRedeemers() {
        var byron = mainBlock(43200, hex(0x0a, 32),
                List.of(transaction(hex(0x11, 32), hex(0x22, 32), 0, 1_000_000)));
        contribute(ByronBlockProjectionEvent.main(43200, 500, hex(0x0b, 32), hex(0x0a, 32), byron));

        var fact = ProjectionFactCodec.decodeUtxoHistory(ProjectionChunking.join(
                store.readEnvelope(500, REQUIRED).orElseThrow()
                        .section(ProjectionSectionType.UTXO_HISTORY).orElseThrow().chunks()));
        assertThat(fact.transactionDatums()).isEmpty();
        assertThat(fact.transactionRedeemers()).isEmpty();
        assertThat(fact.assets()).isEmpty();
        assertThat(fact.pointerRegistrations()).isEmpty();
        assertThat(fact.inputs()).allSatisfy(input -> assertThat(input.inputRole()).isEqualTo("input"));
    }

    @Test
    void anEpochBoundaryBlockEmitsAnEmptyEnvelopeAndAdvancesEveryContributor() {
        contribute(ByronBlockProjectionEvent.epochBoundary(21600, 499, hex(0x0c, 32), hex(0x09, 32),
                epochBoundaryBlock(21600, hex(0x09, 32))));

        var envelope = store.readEnvelope(499, REQUIRED).orElseThrow();
        assertThat(envelope.sections()).isEmpty();
        assertThat(envelope.header().blockKind()).isEqualTo(ProjectionBlockKind.BYRON_EBB);
        assertThat(store.completeThrough(REQUIRED)).isEqualTo(499);
    }

    @Test
    void anEbbBetweenTwoMainBlocksKeepsTheCoordinateContiguous() {
        contribute(ByronBlockProjectionEvent.main(21580, 498, hex(0x0d, 32), hex(0x08, 32),
                mainBlock(21580, hex(0x08, 32), List.of(transaction(hex(0x31, 32), hex(0x32, 32), 0, 5)))));
        contribute(ByronBlockProjectionEvent.epochBoundary(21600, 499, hex(0x0c, 32), hex(0x0d, 32),
                epochBoundaryBlock(21600, hex(0x0d, 32))));
        contribute(ByronBlockProjectionEvent.main(21620, 500, hex(0x0b, 32), hex(0x0c, 32),
                mainBlock(21620, hex(0x0c, 32), List.of(transaction(hex(0x33, 32), hex(0x34, 32), 0, 6)))));

        // No hole: every block in 498..500 has a readable envelope.
        for (long block = 498; block <= 500; block++) {
            assertThat(store.readEnvelope(block, REQUIRED)).as("block %d", block).isPresent();
        }
        assertThat(store.completeThrough(REQUIRED)).isEqualTo(500);
    }

    @Test
    void theByronParentBridgeIsPreservedThroughTheEnvelopeHeader() {
        contribute(ByronBlockProjectionEvent.epochBoundary(21600, 499, hex(0x0c, 32), hex(0x0d, 32),
                epochBoundaryBlock(21600, hex(0x0d, 32))));
        var envelope = store.readEnvelope(499, REQUIRED).orElseThrow();
        assertThat(envelope.header().parentHash())
                .isEqualTo(com.bloxbean.cardano.yaci.core.util.HexUtil.decodeHexString(hex(0x0d, 32)));
    }

    @Test
    void anEmptyByronMainBlockStillProducesAnEnvelope() {
        contribute(ByronBlockProjectionEvent.main(43200, 501, hex(0x0e, 32), hex(0x0b, 32),
                mainBlock(43200, hex(0x0b, 32), List.of())));
        var envelope = store.readEnvelope(501, REQUIRED).orElseThrow();
        assertThat(envelope.header().blockKind()).isEqualTo(ProjectionBlockKind.BYRON_MAIN);
        assertThat(ProjectionFactCodec.decodeTransactions(ProjectionChunking.join(
                envelope.section(ProjectionSectionType.TRANSACTION).orElseThrow().chunks()))).isEmpty();
    }

    @Test
    void theNormalizerAgreesWithTheCarrierPath() {
        // The retained normalizer is the single definition of a normalized Byron block;
        // replay and the projection carrier must not drift apart.
        var byron = mainBlock(43200, hex(0x0a, 32),
                List.of(transaction(hex(0x11, 32), hex(0x22, 32), 0, 1_000_000)));
        var normalized = ByronBlockNormalizer.normalizeMain(byron, 500,
                com.bloxbean.cardano.yaci.core.util.HexUtil.decodeHexString(hex(0x0b, 32)));

        assertThat(normalized.getEra()).isEqualTo(com.bloxbean.cardano.yaci.core.model.Era.Byron);
        assertThat(normalized.getTransactionBodies()).hasSize(1);
        assertThat(normalized.getTransactionBodies().get(0).getFee()).isNull();
        assertThat(normalized.getInvalidTransactions()).isEmpty();
        assertThat(normalized.getHeader().getHeaderBody().getPrevHash()).isEqualTo(hex(0x0a, 32));
    }

    @Test
    void everyShippedSectionIsBuildable() {
        // Phase 4b's exit condition. The collector fails closed on a required section it cannot
        // build, because advancing the cursor with no section would stall every envelope
        // forever - so "all four are accepted" is the statement that the four datasets are
        // actually covered, not merely declared.
        var all = new ProjectionIdentity(NETWORK, "synthetic", 1,
                java.util.Set.of(ProjectionSectionType.TRANSACTION, ProjectionSectionType.UTXO_HISTORY,
                        ProjectionSectionType.ACCOUNT_EVENT, ProjectionSectionType.ADDRESS_TRANSACTION));
        new CanonicalProjectionCollector(store, all, slot -> 0, slot -> 0);

        // And the fail-closed guard itself still works, for any section added in future before
        // its contributor exists.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new CanonicalProjectionCollector(store, all, slot -> 0, slot -> 0,
                                1 << 20, true, com.bloxbean.cardano.yano.api.archive
                                        .PointerCredentialSource.NONE,
                                com.bloxbean.cardano.yano.api.genesis.GenesisUtxoProvider.EMPTY,
                                java.util.Set.of(ProjectionSectionType.TRANSACTION)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot");
    }
}
