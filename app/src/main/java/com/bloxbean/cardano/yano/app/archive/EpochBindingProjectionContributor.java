package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yano.api.archive.CanonicalProjectionContributor;
import com.bloxbean.cardano.yano.api.archive.ConsumedOutputAddresses;
import com.bloxbean.cardano.yano.api.archive.ProjectionStagingWriter;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.ByronBlockProjectionEvent;
import com.bloxbean.cardano.yano.api.events.ByronMainBlockAppliedEvent;

import java.util.Objects;

/** Attaches completed epoch evidence to its carrier block's canonical write batch. */
final class EpochBindingProjectionContributor implements CanonicalProjectionContributor {
    @FunctionalInterface
    interface ArtifactStager {
        void stage(ProjectionStagingWriter writer, long carrierBlockNumber,
                   EpochArchiveStagingService.BoundArtifact artifact);
    }

    @FunctionalInterface
    interface PendingArtifactBinder {
        void bind(ProjectionStagingWriter writer, long carrierBlockNumber);
    }

    @FunctionalInterface
    interface FailureReporter {
        void report(long blockNumber, RuntimeException failure);
    }

    private final CanonicalProjectionContributor delegate;
    private final EpochArchiveStagingService staging;
    private final ArtifactStager artifactStager;
    private final PendingArtifactBinder pendingArtifactBinder;
    private final FailureReporter failureReporter;

    EpochBindingProjectionContributor(CanonicalProjectionContributor delegate,
                                      EpochArchiveStagingService staging,
                                      ArtifactStager artifactStager,
                                      PendingArtifactBinder pendingArtifactBinder,
                                      FailureReporter failureReporter) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.staging = staging;
        this.artifactStager = Objects.requireNonNull(artifactStager, "artifactStager");
        this.pendingArtifactBinder = Objects.requireNonNull(pendingArtifactBinder, "pendingArtifactBinder");
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
    }

    @Override
    public boolean enabled() {
        return delegate.enabled();
    }

    @Override
    public boolean needsConsumedOutputAddresses() {
        return delegate.needsConsumedOutputAddresses();
    }

    @Override
    public void contributeBlock(BlockAppliedEvent event, ProjectionStagingWriter writer) {
        delegate.contributeBlock(event, writer);
        bind(event, writer);
    }

    @Override
    public void contributeBlock(BlockAppliedEvent event, ConsumedOutputAddresses consumed,
                                ProjectionStagingWriter writer) {
        delegate.contributeBlock(event, consumed, writer);
        bind(event, writer);
    }

    private void bind(BlockAppliedEvent event, ProjectionStagingWriter writer) {
        pendingArtifactBinder.bind(writer, event.blockNumber());
        if (staging != null && staging.hasCompletedCarrier(event.blockNumber())) {
            for (EpochArchiveStagingService.BoundArtifact artifact
                    : staging.completedArtifacts(event.blockNumber())) {
                artifactStager.stage(writer, event.blockNumber(), artifact);
            }
        }
    }

    @Override
    public void contributeByronBlock(ByronBlockProjectionEvent event, ProjectionStagingWriter writer) {
        delegate.contributeByronBlock(event, writer);
    }

    @Override
    public void contributeByronMainBlock(ByronMainBlockAppliedEvent event,
                                         ConsumedOutputAddresses consumed,
                                         ProjectionStagingWriter writer) {
        delegate.contributeByronMainBlock(event, consumed, writer);
    }

    @Override
    public void reinitializeAfterSnapshotRestore() {
        delegate.reinitializeAfterSnapshotRestore();
    }

    @Override
    public void contributionFailed(long blockNumber, RuntimeException failure) {
        failureReporter.report(blockNumber, failure);
    }

    @Override
    public void rollbackFrom(long fromBlockNumber) {
        delegate.rollbackFrom(fromBlockNumber);
    }
}
