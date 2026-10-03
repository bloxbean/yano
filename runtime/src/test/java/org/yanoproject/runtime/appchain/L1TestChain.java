package org.yanoproject.runtime.appchain;

import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.yanoproject.api.CanonicalBlockReference;
import org.yanoproject.api.ChainBlockReader;
import org.yanoproject.runtime.blockproducer.DevnetBlockBuilder;
import org.yanoproject.runtime.chain.InMemoryChainState;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * A small L1 for delivery tests: real Conway blocks in an in-memory chain state, with forks of any depth, and hooks
 * that run inside the reader so a test can land a fork in the middle of a delivery step.
 */
final class L1TestChain {
    private final InMemoryChainState chain = new InMemoryChainState();
    private final DevnetBlockBuilder builder = new DevnetBlockBuilder();
    private final List<CanonicalBlockReference> blocks = new ArrayList<>();
    /** Runs at the start of every body read. */
    Runnable beforeBodyRead = () -> { };
    /** Bodies below this block number read as pruned. */
    long earliestRetained;
    boolean sequenceSupported = true;

    /** Appends one block per slot to the canonical chain. */
    void append(long... slots) {
        for (long slot : slots) {
            long number = blocks.size();
            byte[] previous = blocks.isEmpty() ? null : blocks.getLast().blockHash();
            var built = builder.buildBlock(number, slot, previous, List.of());
            chain.storeBlockHeader(built.blockHash(), number, slot, built.wrappedHeaderCbor());
            chain.storeBlock(built.blockHash(), number, slot, built.blockCbor());
            blocks.add(new CanonicalBlockReference(number, slot, built.blockHash()));
        }
    }

    /** Rolls the chain back to block {@code keep}, then appends a new branch at the given slots. */
    void fork(long keep, long... slots) {
        CanonicalBlockReference base = blocks.get((int) keep);
        chain.rollbackTo(new Point(base.slot(), HexUtil.encodeHexString(base.blockHash())));
        while (blocks.size() > keep + 1) {
            blocks.removeLast();
        }
        append(slots);
    }

    /** Replaces the whole chain, genesis included, with a new branch at the given slots. */
    void forkFromOrigin(long... slots) {
        chain.rollbackToOrigin();
        blocks.clear();
        append(slots);
    }

    CanonicalBlockReference block(long number) {
        return blocks.get((int) number);
    }

    L1Point point(long number) {
        return L1Point.of(block(number));
    }

    long tipNumber() {
        return blocks.size() - 1;
    }

    ChainBlockReader reader() {
        return new ChainBlockReader() {
            @Override
            public ChainTip getLocalTip() {
                return chain.getTip();
            }

            @Override
            public byte[] getBlockByNumber(long blockNumber) {
                beforeBodyRead.run();
                return blockNumber < earliestRetained ? null : chain.getBlockByNumber(blockNumber);
            }

            @Override
            public Era getBlockEra(long blockNumber) {
                return chain.getBlockEra(blockNumber);
            }

            @Override
            public Optional<CanonicalBlockReference> getCanonicalBlockReference(long blockNumber) {
                return chain.getCanonicalBlockReference(blockNumber);
            }

            @Override
            public Optional<CanonicalBlockReference> getCanonicalBlockReferenceAtSlot(long slot) {
                Long number = chain.getBlockNumberBySlot(slot);
                return number == null ? Optional.empty()
                        : chain.getCanonicalBlockReference(number).filter(reference -> reference.slot() == slot);
            }

            @Override
            public OptionalLong canonicalMutationSequence() {
                return sequenceSupported ? chain.canonicalMutationSequence() : OptionalLong.empty();
            }

            @Override
            public OptionalLong getEarliestRetainedBodyBlockNumber() {
                return OptionalLong.of(earliestRetained);
            }
        };
    }
}
