package com.bloxbean.cardano.yano.archive.api.projection;

import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;

import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Identity a projection-outbox history must match on every start (ADR-039 §1).
 *
 * <p>The required-section set is part of identity, not configuration: a sink that
 * cannot read a required section must fail at startup rather than quietly acknowledge
 * envelopes that omit it.
 */
public record ProjectionIdentity(ArchiveNetworkIdentity networkIdentity, String sinkEngine,
                                 int canonicalProjectionVersion, Set<ProjectionSectionType> requiredSections) {
    public ProjectionIdentity {
        Objects.requireNonNull(networkIdentity, "networkIdentity");
        sinkEngine = Objects.requireNonNull(sinkEngine, "sinkEngine").trim().toLowerCase();
        if (sinkEngine.isEmpty()) throw new IllegalArgumentException("sinkEngine is required");
        if (canonicalProjectionVersion < 1) throw new IllegalArgumentException("canonicalProjectionVersion must be positive");
        requiredSections = Set.copyOf(Objects.requireNonNull(requiredSections, "requiredSections"));
        if (requiredSections.isEmpty()) throw new IllegalArgumentException("at least one required section is expected");
    }

    /**
     * Stable comparable form. Sections are sorted by wire name so that the fingerprint
     * depends on the section set and their shipped versions, never on iteration order.
     */
    public String fingerprint() {
        String sections = requiredSections.stream()
                .map(ProjectionSectionType::wireName)
                .collect(Collectors.toCollection(TreeSet::new))
                .stream()
                .collect(Collectors.joining(","));
        return networkIdentity.canonicalForm() + "|" + sinkEngine + "|v" + canonicalProjectionVersion + "|" + sections;
    }

    public boolean matches(ProjectionIdentity other) {
        return other != null && fingerprint().equals(other.fingerprint());
    }
}
