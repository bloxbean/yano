package org.yanoproject.api.utxo;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.api.utxo.model.Utxo;
import java.util.Optional;

/** Request-scoped, consistent subject iterator and point reads. Always close with try-with-resources. */
public interface UtxoReadView extends AutoCloseable {
    int MAX_PAGE_SIZE = 100;
    int MAX_SCANNED_OUTPUTS = 100_000;
    long MAX_QUERY_NANOS = 5_000_000_000L;

    /** Next confirmed output, or empty at the end. Implementations must bound scan work. */
    Optional<Utxo> next();
    Optional<Utxo> getUtxo(Outpoint outpoint);
    /** Fail if the canonical chain moved since this view was opened. */
    void checkCurrent();
    @Override void close();
}
