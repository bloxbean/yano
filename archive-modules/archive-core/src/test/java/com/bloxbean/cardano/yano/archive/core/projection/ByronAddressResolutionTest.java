package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Epoch;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionInput;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
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
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.archive.ConsumedOutputAddresses;
import com.bloxbean.cardano.yano.api.archive.PointerCredentialSource;
import com.bloxbean.cardano.yano.api.archive.ProjectionCfNames;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.ByronBlockProjectionEvent;
import com.bloxbean.cardano.yano.api.genesis.GenesisUtxo;
import com.bloxbean.cardano.yano.api.genesis.GenesisUtxoProvider;
import com.bloxbean.cardano.yano.api.genesis.GenesisUtxos;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionBlockKind;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSectionType;
import com.bloxbean.cardano.yano.archive.core.dataset.AddressParticipationFact;
import com.bloxbean.cardano.yano.archive.core.dataset.AddressSubjectRows;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Consumed-address resolution for outputs the live UTXO subsystem never held.
 *
 * <p>Shelley+ inputs are resolved from what apply captured while deleting the spent output.
 * Byron has no such moment — {@code BlockAppliedEvent.block()} is {@code null} there and the
 * UTXO store returns on that sentinel — so a Byron output exists in no live column family, and
 * the address-transaction section had nothing to resolve an input against. On mainnet that is
 * not an edge case: block 3,314 spends a genesis AVVM output and every fresh archive sync
 * stopped dead there.
 *
 * <p>The fixtures use the genuine mainnet AVVM entry from that failure, so the address, the
 * transaction-hash convention and the outpoint are the real ones rather than a plausible shape.
 */
class ByronAddressResolutionTest {
    static { RocksDB.loadLibrary(); }

    private static final ArchiveNetworkIdentity NETWORK = new ArchiveNetworkIdentity(764824073, "5f20df93");
    /** What a wallet node carries: the one section that needs consumed addresses. */
    private static final Set<ProjectionSectionType> REQUIRED = Set.of(ProjectionSectionType.ADDRESS_TRANSACTION);
    private static final ProjectionIdentity IDENTITY = new ProjectionIdentity(NETWORK, "synthetic", 1, REQUIRED);

