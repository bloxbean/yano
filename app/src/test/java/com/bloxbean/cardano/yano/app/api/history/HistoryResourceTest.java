package com.bloxbean.cardano.yano.app.api.history;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.app.archive.HistoryArchiveService;
import com.bloxbean.cardano.yano.app.archive.ProjectionHistoryService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HistoryResourceTest {
    @Test
    void returnsConflictWhenProjectionHistoryIsUnavailable() {
        HistoryArchiveService service = mock(HistoryArchiveService.class);
        HistoryResource resource = new HistoryResource();
        resource.history = service;
        resource.projection = notPrimary();

        var response = resource.watermark("transaction,account_event", 1L, null, null);

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getEntity()).isEqualTo(
                Map.of("error", "projection history is unavailable"));
    }

    @Test
    void defaultsToAllEnabledBlockDatasets() {
        HistoryArchiveService service = mock(HistoryArchiveService.class);
        when(service.enabledBlockDatasets()).thenReturn(Set.of(ArchiveDatasetId.TRANSACTION));
        ProjectionHistoryService projection = mock(ProjectionHistoryService.class);
        when(projection.consistencyPoint(eq(Set.of(ArchiveDatasetId.TRANSACTION))))
                .thenReturn(Optional.of(Map.of("toBlock", 50L)));
        HistoryResource resource = new HistoryResource();
        resource.history = service;
        resource.projection = projection;

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

    /** Projection history is disabled or could not initialize. */
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

    @Test
    void resumeReturnsDatasetStatusAndConflictsWhenNotPaused() {
        ProjectionHistoryService projection = mock(ProjectionHistoryService.class);
        when(projection.resumeEpochArtifact(ArchiveDatasetId.REWARD))
                .thenReturn(Map.of("dataset", "reward", "captureState", "ACTIVE"));
        HistoryResource resource = new HistoryResource();
        resource.history = mock(HistoryArchiveService.class);
        resource.projection = projection;

        assertThat(resource.resume("reward").getStatus()).isEqualTo(200);
        when(projection.resumeEpochArtifact(ArchiveDatasetId.REWARD))
                .thenThrow(new IllegalStateException("reward is not paused"));
        assertThat(resource.resume("reward").getStatus()).isEqualTo(409);
        assertThat(resource.resume("unknown").getStatus()).isEqualTo(400);
    }

    @Test
    void coverageDetailIsBoundedAndRejectsUnknownDatasets() {
        ProjectionHistoryService projection = mock(ProjectionHistoryService.class);
        when(projection.coverageDetails(ArchiveDatasetId.REWARD, 400, 500, 0, 25))
                .thenReturn(Map.of("gapDetail", Map.of("total", 1)));
        HistoryResource resource = new HistoryResource();
        resource.history = mock(HistoryArchiveService.class);
        resource.projection = projection;

        assertThat(resource.coverage("reward", 400, 500, 0, 25).getStatus()).isEqualTo(200);
        assertThat(resource.coverage("unknown", null, null, null, null).getStatus()).isEqualTo(400);
    }
}
