package com.bloxbean.cardano.yano.api.events;

import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.events.api.Event;

import java.util.Objects;

/**
 * Carries an already-decoded Byron epoch-boundary block to projection history.
 *
 * <p>This is a <strong>separate event type</strong> rather than a change to
 * {@link BlockAppliedEvent}. That event is published with a {@code null} block for both
 * Byron main blocks and epoch-boundary blocks, and five unrelated consumers treat that
 * {@code null} as a load-bearing "skip this" sentinel — the UTXO store, the UTXO
 * processor, the mempool eviction policy, the in-memory account state store, and the
 * app-chain subsystem. Overloading the sentinel would change behaviour for all of them;
 * a new type leaves every one untouched.
 *
 * <p>Main blocks are projected transactionally by the UTXO subsystem through
 * {@link ByronMainBlockAppliedEvent}. This carrier deliberately represents only EBBs,
 * which have no UTXO transition with whose write batch they could be committed.
 */
public final class ByronBlockProjectionEvent implements Event {
    private final long slot;
    private final long blockNumber;
    private final String blockHash;
    private final String prevBlockHash;
    private final ByronEbBlock epochBoundaryBlock;

    private ByronBlockProjectionEvent(long slot, long blockNumber, String blockHash, String prevBlockHash,
                                      ByronEbBlock epochBoundaryBlock) {
        this.slot = slot;
        this.blockNumber = blockNumber;
        this.blockHash = blockHash;
        this.prevBlockHash = prevBlockHash;
        this.epochBoundaryBlock = Objects.requireNonNull(epochBoundaryBlock,
                "epoch-boundary block is required");
    }

    public static ByronBlockProjectionEvent epochBoundary(long slot, long blockNumber, String blockHash,
                                                          String prevBlockHash, ByronEbBlock block) {
        return new ByronBlockProjectionEvent(slot, blockNumber, blockHash, prevBlockHash, block);
    }

    public long slot() { return slot; }
    public long blockNumber() { return blockNumber; }
    public String blockHash() { return blockHash; }
    public String prevBlockHash() { return prevBlockHash; }
    public ByronEbBlock epochBoundaryBlock() { return epochBoundaryBlock; }
}
