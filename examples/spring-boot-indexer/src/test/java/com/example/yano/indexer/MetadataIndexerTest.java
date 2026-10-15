package com.example.yano.indexer;

import com.bloxbean.cardano.yaci.core.model.AuxData;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;
import org.yanoproject.api.CanonicalBlockReference;
import org.yanoproject.api.ChainQuery;
import org.yanoproject.api.events.BlockAppliedEvent;
import org.yanoproject.api.events.RollbackEvent;
import org.yanoproject.runtime.assembly.Yano;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetadataIndexerTest {

    /** Smallest stored body Yano recognises as a Byron block. */
    private static final byte[] BYRON_BODY = {(byte) 0x82, 0x01, (byte) 0x80};

    private EmbeddedDatabase db;
    private MetadataStore store;
    private MetadataIndexer indexer;

    @BeforeEach
    void setUp() {
        db = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).generateUniqueName(true)
                .addScript("schema.sql").build();
        store = new MetadataStore(new JdbcTemplate(db), new TransactionTemplate(new DataSourceTransactionManager(db)));
        indexer = new MetadataIndexer(store, JsonMapper.builder().build(), 674);
    }

    @AfterEach
    void tearDown() {
        db.shutdown();
    }

    @Test
    void indexesOnlyValidTransactionsWithTheLabel() {
        Block block = Block.builder()
                .era(Era.Conway)
                .transactionBodies(List.of(tx("aa"), tx("bb"), tx("cc")))
                .auxiliaryDataMap(Map.of(
                        0, aux("{\"674\":{\"msg\":[\"hello\"]}}"),
                        1, aux("{\"721\":{}}"),
                        2, aux("{\"674\":{\"msg\":[\"failed script\"]}}")))
                .invalidTransactions(List.of(2))
                .build();

        indexer.onBlockApplied(new BlockAppliedEvent(Era.Conway, 100, 10, hash(10), block));

        assertThat(store.latest(10)).containsExactly(
                new MetadataStore.Entry("aa", 674, "{\"msg\":[\"hello\"]}", 10, 100));
        assertThat(store.lastBlock()).contains(new MetadataStore.IndexedBlock(10, 100, hash(10)));
    }

    @Test
    void replayingABlockDoesNotDuplicateRows() {
        BlockAppliedEvent event = new BlockAppliedEvent(Era.Conway, 100, 10, hash(10), blockWithMessage("aa"));

        indexer.onBlockApplied(event);
        indexer.onBlockApplied(event);

        assertThat(store.latest(10)).hasSize(1);
    }

    @Test
    void rollbackRemovesEverythingAfterTheTarget() {
        indexer.onBlockApplied(new BlockAppliedEvent(Era.Conway, 100, 10, hash(10), blockWithMessage("aa")));
        indexer.onBlockApplied(new BlockAppliedEvent(Era.Conway, 120, 11, hash(11), blockWithMessage("bb")));

        indexer.onRollback(new RollbackEvent(new Point(100, hash(10)), true));

        assertThat(store.latest(10)).extracting(MetadataStore.Entry::txHash).containsExactly("aa");
        assertThat(store.lastBlock()).map(MetadataStore.IndexedBlock::blockNumber).contains(10L);
    }

    @Test
    void storeFailureReachesYanoSoTheBlockIsNotApplied() {
        db.shutdown();

        assertThatThrownBy(() -> indexer.onBlockApplied(
                new BlockAppliedEvent(Era.Conway, 100, 10, hash(10), blockWithMessage("aa"))))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void byronBlocksAreNotIndexed() {
        indexer.onBlockApplied(new BlockAppliedEvent(Era.Byron, 10, 1, hash(1), null));

        assertThat(store.lastBlock()).isEmpty();
    }

    @Test
    void catchUpSkipsByronIncludingTheUnnumberedGenesisBoundaryBlock() {
        ChainQuery chain = chain(2);
        stubByron(chain, 1, 2);

        lifecycle().catchUp(chain);

        assertThat(store.lastBlock()).isEmpty();
    }

    @Test
    void catchUpDropsBlocksYanoNoLongerHas() {
        for (long n = 1; n <= 3; n++) {
            indexer.index(conway(n, hash(n)));
        }
        ChainQuery chain = chain(2);
        stubCanonical(chain, 1, hash(1));
        stubCanonical(chain, 2, hash(2));

        lifecycle().catchUp(chain);

        assertThat(store.lastBlock()).map(MetadataStore.IndexedBlock::blockNumber).contains(2L);
    }

    @Test
    void catchUpWalksBackPastAForkAtTheSameHeight() {
        for (long n = 1; n <= 3; n++) {
            indexer.index(conway(n, hash(n)));
        }
        // Yano switched to another block 3 while the indexer was down.
        ChainQuery chain = chain(3);
        stubCanonical(chain, 1, hash(1));
        stubCanonical(chain, 2, hash(2));
        stubCanonical(chain, 3, hash(99));
        stubByron(chain, 3);

        lifecycle().catchUp(chain);

        assertThat(store.lastBlock()).map(MetadataStore.IndexedBlock::blockNumber).contains(2L);
    }

    @Test
    void catchUpFailsWhenABodyIsMissing() {
        ChainQuery chain = chain(1);

        assertThatThrownBy(() -> lifecycle().catchUp(chain))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no body for block 1");
    }

    private YanoNodeLifecycle lifecycle() {
        return new YanoNodeLifecycle(mock(Yano.class), indexer, store);
    }

    private static ChainQuery chain(long tipBlock) {
        ChainQuery chain = mock(ChainQuery.class);
        when(chain.getLocalTip()).thenReturn(new ChainTip(tipBlock * 10, HexUtil.decodeHexString(hash(tipBlock)),
                tipBlock));
        return chain;
    }

    private static void stubCanonical(ChainQuery chain, long number, String hash) {
        when(chain.getCanonicalBlockReference(number)).thenReturn(Optional.of(
                new CanonicalBlockReference(number, number * 10, HexUtil.decodeHexString(hash))));
    }

    private static void stubByron(ChainQuery chain, long... numbers) {
        for (long n : numbers) {
            when(chain.getBlockByNumber(n)).thenReturn(BYRON_BODY);
        }
    }

    private BlockAppliedEvent conway(long number, String hash) {
        return new BlockAppliedEvent(Era.Conway, number * 10, number, hash, blockWithMessage("tx" + number));
    }

    private static Block blockWithMessage(String txHash) {
        return Block.builder().era(Era.Conway).transactionBodies(List.of(tx(txHash)))
                .auxiliaryDataMap(Map.of(0, aux("{\"674\":{\"msg\":[\"hi\"]}}"))).build();
    }

    private static TransactionBody tx(String hash) {
        return TransactionBody.builder().txHash(hash).build();
    }

    private static AuxData aux(String metadataJson) {
        return AuxData.builder().metadataJson(metadataJson).build();
    }

    private static String hash(long n) {
        return "%064x".formatted(n);
    }
}
