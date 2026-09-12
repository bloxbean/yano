package org.yanoproject.archive.core.dataset;

import org.yanoproject.archive.api.ArchiveDatasetId;
import org.yanoproject.archive.api.ArchiveRow;
import org.yanoproject.archive.api.ArchiveJob;

import java.util.function.Consumer;

/** Deterministic bounded projection from one canonical block. */
public interface BlockArchiveDataset<B> {
    ArchiveDatasetId dataset();

    int projectionVersion();

    void derive(ArchiveJob archiveJob, BlockSourceContext<B> source, Consumer<ArchiveRow> sink);
}
