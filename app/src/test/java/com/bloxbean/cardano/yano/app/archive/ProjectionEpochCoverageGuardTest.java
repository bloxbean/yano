package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.EpochRange;
import com.bloxbean.cardano.yano.archive.api.projection.*;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectionEpochCoverageGuardTest {

    @TempDir Path temp;

    @Test
    void freshContiguousCoverageAllowsReadButProspectivePrefixAndGapRefuseIt() throws Exception {
        var sink = mock(ProjectionSink.class);
        when(sink.epochArtifactCoverage()).thenReturn(Map.of(
                ArchiveDatasetId.REWARD, List.of(new EpochRange(209, 500))));
        when(sink.epochArtifactGaps()).thenReturn(List.of());
        var fresh = service(sink, enrollment(ProjectionArtifactEnrollmentOrigin.FRESH));
        assertThatCode(() -> fresh.requireCompleteEpochHistory(ArchiveDatasetId.REWARD))
                .doesNotThrowAnyException();

        var prospective = service(sink, enrollment(ProjectionArtifactEnrollmentOrigin.PROSPECTIVE_JOIN));
        assertThatThrownBy(() -> prospective.requireCompleteEpochHistory(ArchiveDatasetId.REWARD))
                .isInstanceOf(IncompleteEpochHistoryException.class)
                .hasMessageContaining("NOT_PROJECTED");

        when(sink.epochArtifactGaps()).thenReturn(List.of(new EpochArtifactGap(
                ArchiveDatasetId.REWARD, 450, 9_000, 90_000, new byte[] {7},
                "io", "disk", Instant.now())));
        var gapped = service(sink, enrollment(ProjectionArtifactEnrollmentOrigin.FRESH));
        assertThatThrownBy(() -> gapped.requireCompleteEpochHistory(ArchiveDatasetId.REWARD))
                .isInstanceOf(IncompleteEpochHistoryException.class)
                .hasMessageContaining("GAP");
        assertThatCode(() -> gapped.requireCompleteEpochHistory(
                ArchiveDatasetId.REWARD, 451, 500)).doesNotThrowAnyException();
        assertThatThrownBy(() -> gapped.requireCompleteEpochHistory(
                ArchiveDatasetId.REWARD, 400, 500))
                .isInstanceOf(IncompleteEpochHistoryException.class)
                .hasMessageContaining("GAP");
    }

    @Test
    void unstructuredLegacyFailureRefusesStagedReadsAndAcknowledgementRequiresRestart()
            throws Exception {
        var sink = mock(ProjectionSink.class);
        when(sink.epochArtifactCoverage()).thenReturn(Map.of(
                ArchiveDatasetId.REWARD, List.of(new EpochRange(209, 500))));
        when(sink.epochArtifactGaps()).thenReturn(List.of());
        var service = service(sink, enrollment(ProjectionArtifactEnrollmentOrigin.FRESH));
        set(service, "selectedArtifacts", ProjectionArtifactIdentity.of(List.of(
                ProjectionArtifactContracts.reward())));
        set(service, "historyDirectory", temp);
        Files.createDirectories(temp.resolve("epoch-source"));
        Files.writeString(temp.resolve("epoch-source/FAILED"), "legacy capture provenance unknown");

        assertThatThrownBy(() -> service.requireCompleteEpochHistory(ArchiveDatasetId.REWARD))
                .isInstanceOf(IncompleteEpochHistoryException.class)
                .hasMessageContaining("UNKNOWN_LEGACY_FAILURE");

        org.assertj.core.api.Assertions.assertThat(service.acknowledgeLegacyStagingFailure())
                .containsEntry("acknowledged", true)
                .containsEntry("restartRequired", true);
        org.assertj.core.api.Assertions.assertThat(temp.resolve("epoch-source/FAILED")).doesNotExist();
    }

    @Test
    void consistencyPointDoesNotClaimASelectedButGappedEpochDataset() throws Exception {
        var sink = mock(ProjectionSink.class);
        when(sink.coordinate()).thenReturn(new ProjectionCoordinate(10_000, 100_000,
                new byte[] {1}, "envelope"));
        when(sink.epochArtifactCoverage()).thenReturn(Map.of(
                ArchiveDatasetId.REWARD, List.of(new EpochRange(209, 500))));
        when(sink.epochArtifactGaps()).thenReturn(List.of(new EpochArtifactGap(
                ArchiveDatasetId.REWARD, 450, 9_000, 90_000, new byte[] {7},
                "io", "disk", Instant.now())));
        var service = service(sink, enrollment(ProjectionArtifactEnrollmentOrigin.FRESH));
        var selected = ProjectionArtifactIdentity.of(List.of(ProjectionArtifactContracts.reward()));
        set(service, "selectedArtifacts", selected);
        set(service, "enabled", true);
        set(service, "genesisComplete", true);
        set(service, "identity", new ProjectionIdentity(
                new com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity(1, "genesis"),
                "ducklake", 1, Set.of(ProjectionSectionType.TRANSACTION)));

        org.assertj.core.api.Assertions.assertThat(service.consistencyPoint(Set.of(ArchiveDatasetId.REWARD)))
                .get().extracting(value -> value.get("available")).isEqualTo(false);
    }

    private static ProjectionHistoryService service(
            ProjectionSink sink, ProjectionArtifactEnrollment enrollment) throws Exception {
        var service = new ProjectionHistoryService(mock(Config.class));
        set(service, "artifactEnrollments", ProjectionArtifactEnrollments.of(List.of(enrollment)));
        set(service, "projectionSink", sink);
        return service;
    }

    private static ProjectionArtifactEnrollment enrollment(ProjectionArtifactEnrollmentOrigin origin) {
        return new ProjectionArtifactEnrollment(ArchiveDatasetId.REWARD,
                OptionalInt.of(209), origin);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
