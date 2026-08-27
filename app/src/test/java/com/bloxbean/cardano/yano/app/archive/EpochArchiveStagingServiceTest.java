package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yano.api.CanonicalBlockReference;
import com.bloxbean.cardano.yano.api.ChainQuery;
import com.bloxbean.cardano.yano.api.LedgerQuery;
import com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.core.dataset.DrepDistributionFact;
import com.bloxbean.cardano.yano.runtime.config.NetworkGenesisConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EpochArchiveStagingServiceTest {
    @TempDir Path temp;

    @Test
    void onlyCompletedBoundaryIsRestartVisibleAndFailedBoundaryIsDiscarded() {
        ChainQuery chain = mock(ChainQuery.class);
        LedgerQuery ledger = mock(LedgerQuery.class);
        when(chain.getCanonicalBlockReference(10)).thenReturn(Optional.of(
                new CanonicalBlockReference(10, 100, new byte[] {1, 2, 3})));
        when(chain.getCanonicalBlockReference(11)).thenReturn(Optional.of(
                new CanonicalBlockReference(11, 110, new byte[] {4, 5, 6})));
        when(chain.getCanonicalBlockReference(12)).thenReturn(Optional.of(
                new CanonicalBlockReference(12, 120, new byte[] {7, 8, 9})));
        when(ledger.slotToUnixTime(100)).thenReturn(1_700_000_000L);
        when(ledger.slotToUnixTime(110)).thenReturn(1_700_000_010L);
        when(ledger.slotToUnixTime(120)).thenReturn(1_700_000_020L);
        var network = new ArchiveNetworkIdentity(1, "genesis");
        var enabled = EnumSet.of(EpochArchiveStagingSink.Dataset.EPOCH_STAKE);
        var staging = new EpochArchiveStagingService(chain, ledger, network, temp, enabled, 0);
        var boundary = new EpochArchiveStagingSink.Boundary(1, 2, 100, 10);

        staging.beginBoundary(boundary);
        try (var writer = staging.openStake(2)) {
            writer.append(new EpochArchiveStagingSink.StakeFact(0, "01", "02", BigInteger.TEN));
            writer.commit();
        }
        var binding = staging.sources().iterator().next();
        assertThat(staging.pending(binding, 10)).isEmpty();
        staging.completeBoundary(boundary);
        assertThat(staging.pending(binding, 10)).hasSize(1);

        var restarted = new EpochArchiveStagingService(chain, ledger, network, temp, enabled, 0);
        var restartedBinding = restarted.sources().iterator().next();
        var job = restarted.pending(restartedBinding, 10).getFirst();
        restarted.acknowledge(restartedBinding, job);
        assertThat(restarted.pending(restartedBinding, 10)).isEmpty();

        var interrupted = new EpochArchiveStagingSink.Boundary(2, 3, 110, 11);
        restarted.beginBoundary(interrupted);
        try (var writer = restarted.openStake(3)) {
            writer.append(new EpochArchiveStagingSink.StakeFact(0, "03", "04", BigInteger.ONE));
            writer.commit();
        }
        restarted.abortBoundary(interrupted);
        assertThat(restarted.pending(restartedBinding, 10)).isEmpty();
        var resumed = new EpochArchiveStagingService(chain, ledger, network, temp, enabled, 0);
        resumed.beginBoundary(interrupted);
        resumed.completeBoundary(interrupted);
        var resumedBinding = resumed.sources().iterator().next();
        assertThat(resumed.pending(resumedBinding, 10)).hasSize(1);
        resumed.acknowledge(resumedBinding, resumed.pending(resumedBinding, 10).getFirst());

        var failed = new EpochArchiveStagingSink.Boundary(3, 4, 120, 12);
        resumed.beginBoundary(failed);
        try (var writer = resumed.openStake(4)) {
            writer.append(new EpochArchiveStagingSink.StakeFact(0, "not-hex", "02", BigInteger.ONE));
            writer.commit();
        }
        resumed.completeBoundary(failed);
        assertThat(resumed.error()).isPresent();
        assertThat(resumed.pending(resumedBinding, 10)).isEmpty();
        var failedRestart = new EpochArchiveStagingService(chain, ledger, network, temp, enabled, 0);
        assertThat(failedRestart.error()).isPresent();
        assertThat(failedRestart.enabled(EpochArchiveStagingSink.Dataset.EPOCH_STAKE)).isFalse();
    }

    @Test
    void virtualDrepsUseNullableCredentialsAndSurviveDurableCodec() {
        ChainQuery chain = mock(ChainQuery.class);
        LedgerQuery ledger = mock(LedgerQuery.class);
        when(chain.getCanonicalBlockReference(20)).thenReturn(Optional.of(
                new CanonicalBlockReference(20, 200, new byte[] {1, 2, 3})));
        when(ledger.slotToUnixTime(200)).thenReturn(1_700_000_000L);
        var staging = new EpochArchiveStagingService(chain, ledger,
                new ArchiveNetworkIdentity(1, "genesis"), temp,
                EnumSet.of(EpochArchiveStagingSink.Dataset.DREP_DISTRIBUTION), 0);
        var boundary = new EpochArchiveStagingSink.Boundary(4, 5, 200, 20);

        staging.beginBoundary(boundary);
        try (var writer = staging.openDrep(5)) {
            writer.append(new EpochArchiveStagingSink.DrepFact(
                    2, "abstain", BigInteger.TEN, null, 0, null, true));
            writer.commit();
        }
        staging.completeBoundary(boundary);

        var binding = staging.sources().iterator().next();
        var job = staging.pending(binding, 1).getFirst();
        try (var lease = binding.source().acquire(job, java.time.Instant.now().plusSeconds(30))) {
            Object decoded = binding.source().read(job, Optional.empty(), 1, lease).rows().getFirst();
            assertThat(decoded).isInstanceOf(DrepDistributionFact.class);
            assertThat(((DrepDistributionFact) decoded).drepType()).isEqualTo("always_abstain");
            assertThat(((DrepDistributionFact) decoded).credential()).isNull();
        }
        assertThat(staging.error()).isEmpty();
    }

    @Test
    void rollbackDiscardsSameEpochReplacementByBoundaryBlockAndIgnoresTmpMarkers() throws Exception {
        ChainQuery chain = mock(ChainQuery.class);
        LedgerQuery ledger = mock(LedgerQuery.class);
        when(chain.getCanonicalBlockReference(30)).thenReturn(Optional.of(
                new CanonicalBlockReference(30, 300, new byte[] {3})));
        when(chain.getCanonicalBlockReference(31)).thenReturn(Optional.of(
                new CanonicalBlockReference(31, 310, new byte[] {4})));
        when(ledger.slotToUnixTime(300)).thenReturn(1_700_000_000L);
        when(ledger.slotToUnixTime(310)).thenReturn(1_700_000_010L);
        var enabled = EnumSet.of(EpochArchiveStagingSink.Dataset.EPOCH_STAKE);
        var staging = new EpochArchiveStagingService(chain, ledger,
                new ArchiveNetworkIdentity(1, "genesis"), temp, enabled, 0);
        stageStake(staging, new EpochArchiveStagingSink.Boundary(4, 5, 300, 30), 5, "01");
        stageStake(staging, new EpochArchiveStagingSink.Boundary(4, 5, 310, 31), 5, "02");
        Files.writeString(temp.resolve("completed").resolve("orphan.tmp"), "incomplete");

        var restarted = new EpochArchiveStagingService(chain, ledger,
                new ArchiveNetworkIdentity(1, "genesis"), temp, enabled, 0);
        assertThat(restarted.discardAfterBlock(30)).isEqualTo(1);
        var binding = restarted.sources().iterator().next();
        assertThat(restarted.pending(binding, 10)).singleElement()
                .extracting(com.bloxbean.cardano.yano.archive.core.source.EpochArchiveJob::boundaryBlockNumber)
                .isEqualTo(30L);
        assertThat(temp.resolve("completed").resolve("orphan.tmp")).doesNotExist();
    }

    @Test
    void byronSourceBoundariesAreNoopsAndShelleyBoundaryStages() throws Exception {
        ChainQuery chain = mock(ChainQuery.class);
        LedgerQuery ledger = mock(LedgerQuery.class);
        Path root = temp.resolve("mainnet");
        var staging = new EpochArchiveStagingService(chain, ledger,
                new ArchiveNetworkIdentity(764824073, "genesis"), root,
                EnumSet.of(EpochArchiveStagingSink.Dataset.REWARD), 208);

        var firstByronBoundary = new EpochArchiveStagingSink.Boundary(0, 1, 21_600, 21_586);
        staging.beginBoundary(firstByronBoundary);
        assertThat(staging.enabled(EpochArchiveStagingSink.Dataset.REWARD)).isFalse();
        try (var writer = staging.openRewards(1, "pool-reap")) {
            writer.commit();
        }
        staging.completeBoundary(firstByronBoundary);

        var shelleyStartBoundary = new EpochArchiveStagingSink.Boundary(
                207, 208, 4_492_800, 4_490_511);
        staging.beginBoundary(shelleyStartBoundary);
        assertThat(staging.enabled(EpochArchiveStagingSink.Dataset.REWARD)).isFalse();
        try (var writer = staging.openRewards(208, "pool-reap")) {
            writer.commit();
        }
        staging.abortBoundary(shelleyStartBoundary);

        assertThat(staging.error()).isEmpty();
        assertThat(root.resolve("FAILED")).doesNotExist();
        assertThat(root.resolve("completed")).isEmptyDirectory();
        verifyNoInteractions(chain);

        var firstShelleySourceBoundary = new EpochArchiveStagingSink.Boundary(
                208, 209, 4_924_800, 4_512_067);
        when(chain.getCanonicalBlockReference(4_512_067)).thenReturn(Optional.of(
                new CanonicalBlockReference(4_512_067, 4_924_800, new byte[] {1, 2, 3})));
        when(ledger.slotToUnixTime(4_924_800)).thenReturn(1_598_402_851L);

        staging.beginBoundary(firstShelleySourceBoundary);
        assertThat(staging.enabled(EpochArchiveStagingSink.Dataset.REWARD)).isTrue();
        try (var writer = staging.openRewards(209, "pool-reap")) {
            writer.append(new EpochArchiveStagingSink.RewardFact(
                    0, "01", null, "refund", 208, 209, BigInteger.TEN, "test"));
            writer.commit();
        }
        staging.completeBoundary(firstShelleySourceBoundary);

        assertThat(staging.error()).isEmpty();
        assertThat(root.resolve("completed").resolve("4512067.properties")).exists();
        assertThat(staging.sources().stream()
                .flatMap(binding -> staging.pending(binding, 10).stream()))
                .hasSize(1);
    }

    @Test
    void boundaryEligibilityUsesResolvedNetworkSchedule() {
        assertBoundaryEligibility(4, 3, false, temp.resolve("preprod-byron"));
        assertBoundaryEligibility(4, 4, true, temp.resolve("preprod-shelley"));
        assertBoundaryEligibility(0, 0, true, temp.resolve("shelley-only"));
    }

    @Test
    void projectionInitializationResolvesEpochBeforeRuntimeConfigPropagation() {
        NetworkGenesisConfig genesis = mock(NetworkGenesisConfig.class);
        when(genesis.getNetworkMagic()).thenReturn(1L);
        when(genesis.hasByronGenesis()).thenReturn(true);
        when(genesis.getEpochLength()).thenReturn(432_000L);
        when(genesis.getByronSlotsPerEpoch()).thenReturn(21_600L);

        assertThat(ProjectionHistoryService.resolveFirstPostByronEpoch(
                genesis, YanoConfig.builder().build()))
                .isEqualTo(4);
    }

    @Test
    void explicitlyResolvedCustomByronScheduleIsPreserved() {
        NetworkGenesisConfig genesis = mock(NetworkGenesisConfig.class);
        when(genesis.getNetworkMagic()).thenReturn(42L);
        when(genesis.hasByronGenesis()).thenReturn(true);
        when(genesis.getEpochLength()).thenReturn(100_000L);
        when(genesis.getByronSlotsPerEpoch()).thenReturn(10_000L);
        var nodeConfig = YanoConfig.builder().firstNonByronSlot(30_000L).build();

        assertThat(ProjectionHistoryService.resolveFirstPostByronEpoch(genesis, nodeConfig))
                .isEqualTo(3);
    }

    @Test
    void unpreparedWriterStillFailsClosed() throws Exception {
        Path root = temp.resolve("unprepared");
        var staging = new EpochArchiveStagingService(mock(ChainQuery.class), mock(LedgerQuery.class),
                new ArchiveNetworkIdentity(1, "genesis"), root,
                EnumSet.of(EpochArchiveStagingSink.Dataset.REWARD), 208);

        try (var writer = staging.openRewards(209, "pool-reap")) {
            writer.commit();
        }

        assertThat(staging.error()).hasValue("epoch boundary was not prepared");
        assertThat(root.resolve("FAILED")).hasContent("epoch boundary was not prepared");
    }

    private static void assertBoundaryEligibility(int firstPostByronEpoch, int previousEpoch,
                                                  boolean expected, Path root) {
        var staging = new EpochArchiveStagingService(mock(ChainQuery.class), mock(LedgerQuery.class),
                new ArchiveNetworkIdentity(1, "genesis"), root,
                EnumSet.of(EpochArchiveStagingSink.Dataset.REWARD), firstPostByronEpoch);
        var boundary = new EpochArchiveStagingSink.Boundary(previousEpoch, previousEpoch + 1,
                (previousEpoch + 1L) * 100, previousEpoch + 1L);
        staging.beginBoundary(boundary);
        assertThat(staging.enabled(EpochArchiveStagingSink.Dataset.REWARD)).isEqualTo(expected);
        staging.abortBoundary(boundary);
    }

    private static void stageStake(EpochArchiveStagingService staging,
                                   EpochArchiveStagingSink.Boundary boundary, int epoch, String hash) {
        staging.beginBoundary(boundary);
        try (var writer = staging.openStake(epoch)) {
            writer.append(new EpochArchiveStagingSink.StakeFact(0, hash, "03", BigInteger.ONE));
            writer.commit();
        }
        staging.completeBoundary(boundary);
    }
}
