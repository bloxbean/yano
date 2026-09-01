package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.archive.CanonicalProjectionContributor;
import com.bloxbean.cardano.yano.api.archive.ConsumedOutputAddresses;
import com.bloxbean.cardano.yano.api.archive.ProjectionStagingWriter;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.ByronBlockProjectionEvent;
import com.bloxbean.cardano.yano.api.events.ByronMainBlockAppliedEvent;

import java.util.Objects;

/** Binds locally captured epoch evidence inside its producing block's canonical write batch. */
final class EpochBindingProjectionContributor implements CanonicalProjectionContributor {
    @FunctionalInterface
    interface ArtifactStager {
        void stage(ProjectionStagingWriter writer, EpochArchiveStagingService.BoundArtifact artifact);
    }

    private final CanonicalProjectionContributor delegate;
    private final EpochArchiveStagingService staging;
    private final ArtifactStager artifactStager;

    EpochBindingProjectionContributor(CanonicalProjectionContributor delegate,
                                      EpochArchiveStagingService staging,
                                      ArtifactStager artifactStager) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.staging = Objects.requireNonNull(staging, "staging");
        this.artifactStager = Objects.requireNonNull(artifactStager, "artifactStager");
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
        if (!staging.hasProvisionalBoundary(event.blockNumber())) return;
        byte[] hash = HexUtil.decodeHexString(event.blockHash());
        for (EpochArchiveStagingService.BoundArtifact artifact
                : staging.bindCanonicalBoundary(event.blockNumber(), event.slot(), hash)) {
            artifactStager.stage(writer, artifact);
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
    public void rollbackFrom(long fromBlockNumber) {
        delegate.rollbackFrom(fromBlockNumber);
    }
}
