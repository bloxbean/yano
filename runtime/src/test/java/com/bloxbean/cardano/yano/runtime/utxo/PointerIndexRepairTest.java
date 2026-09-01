package com.bloxbean.cardano.yano.runtime.utxo;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.utxo.PointerUtxo;
import com.bloxbean.cardano.yano.api.utxo.StakeCredentialExtractor;
import com.bloxbean.cardano.yano.runtime.chain.DirectRocksDBChainState;
import com.bloxbean.cardano.yano.runtime.db.UtxoCfNames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PointerIndexRepairTest {
    private static final long BLOCK = 42;
    private static final long SLOT = 1234;
    private static final String HASH = "ab".repeat(32);
    private static final String POINTER_ADDRESS =
            "addr1gxrgsz5tkx0vsapdhyrk09w9zplhllr94zy70vycpll2egsvpsxqgnmy5k";
    private static final String ENTERPRISE_ADDRESS = UtxoTestAddresses.enterprise(19);

    @TempDir
    Path tempDirectory;

    @Test
    void verifiesEveryLiveUtxoAndWritesMarkerAtTheAgreedCoordinate() throws Exception {
        Path database = database();
        byte[] pointerOutpoint = outpoint("11", 0);
        byte[] enterpriseOutpoint = outpoint("22", 1);
        createRepairableState(database, state -> {
            putUtxo(state, pointerOutpoint, POINTER_ADDRESS, 7_000_000, 1200);
            putPointer(state, pointerOutpoint, 7_000_000, 1200);
            putUtxo(state, enterpriseOutpoint, ENTERPRISE_ADDRESS, 8_000_000, 1201);
        });

        PointerIndexRepair.RepairResult result = PointerIndexRepair.repair(database);

        assertThat(result.repaired()).isTrue();
        assertThat(result.unspentRows()).isEqualTo(2);
        assertThat(result.pointerRows()).isEqualTo(1);
        PointerIndexRepair.RepairResult verifiedAgain =
                PointerIndexRepair.repair(database);
        assertThat(verifiedAgain.repaired()).isFalse();
        assertThat(verifiedAgain.unspentRows()).isEqualTo(2);
        assertThat(verifiedAgain.pointerRows()).isEqualTo(1);
        try (DirectRocksDBChainState state = new DirectRocksDBChainState(database.toString())) {
            byte[] encoded = state.rocks().db().get(
                    state.rocks().handle(UtxoCfNames.UTXO_META),
                    PointerIndexMarker.KEY);
            PointerIndexMarker marker = PointerIndexMarker.decode(encoded);
            assertThat(marker).isNotNull();
            assertThat(marker.blockNumber()).isEqualTo(BLOCK);
            assertThat(marker.slot()).isEqualTo(SLOT);
            assertThat(marker.blockHash()).containsExactly(HexUtil.decodeHexString(HASH));
        }
    }

    @Test
    void interruptedScanLeavesMarkerAbsent() throws Exception {
        Path database = database();
        byte[] outpoint = outpoint("33", 0);
        createRepairableState(database, state -> {
            putUtxo(state, outpoint, POINTER_ADDRESS, 9_000_000, 1202);
            putPointer(state, outpoint, 9_000_000, 1202);
        });

        assertThatThrownBy(() -> PointerIndexRepair.repair(
                database, () -> { throw new IllegalStateException("interrupted"); }))
                .hasMessageContaining("interrupted");

        assertMarkerAbsent(database);
    }

    @Test
    void missingPointerRowFailsClosed() throws Exception {
        Path database = database();
        byte[] outpoint = outpoint("44", 0);
        createRepairableState(database,
                state -> putUtxo(
                        state, outpoint, POINTER_ADDRESS, 10_000_000, 1203));

        assertThatThrownBy(() -> PointerIndexRepair.repair(database))
                .hasMessageContaining("missing its index row");

        assertMarkerAbsent(database);
    }

    @Test
    void extraPointerRowFailsClosed() throws Exception {
        Path database = database();
        byte[] outpoint = outpoint("55", 0);
        createRepairableState(database, state -> {
            putUtxo(state, outpoint, ENTERPRISE_ADDRESS, 11_000_000, 1204);
            putPointer(state, outpoint, 11_000_000, 1204);
        });

        assertThatThrownBy(() -> PointerIndexRepair.repair(database))
                .hasMessageContaining("non-pointer UTXO has a pointer index row");

        assertMarkerAbsent(database);
    }

    @Test
    void orphanPointerRowFailsClosed() throws Exception {
        Path database = database();
        byte[] outpoint = outpoint("66", 0);
        createRepairableState(database,
                state -> putPointer(state, outpoint, 12_000_000, 1205));

        assertThatThrownBy(() -> PointerIndexRepair.repair(database))
                .hasMessageContaining("orphan pointer index row");

        assertMarkerAbsent(database);
    }

    @Test
    void mismatchedPointerRowFailsClosed() throws Exception {
        Path database = database();
        byte[] outpoint = outpoint("77", 0);
        createRepairableState(database, state -> {
            putUtxo(state, outpoint, POINTER_ADDRESS, 13_000_000, 1206);
            putPointer(state, outpoint, 99_000_000, 1206);
        });

        assertThatThrownBy(() -> PointerIndexRepair.repair(database))
                .hasMessageContaining("does not match its live UTXO");

        assertMarkerAbsent(database);
    }

    @Test
    void coordinateDisagreementFailsBeforeMarkerWrite() throws Exception {
        Path database = database();
        createRepairableState(database, state -> state.rocks().db().put(
                state.rocks().handle(UtxoCfNames.UTXO_META),
                bytes("meta.last_applied_slot"), longBytes(SLOT - 1)));

        assertThatThrownBy(() -> PointerIndexRepair.repair(database))
                .hasMessageContaining("chain tip and UTXO coordinate do not agree");

        assertMarkerAbsent(database);
    }

    @Test
    void accountProofIsRequiredBeforeScanning() throws Exception {
        Path database = database();
        createRepairableState(database, state -> state.rocks().db().delete(
                state.rocks().handle("acct_state"),
                bytes("meta.pointer.index.from-genesis")));

        assertThatThrownBy(() -> PointerIndexRepair.repair(database))
                .hasMessageContaining("not proven complete from genesis");

        assertMarkerAbsent(database);
    }

    private Path database() {
        return tempDirectory.resolve("chainstate");
    }

    private void createRepairableState(
            Path database, StateMutation mutation) throws Exception {
        try (DirectRocksDBChainState state =
                     new DirectRocksDBChainState(database.toString())) {
            RocksDB db = state.rocks().db();
            ColumnFamilyHandle metadata = state.rocks().handle("metadata");
            ColumnFamilyHandle utxoMeta =
                    state.rocks().handle(UtxoCfNames.UTXO_META);
            ColumnFamilyHandle account = state.rocks().handle("acct_state");
            db.put(metadata, bytes("tip"), coordinateBytes());
            db.put(utxoMeta, bytes("meta.last_applied_block"), longBytes(BLOCK));
            db.put(utxoMeta, bytes("meta.last_applied_slot"), longBytes(SLOT));
            db.put(utxoMeta, bytes("meta.last_applied_hash"),
                    HexUtil.decodeHexString(HASH));
            db.put(account, bytes("meta.last_block"), longBytes(BLOCK));
            db.put(account, bytes("meta.last_applied_slot"), longBytes(SLOT));
            db.put(account, bytes("meta.pointer.index.from-genesis"), new byte[0]);
            mutation.apply(state);
        }
    }

    private void putUtxo(
            DirectRocksDBChainState state,
            byte[] outpoint,
            String address,
            long lovelace,
            long creationSlot) throws Exception {
        state.rocks().db().put(
                state.rocks().handle(UtxoCfNames.UTXO_UNSPENT),
                outpoint,
                UtxoCborCodec.encodeUtxoRecord(
                        address, BigInteger.valueOf(lovelace), null,
                        null, null, null, false,
                        creationSlot, BLOCK, HASH));
    }

    private void putPointer(
            DirectRocksDBChainState state,
            byte[] outpoint,
            long lovelace,
            long creationSlot) throws Exception {
        state.rocks().db().put(
                state.rocks().handle(UtxoCfNames.UTXO_POINTER),
                outpoint,
                PointerUtxoCodec.encode(new PointerUtxo(
                        creationSlot,
                        BigInteger.valueOf(lovelace),
                        StakeCredentialExtractor.extractPointer(POINTER_ADDRESS))));
    }

    private void assertMarkerAbsent(Path database) throws Exception {
        try (DirectRocksDBChainState state =
                     new DirectRocksDBChainState(database.toString())) {
            assertThat(state.rocks().db().get(
                    state.rocks().handle(UtxoCfNames.UTXO_META),
                    PointerIndexMarker.KEY)).isNull();
        }
    }

    private static byte[] coordinateBytes() {
        return ByteBuffer.allocate(Long.BYTES * 2 + 32)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(SLOT)
                .putLong(BLOCK)
                .put(HexUtil.decodeHexString(HASH))
                .array();
    }

    private static byte[] longBytes(long value) {
        return ByteBuffer.allocate(Long.BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(value)
                .array();
    }

    private static byte[] outpoint(String hexByte, int index) {
        return UtxoKeyUtil.outpointKey(hexByte.repeat(32), index);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface StateMutation {
        void apply(DirectRocksDBChainState state) throws Exception;
    }
}
