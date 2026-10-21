package org.yanoproject.runtime.chain;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import org.yanoproject.api.db.RocksDbAccess;
import org.yanoproject.api.rollback.RollbackCapableStore;
import org.yanoproject.runtime.blockproducer.NonceStateStore;
import org.yanoproject.runtime.db.RocksDbSupplier;
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
            assertThat(chainState).isInstanceOf(ArchiveChainStateCapabilities.class);
        }
    }

    @Test
    void inMemoryChainStateOnlyExposesInMemorySafeCapabilities() {
        InMemoryChainState chainState = new InMemoryChainState();

        assertThat(chainState).isInstanceOf(ByronEbHeaderStore.class);
        assertThat(chainState).isInstanceOf(OriginRollbackCapable.class);
        assertThat(chainState).isInstanceOf(NonceStateStore.class);
        assertThat(chainState).isInstanceOf(ArchiveChainStateCapabilities.class);

        assertThat(chainState).isNotInstanceOf(ChainStateRecovery.class);
        assertThat(chainState).isNotInstanceOf(EraMetadataStore.class);
        assertThat(chainState).isNotInstanceOf(ByronGenesisUtxoMetadataStore.class);
        assertThat(chainState).isNotInstanceOf(NearestSlotLookup.class);
        assertThat(chainState).isNotInstanceOf(ChainStateSnapshots.class);
        assertThat(chainState).isNotInstanceOf(BootstrapChainStateWriter.class);
        assertThat(chainState).isNotInstanceOf(RocksDbSupplier.class);
        assertThat(chainState).isNotInstanceOf(RocksDbAccess.class);
    }

    @Test
    void bothChainStatesIndexHeaderOnlyBlocksAheadOfTheBodyTip() {
        try (DirectRocksDBChainState chainState =
                     new DirectRocksDBChainState(tempDir.resolve("chainstate").toString())) {
            assertHeaderOnlyBlockIsCanonical(chainState, chainState);
        }
        InMemoryChainState inMemory = new InMemoryChainState();
        assertHeaderOnlyBlockIsCanonical(inMemory, inMemory);
    }

    private static void assertHeaderOnlyBlockIsCanonical(ChainState chainState, ArchiveChainStateCapabilities index) {
        for (long number = 1; number <= 3; number++) {
            chainState.storeBlockHeader(hash(number), number, number * 10, ("header-" + number).getBytes());
            if (number <= 2) {
                chainState.storeBlock(hash(number), number, number * 10, ("body-" + number).getBytes());
            }
        }

        assertThat(chainState.getTip().getBlockNumber()).isEqualTo(2L);
        assertThat(index.getCanonicalBlockReference(3)).hasValueSatisfying(reference -> {
            assertThat(reference.slot()).isEqualTo(30L);
            assertThat(reference.blockHash()).isEqualTo(hash(3));
        });
        assertThat(index.getCanonicalBlockReference(2)).hasValueSatisfying(
                reference -> assertThat(reference.slot()).isEqualTo(20L));
    }

    private static byte[] hash(long number) {
        byte[] hash = new byte[32];
        hash[31] = (byte) number;
        return hash;
    }
}
