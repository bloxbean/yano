package com.bloxbean.cardano.yano.archive.core.dataset;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.api.ArchiveJob;

import java.util.function.Consumer;

/** Deterministic bounded projection from one canonical block. */
public interface BlockArchiveDataset<B> {
    ArchiveDatasetId dataset();

    int projectionVersion();

    void derive(ArchiveJob archiveJob, BlockSourceContext<B> source, Consumer<ArchiveRow> sink);
}
