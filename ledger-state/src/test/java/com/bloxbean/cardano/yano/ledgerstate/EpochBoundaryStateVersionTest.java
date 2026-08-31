package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yano.api.db.IncompatibleChainStateException;
import com.bloxbean.cardano.yano.ledgerstate.test.TestRocksDBHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.RocksIterator;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EpochBoundaryStateVersionTest {
    private static final byte[] VERSION_KEY =
            "meta.epoch_boundary_state_version".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SNAPSHOT_DEREG_VERSION_KEY =
            "meta.snapshot_dereg_index_version".getBytes(StandardCharsets.UTF_8);
    private static final byte[] REWARD_EVENT_VERSION_KEY =
            "meta.reward_event_index_version".getBytes(StandardCharsets.UTF_8);
    private static final byte[] POOL_LIFECYCLE_VERSION_KEY =
            "meta.pool_lifecycle_state_version".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    @Test
    void emptyStoreInitializesAllReadinessMarkersAtomically() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var store = new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(), LoggerFactory.getLogger(getClass()), true);

            assertThat(rocks.db().get(rocks.cfState(), VERSION_KEY)).containsExactly(1);
            assertThat(rocks.db().get(rocks.cfState(), SNAPSHOT_DEREG_VERSION_KEY))
                    .containsExactly(1);
            assertThat(rocks.db().get(rocks.cfState(), REWARD_EVENT_VERSION_KEY))
                    .containsExactly(1);
            assertThat(rocks.db().get(rocks.cfState(), POOL_LIFECYCLE_VERSION_KEY))
                    .containsExactly(1);
            assertThatThrownBy(() -> store.requirePointerIndexReady(false))
                    .isInstanceOf(IncompatibleChainStateException.class)
                    .hasMessageContaining("complete pointer UTXO index")
                    .hasMessageContaining("resync");
            assertThatCode(() -> store.requirePointerIndexReady(true))
                    .doesNotThrowAnyException();
            assertThat(rocks.db().get(rocks.cfState(), VERSION_KEY)).containsExactly(1);
        }
    }

    @Test
    void existingV1StateReopensWithoutPromotion() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(), LoggerFactory.getLogger(getClass()), true);

            var restarted = new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(), LoggerFactory.getLogger(getClass()), true);
            assertThatThrownBy(() -> restarted.requirePointerIndexReady(false))
                    .isInstanceOf(IncompatibleChainStateException.class)
                    .hasMessageContaining("resync");
            assertThat(rocks.db().get(rocks.cfState(), VERSION_KEY)).containsExactly(1);

            restarted.requirePointerIndexReady(true);

            assertThat(rocks.db().get(rocks.cfState(), VERSION_KEY)).containsExactly(1);
            assertThatCode(() -> new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(), LoggerFactory.getLogger(getClass()), true))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void unknownVersionIsRejectedWithoutMigration() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(), LoggerFactory.getLogger(getClass()), true);
            rocks.db().put(rocks.cfState(), VERSION_KEY, new byte[]{2});

            assertThatThrownBy(() -> new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(), LoggerFactory.getLogger(getClass()), true))
                    .isInstanceOf(IncompatibleChainStateException.class)
                    .hasMessageContaining("requires epoch-boundary state v1")
                    .hasMessageContaining("resync");
            assertThat(rocks.db().get(rocks.cfState(), VERSION_KEY)).containsExactly(2);
        }
    }

    @Test
    void populatedPrePoolLifecycleStoreIsRejectedWithoutWrites() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(), LoggerFactory.getLogger(getClass()), true);
            rocks.db().delete(rocks.cfState(), POOL_LIFECYCLE_VERSION_KEY);
            rocks.db().put(rocks.cfState(),
                    DefaultAccountStateStore.accountKey(0, "11".repeat(28)),
                    AccountStateCborCodec.encodeStakeAccount(
                            BigInteger.ZERO, BigInteger.valueOf(2_000_000L)));
            Map<String, String> before = stateContents(rocks);

            assertThatThrownBy(() -> new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(), LoggerFactory.getLogger(getClass()), true))
                    .isInstanceOf(IncompatibleChainStateException.class)
                    .hasMessageContaining("pool-lifecycle-state-v1")
                    .hasMessageContaining("resync");

            assertThat(stateContents(rocks)).isEqualTo(before);
            assertThat(rocks.db().get(rocks.cfState(), POOL_LIFECYCLE_VERSION_KEY)).isNull();
        }
    }

    @Test
    void unknownPoolLifecycleVersionIsRejectedWithoutWrites() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(), LoggerFactory.getLogger(getClass()), true);
            rocks.db().put(rocks.cfState(), POOL_LIFECYCLE_VERSION_KEY, new byte[]{2});
            Map<String, String> before = stateContents(rocks);

            assertThatThrownBy(() -> new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(), LoggerFactory.getLogger(getClass()), true))
                    .isInstanceOf(IncompatibleChainStateException.class)
                    .hasMessageContaining("unsupported pool lifecycle state version")
                    .hasMessageContaining("resync");

            assertThat(stateContents(rocks)).isEqualTo(before);
        }
    }

    @Test
    void nonEmptyUnversionedStateIsRejectedWithoutWritingVersion() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(), LoggerFactory.getLogger(getClass()), true);
            rocks.db().delete(rocks.cfState(), VERSION_KEY);

            assertThatThrownBy(() -> new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(), LoggerFactory.getLogger(getClass()), true))
                    .isInstanceOf(IncompatibleChainStateException.class)
                    .hasMessageContaining("existing non-empty account chainstate is not compatible")
                    .hasMessageContaining("resync");
            assertThat(rocks.db().get(rocks.cfState(), VERSION_KEY)).isNull();
        }
    }

    private static Map<String, String> stateContents(TestRocksDBHelper rocks) {
        Map<String, String> contents = new LinkedHashMap<>();
        try (RocksIterator iterator = rocks.db().newIterator(rocks.cfState())) {
            iterator.seekToFirst();
            while (iterator.isValid()) {
                contents.put(HexFormat.of().formatHex(iterator.key()),
                        HexFormat.of().formatHex(iterator.value()));
                iterator.next();
            }
        }
        return contents;
    }
}
