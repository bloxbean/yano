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
        when(chain.getCanonicalBlockReference(9)).thenReturn(Optional.of(
                new CanonicalBlockReference(9, 99, new byte[] {1, 2, 3})));
        when(chain.getCanonicalBlockReference(10)).thenReturn(Optional.of(
                new CanonicalBlockReference(10, 109, new byte[] {4, 5, 6})));
        when(chain.getCanonicalBlockReference(11)).thenReturn(Optional.of(
                new CanonicalBlockReference(11, 119, new byte[] {7, 8, 9})));
        when(ledger.slotToUnixTime(99)).thenReturn(1_700_000_000L);
        when(ledger.slotToUnixTime(109)).thenReturn(1_700_000_010L);
        when(ledger.slotToUnixTime(119)).thenReturn(1_700_000_020L);
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
        when(chain.getCanonicalBlockReference(19)).thenReturn(Optional.of(
                new CanonicalBlockReference(19, 199, new byte[] {1, 2, 3})));
        when(ledger.slotToUnixTime(199)).thenReturn(1_700_000_000L);
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
    void producerBoundaryUsesCanonicalPredecessorAndIsRestartSafeUntilCarrierArrives() {
        ChainQuery chain = mock(ChainQuery.class);
        LedgerQuery ledger = mock(LedgerQuery.class);
        byte[] anchorHash = new byte[] {2, 4};
        when(chain.getCanonicalBlockReference(24)).thenReturn(Optional.of(
                new CanonicalBlockReference(24, 240, anchorHash)));
        when(chain.getCanonicalBlockReference(25)).thenReturn(Optional.empty());
        when(ledger.slotToUnixTime(240)).thenReturn(1_700_000_240L);
        var network = new ArchiveNetworkIdentity(1, "genesis");
        var enabled = EnumSet.of(EpochArchiveStagingSink.Dataset.REWARD);
        Path root = temp.resolve("local");
        var staging = new EpochArchiveStagingService(chain, ledger, network, root, enabled, 0);
        var boundary = new EpochArchiveStagingSink.Boundary(5, 6, 250, 25);

        staging.beginBoundary(boundary);
        try (var writer = staging.openRewards(6, "rewards")) {
            writer.append(new EpochArchiveStagingSink.RewardFact(
                    0, "01", null, "member", 5, 6, BigInteger.TEN, "local"));
            writer.commit();
        }
        staging.completeBoundary(boundary);

        assertThat(staging.sources().stream()
                .flatMap(binding -> binding.source().pending(Integer.MAX_VALUE).stream())).singleElement()
                .satisfies(job -> {
                    assertThat(job.boundaryBlockNumber()).isEqualTo(24);
                    assertThat(job.boundarySlot()).isEqualTo(240);
                    assertThat(job.boundaryBlockHash()).containsExactly(anchorHash);
                });
        assertThat(staging.hasCompletedCarrier(25)).isTrue();

        var restarted = new EpochArchiveStagingService(chain, ledger, network, root, enabled, 0);
        var completed = restarted.completedArtifacts(25);
        assertThat(completed).singleElement().satisfies(artifact -> {
            assertThat(artifact.job().boundaryBlockHash()).containsExactly(anchorHash);
            assertThat(artifact.evidence().rowCount()).isEqualTo(1);
        });
        var job = completed.getFirst().job();
        restarted.release(job.dataset(), job.jobId());

        assertThat(restarted.sources().stream()
                .flatMap(binding -> binding.source().pending(Integer.MAX_VALUE).stream())).isEmpty();
        assertThat(root.resolve("completed/6-24.properties")).doesNotExist();
    }

    @Test
    void skippedEpochsKeepIndependentCompletionMarkersOnOneAnchorAndCarrier() {
        ChainQuery chain = mock(ChainQuery.class);
        LedgerQuery ledger = mock(LedgerQuery.class);
        when(chain.getCanonicalBlockReference(24)).thenReturn(Optional.of(
                new CanonicalBlockReference(24, 240, new byte[] {2, 4})));
        when(ledger.slotToUnixTime(240)).thenReturn(1_700_000_240L);
        Path root = temp.resolve("skipped");
        var staging = new EpochArchiveStagingService(chain, ledger,
                new ArchiveNetworkIdentity(1, "genesis"), root,
                EnumSet.of(EpochArchiveStagingSink.Dataset.REWARD), 0);

        var first = new EpochArchiveStagingSink.Boundary(5, 6, 250, 25);
        staging.beginBoundary(first);
        try (var writer = staging.openRewards(6, "rewards")) { writer.commit(); }
        staging.completeBoundary(first);

        var second = new EpochArchiveStagingSink.Boundary(6, 7, 250, 25);
        staging.beginBoundary(second);
        try (var writer = staging.openRewards(7, "rewards")) { writer.commit(); }
        staging.completeBoundary(second);

        assertThat(staging.completedArtifacts(25)).hasSize(2)
                .extracting(artifact -> artifact.job().epoch()).containsExactlyInAnyOrder(6L, 7L);
        assertThat(root.resolve("completed/6-24.properties")).exists();
        assertThat(root.resolve("completed/7-24.properties")).exists();
    }

    @Test
    void rollbackDiscardsSameEpochReplacementByBoundaryBlockAndIgnoresTmpMarkers() throws Exception {
        ChainQuery chain = mock(ChainQuery.class);
        LedgerQuery ledger = mock(LedgerQuery.class);
        when(chain.getCanonicalBlockReference(29)).thenReturn(Optional.of(
                new CanonicalBlockReference(29, 299, new byte[] {3})));
        when(chain.getCanonicalBlockReference(30)).thenReturn(Optional.of(
                new CanonicalBlockReference(30, 309, new byte[] {4})));
        when(ledger.slotToUnixTime(299)).thenReturn(1_700_000_000L);
        when(ledger.slotToUnixTime(309)).thenReturn(1_700_000_010L);
        var enabled = EnumSet.of(EpochArchiveStagingSink.Dataset.EPOCH_STAKE);
        var staging = new EpochArchiveStagingService(chain, ledger,
                new ArchiveNetworkIdentity(1, "genesis"), temp, enabled, 0);
        stageStake(staging, new EpochArchiveStagingSink.Boundary(4, 5, 300, 30), 5, "01");
        stageStake(staging, new EpochArchiveStagingSink.Boundary(4, 5, 310, 31), 5, "02");
        Files.writeString(temp.resolve("completed").resolve("orphan.tmp"), "incomplete");

        var restarted = new EpochArchiveStagingService(chain, ledger,
                new ArchiveNetworkIdentity(1, "genesis"), temp, enabled, 0);
        assertThat(restarted.discardAfterBlock(29)).isEqualTo(1);
        var binding = restarted.sources().iterator().next();
        assertThat(restarted.pending(binding, 10)).singleElement()
                .extracting(com.bloxbean.cardano.yano.archive.core.source.EpochArchiveJob::boundaryBlockNumber)
                .isEqualTo(29L);
        assertThat(temp.resolve("completed").resolve("orphan.tmp")).doesNotExist();
    }

    @Test
    void rollbackKeepsAnchorAtTargetButDiscardsAnAnchorFromTheRolledBackFork() {
        ChainQuery chain = mock(ChainQuery.class);
        LedgerQuery ledger = mock(LedgerQuery.class);
        byte[] hash = {3};
        when(chain.getCanonicalBlockReference(29)).thenReturn(Optional.of(
                new CanonicalBlockReference(29, 299, hash)));
        when(ledger.slotToUnixTime(299)).thenReturn(1_700_000_299L);
        var staging = new EpochArchiveStagingService(chain, ledger,
                new ArchiveNetworkIdentity(1, "genesis"), temp.resolve("point-rollback"),
                EnumSet.of(EpochArchiveStagingSink.Dataset.REWARD), 0);
        var boundary = new EpochArchiveStagingSink.Boundary(4, 5, 300, 30);
        staging.beginBoundary(boundary);
        try (var writer = staging.openRewards(5, "rewards")) { writer.commit(); }
        staging.completeBoundary(boundary);

        assertThat(staging.discardAfterPoint(299, hash, false)).isZero();
        assertThat(staging.completedArtifacts(30)).hasSize(1);
        assertThat(staging.discardAfterPoint(299, new byte[] {4}, false)).isEqualTo(1);
        assertThat(staging.completedArtifacts(30)).isEmpty();
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
        when(chain.getCanonicalBlockReference(4_512_066)).thenReturn(Optional.of(
                new CanonicalBlockReference(4_512_066, 4_924_799, new byte[] {1, 2, 3})));
        when(ledger.slotToUnixTime(4_924_799)).thenReturn(1_598_402_851L);

        staging.beginBoundary(firstShelleySourceBoundary);
        assertThat(staging.enabled(EpochArchiveStagingSink.Dataset.REWARD)).isTrue();
        try (var writer = staging.openRewards(209, "pool-reap")) {
            writer.append(new EpochArchiveStagingSink.RewardFact(
                    0, "01", null, "refund", 208, 209, BigInteger.TEN, "test"));
            writer.commit();
        }
        staging.completeBoundary(firstShelleySourceBoundary);

        assertThat(staging.error()).isEmpty();
        assertThat(root.resolve("completed").resolve("209-4512066.properties")).exists();
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
    void unpreparedWriterIsAnUnarmedNoop() throws Exception {
        Path root = temp.resolve("unprepared");
        var staging = new EpochArchiveStagingService(mock(ChainQuery.class), mock(LedgerQuery.class),
                new ArchiveNetworkIdentity(1, "genesis"), root,
                EnumSet.of(EpochArchiveStagingSink.Dataset.REWARD), 208);

        try (var writer = staging.openRewards(209, "pool-reap")) {
            writer.commit();
        }

        assertThat(staging.error()).isEmpty();
        assertThat(root.resolve("FAILED")).doesNotExist();
    }

    @Test
    void prospectiveEnrollmentDoesNotStageBeforeProjectedFromEpoch() throws Exception {
        ChainQuery chain = mock(ChainQuery.class);
        LedgerQuery ledger = mock(LedgerQuery.class);
        when(chain.getCanonicalBlockReference(500)).thenReturn(Optional.of(
                new CanonicalBlockReference(500, 50_099, new byte[] {5, 0, 1})));
        when(ledger.slotToUnixTime(50_099)).thenReturn(1_700_000_500L);
        var dataset = EpochArchiveStagingSink.Dataset.REWARD;
        var staging = new EpochArchiveStagingService(chain, ledger,
                new ArchiveNetworkIdentity(1, "genesis"), temp.resolve("joined"),
                EnumSet.of(dataset), 0, java.util.Map.of(dataset, 501));

        var beforeJoin = new EpochArchiveStagingSink.Boundary(499, 500, 50_000, 500);
        staging.beginBoundary(beforeJoin);
        assertThat(staging.enabled(dataset)).isFalse();
        try (var writer = staging.openRewards(500, "rewards")) {
            writer.commit();
        }
        staging.completeBoundary(beforeJoin);

        var firstCaptured = new EpochArchiveStagingSink.Boundary(500, 501, 50_100, 501);
        staging.beginBoundary(firstCaptured);
        assertThat(staging.enabled(dataset)).isTrue();
        try (var writer = staging.openRewards(501, "rewards")) {
            writer.commit();
        }
        staging.completeBoundary(firstCaptured);

        assertThat(staging.sources().stream()
                .flatMap(binding -> staging.pending(binding, 10).stream())
                .map(com.bloxbean.cardano.yano.archive.core.source.EpochArchiveJob::epoch))
                .containsExactly(501L);
    }

    @Test
    void selectedDatasetWithNoProducerRowsPublishesZeroRowCompleteEvidence() {
        ChainQuery chain = mock(ChainQuery.class);
        LedgerQuery ledger = mock(LedgerQuery.class);
        when(chain.getCanonicalBlockReference(549)).thenReturn(Optional.of(
                new CanonicalBlockReference(549, 54_999, new byte[] {5, 5, 0})));
        when(ledger.slotToUnixTime(54_999)).thenReturn(1_700_000_550L);
        var reward = EpochArchiveStagingSink.Dataset.REWARD;
        var staging = new EpochArchiveStagingService(chain, ledger,
                new ArchiveNetworkIdentity(1, "genesis"), temp.resolve("zero-row"),
                EnumSet.of(reward), 0, java.util.Map.of(reward, 550));
        var boundary = new EpochArchiveStagingSink.Boundary(549, 550, 55_000, 550);
        staging.beginBoundary(boundary);
        // Deliberately do not open a reward writer: this is the legitimate empty-producer path.
        staging.completeBoundary(boundary);

        assertThat(staging.completedArtifacts(550)).singleElement().satisfies(artifact ->
                assertThat(artifact.evidence().rowCount()).isZero());
        assertThat(staging.sources().stream()
                .flatMap(binding -> staging.pending(binding, 10).stream())
                .map(com.bloxbean.cardano.yano.archive.core.source.EpochArchiveJob::epoch))
                .containsExactly(550L);
        var restarted = new EpochArchiveStagingService(chain, ledger,
                new ArchiveNetworkIdentity(1, "genesis"), temp.resolve("zero-row"),
                EnumSet.of(reward), 0, java.util.Map.of(reward, 550));
        assertThat(restarted.sources().stream()
                .flatMap(binding -> restarted.pending(binding, 10).stream())
                .map(com.bloxbean.cardano.yano.archive.core.source.EpochArchiveJob::epoch))
                .containsExactly(550L);
    }

    @Test
    void completedEvidenceIsReleasedOnlyAfterCarrierAcknowledgement() {
        ChainQuery chain = mock(ChainQuery.class);
        LedgerQuery ledger = mock(LedgerQuery.class);
        when(chain.getCanonicalBlockReference(550)).thenReturn(Optional.of(
                new CanonicalBlockReference(550, 55_099, new byte[] {5, 5, 1})));
        when(ledger.slotToUnixTime(55_099)).thenReturn(1_700_000_551L);
        var reward = EpochArchiveStagingSink.Dataset.REWARD;
        var staging = new EpochArchiveStagingService(chain, ledger,
                new ArchiveNetworkIdentity(1, "genesis"), temp.resolve("fast-ack"),
                EnumSet.of(reward), 0, java.util.Map.of(reward, 551));
        var boundary = new EpochArchiveStagingSink.Boundary(550, 551, 55_100, 551);
        staging.beginBoundary(boundary);
        try (var writer = staging.openRewards(551, "rewards")) {
            writer.commit();
        }
        staging.completeBoundary(boundary);

        var artifact = staging.completedArtifacts(551).getFirst();
        staging.release(artifact.job().dataset(), artifact.job().jobId());
        assertThat(staging.sources().stream()
                .flatMap(binding -> binding.source().pending(Integer.MAX_VALUE).stream())).isEmpty();
        assertThat(temp.resolve("fast-ack/completed/551-550.properties")).doesNotExist();
    }

    @Test
    void datasetFailurePausesOnlyThatDatasetAndExplicitResumeKeepsFutureCapturePossible() {
        ChainQuery chain = mock(ChainQuery.class);
        LedgerQuery ledger = mock(LedgerQuery.class);
        when(chain.getCanonicalBlockReference(599)).thenReturn(Optional.of(
                new CanonicalBlockReference(599, 59_999, new byte[] {6})));
        when(chain.getCanonicalBlockReference(600)).thenReturn(Optional.of(
                new CanonicalBlockReference(600, 60_099, new byte[] {7})));
        when(ledger.slotToUnixTime(org.mockito.ArgumentMatchers.anyLong())).thenReturn(1_700_000_600L);
        var rewards = EpochArchiveStagingSink.Dataset.REWARD;
        var dreps = EpochArchiveStagingSink.Dataset.DREP_DISTRIBUTION;
        var staging = new EpochArchiveStagingService(chain, ledger,
                new ArchiveNetworkIdentity(1, "genesis"), temp.resolve("isolated-failure"),
                EnumSet.of(rewards, dreps), 0);
        var failures = new java.util.ArrayList<String>();
        staging.setDatasetFailureListener((dataset, epoch, boundary, failure) ->
                failures.add(dataset + ":" + epoch + ":" + boundary.blockNumber()));

        var boundary = new EpochArchiveStagingSink.Boundary(599, 600, 60_000, 600);
        staging.beginBoundary(boundary);
        try (var writer = staging.openRewards(600, "rewards")) {
            writer.append(new EpochArchiveStagingSink.RewardFact(
                    0, "not-hex", null, "member", 598, 600, BigInteger.ONE, "test"));
            writer.commit();
        }
        assertThat(staging.enabled(rewards)).isFalse();
        assertThat(staging.enabled(dreps)).isTrue();
        try (var writer = staging.openDrep(600)) {
            writer.append(new EpochArchiveStagingSink.DrepFact(
                    2, null, BigInteger.TEN, null, 0, null, true));
            writer.commit();
        }
        staging.completeBoundary(boundary);

        assertThat(failures).containsExactly("REWARD:600:600");
        assertThat(staging.failure()).isEmpty();
        assertThat(staging.datasetFailures()).containsOnlyKeys(rewards);

        staging.resumeDurably(rewards, () -> failures.add("resumed"));
        var next = new EpochArchiveStagingSink.Boundary(600, 601, 60_100, 601);
        staging.beginBoundary(next);
        assertThat(staging.enabled(rewards)).isTrue();
        assertThat(staging.enabled(dreps)).isTrue();
        staging.abortBoundary(next);
        assertThat(failures).containsExactly("REWARD:600:600", "resumed");
    }

    @Test
    void sharedBoundaryFailureRecordsEveryDatasetPreciselyWithoutLegacyMarker() throws Exception {
        ChainQuery chain = mock(ChainQuery.class);
        LedgerQuery ledger = mock(LedgerQuery.class);
        when(chain.getCanonicalBlockReference(699)).thenReturn(Optional.of(
                new CanonicalBlockReference(699, 69_999, new byte[] {7})));
        when(ledger.slotToUnixTime(70_000)).thenReturn(1_700_000_700L);
        var rewards = EpochArchiveStagingSink.Dataset.REWARD;
        var dreps = EpochArchiveStagingSink.Dataset.DREP_DISTRIBUTION;
        Path root = temp.resolve("shared-failure");
        var staging = new EpochArchiveStagingService(chain, ledger,
                new ArchiveNetworkIdentity(1, "genesis"), root,
                EnumSet.of(rewards, dreps), 0);
        var failed = new java.util.ArrayList<String>();
        staging.setDatasetFailureListener((dataset, epoch, boundary, failure) ->
                failed.add(dataset + ":" + epoch + ":" + boundary.blockNumber()));

        var boundary = new EpochArchiveStagingSink.Boundary(699, 700, 70_000, 700);
        staging.beginBoundary(boundary);
        try (var reward = staging.openRewards(700, "rewards");
             var drep = staging.openDrep(700)) {
            reward.commit();
            drep.commit();
        }
        // Corrupt only the shared completion-marker path after evidence is durable.
        Files.delete(root.resolve("completed"));
        Files.writeString(root.resolve("completed"), "not-a-directory");
        staging.completeBoundary(boundary);

        assertThat(failed).containsExactlyInAnyOrder("REWARD:700:700", "DREP_DISTRIBUTION:700:700");
        assertThat(staging.datasetFailures()).containsOnlyKeys(rewards, dreps);
        assertThat(staging.failure()).isEmpty();
        assertThat(root.resolve("FAILED")).doesNotExist();
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
