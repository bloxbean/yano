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

    /**
     * Decode the stable comparable form written by {@link #fingerprint()}.
     *
     * <p>The value is deliberately structured rather than hashed, so an omitted section
     * configuration can preserve an existing archive's required set across a software upgrade.
     */
    public static ProjectionIdentity parseFingerprint(String fingerprint) {
        String value = Objects.requireNonNull(fingerprint, "fingerprint").trim();
        String[] parts = value.split("\\|", -1);
        if (parts.length != 4 || parts[0].isBlank() || parts[1].isBlank()
                || !parts[2].startsWith("v") || parts[3].isBlank()) {
            throw new IllegalArgumentException("malformed projection identity fingerprint: " + fingerprint);
        }
        int networkSeparator = parts[0].indexOf(':');
        if (networkSeparator <= 0 || networkSeparator == parts[0].length() - 1) {
            throw new IllegalArgumentException("malformed projection network identity: " + parts[0]);
        }
        int networkMagic;
        int version;
        try {
            networkMagic = Integer.parseInt(parts[0].substring(0, networkSeparator));
            version = Integer.parseInt(parts[2].substring(1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("malformed projection identity fingerprint: " + fingerprint, e);
        }
        Set<ProjectionSectionType> sections;
        try {
            sections = java.util.Arrays.stream(parts[3].split(","))
                    .map(ProjectionSectionType::fromWireName)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("malformed projection section set in fingerprint: "
                    + fingerprint, e);
        }
        return new ProjectionIdentity(
                new com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity(
                        networkMagic, parts[0].substring(networkSeparator + 1)),
                parts[1], version, sections);
    }

    public boolean matches(ProjectionIdentity other) {
        return other != null && fingerprint().equals(other.fingerprint());
    }
}
