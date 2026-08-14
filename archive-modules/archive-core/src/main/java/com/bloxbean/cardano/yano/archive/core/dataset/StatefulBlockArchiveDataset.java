package com.bloxbean.cardano.yano.archive.core.dataset;

import com.bloxbean.cardano.yano.archive.api.ArchiveJob;
import com.bloxbean.cardano.yano.archive.api.ArchiveReceipt;

import java.util.List;

/** Dataset with private derived state committed only after backend publication. */
public interface StatefulBlockArchiveDataset<B> extends BlockArchiveDataset<B> {
    void beginBatch(ArchiveJob job, List<BlockSourceContext<B>> blocks);
    void commitBatch(ArchiveReceipt receipt);
    /** Commits private derived state when the same canonical range is already cold-covered. */
    void commitCoveredBatch(long backendGeneration);
    void abortBatch();
}
