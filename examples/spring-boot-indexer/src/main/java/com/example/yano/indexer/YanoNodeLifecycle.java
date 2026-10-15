package com.example.yano.indexer;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.yanoproject.api.ChainQuery;
import org.yanoproject.api.events.BlockAppliedEvent;
import org.yanoproject.api.util.StoredBlockUtil;
import org.yanoproject.runtime.assembly.Yano;

/**
 * Starts Yano after the indexer has caught up with the blocks Yano already stored, and stops
 * it before Spring closes the database.
 *
 * <p>Yano publishes {@code BlockAppliedEvent} after storing a block. If the application stopped
 * between the two, Yano has blocks the indexer never saw, and after a rollback the indexer may
 * hold blocks Yano no longer has. Startup repairs both from Yano's own block store before any
 * new events arrive.
 */
@Component
public class YanoNodeLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(YanoNodeLifecycle.class);

    private final Yano yano;
    private final MetadataIndexer indexer;
    private final MetadataStore store;
    private volatile boolean running;

    public YanoNodeLifecycle(Yano yano, MetadataIndexer indexer, MetadataStore store) {
        this.yano = yano;
        this.indexer = indexer;
        this.store = store;
    }

    @Override
    public void start() {
        // Register before start() so no event is published without a listener.
        yano.chain().registerListeners(indexer);
        catchUp(yano.chain());
        yano.start();
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        yano.stop();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    void catchUp(ChainQuery chain) {
        ChainTip tip = chain.getLocalTip();
        long tipNumber = tip != null ? tip.getBlockNumber() : -1;

        // Walk back to the newest block both sides agree on and drop anything after it.
        long agreed = Math.min(tipNumber, store.lastBlock().map(MetadataStore.IndexedBlock::blockNumber).orElse(-1L));
        while (agreed >= 0 && !sameBlock(chain, agreed)) {
            agreed--;
        }
        long agreedSlot = agreed >= 0 ? store.block(agreed).orElseThrow().slot() : -1;
        store.rollbackToSlot(agreedSlot);

        if (agreed < tipNumber) {
            log.info("Catching up from block {} to {}", agreed + 1, tipNumber);
        }
        for (long number = agreed + 1; number <= tipNumber; number++) {
            BlockAppliedEvent block = readBlock(chain, number);
            if (block != null) {
                indexer.index(block);
            }
            if (number % 100_000 == 0) {
                log.info("Caught up to block {} of {}", number, tipNumber);
            }
        }
    }

    private boolean sameBlock(ChainQuery chain, long number) {
        String indexed = store.block(number).map(MetadataStore.IndexedBlock::blockHash).orElse(null);
        return indexed != null && chain.getCanonicalBlockReference(number)
                .map(ref -> indexed.equals(HexUtil.encodeHexString(ref.blockHash())))
                .orElse(false);
    }

    /** Returns the stored block as Yano published it, or null for a Byron block. */
    private static BlockAppliedEvent readBlock(ChainQuery chain, long number) {
        byte[] body = chain.getBlockByNumber(number);
        if (body == null) {
            // Byron's genesis boundary block is not stored under a block number.
            if (number == 0) {
                return null;
            }
            throw new IllegalStateException("Yano has no body for block " + number
                    + "; keep yano.chain.block-body-prune-depth at 0 to rebuild the index");
        }
        if (StoredBlockUtil.isStoredByronBlock(null, body)) {
            return null;
        }
        Block block = BlockSerializer.INSTANCE.deserialize(body);
        HeaderBody header = block.getHeader().getHeaderBody();
        return new BlockAppliedEvent(block.getEra(), header.getSlot(), number, header.getBlockHash(), block);
    }
}
