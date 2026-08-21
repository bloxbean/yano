package com.bloxbean.cardano.yano.archive.api.projection;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A complete logical projection envelope: header plus the section payloads its
 * manifest describes.
 *
 * <p>Construction verifies the header against the payloads, so an envelope that
 * exists is by definition internally consistent. A sink that reassembles one from
 * outbox records therefore proves completeness by construction rather than by a
 * separate check it might forget to run (ADR-039 invariant 11).
 */
public record ProjectionEnvelope(ProjectionEnvelopeHeader header, List<ProjectionSection> sections) {
    public ProjectionEnvelope {
        Objects.requireNonNull(header, "header");
        sections = List.copyOf(Objects.requireNonNull(sections, "sections"));
        if (sections.size() != header.sections().size()) {
            throw new IllegalArgumentException("envelope section count does not match its manifest");
        }
        for (ProjectionSectionManifest manifest : header.sections()) {
            ProjectionSection section = sections.stream()
                    .filter(s -> s.type() == manifest.type())
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "envelope is missing required section " + manifest.type().wireName()));
            ProjectionSectionManifest actual = section.manifest();
            if (!actual.equals(manifest)) {
                throw new IllegalArgumentException("section " + manifest.type().wireName()
                        + " does not match its manifest (expected " + manifest + " but was " + actual + ")");
            }
        }
    }

    public long blockNumber() {
        return header.blockNumber();
    }

    public String envelopeId() {
        return header.envelopeId();
    }

    public Optional<ProjectionSection> section(ProjectionSectionType type) {
        return sections.stream().filter(s -> s.type() == type).findFirst();
    }
}
