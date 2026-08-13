package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yano.api.CanonicalBlockReference;
import com.bloxbean.cardano.yano.api.ChainQuery;
import com.bloxbean.cardano.yano.api.LedgerQuery;
import com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
        var staging = new EpochArchiveStagingService(chain, ledger, network, temp, enabled);
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

        var restarted = new EpochArchiveStagingService(chain, ledger, network, temp, enabled);
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
        var resumed = new EpochArchiveStagingService(chain, ledger, network, temp, enabled);
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
        var failedRestart = new EpochArchiveStagingService(chain, ledger, network, temp, enabled);
        assertThat(failedRestart.error()).isPresent();
        assertThat(failedRestart.enabled(EpochArchiveStagingSink.Dataset.EPOCH_STAKE)).isFalse();
    }
}
