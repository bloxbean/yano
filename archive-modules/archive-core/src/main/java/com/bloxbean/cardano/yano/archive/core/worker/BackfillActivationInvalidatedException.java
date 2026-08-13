package com.bloxbean.cardano.yano.archive.core.worker;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;

/** Signals that a deep canonical rollback crossed already-pruned backfill undo. */
public final class BackfillActivationInvalidatedException extends ArchiveStoreException {
    private final ArchiveDatasetId dataset;
    private final long activation;

    public BackfillActivationInvalidatedException(ArchiveDatasetId dataset, long activation, Throwable cause) {
        super("canonical rollback crossed the backfill undo window for " + dataset, cause);
        this.dataset = dataset;
        this.activation = activation;
    }

    public ArchiveDatasetId dataset() { return dataset; }
    public long activation() { return activation; }
}
