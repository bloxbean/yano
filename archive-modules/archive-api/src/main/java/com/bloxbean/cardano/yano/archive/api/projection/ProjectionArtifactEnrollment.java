package com.bloxbean.cardano.yano.archive.api.projection;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;

import java.util.Objects;
import java.util.OptionalInt;

/** Durable start of one selected epoch artifact's honest capture lifetime (ADR-044). */
public record ProjectionArtifactEnrollment(
        ArchiveDatasetId dataset,
        OptionalInt projectedFromEpoch,
        ProjectionArtifactEnrollmentOrigin origin) {

    public ProjectionArtifactEnrollment {
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(projectedFromEpoch, "projectedFromEpoch");
        Objects.requireNonNull(origin, "origin");
        if (dataset.sourceKind() != com.bloxbean.cardano.yano.archive.api.SourceKind.EPOCH) {
            throw new IllegalArgumentException("artifact enrollment requires an epoch dataset: " + dataset);
        }
        if (origin == ProjectionArtifactEnrollmentOrigin.LEGACY_UNKNOWN) {
            if (projectedFromEpoch.isPresent()) {
                throw new IllegalArgumentException("legacy-unknown enrollment cannot claim a projected-from epoch");
            }
        } else if (projectedFromEpoch.isEmpty() || projectedFromEpoch.getAsInt() < 0) {
            throw new IllegalArgumentException("fresh/joined enrollment requires a non-negative projected-from epoch");
        }
    }

    public String wireName() {
        return dataset.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    public String wireForm() {
        return wireName() + '=' + (projectedFromEpoch.isPresent()
                ? Integer.toString(projectedFromEpoch.getAsInt()) : "-") + ',' + origin.name();
    }

    static ProjectionArtifactEnrollment parse(String wire) {
        int equals = wire.indexOf('=');
        int comma = wire.indexOf(',', equals + 1);
        if (equals <= 0 || comma <= equals + 1 || comma == wire.length() - 1) {
            throw new IllegalArgumentException("malformed artifact enrollment: " + wire);
        }
        ArchiveDatasetId dataset = ArchiveDatasetId.valueOf(
                wire.substring(0, equals).toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
        String epoch = wire.substring(equals + 1, comma);
        return new ProjectionArtifactEnrollment(dataset,
                "-".equals(epoch) ? OptionalInt.empty() : OptionalInt.of(Integer.parseInt(epoch)),
                ProjectionArtifactEnrollmentOrigin.valueOf(wire.substring(comma + 1)));
    }
}
