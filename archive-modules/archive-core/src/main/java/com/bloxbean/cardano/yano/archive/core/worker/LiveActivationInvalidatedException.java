package com.bloxbean.cardano.yano.archive.core.worker;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;

/** Signals that a stateful live resolver snapshot must be rebuilt at a new anchor. */
public final class LiveActivationInvalidatedException extends RuntimeException {
    private final ArchiveDatasetId dataset;
    private final long activation;
    public LiveActivationInvalidatedException(ArchiveDatasetId dataset, long activation) {
        super("canonical rollback invalidated live activation " + activation + " for " + dataset.logicalName());
        this.dataset = dataset; this.activation = activation;
    }
    public ArchiveDatasetId dataset() { return dataset; }
    public long activation() { return activation; }
}
