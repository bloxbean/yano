package com.bloxbean.cardano.yano.archive.core.dataset;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.api.ArchiveJob;
import com.bloxbean.cardano.yano.archive.core.source.EpochSourcePage;

import java.util.function.Consumer;

/** Streaming projection from one durable, immutable epoch-source page. */
public interface EpochArchiveDataset<S> {
    ArchiveDatasetId dataset();

    int projectionVersion();

    void derive(ArchiveJob archiveJob, EpochSourcePage<S> source, Consumer<ArchiveRow> sink);
}
