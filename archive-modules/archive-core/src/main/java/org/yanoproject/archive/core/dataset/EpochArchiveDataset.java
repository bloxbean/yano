package org.yanoproject.archive.core.dataset;

import org.yanoproject.archive.api.ArchiveDatasetId;
import org.yanoproject.archive.api.ArchiveRow;
import org.yanoproject.archive.api.ArchiveJob;
import org.yanoproject.archive.core.source.EpochSourcePage;

import java.util.function.Consumer;

/** Streaming projection from one durable, immutable epoch-source page. */
public interface EpochArchiveDataset<S> {
    ArchiveDatasetId dataset();

    int projectionVersion();

    void derive(ArchiveJob archiveJob, EpochSourcePage<S> source, Consumer<ArchiveRow> sink);
}
