package com.bloxbean.cardano.yano.archive.api.projection;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/** Versioned persisted enrollment map for selected epoch artifacts. */
public record ProjectionArtifactEnrollments(Map<ArchiveDatasetId, ProjectionArtifactEnrollment> values) {
    private static final String PREFIX = "enrollments:v1";

    public static final ProjectionArtifactEnrollments NONE = new ProjectionArtifactEnrollments(Map.of());

    public ProjectionArtifactEnrollments {
        Objects.requireNonNull(values, "values");
        values.forEach((dataset, enrollment) -> {
            if (dataset != enrollment.dataset()) {
                throw new IllegalArgumentException("artifact enrollment keyed as " + dataset
                        + " but describes " + enrollment.dataset());
            }
        });
        values = Map.copyOf(values);
    }

    public static ProjectionArtifactEnrollments of(Collection<ProjectionArtifactEnrollment> values) {
        return new ProjectionArtifactEnrollments(values.stream().collect(Collectors.toMap(
                ProjectionArtifactEnrollment::dataset, value -> value)));
    }

    public Optional<ProjectionArtifactEnrollment> enrollmentFor(ArchiveDatasetId dataset) {
        return Optional.ofNullable(values.get(dataset));
    }

    public String wireForm() {
        if (values.isEmpty()) return PREFIX;
        return PREFIX + '|' + values.values().stream()
                .sorted(java.util.Comparator.comparing(ProjectionArtifactEnrollment::wireName))
                .map(ProjectionArtifactEnrollment::wireForm)
                .collect(Collectors.joining("|"));
    }

    public static ProjectionArtifactEnrollments parse(String wire) {
        if (wire == null || wire.isBlank()) return NONE;
        String[] parts = wire.split("\\|", -1);
        if (!PREFIX.equals(parts[0])) {
            throw new IllegalArgumentException("unsupported artifact enrollment wire form: " + wire);
        }
        if (parts.length == 1) return NONE;
        return of(java.util.Arrays.stream(parts).skip(1)
                .map(ProjectionArtifactEnrollment::parse).toList());
    }

    public void requireMatches(ProjectionArtifactIdentity identity) {
        if (!values.keySet().equals(identity.contracts().keySet())) {
            throw new IllegalArgumentException("artifact enrollment datasets " + values.keySet()
                    + " do not match selected contracts " + identity.contracts().keySet());
        }
    }
}
