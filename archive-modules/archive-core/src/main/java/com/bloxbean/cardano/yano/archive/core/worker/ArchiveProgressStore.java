package com.bloxbean.cardano.yano.archive.core.worker;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveReceipt;

import java.util.Optional;

public interface ArchiveProgressStore {
    Optional<ArchiveProgress> load(ArchiveDatasetId dataset, ArchiveTrack track);

    /** Persists receipt and cursor as one durable operation. */
    void save(ArchiveProgress progress, ArchiveReceipt receipt);
}
