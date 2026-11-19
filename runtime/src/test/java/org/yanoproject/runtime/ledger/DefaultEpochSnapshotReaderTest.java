package org.yanoproject.runtime.ledger;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import org.yanoproject.api.EpochBoundary;
import org.yanoproject.api.EpochParamProvider;
import org.yanoproject.api.util.EpochSlotCalc;
import org.yanoproject.ledgerstate.DefaultAccountStateStore;
import org.yanoproject.ledgerstate.EpochBoundaryProcessor;
import org.yanoproject.runtime.chain.DirectRocksDBChainState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultEpochSnapshotReaderTest {
    private static final EpochParamProvider PARAMS = new EpochParamProvider() {
        @Override public BigInteger getKeyDeposit(long epoch) { return BigInteger.valueOf(2_000_000L); }
        @Override public BigInteger getPoolDeposit(long epoch) { return BigInteger.valueOf(500_000_000L); }
        @Override public EpochSlotCalc getEpochSlotCalc() { return new EpochSlotCalc(100, 100, 0); }
    };

    @TempDir
    Path tempDir;

    @Test
    void derivesTheFirstObservableEpochFromNetworkEraConfiguration() {
        assertThat(DefaultEpochSnapshotReader.firstObservableEpoch(
                new EpochSlotCalc(432_000, 21_600, 4_492_800))).isEqualTo(208);
        assertThat(DefaultEpochSnapshotReader.firstObservableEpoch(
                new EpochSlotCalc(432_000, 21_600, 86_400))).isEqualTo(4);
        assertThat(DefaultEpochSnapshotReader.firstObservableEpoch(
                new EpochSlotCalc(86_400, 21_600, 0))).isEqualTo(1);
    }

    @Test
    void completedBoundaryScanDoesNotEnumerateByronEpochs() {
        assertThat(DefaultEpochSnapshotReader.firstBoundaryToScan(
                -1, 4, 50, 4)).isEqualTo(4);
        assertThat(DefaultEpochSnapshotReader.firstBoundaryToScan(
                -1, 211, 50, 208)).isEqualTo(208);
        assertThat(DefaultEpochSnapshotReader.firstBoundaryToScan(
                -1, 311, 50, 4)).isEqualTo(262);
    }

    @Test
    void exposesOnlyCompletedBoundariesAtTheirFirstBlock() {
        try (var chainstate = new DirectRocksDBChainState(tempDir.toString())) {
            var store = store(chainstate);
            // Epoch 2 has no block; epochs 1 and 3 start with a block two slots after their first slot.
            var chain = (ChainState) Proxy.newProxyInstance(ChainState.class.getClassLoader(),
                    new Class<?>[]{ChainState.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "findNextBlock" -> {
                            long after = ((Point) args[0]).getSlot();
                            yield after == 199 ? null : new Point(after + 2, "ab".repeat(32));
                        }
                        case "getBlockNumberBySlot" -> Map.of(101L, 7L, 301L, 21L).get((Long) args[0]);
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            var reader = new DefaultEpochSnapshotReader(store, chain, PARAMS);
            store.setBoundaryStep(3, EpochBoundaryProcessor.STEP_COMPLETE - 1);
            assertThat(reader.completedBoundaries(0, 10)).isEmpty();

            store.setBoundaryStep(3, EpochBoundaryProcessor.STEP_COMPLETE);
            assertThat(reader.completedBoundaries(0, 10)).containsExactly(
                    new EpochBoundary(0, 1, 101, filled(0xab), 7),
                    new EpochBoundary(2, 3, 301, filled(0xab), 21));
            assertThat(reader.completedBoundaries(0, 1)).hasSize(1);
            assertThat(reader.completedBoundaries(3, 10)).isEmpty();
            assertThat(reader.completedBoundaries(0, 0)).isEmpty();
        }
    }

    @Test
    void snapshotsArePinnedToTheirBoundaryAndCloseScoped() {
        try (var chainstate = new DirectRocksDBChainState(tempDir.toString())) {
            var store = store(chainstate);
            var reader = new DefaultEpochSnapshotReader(store, chainstate, PARAMS);
            var boundary = new EpochBoundary(4, 5, 501, filled(1), 40);
            store.setBoundaryStep(4, EpochBoundaryProcessor.STEP_COMPLETE);
            assertThat(reader.open(boundary)).isEmpty();

            store.setBoundaryStep(5, EpochBoundaryProcessor.STEP_COMPLETE);
            var snapshot = reader.open(boundary).orElseThrow();
            assertThat(snapshot.previousEpoch()).isEqualTo(4);
            assertThat(snapshot.newEpoch()).isEqualTo(5);
            assertThat(snapshot.protocolParams(5)).isEqualTo(store.getProtocolParameters(5).orElseThrow());
            assertThat(snapshot.hasStakeSnapshot(4)).isFalse();
            assertThat(snapshot.hasProposalStatusSnapshot(5)).isFalse();
            assertThatThrownBy(() -> snapshot.protocolParams(4)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> snapshot.hasStakeSnapshot(5)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> snapshot.hasDRepDistributionSnapshot(4))
                    .isInstanceOf(IllegalArgumentException.class);

            snapshot.close();
            snapshot.close();
            assertThatThrownBy(snapshot::newEpoch).isInstanceOf(IllegalStateException.class);
        }
    }

    private static DefaultAccountStateStore store(DirectRocksDBChainState chainstate) {
        return new DefaultAccountStateStore((RocksDB) chainstate.getDb(),
                name -> (ColumnFamilyHandle) chainstate.getColumnFamilyHandle(name),
                LoggerFactory.getLogger(DefaultEpochSnapshotReaderTest.class), true, PARAMS);
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
