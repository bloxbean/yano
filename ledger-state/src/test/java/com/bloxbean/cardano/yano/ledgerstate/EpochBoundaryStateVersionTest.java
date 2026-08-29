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
    void freshV2StateStillRequiresPointerIndexReadinessAtStartup() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var store = new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(), LoggerFactory.getLogger(getClass()), true);

            assertThat(rocks.db().get(rocks.cfState(), VERSION_KEY)).containsExactly(2);
            assertThatThrownBy(() -> store.completeEpochBoundaryStateV2(false))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("complete pointer UTXO index")
                    .hasMessageContaining("resync");
            assertThatCode(() -> store.completeEpochBoundaryStateV2(true))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void existingV1StatePromotesOnlyAfterPointerIndexValidation() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(), LoggerFactory.getLogger(getClass()), true);
            rocks.db().put(rocks.cfState(), VERSION_KEY, new byte[]{1});

            var restarted = new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(), LoggerFactory.getLogger(getClass()), true);
            assertThatThrownBy(() -> restarted.completeEpochBoundaryStateV2(false))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("resync");
            assertThat(rocks.db().get(rocks.cfState(), VERSION_KEY)).containsExactly(1);

            restarted.completeEpochBoundaryStateV2(true);

            assertThat(rocks.db().get(rocks.cfState(), VERSION_KEY)).containsExactly(2);
            assertThatCode(() -> new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(), LoggerFactory.getLogger(getClass()), true))
                    .doesNotThrowAnyException();
        }
    }
}
