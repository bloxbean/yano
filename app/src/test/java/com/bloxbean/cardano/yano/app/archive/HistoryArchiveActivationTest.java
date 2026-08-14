package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yano.api.ChainQuery;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.archive.core.address.AddressKeyCodec;
import com.bloxbean.cardano.yano.archive.core.config.ArchiveStartMode;
import com.bloxbean.cardano.yano.archive.core.dataset.AddressTransactionDataset;
import com.bloxbean.cardano.yano.archive.core.hot.RocksDbHotHistoryStore;
import com.bloxbean.cardano.yano.archive.core.worker.ArchiveTrack;
import com.bloxbean.cardano.yano.runtime.config.NetworkGenesisConfig;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HistoryArchiveActivationTest {
    @TempDir Path temp;

    @Test
    void fullRequiredAcceptsBlockOneAsTheByronOriginOnRestart() {
        assertThat(HistoryArchiveService.firstCanonicalBlockNumber(true)).isEqualTo(1);
        assertThat(HistoryArchiveService.resolveBlockActivationStart(
                ArchiveStartMode.FULL_REQUIRED, 1, 317_482, OptionalLong.of(1)))
                .isEqualTo(1);
    }

    @Test
    void fullRequiredUsesTheByronOriginBeforeTheFirstBodyArrives() {
        assertThat(HistoryArchiveService.resolveBlockActivationStart(
                ArchiveStartMode.FULL_REQUIRED, 1, -1, OptionalLong.empty()))
                .isEqualTo(1);
    }

    @Test
    void fullRequiredStillRejectsActuallyPrunedOriginBodies() {
        assertThatThrownBy(() -> HistoryArchiveService.resolveBlockActivationStart(
                ArchiveStartMode.FULL_REQUIRED, 1, 317_482, OptionalLong.of(2)))
                .isInstanceOf(ArchiveStoreException.class)
                .hasMessageContaining("expected first canonical block 1");
    }

    @Test
    void fullRequiredRejectsPersistedTipWithoutAnyRetainedBody() {
        assertThatThrownBy(() -> HistoryArchiveService.resolveBlockActivationStart(
                ArchiveStartMode.FULL_REQUIRED, 1, 317_482, OptionalLong.empty()))
                .isInstanceOf(ArchiveStoreException.class)
                .hasMessageContaining("no retained block bodies");
    }

    @Test
    void shelleyOnlyAndDevnetChainsStillBeginAtBlockZero() {
        assertThat(HistoryArchiveService.firstCanonicalBlockNumber(false)).isZero();
        assertThat(HistoryArchiveService.resolveBlockActivationStart(
                ArchiveStartMode.FULL_REQUIRED, 0, 10, OptionalLong.of(0)))
                .isZero();
    }

    @Test
    void reanchorsAStaleLiveTrackOnlyAfterCoreReachesItsUpstreamTarget() {
        assertThat(HistoryArchiveService.shouldReanchorLive(
                5_000_000, 5_000_010, 500_000, 100, 4_320)).isTrue();
        assertThat(HistoryArchiveService.shouldReanchorLive(
                4_000_000, 5_000_000, 500_000, 100, 4_320)).isFalse();
        assertThat(HistoryArchiveService.shouldReanchorLive(
                5_000_000, 5_000_010, 4_998_000, 100, 4_320)).isFalse();
    }

    @Test
    void emptyByronChainSeedsLiveAddressResolverFromGenesisAtBlockOne() throws Exception {
        var service = new HistoryArchiveService(mock(Config.class));
        var chain = mock(ChainQuery.class);
        when(chain.getLocalTip()).thenReturn(null);
        var genesis = mock(NetworkGenesisConfig.class);
        when(genesis.getInitialFunds()).thenReturn(Map.of());
        when(genesis.getAllByronBalances()).thenReturn(Map.of());
        var activations = new ActivationStore(temp.resolve("activation.properties"));
        try (var hot = new RocksDbHotHistoryStore(temp.resolve("hot"))) {
            var dataset = new AddressTransactionDataset(hot, new AddressKeyCodec(),
                    "live", ArchiveTrack.LIVE);
            set(service, "chain", chain);
            set(service, "activations", activations);
            set(service, "firstCanonicalBlockNumber", 1L);

            service.initializeAddressLiveResolver(dataset, genesis);

            assertThat(activations.start(com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.ADDRESS_TRANSACTION,
                    ArchiveTrack.LIVE)).hasValue(1);
            assertThat(dataset.resolverSeeded()).isTrue();
            assertThat(dataset.resolverBaseBlock()).hasValue(0);
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
