package com.bloxbean.cardano.yano.archive.api;

public interface UtxoHistoryRepository<T> extends ArchiveRepository<T> {
    @Override
    default ArchiveDatasetId dataset() { return ArchiveDatasetId.UTXO_HISTORY; }
}
