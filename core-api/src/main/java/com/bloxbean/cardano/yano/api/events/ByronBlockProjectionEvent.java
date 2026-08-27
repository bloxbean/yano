package com.bloxbean.cardano.yano.api.events;

import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.events.api.Event;

/**
 * Carries an already-decoded Byron block to projection history (ADR-039 §3).
 *
 * <p>This is a <strong>separate event type</strong> rather than a change to
 * {@link BlockAppliedEvent}. That event is published with a {@code null} block for both
 * Byron main blocks and epoch-boundary blocks, and five unrelated consumers treat that
 * {@code null} as a load-bearing "skip this" sentinel — the UTXO store, the UTXO
 * processor, the mempool eviction policy, the in-memory account state store, and the
 * app-chain subsystem. Overloading the sentinel would change behaviour for all of them;
 * a new type leaves every one untouched.
 *
 * <p>The block is carried already decoded because canonical application has just parsed
 * it. Publishing the raw model costs one allocation and a bus dispatch, and it means a
 * node with history disabled pays nothing: no subscriber, no work.
 *
 * <p>Exactly one of {@link #mainBlock()} and {@link #epochBoundaryBlock()} is non-null.
 * An epoch-boundary block carries no transactions but does occupy a block number, so it
 * must still produce an (empty) projection envelope or the archive's contiguous block
 * coordinate acquires a permanent hole.
 */
public final class ByronBlockProjectionEvent implements Event {
    private final long slot;
    private final long blockNumber;
    private final String blockHash;
    private final String prevBlockHash;
    private final ByronMainBlock mainBlock;
    private final ByronEbBlock epochBoundaryBlock;

    private ByronBlockProjectionEvent(long slot, long blockNumber, String blockHash, String prevBlockHash,
                                      ByronMainBlock mainBlock, ByronEbBlock epochBoundaryBlock) {
        this.slot = slot;
        this.blockNumber = blockNumber;
        this.blockHash = blockHash;
        this.prevBlockHash = prevBlockHash;
        this.mainBlock = mainBlock;
        this.epochBoundaryBlock = epochBoundaryBlock;
    }

    public static ByronBlockProjectionEvent main(long slot, long blockNumber, String blockHash,
                                                 String prevBlockHash, ByronMainBlock block) {
        if (block == null) throw new IllegalArgumentException("main block is required");
        return new ByronBlockProjectionEvent(slot, blockNumber, blockHash, prevBlockHash, block, null);
    }

    public static ByronBlockProjectionEvent epochBoundary(long slot, long blockNumber, String blockHash,
                                                          String prevBlockHash, ByronEbBlock block) {
        if (block == null) throw new IllegalArgumentException("epoch-boundary block is required");
        return new ByronBlockProjectionEvent(slot, blockNumber, blockHash, prevBlockHash, null, block);
    }

    public long slot() { return slot; }
    public long blockNumber() { return blockNumber; }
    public String blockHash() { return blockHash; }
    public String prevBlockHash() { return prevBlockHash; }
    public ByronMainBlock mainBlock() { return mainBlock; }
    public ByronEbBlock epochBoundaryBlock() { return epochBoundaryBlock; }

    public boolean isEpochBoundary() { return epochBoundaryBlock != null; }
}
