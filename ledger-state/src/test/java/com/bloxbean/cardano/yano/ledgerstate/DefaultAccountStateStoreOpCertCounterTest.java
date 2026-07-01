package com.bloxbean.cardano.yano.ledgerstate;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.OperationalCert;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.ChainBlockReader;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.RollbackEvent;
import com.bloxbean.cardano.yano.ledgerstate.test.TestRocksDBHelper;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAccountStateStoreOpCertCounterTest {
    private static final String ISSUER_VKEY = "11".repeat(32);
    private static final String ISSUER_HASH = HexUtil.encodeHexString(
            Blake2bUtil.blake2bHash224(HexUtil.decodeHexString(ISSUER_VKEY)));

    @TempDir
    Path tempDir;

    @Test
    void canonicalBlockApplyStoresLatestOpCertCounter() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var store = new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(DefaultAccountStateStoreOpCertCounterTest.class),
                    true);

            store.applyBlock(appliedBlock(10, 100, 3, "aa".repeat(32)));
            store.applyBlock(appliedBlock(11, 120, 4, "bb".repeat(32)));

            assertThat(store.getOpCertCounter(ISSUER_HASH)).hasValue(4);
            assertThat(store.getOpCertCounterState(ISSUER_HASH)).hasValueSatisfying(state -> {
                assertThat(state.counter()).isEqualTo(4);
                assertThat(state.lastUpdatedSlot()).isEqualTo(120);
                assertThat(state.lastUpdatedBlockNumber()).isEqualTo(11);
                assertThat(state.lastUpdatedBlockHash()).isEqualTo("bb".repeat(32));
            });
        }
    }

    @Test
    void rollbackRestoresPreviousOpCertCounter() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var store = new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(DefaultAccountStateStoreOpCertCounterTest.class),
                    true);

            store.applyBlock(appliedBlock(10, 100, 3, "aa".repeat(32)));
            store.applyBlock(appliedBlock(11, 120, 4, "bb".repeat(32)));

            store.rollbackTo(new RollbackEvent(new Point(100, "aa".repeat(32)), true));

            assertThat(store.getOpCertCounter(ISSUER_HASH)).hasValue(3);
            assertThat(store.getOpCertCounterState(ISSUER_HASH)).hasValueSatisfying(state -> {
                assertThat(state.lastUpdatedSlot()).isEqualTo(100);
                assertThat(state.lastUpdatedBlockNumber()).isEqualTo(10);
            });
        }
    }

    @Test
    void rollbackBeforeFirstCounterRemovesOpCertCounter() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var store = new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(DefaultAccountStateStoreOpCertCounterTest.class),
                    true);

            store.applyBlock(appliedBlock(10, 100, 3, "aa".repeat(32)));

            store.rollbackTo(new RollbackEvent(new Point(90, "00".repeat(32)), true));

            assertThat(store.getOpCertCounter(ISSUER_HASH)).isEmpty();
        }
    }

    @Test
    void persistedCounterSurvivesStoreRestart() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var store = new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(DefaultAccountStateStoreOpCertCounterTest.class),
                    true);
            store.applyBlock(appliedBlock(10, 100, 3, "aa".repeat(32)));
        }

        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var restarted = new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(DefaultAccountStateStoreOpCertCounterTest.class),
                    true);

            assertThat(restarted.getOpCertCounter(ISSUER_HASH)).hasValue(3);
        }
    }

    @Test
    void reconcileReplaysStoredBlocksIntoOpCertCounterState() throws Exception {
        String blockHash = "aa".repeat(32);
        byte[] blockBytes = storedConwayBlock(1, 100, 3);
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var store = new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(DefaultAccountStateStoreOpCertCounterTest.class),
                    true);

            store.reconcile(new ChainBlockReader() {
                @Override
                public ChainTip getLocalTip() {
                    return new ChainTip(100, HexUtil.decodeHexString(blockHash), 1);
                }

                @Override
                public byte[] getBlockByNumber(long blockNumber) {
                    return blockNumber == 1 ? blockBytes : null;
                }

                @Override
                public Era getBlockEra(long blockNumber) {
                    return Era.Conway;
                }
            });

            assertThat(store.getOpCertCounter(ISSUER_HASH)).hasValue(3);
        }
    }

    private static BlockAppliedEvent appliedBlock(long blockNumber, long slot, int opcertCounter, String blockHash) {
        HeaderBody body = HeaderBody.builder()
                .blockNumber(blockNumber)
                .slot(slot)
                .issuerVkey(ISSUER_VKEY)
                .operationalCert(OperationalCert.builder()
                        .sequenceNumber(opcertCounter)
                        .build())
                .build();
        Block block = Block.builder()
                .header(BlockHeader.builder().headerBody(body).build())
                .build();
        return new BlockAppliedEvent(Era.Conway, slot, blockNumber, blockHash, block);
    }

    private static byte[] storedConwayBlock(long blockNumber, long slot, int opcertCounter) {
        Array headerBody = new Array();
        headerBody.add(new UnsignedInteger(blockNumber));
        headerBody.add(new UnsignedInteger(slot));
        headerBody.add(SimpleValue.NULL);
        headerBody.add(new ByteString(HexUtil.decodeHexString(ISSUER_VKEY)));
        headerBody.add(new ByteString(new byte[32]));
        Array vrfResult = new Array();
        vrfResult.add(new ByteString(new byte[64]));
        vrfResult.add(new ByteString(new byte[80]));
        headerBody.add(vrfResult);
        headerBody.add(new UnsignedInteger(0));
        headerBody.add(new ByteString(new byte[32]));
        Array opcert = new Array();
        opcert.add(new ByteString(new byte[32]));
        opcert.add(new UnsignedInteger(opcertCounter));
        opcert.add(new UnsignedInteger(0));
        opcert.add(new ByteString(new byte[64]));
        headerBody.add(opcert);
        Array protocolVersion = new Array();
        protocolVersion.add(new UnsignedInteger(9));
        protocolVersion.add(new UnsignedInteger(0));
        headerBody.add(protocolVersion);

        Array header = new Array();
        header.add(headerBody);
        header.add(new ByteString(new byte[448]));

        Array block = new Array();
        block.add(header);
        block.add(new Array());
        block.add(new Array());
        block.add(new Map());
        block.add(new Array());

        Array stored = new Array();
        stored.add(new UnsignedInteger(Era.Conway.getValue()));
        stored.add(block);
        return CborSerializationUtil.serialize(stored, true);
    }
}
