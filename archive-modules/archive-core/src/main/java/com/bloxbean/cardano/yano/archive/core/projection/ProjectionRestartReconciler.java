package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.api.CanonicalBlockReference;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionCoordinate;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSectionType;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongFunction;

/** Exact-point outbox reconciliation that must run before the first post-restart drain. */
public final class ProjectionRestartReconciler {
    private ProjectionRestartReconciler() {}

    public static long reconcile(ProjectionOutboxStore outbox,
                                 ProjectionCoordinate committed,
                                 CanonicalBlockReference canonicalBodyTip,
                                 LongFunction<Optional<CanonicalBlockReference>> canonicalLookup,
                                 Set<ProjectionSectionType> requiredSections) {
        long acknowledged = outbox.acknowledgedThrough();
        if (canonicalBodyTip == null) {
            if (acknowledged >= 0 || committed.isPresent()) {
                throw new ProjectionActivationException(
                        "canonical body chain is at origin but projection state has advanced through "
                                + Math.max(acknowledged,
                                committed.isPresent() ? committed.blockNumber() : -1));
            }
            return outbox.rollbackToPoint(0, null, true, requiredSections);
        }

        long protectedCoordinate = Math.max(acknowledged,
                committed.isPresent() ? committed.blockNumber() : -1);
        if (canonicalBodyTip.blockNumber() < protectedCoordinate) {
            throw new ProjectionActivationException("canonical body tip "
                    + canonicalBodyTip.blockNumber()
                    + " is below the projection sink/acknowledgement coordinate "
                    + protectedCoordinate);
        }
        if (acknowledged >= 0 && canonicalLookup.apply(acknowledged).isEmpty()) {
            throw new ProjectionActivationException("cannot verify acknowledged projection block "
                    + acknowledged + " against retained canonical headers");
        }
        if (committed.isPresent()) {
            CanonicalBlockReference canonical = canonicalLookup.apply(committed.blockNumber())
                    .orElseThrow(() -> new ProjectionActivationException(
                            "cannot verify projection sink block " + committed.blockNumber()
                                    + " against retained canonical headers"));
            if (canonical.slot() != committed.slot()
                    || !Arrays.equals(canonical.blockHash(), committed.blockHash())) {
                throw new ProjectionActivationException(
                        "projection sink coordinate is not canonical at block "
                                + committed.blockNumber());
            }
        }

        return outbox.rollbackToPoint(canonicalBodyTip.slot(), canonicalBodyTip.blockHash(),
                false, requiredSections);
    }
}
