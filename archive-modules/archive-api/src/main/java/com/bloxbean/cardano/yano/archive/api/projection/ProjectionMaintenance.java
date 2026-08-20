package com.bloxbean.cardano.yano.archive.api.projection;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Value types for bounded sink maintenance (ADR-039). */
public final class ProjectionMaintenance {

    private ProjectionMaintenance() {}

    /**
     * Bounds one maintenance pass.
     *
     * <p>Housekeeping and compaction are budgeted <strong>separately</strong> and deliberately
     * so. Expiring snapshots and deleting orphaned files are how the archive reclaims space;
     * compaction is an optimisation. A single shared budget lets a large compaction consume
     * everything and starve the cleanup that actually matters, which is how a lagging archive
     * turns into a full disk.
     */
    public record Budget(Duration housekeepingTimeLimit,
                         Duration compactionTimeLimit,
                         long maxBytesToRewrite,
                         long targetFileSizeBytes,
                         int minFilesToCompact,
                         long minSmallFileBytes,
                         boolean compactionAllowed) {

        public Budget {
            Objects.requireNonNull(housekeepingTimeLimit, "housekeepingTimeLimit");
            Objects.requireNonNull(compactionTimeLimit, "compactionTimeLimit");
            if (housekeepingTimeLimit.isNegative() || compactionTimeLimit.isNegative()) {
                throw new IllegalArgumentException("time limits must not be negative");
            }
            if (maxBytesToRewrite < 0 || targetFileSizeBytes < 1) {
                throw new IllegalArgumentException("invalid compaction bounds");
            }
            if (minFilesToCompact < 2) {
                throw new IllegalArgumentException("compacting fewer than two files cannot help");
            }
            if (minSmallFileBytes < 1) throw new IllegalArgumentException("minSmallFileBytes must be positive");
        }

        /**
         * Housekeeping only; compaction withheld. Used during bootstrap, where compaction
         * would contend with the sink for the same bounded bulk pool and would re-merge the
         * same neighbourhood as the sink keeps appending.
         */
        public static Budget housekeepingOnly(Duration timeLimit) {
            return new Budget(timeLimit, Duration.ZERO, 0, 512L << 20, 8, 32L << 20, false);
        }

        /** Housekeeping plus bounded compaction, for an idle sink caught up at the tip. */
        public static Budget full(Duration housekeeping, Duration compaction, long maxBytesToRewrite) {
            return new Budget(housekeeping, compaction, maxBytesToRewrite, 512L << 20, 8, 32L << 20, true);
        }
    }

    /** What a maintenance pass actually did. */
    public enum Outcome {
        /** Ran and finished within budget. */
        COMPLETED,
        /** Ran partially and stopped at a bound; safe to call again. */
        PARTIAL,
        /** Withheld deliberately, e.g. a pinned reader or an active writer. */
        DEFERRED,
        /** Nothing needed doing; thresholds were not met. */
        UNNECESSARY,
        /** This backend has no maintenance to perform. */
        UNSUPPORTED,
        /** Attempted and failed; the reason is carried. */
        FAILED
    }

    /**
     * Result of a pass, with enough detail to tune the thresholds.
     *
     * <p>{@code UNSUPPORTED} is a first-class outcome rather than a silent default no-op: a
     * backend that genuinely has nothing to maintain should say so, so an operator can tell
     * that apart from maintenance that is configured but never running.
     */
    public record Result(Outcome outcome,
                         Duration duration,
                         long filesBefore,
                         long filesAfter,
                         long bytesRewritten,
                         long snapshotsExpired,
                         long orphanedFilesDeleted,
                         Duration writerWait,
                         Optional<String> detail) {

        public Result {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(duration, "duration");
            Objects.requireNonNull(writerWait, "writerWait");
            Objects.requireNonNull(detail, "detail");
        }

        public static Result unsupported(String why) {
            return new Result(Outcome.UNSUPPORTED, Duration.ZERO, 0, 0, 0, 0, 0,
                    Duration.ZERO, Optional.of(why));
        }

        public static Result deferred(String why) {
            return new Result(Outcome.DEFERRED, Duration.ZERO, 0, 0, 0, 0, 0,
                    Duration.ZERO, Optional.of(why));
        }

        public static Result unnecessary(long files) {
            return new Result(Outcome.UNNECESSARY, Duration.ZERO, files, files, 0, 0, 0,
                    Duration.ZERO, Optional.empty());
        }

        public long filesReclaimed() {
            return Math.max(0, filesBefore - filesAfter);
        }
    }
}
