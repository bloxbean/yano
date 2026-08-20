package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.archive.api.projection.ProjectionCoordinate;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSectionType;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Enforces the fresh-sync identity invariant before any projection work is accepted
 * (ADR-039 §1, invariant 12).
 *
 * <p>The rule this encodes is that partial history must never be presented as complete
 * history. Enabling history on a chainstate that has already advanced would produce an
 * archive whose coverage begins mid-chain while its metadata claims genesis, and no
 * later check could distinguish that from a legitimately complete archive. Refusing at
 * startup is the only point where the difference is still visible.
 */
public final class ProjectionStartupGuard {

    /** Observed state of the node and its configured sink at startup. */
    public record Observed(long canonicalTipBlockNumber,
                           boolean outboxHasProjectionIdentity,
                           Optional<ProjectionIdentity> outboxIdentity,
                           boolean sinkEmpty,
                           Optional<ProjectionIdentity> sinkIdentity,
                           ProjectionCoordinate sinkCoordinate,
                           Set<ProjectionSectionType> sinkReadableSections) {
        public Observed {
            Objects.requireNonNull(outboxIdentity, "outboxIdentity");
            Objects.requireNonNull(sinkIdentity, "sinkIdentity");
            Objects.requireNonNull(sinkCoordinate, "sinkCoordinate");
            sinkReadableSections = Set.copyOf(Objects.requireNonNull(sinkReadableSections, "sinkReadableSections"));
        }
    }

    private ProjectionStartupGuard() {}

    /**
     * @param expected the identity this node is configured to produce
     * @param observed what the chainstate, outbox and sink actually contain
     * @throws ProjectionActivationException on any unsupported or mismatched start
     */
    public static void verify(ProjectionIdentity expected, Observed observed) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(observed, "observed");

        // A required section the sink cannot write is a startup error, never a silent
        // omission: the alternative is acknowledging envelopes that drop it.
        Set<ProjectionSectionType> missing = expected.requiredSections().stream()
                .filter(section -> !observed.sinkReadableSections().contains(section))
                .collect(Collectors.toCollection(TreeSet::new));
        if (!missing.isEmpty()) {
            throw new ProjectionActivationException("configured sink cannot serve required projection section(s): "
                    + missing.stream().map(ProjectionSectionType::wireName).collect(Collectors.joining(", ")));
        }

        boolean freshChain = observed.canonicalTipBlockNumber() <= 0;

        if (observed.outboxHasProjectionIdentity()) {
            ProjectionIdentity stored = observed.outboxIdentity().orElseThrow(() ->
                    new ProjectionActivationException("outbox reports a projection identity but none could be read"));
            if (!expected.matches(stored)) {
                throw new ProjectionActivationException("projection identity mismatch: node is configured for "
                        + expected.fingerprint() + " but the outbox was written by " + stored.fingerprint());
            }
        } else if (!freshChain) {
            // Chain has advanced with no projection identity recorded: this is exactly
            // mid-chain activation, and the blocks already applied have no envelopes.
            throw new ProjectionActivationException(
                    "history cannot be enabled mid-chain: canonical tip is at block "
                            + observed.canonicalTipBlockNumber()
                            + " but no projection identity exists. Projection history must be enabled from genesis;"
                            + " start a fresh sync or use a coordinated snapshot that includes projection metadata");
        }

        if (observed.sinkEmpty()) {
            if (observed.sinkCoordinate().isPresent()) {
                throw new ProjectionActivationException(
                        "configured sink reports itself empty but exposes a committed coordinate at block "
                                + observed.sinkCoordinate().blockNumber());
            }
            if (!freshChain && !observed.outboxHasProjectionIdentity()) {
                throw new ProjectionActivationException(
                        "an existing chainstate cannot adopt an empty archive: canonical tip is at block "
                                + observed.canonicalTipBlockNumber());
            }
            return;
        }

        ProjectionIdentity sink = observed.sinkIdentity().orElseThrow(() ->
                new ProjectionActivationException("configured sink is non-empty but exposes no projection identity"));
        if (!expected.matches(sink)) {
            throw new ProjectionActivationException("archive identity mismatch: node is configured for "
                    + expected.fingerprint() + " but the archive was written by " + sink.fingerprint());
        }
        if (freshChain && observed.sinkCoordinate().isPresent()) {
            throw new ProjectionActivationException(
                    "a fresh chainstate cannot adopt an archive that already covers blocks through "
                            + observed.sinkCoordinate().blockNumber());
        }
    }
}
