package com.bloxbean.cardano.yano.archive.core.projection;

import java.util.Objects;
import java.util.Optional;

/**
 * Backlog pressure derived from outbox size (ADR-039 §9).
 *
 * <p>At the hard limit canonical ingestion pauses <em>visibly</em>. It never satisfies
 * a limit by discarding projection data or by pruning an unacknowledged source: losing
 * history silently is the one outcome the whole design exists to prevent.
 */
public record ProjectionBackpressure(Level level, Optional<String> reason) {

    public enum Level {
        /** Normal operation. */
        NORMAL,
        /** Soft bound crossed: health degraded, metrics exposed, ingestion continues. */
        DEGRADED,
        /** Hard bound crossed: canonical ingestion pauses until the sink drains. */
        PAUSE_INGEST
    }

    public ProjectionBackpressure {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(reason, "reason");
    }

    public static ProjectionBackpressure evaluate(ProjectionOutboxStats stats, ProjectionConsumerBounds bounds) {
        if (stats.pendingBlocks() >= bounds.hardBacklogBlocks()) {
            return pause("outbox backlog of " + stats.pendingBlocks() + " blocks reached the hard bound "
                    + bounds.hardBacklogBlocks());
        }
        if (stats.pendingBytes() >= bounds.hardBacklogBytes()) {
            return pause("outbox backlog of " + stats.pendingBytes() + " bytes reached the hard bound "
                    + bounds.hardBacklogBytes());
        }
        if (stats.pendingBlocks() >= bounds.softBacklogBlocks()) {
            return degraded("outbox backlog of " + stats.pendingBlocks() + " blocks crossed the soft bound "
                    + bounds.softBacklogBlocks());
        }
        if (stats.pendingBytes() >= bounds.softBacklogBytes()) {
            return degraded("outbox backlog of " + stats.pendingBytes() + " bytes crossed the soft bound "
                    + bounds.softBacklogBytes());
        }
        return new ProjectionBackpressure(Level.NORMAL, Optional.empty());
    }

    private static ProjectionBackpressure degraded(String reason) {
        return new ProjectionBackpressure(Level.DEGRADED, Optional.of(reason));
    }

    private static ProjectionBackpressure pause(String reason) {
        return new ProjectionBackpressure(Level.PAUSE_INGEST, Optional.of(reason));
    }

    public boolean pausesIngest() {
        return level == Level.PAUSE_INGEST;
    }
}
