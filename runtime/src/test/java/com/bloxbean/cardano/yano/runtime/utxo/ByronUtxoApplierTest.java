package com.bloxbean.cardano.yano.runtime.utxo;

import com.bloxbean.cardano.yaci.core.model.byron.ByronAddress;
import com.bloxbean.cardano.yaci.core.model.byron.ByronBlockBody;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronTx;
import com.bloxbean.cardano.yaci.core.model.byron.ByronTxIn;
import com.bloxbean.cardano.yaci.core.model.byron.ByronTxOut;
import com.bloxbean.cardano.yaci.core.model.byron.payload.ByronTxPayload;
import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionInput;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yano.api.events.ByronMainBlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.archive.CanonicalProjectionContributor;
import com.bloxbean.cardano.yano.api.archive.ConsumedOutputAddresses;
import com.bloxbean.cardano.yano.api.archive.ProjectionCfNames;
import com.bloxbean.cardano.yano.api.archive.ProjectionStagingWriter;
import com.bloxbean.cardano.yano.api.events.ByronBlockProjectionEvent;
import com.bloxbean.cardano.yano.api.plugin.StorageFilter;
import com.bloxbean.cardano.yano.api.plugin.UtxoFilterContext;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.runtime.chain.DirectRocksDBChainState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import com.bloxbean.cardano.yano.runtime.db.UtxoCfNames;
import org.rocksdb.ColumnFamilyHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ByronUtxoApplierTest {
    private static final String GENESIS_AVVM_ADDRESS =
            "Ae2tdPwUPEZ3DdaWu8jn553npu6jwEPAJiahruj3xQjPXxgoxfYDWusJz7x";
    private static final String GENESIS_AVVM_TX =
            "a12a839c25a01fa5d118167db5acdbd9e38172ae8f00e5ac0a4997ef792a2007";
    private static final String BYRON_ADDRESS =
            "Ae2tdPwUPEZFRbyhz3cpfC2CumGzNkFBN2L42rcUc2yjQpEkxDbkPodpMAi";

    @TempDir Path directory;

    private DirectRocksDBChainState chain;
    private DefaultUtxoStore store;

    @BeforeEach
    void setUp() {
        chain = new DirectRocksDBChainState(directory.resolve("db").toString());
        store = new DefaultUtxoStore(chain, LoggerFactory.getLogger(getClass()), Map.of(
                "yano.utxo.enabled", true,
                "yano.metrics.enabled", false));
    }

    @AfterEach
    void tearDown() {
        store.close();
        chain.close();
    }

    @Test
    void mainnetBlock3314SpendRemovesGenesisOutputAndCreatesByronOutput() {
        seedMainnetAvvmOutput();
        String txHash = hex(0x11);
        ByronMainBlock block = block(List.of(tx(txHash,
                List.of(ByronTxIn.builder().txId(GENESIS_AVVM_TX).index(0).build()),
                BYRON_ADDRESS, 900_000L)));

        store.applyByronBlock(event(3313L, 3314L, hex(0x33), block));

        assertThat(store.getUtxo(new Outpoint(GENESIS_AVVM_TX, 0))).isEmpty();
        assertThat(store.getUtxo(new Outpoint(txHash, 0))).get()
                .extracting(utxo -> utxo.address(), utxo -> utxo.lovelace())
                .containsExactly(BYRON_ADDRESS, BigInteger.valueOf(900_000L));
        assertThat(store.getUtxosByAddress(GENESIS_AVVM_ADDRESS, 1, 10)).isEmpty();
        assertThat(store.getLastAppliedBlock()).isEqualTo(3314L);
        assertThat(store.getLatestAppliedSlot()).isEqualTo(3313L);
        UtxoDeltaCodec.Decoded delta = readDelta(3314L);
        assertThat(delta.blockHash()).isEqualTo(hex(0x33));
        assertThat(delta.created()).containsExactly(new UtxoDeltaCodec.OutRef(txHash, 0));
        assertThat(delta.spent()).containsExactly(new UtxoDeltaCodec.OutRef(GENESIS_AVVM_TX, 0));

        store.rollbackToPoint(Point.ORIGIN);
        assertThat(store.getUtxo(new Outpoint(GENESIS_AVVM_TX, 0))).isPresent();
        assertThat(store.getUtxo(new Outpoint(txHash, 0))).isEmpty();
    }

    @Test
    void resolvesOutputCreatedAndSpentInsideSameByronBlock() {
        seedMainnetAvvmOutput();
        String firstHash = hex(0x21);
        String secondHash = hex(0x22);
        ByronMainBlock block = block(List.of(
                tx(firstHash, List.of(input(GENESIS_AVVM_TX)), BYRON_ADDRESS, 900_000L),
                tx(secondHash, List.of(input(firstHash)), GENESIS_AVVM_ADDRESS, 800_000L)));

        store.applyByronBlock(event(4000L, 4001L, hex(0x40), block));

        assertThat(store.getUtxo(new Outpoint(firstHash, 0))).isEmpty();
        assertThat(store.getUtxo(new Outpoint(secondHash, 0))).isPresent();

        store.rollbackToPoint(Point.ORIGIN);
        assertThat(store.getUtxo(new Outpoint(GENESIS_AVVM_TX, 0))).isPresent();
        assertThat(store.getUtxo(new Outpoint(firstHash, 0))).isEmpty();
        assertThat(store.getUtxo(new Outpoint(secondHash, 0))).isEmpty();
    }

    @Test
    void projectionAndByronUtxoCommitInOneBatchWithConsumedAddresses() throws Exception {
        seedMainnetAvvmOutput();
        String firstHash = hex(0x71);
        String secondHash = hex(0x72);
        AtomicReference<String> genesisAddress = new AtomicReference<>();
        AtomicReference<String> intraBlockAddress = new AtomicReference<>();
        byte[] markerKey = "adr042.atomic".getBytes(StandardCharsets.UTF_8);
        store.setProjectionContributor(new TestContributor() {
            @Override
            public boolean needsConsumedOutputAddresses() { return true; }

            @Override
            public void contributeByronMainBlock(ByronMainBlockAppliedEvent event,
                                                 ConsumedOutputAddresses consumed,
                                                 ProjectionStagingWriter writer) {
                genesisAddress.set(consumed.addressOf(GENESIS_AVVM_TX, 0));
                intraBlockAddress.set(consumed.addressOf(firstHash, 0));
                writer.put(ProjectionCfNames.PROJ_META, markerKey, new byte[]{1});
            }
        });

        store.applyByronBlock(event(60L, 6L, hex(0x73), block(List.of(
                tx(firstHash, List.of(input(GENESIS_AVVM_TX)), BYRON_ADDRESS, 900_000L),
                tx(secondHash, List.of(input(firstHash)), GENESIS_AVVM_ADDRESS, 800_000L)))));

        assertThat(genesisAddress.get()).isEqualTo(GENESIS_AVVM_ADDRESS);
        assertThat(intraBlockAddress.get()).isEqualTo(BYRON_ADDRESS);
        ColumnFamilyHandle projectionMeta = (ColumnFamilyHandle) chain.getColumnFamilyHandle(
                ProjectionCfNames.PROJ_META);
        assertThat(store.getDb().get(projectionMeta, markerKey)).containsExactly(1);
        assertThat(store.getUtxo(new Outpoint(secondHash, 0))).isPresent();
    }

    @Test
    void projectionFailureDiscardsProjectionWritesButCommitsByronUtxo() throws Exception {
        seedMainnetAvvmOutput();
        byte[] markerKey = "adr042.must-not-commit".getBytes(StandardCharsets.UTF_8);
        byte[] failureKey = "adr042.failure-must-commit".getBytes(StandardCharsets.UTF_8);
        AtomicReference<RuntimeException> reported = new AtomicReference<>();
        store.setProjectionContributor(new TestContributor() {
            @Override
            public void contributeByronMainBlock(ByronMainBlockAppliedEvent event,
                                                 ConsumedOutputAddresses consumed,
                                                 ProjectionStagingWriter writer) {
                writer.put(ProjectionCfNames.PROJ_META, markerKey, new byte[]{1});
                throw new IllegalStateException("synthetic projection failure");
            }

            @Override
            public void contributionFailed(long blockNumber, ProjectionStagingWriter writer,
                                           RuntimeException failure) {
                assertThat(blockNumber).isEqualTo(7L);
                writer.put(ProjectionCfNames.PROJ_META, failureKey, new byte[]{2});
                reported.set(failure);
            }
        });
        String txHash = hex(0x74);

        store.applyByronBlock(event(61L, 7L, hex(0x75), block(List.of(
                tx(txHash, List.of(input(GENESIS_AVVM_TX)), BYRON_ADDRESS, 900_000L)))));

        ColumnFamilyHandle projectionMeta = (ColumnFamilyHandle) chain.getColumnFamilyHandle(
                ProjectionCfNames.PROJ_META);
        assertThat(store.getDb().get(projectionMeta, markerKey)).isNull();
        assertThat(store.getDb().get(projectionMeta, failureKey)).containsExactly(2);
        assertThat(store.getUtxo(new Outpoint(GENESIS_AVVM_TX, 0))).isEmpty();
        assertThat(store.getUtxo(new Outpoint(txHash, 0))).isPresent();
        assertThat(store.getLastAppliedBlock()).isEqualTo(7L);
        assertThat(reported.get()).hasMessage("synthetic projection failure");
    }

    @Test
    void duplicateCommittedInputFailsBeforeBatchCommit() {
        seedMainnetAvvmOutput();
        ByronMainBlock block = block(List.of(
                tx(hex(0x25), List.of(input(GENESIS_AVVM_TX)), BYRON_ADDRESS, 600_000L),
                tx(hex(0x26), List.of(input(GENESIS_AVVM_TX)), BYRON_ADDRESS, 500_000L)));

        assertThatThrownBy(() -> store.applyByronBlock(event(5L, 1L, hex(0x27), block)))
                .hasRootCauseMessage("Duplicate Byron input " + GENESIS_AVVM_TX + ":0 at block 1");

        assertThat(store.getUtxo(new Outpoint(GENESIS_AVVM_TX, 0))).isPresent();
        assertThat(store.getUtxo(new Outpoint(hex(0x25), 0))).isEmpty();
        assertThat(store.getLastAppliedBlock()).isZero();
    }

    @Test
    void snapshotRestoreRecreatesByronApplierWithReopenedHandles() {
        seedMainnetAvvmOutput();
        Path snapshot = directory.resolve("snapshot");
        chain.createSnapshot(snapshot.toString());
        store.applyByronBlock(event(6L, 1L, hex(0x28), block(List.of(
                tx(hex(0x29), List.of(input(GENESIS_AVVM_TX)), BYRON_ADDRESS, 900_000L)))));

        chain.restoreFromSnapshot(snapshot.toString());
        store.reinitialize();
        String restoredTx = hex(0x2a);
        store.applyByronBlock(event(6L, 1L, hex(0x2b), block(List.of(
                tx(restoredTx, List.of(input(GENESIS_AVVM_TX)), BYRON_ADDRESS, 800_000L)))));

        assertThat(store.getUtxo(new Outpoint(GENESIS_AVVM_TX, 0))).isEmpty();
        assertThat(store.getUtxo(new Outpoint(restoredTx, 0))).isPresent();
        assertThat(store.getLatestAppliedPoint().blockHash()).isEqualTo(hex(0x2b));
    }

    @Test
    void unresolvedInputFailsClosedWithoutCommittingPartialOutputOrCursor() {
        String txHash = hex(0x31);
        ByronMainBlock block = block(List.of(tx(txHash, List.of(input(hex(0x30))),
                BYRON_ADDRESS, 1_000_000L)));

        assertThatThrownBy(() -> store.applyByronBlock(event(10L, 1L, hex(0x32), block)))
                .hasMessageContaining("Byron UTXO apply failed")
                .hasRootCauseMessage("Unresolved Byron input " + hex(0x30) + ":0 at block 1 (slot 10)");

        assertThat(store.getUtxo(new Outpoint(txHash, 0))).isEmpty();
        assertThat(store.getLastAppliedBlock()).isZero();
        assertThat(store.getByronUnresolvedInputCount()).isEqualTo(1L);
    }

    @Test
    void configuredByronFilterMayOmitOutputsAndTheirLaterInputs() {
        StorageFilter rejectAllByron = new StorageFilter() {
            @Override
            public boolean acceptByronUtxoOutput(
                    UtxoFilterContext ctx, ByronMainBlock block, ByronTx tx) {
                return false;
            }
        };
        store.setFilterChain(new StorageFilterChain(List.of(rejectAllByron)));
        String firstHash = hex(0x41);
        String secondHash = hex(0x42);
        ByronMainBlock block = block(List.of(
                tx(firstHash, List.of(), BYRON_ADDRESS, 1_000_000L),
                tx(secondHash, List.of(input(firstHash)), GENESIS_AVVM_ADDRESS, 900_000L)));

        store.applyByronBlock(event(20L, 2L, hex(0x43), block));

        assertThat(store.getUtxo(new Outpoint(firstHash, 0))).isEmpty();
        assertThat(store.getUtxo(new Outpoint(secondHash, 0))).isEmpty();
        assertThat(store.getLastAppliedBlock()).isEqualTo(2L);
        assertThat(store.getByronFilteredStoreUnresolvedInputCount()).isEqualTo(1L);
    }

    @Test
    void exactRollbackDistinguishesSameSlotMainBlockFromPrecedingEbbPoint() {
        seedMainnetAvvmOutput();
        String txHash = hex(0x51);
        String mainHash = hex(0x52);
        store.applyByronBlock(event(30L, 3L, mainHash, block(List.of(
                tx(txHash, List.of(input(GENESIS_AVVM_TX)), BYRON_ADDRESS, 900_000L)))));

        store.rollbackToPoint(new Point(30L, mainHash));
        assertThat(store.getUtxo(new Outpoint(txHash, 0))).isPresent();

        store.rollbackToPoint(new Point(30L, hex(0x53)));
        assertThat(store.getUtxo(new Outpoint(txHash, 0))).isEmpty();
        assertThat(store.getUtxo(new Outpoint(GENESIS_AVVM_TX, 0))).isPresent();
        assertThat(store.getLastAppliedBlock()).isZero();
    }

    @Test
    void rollbackAcrossByronShelleyBoundaryRestoresByronCursorAndOutput() {
        seedMainnetAvvmOutput();
        String byronTx = hex(0x61);
        String byronHash = hex(0x62);
        store.applyByronBlock(event(40L, 4L, byronHash, block(List.of(
                tx(byronTx, List.of(input(GENESIS_AVVM_TX)), BYRON_ADDRESS, 900_000L)))));

        String shelleyTx = hex(0x63);
        TransactionBody transaction = TransactionBody.builder()
                .txHash(shelleyTx)
                .inputs(java.util.Set.of(TransactionInput.builder()
                        .transactionId(byronTx).index(0).build()))
                .outputs(List.of(TransactionOutput.builder()
                        .address(UtxoTestAddresses.enterprise(1))
                        .amounts(List.of(Amount.builder().unit("lovelace")
                                .quantity(BigInteger.valueOf(800_000L)).build()))
                        .build()))
                .build();
        Block shelley = Block.builder().era(Era.Shelley)
                .transactionBodies(List.of(transaction)).invalidTransactions(List.of()).build();
        store.applyBlock(new BlockAppliedEvent(Era.Shelley, 50L, 5L, hex(0x64), shelley));
        assertThat(store.getUtxo(new Outpoint(byronTx, 0))).isEmpty();

        store.rollbackToPoint(new Point(40L, byronHash));

        assertThat(store.getUtxo(new Outpoint(byronTx, 0))).isPresent();
        assertThat(store.getUtxo(new Outpoint(shelleyTx, 0))).isEmpty();
        assertThat(store.getLatestAppliedPoint().slot()).isEqualTo(40L);
        assertThat(store.getLatestAppliedPoint().blockHash()).isEqualTo(byronHash);
    }

    @Test
    void shelleyProjectionCapturesAddressOfNativeByronOutputWithoutArchiveResolver() {
        String byronTx = hex(0x76);
        store.applyByronBlock(event(70L, 8L, hex(0x77), block(List.of(
                tx(byronTx, List.of(), BYRON_ADDRESS, 900_000L)))));
        AtomicReference<String> consumedAddress = new AtomicReference<>();
        store.setProjectionContributor(new TestContributor() {
            @Override public boolean needsConsumedOutputAddresses() { return true; }

            @Override
            public void contributeBlock(BlockAppliedEvent event, ConsumedOutputAddresses consumed,
                                        ProjectionStagingWriter writer) {
                consumedAddress.set(consumed.addressOf(byronTx, 0));
            }
        });
        String shelleyTx = hex(0x78);
        TransactionBody transaction = TransactionBody.builder().txHash(shelleyTx)
                .inputs(java.util.Set.of(TransactionInput.builder()
                        .transactionId(byronTx).index(0).build()))
                .outputs(List.of()).build();
        Block shelley = Block.builder().era(Era.Shelley)
                .transactionBodies(List.of(transaction)).invalidTransactions(List.of()).build();

        store.applyBlock(new BlockAppliedEvent(Era.Shelley, 71L, 9L, hex(0x79), shelley));

        assertThat(consumedAddress.get()).isEqualTo(BYRON_ADDRESS);
    }

    private void seedMainnetAvvmOutput() {
        store.storeByronGenesisUtxos(
                Map.of(GENESIS_AVVM_ADDRESS, BigInteger.valueOf(1_000_000L)),
                0L, 0L, hex(0));
        assertThat(UtxoKeyUtil.txHashFromOutpointKey(store.getByronGenesisOutpointKeys().getFirst()))
                .isEqualTo(GENESIS_AVVM_TX);
    }

    private UtxoDeltaCodec.Decoded readDelta(long blockNumber) {
        try {
            ColumnFamilyHandle delta = (ColumnFamilyHandle) chain.getColumnFamilyHandle(
                    UtxoCfNames.UTXO_BLOCK_DELTA);
            byte[] key = ByteBuffer.allocate(Long.BYTES).order(ByteOrder.BIG_ENDIAN)
                    .putLong(blockNumber).array();
            return UtxoDeltaCodec.decode(store.getDb().get(delta, key));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static ByronMainBlockAppliedEvent event(
            long slot, long blockNumber, String blockHash, ByronMainBlock block) {
        return new ByronMainBlockAppliedEvent(slot, blockNumber, blockHash, block);
    }

    private static ByronMainBlock block(List<ByronTx> transactions) {
        return ByronMainBlock.builder()
                .body(ByronBlockBody.builder()
                        .txPayload(transactions.stream()
                                .map(tx -> ByronTxPayload.builder().transaction(tx).witnesses(List.of()).build())
                                .toList())
                        .build())
                .build();
    }

    private static ByronTx tx(String txHash, List<ByronTxIn> inputs, String address, long amount) {
        return ByronTx.builder().txHash(txHash).inputs(inputs)
                .outputs(List.of(ByronTxOut.builder()
                        .address(ByronAddress.builder().base58Raw(address).build())
                        .amount(BigInteger.valueOf(amount)).build()))
                .build();
    }

    private static ByronTxIn input(String txHash) {
        return ByronTxIn.builder().txId(txHash).index(0).build();
    }

    private static String hex(int value) {
        return String.format("%02x", value).repeat(32);
    }

    private abstract static class TestContributor implements CanonicalProjectionContributor {
        @Override public boolean enabled() { return true; }
        @Override public void contributeBlock(BlockAppliedEvent event, ProjectionStagingWriter writer) { }
        @Override public void contributeByronBlock(ByronBlockProjectionEvent event,
                                                   ProjectionStagingWriter writer) { }
        @Override public void rollbackFrom(long fromBlockNumber) { }
    }
}
