package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;

/**
 * Drives a {@link FinalizedStreamSink} with a persisted per-sink cursor
 * (ADR app-layer/006 E3.2): delivers finalized blocks strictly in height order,
 * at-least-once, advancing the cursor only on success and resuming across
 * restarts. Sinks implement only the write; ordering/durability live here.
 */
final class SinkRunner implements AutoCloseable {

    private final FinalizedStreamSink sink;
    private final AppLedgerStore ledger;
    private final String cursorKey;
    private final Logger log;

    private volatile long deliveredCount;
    private volatile String lastError;

    SinkRunner(FinalizedStreamSink sink, AppLedgerStore ledger, Logger log) {
        this.sink = sink;
        this.ledger = ledger;
        this.log = log;
        this.cursorKey = "sink_cursor_" + HexUtil.encodeHexString(
                Blake2bUtil.blake2bHash224(sink.id().getBytes(StandardCharsets.UTF_8)));
        // First registration starts at the current tip: sinks receive NEW blocks;
        // history is available via catch-up/REST if a consumer needs it.
        if (ledger.metaLong(cursorKey, -1L) < 0) {
            ledger.metaPutLong(cursorKey, ledger.tipHeight());
        }
    }

    String id() {
        return sink.id();
    }

    long cursor() {
        return ledger.metaLong(cursorKey, 0L);
    }

    long deliveredCount() {
        return deliveredCount;
    }

    String lastError() {
        return lastError;
    }

    /** Deliver pending blocks up to the tip; stops at the first failure. */
    void deliveryTick() {
        long tip = ledger.tipHeight();
        long cursor = cursor();
        while (cursor < tip) {
            long next = cursor + 1;
            AppBlock block = ledger.block(next).orElse(null);
            if (block == null) {
                return;
            }
            boolean ok;
            try {
                ok = sink.deliver(block);
                if (ok) {
                    lastError = null;
                }
            } catch (Exception e) {
                lastError = e.toString();
                log.warn("Sink {} delivery of block {} failed: {}", sink.id(), next, e.toString());
                ok = false;
            }
            if (!ok) {
                return; // retry the same block next tick (at-least-once)
            }
            ledger.metaPutLong(cursorKey, next);
            cursor = next;
            deliveredCount++;
        }
    }

    @Override
    public void close() {
        try {
            sink.close();
        } catch (Exception e) {
            log.debug("Error closing sink {}: {}", sink.id(), e.toString());
        }
    }
}
