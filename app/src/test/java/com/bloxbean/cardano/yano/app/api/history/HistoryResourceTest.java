package com.bloxbean.cardano.yano.app.api.history;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.app.archive.HistoryArchiveService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HistoryResourceTest {
    @Test
    void returnsOneFinalizedWatermarkForTheSelectedDatasets() {
        HistoryArchiveService service = mock(HistoryArchiveService.class);
        when(service.finalizedWatermark(any(), eq(1L), eq(OptionalLong.empty()), eq(OptionalLong.empty())))
                .thenReturn(Map.of("generation", 9L, "toBlock", 100L));
        HistoryResource resource = new HistoryResource();
        resource.history = service;

        var response = resource.watermark("transaction,account_event", 1L, null, null);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getEntity()).isEqualTo(Map.of("generation", 9L, "toBlock", 100L));
    }

    @Test
    void defaultsToAllEnabledBlockDatasetsAndCanonicalStart() {
        HistoryArchiveService service = mock(HistoryArchiveService.class);
        when(service.enabledBlockDatasets()).thenReturn(Set.of(ArchiveDatasetId.TRANSACTION));
        when(service.firstCanonicalHistoryBlock()).thenReturn(1L);
        when(service.finalizedWatermark(eq(Set.of(ArchiveDatasetId.TRANSACTION)), eq(1L),
                eq(OptionalLong.empty()), eq(OptionalLong.empty())))
                .thenReturn(Map.of("toBlock", 50L));
        HistoryResource resource = new HistoryResource();
        resource.history = service;

        assertThat(resource.watermark(null, null, null, null).getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsUnknownDatasetNames() {
        HistoryResource resource = new HistoryResource();
        resource.history = mock(HistoryArchiveService.class);

        var response = resource.watermark("not_a_dataset", 1L, null, null);

        assertThat(response.getStatus()).isEqualTo(400);
    }
}
