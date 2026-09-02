package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yano.api.CanonicalBlockReference;
import com.bloxbean.cardano.yano.api.ChainQuery;
import com.bloxbean.cardano.yano.api.LedgerQuery;
import com.bloxbean.cardano.yano.api.archive.CanonicalProjectionContributor;
import com.bloxbean.cardano.yano.api.archive.ConsumedOutputAddresses;
import com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink;
import com.bloxbean.cardano.yano.api.archive.ProjectionStagingWriter;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class EpochBindingProjectionContributorTest {
    @TempDir Path temp;

    @Test
    void bindsLocalEvidenceInTheDelegatesCanonicalBatch() {
        ChainQuery chain = mock(ChainQuery.class);
        LedgerQuery ledger = mock(LedgerQuery.class);
        when(chain.getCanonicalBlockReference(24)).thenReturn(Optional.of(
                new CanonicalBlockReference(
                        24, 240, new byte[] {1, 2, 3})));
        when(ledger.slotToUnixTime(240)).thenReturn(1_700_000_240L);
        var staging = new EpochArchiveStagingService(chain, ledger,
                new ArchiveNetworkIdentity(1, "genesis"), temp,
                EnumSet.of(EpochArchiveStagingSink.Dataset.REWARD), 0);
        var boundary = new EpochArchiveStagingSink.Boundary(5, 6, 250, 25);
        staging.beginBoundary(boundary);
        try (var writer = staging.openRewards(6, "rewards")) {
            writer.append(new EpochArchiveStagingSink.RewardFact(
                    0, "01", null, "member", 5, 6, BigInteger.ONE, "local"));
            writer.commit();
        }
        staging.completeBoundary(boundary);

        CanonicalProjectionContributor delegate = mock(CanonicalProjectionContributor.class);
        var staged = new ArrayList<EpochArchiveStagingService.BoundArtifact>();
        var contributor = new EpochBindingProjectionContributor(
                delegate, staging, (writer, carrier, artifact) -> {
                    assertThat(carrier).isEqualTo(25);
                    staged.add(artifact);
                }, (writer, carrier, carrierEpoch) -> {
                    assertThat(carrier).isEqualTo(25);
                    assertThat(carrierEpoch).isEqualTo(6);
                }, (block, writer, failure) -> { }, ignored -> 6);
        var event = new BlockAppliedEvent(null, 250, 25, "02".repeat(32), mock(Block.class));
        ConsumedOutputAddresses consumed = ConsumedOutputAddresses.NONE;
        ProjectionStagingWriter writer =
                (columnFamily, key, value) -> { };

        contributor.contributeBlock(event, consumed, writer);

        assertThat(staged).singleElement().satisfies(artifact -> {
            assertThat(artifact.job().boundaryBlockHash()).containsExactly(1, 2, 3);
            assertThat(artifact.job().boundaryBlockNumber()).isEqualTo(24);
            assertThat(artifact.evidence().rowCount()).isEqualTo(1);
        });
        verify(delegate).contributeBlock(event, consumed, writer);
    }

    @Test
    void doesNotBindLocalEvidenceToAReplacementBlockBeforeItsSemanticEpoch() {
        ChainQuery chain = mock(ChainQuery.class);
        LedgerQuery ledger = mock(LedgerQuery.class);
        when(chain.getCanonicalBlockReference(24)).thenReturn(Optional.of(
                new CanonicalBlockReference(24, 240, new byte[] {1, 2, 3})));
        when(ledger.slotToUnixTime(240)).thenReturn(1_700_000_240L);
        var staging = new EpochArchiveStagingService(chain, ledger,
                new ArchiveNetworkIdentity(1, "genesis"), temp,
                EnumSet.of(EpochArchiveStagingSink.Dataset.REWARD), 0);
        var boundary = new EpochArchiveStagingSink.Boundary(5, 6, 250, 25);
        staging.beginBoundary(boundary);
        try (var output = staging.openRewards(6, "rewards")) {
            output.commit();
        }
        staging.completeBoundary(boundary);

        var staged = new ArrayList<EpochArchiveStagingService.BoundArtifact>();
        var contributor = new EpochBindingProjectionContributor(
                mock(CanonicalProjectionContributor.class), staging,
                (writer, carrier, artifact) -> staged.add(artifact),
                (writer, carrier, carrierEpoch) -> { },
                (block, writer, failure) -> { }, ignored -> 5);

        contributor.contributeBlock(
                new BlockAppliedEvent(null, 245, 25, "02".repeat(32), mock(Block.class)),
                (columnFamily, key, value) -> { });

        assertThat(staged).isEmpty();
    }

    @Test
    void ordinaryBlocksCheckPendingIntentsWithoutScanningStagedFiles() {
        CanonicalProjectionContributor delegate = mock(CanonicalProjectionContributor.class);
        EpochArchiveStagingService staging = mock(EpochArchiveStagingService.class);
        var contributor = new EpochBindingProjectionContributor(
                delegate, staging, (writer, carrier, artifact) -> { },
                (writer, carrier, carrierEpoch) -> {
                    assertThat(carrier).isEqualTo(26);
                    assertThat(carrierEpoch).isEqualTo(6);
                }, (block, writer, failure) -> { }, ignored -> 6);
        var event = new BlockAppliedEvent(null, 251, 26, "not-decoded", mock(Block.class));
        ProjectionStagingWriter writer =
                (columnFamily, key, value) -> { };

        contributor.contributeBlock(event, writer);

        verify(delegate).contributeBlock(event, writer);
        verify(staging).hasCompletedCarrier(26);
        verifyNoMoreInteractions(staging);
    }

    @Test
    void reportsAContributionFailureToTheHistoryService() {
        CanonicalProjectionContributor delegate = mock(CanonicalProjectionContributor.class);
        var failures = new ArrayList<RuntimeException>();
        var contributor = new EpochBindingProjectionContributor(
                delegate, null, (writer, carrier, artifact) -> { },
                (writer, carrier, carrierEpoch) -> { }, (block, output, failure) -> {
                    assertThat(block).isEqualTo(27);
                    failures.add(failure);
                }, ignored -> 6);
        var failure = new IllegalStateException("anchor is no longer canonical");

        contributor.contributionFailed(27, (columnFamily, key, value) -> { }, failure);

        assertThat(failures).containsExactly(failure);
    }
}
