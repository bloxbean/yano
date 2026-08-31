package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yano.ledgerstate.test.TestRocksDBHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EpochBoundaryStateVersionTest {
    private static final byte[] VERSION_KEY =
            "meta.epoch_boundary_state_version".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    @Test
    void freshV1StateRequiresPointerIndexReadinessWithoutChangingVersion() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var store = new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(), LoggerFactory.getLogger(getClass()), true);

            assertThat(rocks.db().get(rocks.cfState(), VERSION_KEY)).containsExactly(1);
            assertThatThrownBy(() -> store.requirePointerIndexReady(false))
                    .isInstanceOf(IllegalStateException.class)
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
                    .isInstanceOf(IllegalStateException.class)
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
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("requires epoch-boundary state v1")
                    .hasMessageContaining("resync");
            assertThat(rocks.db().get(rocks.cfState(), VERSION_KEY)).containsExactly(2);
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
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("existing non-empty account chainstate is not compatible")
                    .hasMessageContaining("resync");
            assertThat(rocks.db().get(rocks.cfState(), VERSION_KEY)).isNull();
        }
    }
}
