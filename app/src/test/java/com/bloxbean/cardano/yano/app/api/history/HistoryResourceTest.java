package com.bloxbean.cardano.yano.app.api.history;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.app.archive.HistoryArchiveService;
import com.bloxbean.cardano.yano.app.archive.ProjectionHistoryService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
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
        resource.projection = notPrimary();

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
        resource.projection = notPrimary();

        assertThat(resource.watermark(null, null, null, null).getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsUnknownDatasetNames() {
        HistoryResource resource = new HistoryResource();
        resource.history = mock(HistoryArchiveService.class);
        resource.projection = notPrimary();

        var response = resource.watermark("not_a_dataset", 1L, null, null);

        assertThat(response.getStatus()).isEqualTo(400);
    }

    /** A projection that is not the primary writer, so the legacy watermark answers. */
    private static ProjectionHistoryService notPrimary() {
        ProjectionHistoryService projection = mock(ProjectionHistoryService.class);
        when(projection.consistencyPoint(any())).thenReturn(Optional.empty());
        return projection;
    }

    @Test
    void theProjectionAnswersWheneverItIsThePrimaryWriter() {
        // The legacy watermark reads coverage tables the projection never writes, so asking them
        // over a projection archive would report an empty archive rather than a lagging one.
        HistoryArchiveService legacy = mock(HistoryArchiveService.class);
        ProjectionHistoryService projection = mock(ProjectionHistoryService.class);
        when(projection.consistencyPoint(any()))
                .thenReturn(Optional.of(Map.of("source", "projection", "toBlock", 5_079_957L)));

        HistoryResource resource = new HistoryResource();
        resource.history = legacy;
        resource.projection = projection;

        var response = resource.watermark("transaction", null, null, null);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getEntity()).isEqualTo(Map.of("source", "projection", "toBlock", 5_079_957L));
        // The legacy path must not even be consulted.
        org.mockito.Mockito.verifyNoInteractions(legacy);
    }
}
