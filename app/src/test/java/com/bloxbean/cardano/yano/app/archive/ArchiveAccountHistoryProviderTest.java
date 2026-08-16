package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yano.archive.api.ArchiveBackend;
import com.bloxbean.cardano.yano.archive.api.ArchiveCoverage;
import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveReadSession;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArchiveAccountHistoryProviderTest {

    @Test
    void rewardQueriesNeverConsultTheBlockHotStore() {
        HistoryArchiveService service = mock(HistoryArchiveService.class);
        ArchiveBackend backend = mock(ArchiveBackend.class);
        ArchiveReadSession read = mock(ArchiveReadSession.class);
        HistoryArchiveService.QueryLease lease = mock(HistoryArchiveService.QueryLease.class);

        when(service.openQueryLease()).thenReturn(lease);
        when(service.backend()).thenReturn(Optional.of(backend));
        when(backend.openReadSession()).thenReturn(read);
        when(backend.coverage(read, ArchiveDatasetId.REWARD)).thenReturn(
                new ArchiveCoverage(ArchiveDatasetId.REWARD, 4, 1, List.of()));
        when(service.liveCoverage(ArchiveDatasetId.REWARD)).thenReturn(Optional.empty());

        ArchiveAccountHistoryProvider provider = new ArchiveAccountHistoryProvider(service);

        assertThatThrownBy(() -> provider.getRewards(0, "00".repeat(28), 1, 20, "asc"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("history coverage is incomplete");
        verify(service, never()).openHotSnapshot();
    }
}