    /**
     * The mainnet genesis AVVM address behind outpoint
     * {@code a12a839c25a01fa5d118167db5acdbd9e38172ae8f00e5ac0a4997ef792a2007#0}, whose 1 ADA is
     * spent by mainnet block 3,314 — the block that broke the sync this test exists for.
     */
    private static final String GENESIS_AVVM_ADDRESS = "Ae2tdPwUPEZ3DdaWu8jn553npu6jwEPAJiahruj3xQjPXxgoxfYDWusJz7x";
    private static final String GENESIS_AVVM_TX =
            "a12a839c25a01fa5d118167db5acdbd9e38172ae8f00e5ac0a4997ef792a2007";
    private static final String BYRON_ADDRESS = "Ae2tdPwUPEZFRbyhz3cpfC2CumGzNkFBN2L42rcUc2yjQpEkxDbkPodpMAi";

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
        store = new ProjectionOutboxStore(db, handles.get(1), handles.get(2), handles.get(3),
                handles.get(4), handles.get(5));
        collector = new CanonicalProjectionCollector(store, IDENTITY, slot -> slot / 21600,
                slot -> 1_506_203_091L + slot * 20, ProjectionChunking.DEFAULT_CHUNK_BYTES, true,
                PointerCredentialSource.NONE, mainnetGenesisDistribution());
    }

    @AfterEach
    void tearDown() {
        handles.forEach(ColumnFamilyHandle::close);
        db.close();
        dbOptions.close();
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * The one genesis entry the failure named, normalised by the same code the live store and
     * the genesis bootstrap use — so the transaction hash under test is derived, not asserted.
     */
    private static GenesisUtxoProvider mainnetGenesisDistribution() {
        return (blockNumber, slot, blockHash) -> List.of(
                GenesisUtxos.byron(GENESIS_AVVM_ADDRESS, BigInteger.valueOf(1_000_000L),
                        blockNumber, slot, blockHash));
    }

    private static String hex(int b, int len) {
        return String.format("%02x", b).repeat(len);
    }

    private static ByronTx byronTx(String txHash, List<ByronTxIn> inputs, String outputAddress, long amount) {
        return ByronTx.builder().txHash(txHash).inputs(inputs)
                .outputs(List.of(ByronTxOut.builder()
                        .address(ByronAddress.builder().base58Raw(outputAddress).build())
                        .amount(BigInteger.valueOf(amount)).build()))
                .build();
    }

    private static ByronTxIn input(String txId, int index) {
        return ByronTxIn.builder().txId(txId).index(index).build();
    }

    private static ByronMainBlock byronBlock(long slot, List<ByronTx> transactions) {
        return ByronMainBlock.builder()
                .header(ByronBlockHead.builder()
                        .protocolMagic(764824073L).prevBlock(hex(0x0a, 32))
                        .consensusData(ByronBlockCons.builder()
                                .slotId(Epoch.builder().epoch(slot / 21600).slot(slot % 21600).build())
                                .difficulty(BigInteger.ONE).build())
                        .build())
                .body(ByronBlockBody.builder().txPayload(transactions.stream()
                        .map(tx -> ByronTxPayload.builder().transaction(tx).witnesses(List.of()).build())
                        .toList()).build())
                .build();
    }

    /** A Shelley address with distinct payment and stake parts, so it parses as one. */
    private static String shelleyAddress() {
        byte[] raw = new byte[57];
        raw[0] = 0;
        java.util.Arrays.fill(raw, 1, 29, (byte) 0x11);
        java.util.Arrays.fill(raw, 29, 57, (byte) 0x12);
        return HexUtil.encodeHexString(raw);
    }

    private static Block shelleyBlock(long blockNumber, long slot, String spentTx, int spentIndex) {
        var tx = TransactionBody.builder().txHash(hex(0x40, 32))
                .inputs(new LinkedHashSet<>(List.of(TransactionInput.builder()
                        .transactionId(spentTx).index(spentIndex).build())))
                .outputs(List.of(TransactionOutput.builder().address(shelleyAddress())
                        .amounts(List.of(Amount.builder().unit("lovelace")
                                .quantity(BigInteger.valueOf(900_000L)).build())).build()))
                .fee(BigInteger.valueOf(100_000L)).build();
        return Block.builder().era(Era.Babbage)
                .header(BlockHeader.builder().headerBody(HeaderBody.builder()
                        .blockNumber(blockNumber).slot(slot).prevHash(hex(0x0a, 32))
                        .blockHash(hex(0x0b, 32)).build()).build())
                .transactionBodies(List.of(tx)).transactionWitness(List.of())
                .invalidTransactions(List.of()).build();
    }

    // ---------------------------------------------------------------- driving

    private void contributeByron(long slot, long blockNumber, ByronMainBlock block) {
        commit(writer -> collector.contributeByronBlock(
                ByronBlockProjectionEvent.main(slot, blockNumber, hex(0x0b, 32), hex(0x0a, 32), block),
                writer));
    }

    /** Shelley+ contribution with an empty capture map: nothing was in {@code cfUnspent}. */
    private void contributeShelley(long blockNumber, long slot, Block block, Map<String, String> captured) {
        ConsumedOutputAddresses consumed = (txHash, index) -> captured.get(txHash + '#' + index);
        commit(writer -> collector.contributeBlock(
                new BlockAppliedEvent(Era.Babbage, slot, blockNumber, hex(0x0b, 32), block),
                consumed, writer));
    }

    private void commit(java.util.function.Consumer<com.bloxbean.cardano.yano.api.archive.ProjectionStagingWriter> work) {
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            work.accept(ProjectionOutboxStore.batchWriter(batch, store.handles()));
            db.write(options, batch);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private AddressParticipationFact participations(long blockNumber) {
        var envelope = store.readEnvelope(blockNumber, REQUIRED).orElseThrow();
        return ProjectionFactCodec.decodeAddressParticipations(ProjectionChunking.join(
                envelope.section(ProjectionSectionType.ADDRESS_TRANSACTION).orElseThrow().chunks()));
    }

    private static List<String> addressesInRole(AddressParticipationFact fact, AddressSubjectRows.Role role) {
        return fact.transactions().stream()
                .flatMap(tx -> tx.participations().stream())
                .filter(participation -> participation.role().equals(role.name()))
                .map(participation -> participation.participant().address())
                .toList();
    }

    // ----------------------------------------------------------------- the cases

    @Test
    void aByronTransactionSpendingAGenesisOutputResolvesItsFundingAddress() {
        // Mainnet block 3,314 in miniature: the first Byron spend on the chain is of a genesis
        // AVVM output, which no block created and the UTXO path therefore never captured.
        contributeByron(3313, 3314, byronBlock(3313,
                List.of(byronTx(hex(0x11, 32), List.of(input(GENESIS_AVVM_TX, 0)), BYRON_ADDRESS, 900_000))));

        assertThat(addressesInRole(participations(3314), AddressSubjectRows.Role.INPUT))
                .containsExactly(GENESIS_AVVM_ADDRESS);
    }

    @Test
    void theGenesisSeedIsDurableAndServesLaterBlocks() {
        // Seeding happens on the first Byron main block; a second, unrelated block must not
        // reseed and must still resolve a genesis output.
        contributeByron(3313, 3314, byronBlock(3313,
                List.of(byronTx(hex(0x11, 32), List.of(), BYRON_ADDRESS, 900_000))));
        contributeByron(3320, 3315, byronBlock(3320,
                List.of(byronTx(hex(0x12, 32), List.of(input(GENESIS_AVVM_TX, 0)), BYRON_ADDRESS, 800_000))));

        assertThat(addressesInRole(participations(3315), AddressSubjectRows.Role.INPUT))
                .containsExactly(GENESIS_AVVM_ADDRESS);
    }

    @Test
    void anOutputCreatedAndSpentInsideOneByronBlockResolves() {
        // The staged entry is invisible to a RocksDB point read until the batch commits, so
        // intra-block chaining is resolvable only if the block itself is consulted first.
        contributeByron(4000, 4001, byronBlock(4000, List.of(
                byronTx(hex(0x21, 32), List.of(input(GENESIS_AVVM_TX, 0)), BYRON_ADDRESS, 900_000),
                byronTx(hex(0x22, 32), List.of(input(hex(0x21, 32), 0)), GENESIS_AVVM_ADDRESS, 800_000))));

        assertThat(addressesInRole(participations(4001), AddressSubjectRows.Role.INPUT))
                .containsExactly(GENESIS_AVVM_ADDRESS, BYRON_ADDRESS);
    }

    @Test
    void aShelleyTransactionSpendingAByronEraOutputResolvesThroughTheArchiveResolver() {
        // The Byron→Shelley cliff. A Byron block created this output, so it is in no live column
        // family; years later a Shelley transaction spends it and apply captures nothing. Without
        // the resolver being consulted in every era, mainnet would fail at the era boundary
        // instead of at block 3,314 — later, and no less fatally.
        contributeByron(4000, 4001, byronBlock(4000,
                List.of(byronTx(hex(0x21, 32), List.of(input(GENESIS_AVVM_TX, 0)), BYRON_ADDRESS, 900_000))));

        contributeShelley(4_500_000, 4_500_000, shelleyBlock(4_500_000, 4_500_000, hex(0x21, 32), 0),
                Map.of());

        assertThat(addressesInRole(participations(4_500_000), AddressSubjectRows.Role.INPUT))
                .containsExactly(BYRON_ADDRESS);
    }

    @Test
    void captureStillWinsOverTheResolverWhenApplyHadTheOutput() {
        contributeByron(4000, 4001, byronBlock(4000,
                List.of(byronTx(hex(0x21, 32), List.of(input(GENESIS_AVVM_TX, 0)), BYRON_ADDRESS, 900_000))));

        // A Shelley-era spend of a Shelley-era output: apply captured it, and the resolver knows
        // nothing about it. The captured address is what must appear.
        contributeShelley(4_500_000, 4_500_000, shelleyBlock(4_500_000, 4_500_000, hex(0x55, 32), 0),
                Map.of(hex(0x55, 32) + "#0", shelleyAddress()));

        assertThat(addressesInRole(participations(4_500_000), AddressSubjectRows.Role.INPUT))
                .hasSize(1);
    }

    @Test
    void anOutpointNeitherCapturedNorRecordedStillFailsClosed() {
        // The resolver widens what can be answered; it must not turn an unanswerable input into
        // silently missing address history.
        assertThatThrownBy(() -> contributeByron(4000, 4001, byronBlock(4000,
                List.of(byronTx(hex(0x21, 32), List.of(input(hex(0x77, 32), 3)), BYRON_ADDRESS, 900_000)))))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("could not resolve consumed output");
    }

    @Test
    void aNodeWithoutTheAddressSectionNeitherSeedsNorRecords() {
        // The index is contributor state for one section. A node that does not carry that
        // section must not pay for a Byron UTXO index it will never read.
        var identity = new ProjectionIdentity(NETWORK, "synthetic", 1,
                Set.of(ProjectionSectionType.TRANSACTION));
        var lean = new CanonicalProjectionCollector(store, identity, slot -> slot / 21600,
                slot -> 1_506_203_091L + slot * 20, ProjectionChunking.DEFAULT_CHUNK_BYTES, true,
                PointerCredentialSource.NONE, mainnetGenesisDistribution());

        commit(writer -> lean.contributeByronBlock(ByronBlockProjectionEvent.main(4000, 4001,
                hex(0x0b, 32), hex(0x0a, 32), byronBlock(4000, List.of(
                        byronTx(hex(0x21, 32), List.of(input(GENESIS_AVVM_TX, 0)), BYRON_ADDRESS, 900_000)))),
                writer));

        var index = store.byronOutputIndex();
        assertThat(index.genesisSeeded()).isFalse();
        assertThat(index.addressOf(hex(0x21, 32), 0)).isNull();
    }

    @Test
    void anEpochBoundaryBlockDoesNotStealThePrecedingMainBlocksCoordinate() {
        // Real Byron numbering, which the synthetic fixtures elsewhere do not reproduce: an EBB's
        // block number is its chain difficulty, and an EBB does not advance difficulty. Mainnet's
        // epoch-1 EBB at slot 21,600 therefore reports block 21,586 - the number the last main
        // block of epoch 0 already owns. Letting it claim that coordinate replaced a real block's
        // identity with an empty one and orphaned its sections.
        contributeByron(21599, 21586, byronBlock(21599,
                List.of(byronTx(hex(0x31, 32), List.of(input(GENESIS_AVVM_TX, 0)), BYRON_ADDRESS, 700_000))));

        commit(writer -> collector.contributeByronBlock(ByronBlockProjectionEvent.epochBoundary(
                21600, 21586, hex(0x0c, 32), hex(0x0b, 32),
                ByronEbBlock.builder().header(ByronEbHead.builder().protocolMagic(764824073L)
                        .prevBlock(hex(0x0b, 32))
                        .consensusData(ByronEbBlockCons.builder().epoch(1)
                                .difficulty(BigInteger.valueOf(21586)).build())
                        .build()).build()),
                writer));

        // The main block's envelope survives, with its section intact and its own kind.
        var envelope = store.readEnvelope(21586, REQUIRED).orElseThrow();
        assertThat(envelope.header().blockKind()).isEqualTo(ProjectionBlockKind.BYRON_MAIN);
        assertThat(addressesInRole(participations(21586), AddressSubjectRows.Role.INPUT))
                .containsExactly(GENESIS_AVVM_ADDRESS);
    }

    @Test
    void theChainsFirstEpochBoundaryBlockStillGetsItsEnvelope() {
        // Block 0 is the genesis EBB and no main block claims that number. The drain begins at
        // block 0, so skipping this one would fail the first batch's contiguity check and the
        // archive would never start.
        commit(writer -> collector.contributeByronBlock(ByronBlockProjectionEvent.epochBoundary(
                0, 0, hex(0x0c, 32), hex(0x00, 32),
                ByronEbBlock.builder().header(ByronEbHead.builder().protocolMagic(764824073L)
                        .prevBlock(hex(0x00, 32))
                        .consensusData(ByronEbBlockCons.builder().epoch(0)
                                .difficulty(BigInteger.ZERO).build())
                        .build()).build()),
                writer));

        var envelope = store.readEnvelope(0, REQUIRED).orElseThrow();
        assertThat(envelope.header().blockKind()).isEqualTo(ProjectionBlockKind.BYRON_EBB);
        assertThat(envelope.sections()).isEmpty();
        assertThat(store.completeThrough(REQUIRED)).isZero();
    }

    @Test
    void theRecordedOutpointIsExactlyTheOneMainnetCouldNotResolve() {
        // A guard on the transaction-hash convention itself: blake2b-256 over the decoded base58
        // address. If that ever drifts, every genesis-funded input becomes unresolvable again.
        GenesisUtxo utxo = mainnetGenesisDistribution().genesisUtxos(0, 0, "00".repeat(32)).get(0);
        assertThat(utxo.txHash()).isEqualTo(GENESIS_AVVM_TX);
        assertThat(utxo.outputIndex()).isZero();
        assertThat(utxo.isByron()).isTrue();
    }
}
