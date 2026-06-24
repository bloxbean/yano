package com.bloxbean.cardano.yano.runtime.chain;

import com.bloxbean.cardano.yano.api.db.RocksDbAccess;
import com.bloxbean.cardano.yano.api.rollback.RollbackCapableStore;
import com.bloxbean.cardano.yano.runtime.blockproducer.NonceStateStore;
import com.bloxbean.cardano.yano.runtime.db.RocksDbSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ChainStateCapabilityTest {
    @TempDir
    Path tempDir;

    @Test
    void directRocksDbChainStateExposesRuntimeCapabilities() {
        try (DirectRocksDBChainState chainState =
                     new DirectRocksDBChainState(tempDir.resolve("chainstate").toString())) {
            assertThat(chainState).isInstanceOf(ByronEbHeaderStore.class);
            assertThat(chainState).isInstanceOf(OriginRollbackCapable.class);
            assertThat(chainState).isInstanceOf(ChainStateRecovery.class);
            assertThat(chainState).isInstanceOf(EraMetadataStore.class);
            assertThat(chainState).isInstanceOf(ByronGenesisUtxoMetadataStore.class);
            assertThat(chainState).isInstanceOf(NearestSlotLookup.class);
            assertThat(chainState).isInstanceOf(ChainStateSnapshots.class);
            assertThat(chainState).isInstanceOf(BootstrapChainStateWriter.class);
            assertThat(chainState).isInstanceOf(RocksDbSupplier.class);
            assertThat(chainState).isInstanceOf(RocksDbAccess.class);
            assertThat(chainState).isInstanceOf(RollbackCapableStore.class);
            assertThat(chainState).isInstanceOf(NonceStateStore.class);
        }
    }

    @Test
    void inMemoryChainStateOnlyExposesInMemorySafeCapabilities() {
        InMemoryChainState chainState = new InMemoryChainState();

        assertThat(chainState).isInstanceOf(ByronEbHeaderStore.class);
        assertThat(chainState).isInstanceOf(OriginRollbackCapable.class);
        assertThat(chainState).isInstanceOf(NonceStateStore.class);

        assertThat(chainState).isNotInstanceOf(ChainStateRecovery.class);
        assertThat(chainState).isNotInstanceOf(EraMetadataStore.class);
        assertThat(chainState).isNotInstanceOf(ByronGenesisUtxoMetadataStore.class);
        assertThat(chainState).isNotInstanceOf(NearestSlotLookup.class);
        assertThat(chainState).isNotInstanceOf(ChainStateSnapshots.class);
        assertThat(chainState).isNotInstanceOf(BootstrapChainStateWriter.class);
        assertThat(chainState).isNotInstanceOf(RocksDbSupplier.class);
        assertThat(chainState).isNotInstanceOf(RocksDbAccess.class);
    }
}
