package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSectionType;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Distinguishes a contributor that is merely behind from one that has stopped.
 *
 * <p>The outbox itself cannot tell these apart: a block missing a required section
 * reads as "no envelope yet" whether the contributor is a second behind or has been
 * dead for an hour, and in both cases the consumer correctly goes idle. Without this,
 * a permanently stuck contributor would present as a quiet, healthy, idle archive —
 * the failure mode is silence, which is the worst possible shape for it.
 *
 * <p>The discriminator is the ADR-039 operational model's "oldest pending envelope
 * age": how long the slowest required contributor has been holding the same block.
 */
public record ProjectionContributorHealth(Status status,
                                          long completeThroughBlock,
                                          long identityCursor,
                                          Map<String, Long> contributorCursors,
                                          Optional<ProjectionSectionType> slowestContributor,
                                          Duration stalledFor,
                                          Optional<String> detail) {

    public enum Status {
        /** Every required contributor is keeping up. */
        HEALTHY,
        /** A contributor is behind but still advancing. */
        LAGGING,
        /** A contributor has not advanced within its threshold: a fault, not backlog. */
        STALLED
    }

    public ProjectionContributorHealth {
        Objects.requireNonNull(status, "status");
        contributorCursors = Map.copyOf(Objects.requireNonNull(contributorCursors, "contributorCursors"));
        Objects.requireNonNull(slowestContributor, "slowestContributor");
        Objects.requireNonNull(stalledFor, "stalledFor");
        Objects.requireNonNull(detail, "detail");
    }

    public boolean isStalled() {
        return status == Status.STALLED;
    }

    /**
     * Tracks the slowest required contributor over time and reports a stall when it has
     * failed to advance for longer than {@code stallThreshold} while canonical identity
     * has moved on.
     */
    public static final class Monitor {
        private final Duration stallThreshold;
        private long lastCompleteThrough = Long.MIN_VALUE;
        private Instant lastAdvance;

        public Monitor(Duration stallThreshold) {
            this.stallThreshold = Objects.requireNonNull(stallThreshold, "stallThreshold");
        }

        public ProjectionContributorHealth evaluate(ProjectionOutboxStore store,
                                                    java.util.Set<ProjectionSectionType> requiredSections,
                                                    Instant now) {
            long complete = store.completeThrough(requiredSections);
            long identityCursor = store.identityCursor();

            Map<String, Long> cursors = new TreeMap<>();
            ProjectionSectionType slowest = null;
            long slowestCursor = Long.MAX_VALUE;
            for (ProjectionSectionType type : requiredSections) {
                long cursor = store.contributorCursor(type);
                cursors.put(type.wireName(), cursor);
                if (cursor < slowestCursor) {
                    slowestCursor = cursor;
                    slowest = type;
                }
            }

            if (complete != lastCompleteThrough) {
                lastCompleteThrough = complete;
                lastAdvance = now;
            } else if (lastAdvance == null) {
                lastAdvance = now;
            }

            Duration stalledFor = Duration.between(lastAdvance, now);
            boolean behind = identityCursor > complete;

            if (!behind) {
                return new ProjectionContributorHealth(Status.HEALTHY, complete, identityCursor, cursors,
                        Optional.ofNullable(slowest), Duration.ZERO, Optional.empty());
            }
            if (stalledFor.compareTo(stallThreshold) >= 0) {
                String name = slowest == null ? "unknown" : slowest.wireName();
                return new ProjectionContributorHealth(Status.STALLED, complete, identityCursor, cursors,
                        Optional.ofNullable(slowest), stalledFor,
                        Optional.of("contributor " + name + " has not advanced past block " + slowestCursor
                                + " for " + stalledFor.toSeconds() + "s while canonical identity reached "
                                + identityCursor + "; this is a stalled contributor, not archive backlog"));
            }
            return new ProjectionContributorHealth(Status.LAGGING, complete, identityCursor, cursors,
                    Optional.ofNullable(slowest), stalledFor, Optional.empty());
        }
    }
}
