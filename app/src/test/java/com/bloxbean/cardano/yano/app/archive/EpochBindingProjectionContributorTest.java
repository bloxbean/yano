package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yano.api.ChainQuery;
import com.bloxbean.cardano.yano.api.LedgerQuery;
import com.bloxbean.cardano.yano.api.archive.CanonicalProjectionContributor;
import com.bloxbean.cardano.yano.api.archive.ConsumedOutputAddresses;
import com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;

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
        when(ledger.slotToUnixTime(250)).thenReturn(1_700_000_250L);
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
                delegate, staging, (writer, artifact) -> staged.add(artifact));
        var event = new BlockAppliedEvent(null, 250, 25, "02".repeat(32), mock(Block.class));
        ConsumedOutputAddresses consumed = ConsumedOutputAddresses.NONE;
        com.bloxbean.cardano.yano.api.archive.ProjectionStagingWriter writer =
                (columnFamily, key, value) -> { };

        contributor.contributeBlock(event, consumed, writer);

        assertThat(staged).singleElement().satisfies(artifact -> {
            assertThat(artifact.job().boundaryBlockHash()).containsOnly(2);
            assertThat(artifact.evidence().rowCount()).isEqualTo(1);
        });
        verify(delegate).contributeBlock(event, consumed, writer);
    }

    @Test
    void ordinaryFetchedBlocksDoNotEnterProvisionalBinding() {
        CanonicalProjectionContributor delegate = mock(CanonicalProjectionContributor.class);
        EpochArchiveStagingService staging = mock(EpochArchiveStagingService.class);
        var contributor = new EpochBindingProjectionContributor(
                delegate, staging, (writer, artifact) -> { });
        var event = new BlockAppliedEvent(null, 251, 26, "not-decoded", mock(Block.class));
        com.bloxbean.cardano.yano.api.archive.ProjectionStagingWriter writer =
                (columnFamily, key, value) -> { };

        contributor.contributeBlock(event, writer);

        verify(delegate).contributeBlock(event, writer);
        verify(staging).hasProvisionalBoundary(26);
        verifyNoMoreInteractions(staging);
    }
}
