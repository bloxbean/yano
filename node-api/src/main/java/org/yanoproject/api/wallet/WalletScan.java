package org.yanoproject.api.wallet;

import java.util.List;

/** Pull-based bounded scan; the HTTP adapter provides backpressure and cancellation. */
public interface WalletScan extends AutoCloseable {
    List<WalletScanEvent> next();
    boolean finished();
    @Override void close();
}
