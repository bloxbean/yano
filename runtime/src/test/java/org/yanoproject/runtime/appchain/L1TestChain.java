package org.yanoproject.runtime.appchain;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.yanoproject.api.CanonicalBlockReference;
import org.yanoproject.api.ChainBlockReader;
import org.yanoproject.runtime.blockproducer.DevnetBlockBuilder;
import org.yanoproject.runtime.chain.InMemoryChainState;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.LongConsumer;

/**
 * A small L1 for delivery tests: real Conway blocks in an in-memory chain state, with forks of any depth, and hooks
 * that run inside the reader so a test can land a fork in the middle of a delivery step.
 */
final class L1TestChain {
    private final InMemoryChainState chain = new InMemoryChainState();
    private final InvalidMarkingBuilder builder = new InvalidMarkingBuilder();
    private final List<CanonicalBlockReference> blocks = new ArrayList<>();
    /** Runs at the start of every body read. */
    Runnable beforeBodyRead = () -> { };
    /** Runs at the start of every canonical-reference read, with the block number asked for. */
    LongConsumer beforeReferenceRead = number -> { };
    /** Bodies below this block number read as pruned. */
    long earliestRetained;
    /** When set, the body tip the reader reports, as if later blocks had headers and index entries only. */
    Long bodyTipBlock;
    /** When set, the oldest indexed slot the reader reports, as if older history had been restored away. */
    Long earliestIndexedSlot;
    boolean sequenceSupported = true;

    /** Appends one empty block per slot to the canonical chain. */
    void append(long... slots) {
        for (long slot : slots) {
            appendWithTransactions(slot, List.of(), Set.of());
        }
    }

    /** Appends one block with the given transactions; indexes in {@code invalid} are phase-2 invalid. */
    CanonicalBlockReference appendWithTransactions(long slot, List<byte[]> transactions, Set<Integer> invalid) {
        long number = blocks.size();
        byte[] previous = blocks.isEmpty() ? null : blocks.getLast().blockHash();
        builder.invalid = invalid;
        var built = builder.buildBlock(number, slot, previous, transactions);
        chain.storeBlockHeader(built.blockHash(), number, slot, built.wrappedHeaderCbor());
        chain.storeBlock(built.blockHash(), number, slot, built.blockCbor());
        CanonicalBlockReference reference = new CanonicalBlockReference(number, slot, built.blockHash());
        blocks.add(reference);
        return reference;
    }

    /** Appends one Byron main block per slot: a {@code [1, ...]} envelope, which the node never parses for phases. */
    void appendByron(long... slots) {
        for (long slot : slots) {
            long number = blocks.size();
            Array header = new Array();
            header.add(new UnsignedInteger(number));
            header.add(new UnsignedInteger(slot));
            Array envelope = new Array();
            envelope.add(new UnsignedInteger(1));
            envelope.add(header);
            byte[] body = CborSerializationUtil.serialize(envelope);
            byte[] hash = Blake2bUtil.blake2bHash256(body);
            chain.storeBlockHeader(hash, number, slot, body);
            chain.storeBlock(hash, number, slot, body);
            blocks.add(new CanonicalBlockReference(number, slot, hash));
        }
    }

    /** A minimal transaction ({@code [body, witnesses, true, null]}); {@code nonce} makes its hash unique. */
    static byte[] sampleTransaction(int nonce) {
        Map body = new Map();
        Array inputs = new Array();
        Array input = new Array();
        input.add(new ByteString(new byte[32]));
        input.add(new UnsignedInteger(nonce));
        inputs.add(input);
        body.put(new UnsignedInteger(0), inputs);
        Array outputs = new Array();
        Map output = new Map();
        output.put(new UnsignedInteger(0), new ByteString(new byte[28]));
        output.put(new UnsignedInteger(1), new UnsignedInteger(1_000_000));
        outputs.add(output);
        body.put(new UnsignedInteger(1), outputs);
        body.put(new UnsignedInteger(2), new UnsignedInteger(200_000));
        Array transaction = new Array();
        transaction.add(body);
        transaction.add(new Map());
        transaction.add(SimpleValue.TRUE);
        transaction.add(SimpleValue.NULL);
        return CborSerializationUtil.serialize(transaction);
    }

    /** The Cardano transaction id: Blake2b-256 of the body CBOR. */
    static String transactionHash(byte[] transaction) {
        Array decoded = (Array) CborSerializationUtil.deserializeOne(transaction);
        return HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(
                CborSerializationUtil.serialize(decoded.getDataItems().getFirst())));
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

    /**
     * Rolls back to block {@code keep} and replaces block {@code keep + 1} at its own slot with a different block
     * (it carries a transaction, so its hash differs), then appends {@code laterSlots}.
     */
    void forkAtSameSlot(long keep, long... laterSlots) {
        long slot = block(keep + 1).slot();
        fork(keep);
        appendWithTransactions(slot, List.of(sampleTransaction(1_000 + blocks.size())), Set.of());
        append(laterSlots);
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
                if (bodyTipBlock == null) {
                    return chain.getTip();
                }
                CanonicalBlockReference tip = block(bodyTipBlock);
                return new ChainTip(tip.slot(), tip.blockHash(), tip.blockNumber());
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
                beforeReferenceRead.accept(blockNumber);
                return chain.getCanonicalBlockReference(blockNumber);
            }

            @Override
            public Optional<CanonicalBlockReference> getCanonicalBlockReferenceAtSlot(long slot) {
                return chain.getCanonicalBlockReferenceAtSlot(slot);
            }

            @Override
            public OptionalLong canonicalMutationSequence() {
                return sequenceSupported ? chain.canonicalMutationSequence() : OptionalLong.empty();
            }

            @Override
            public OptionalLong getEarliestRetainedBodyBlockNumber() {
                return OptionalLong.of(earliestRetained);
            }

            @Override
            public OptionalLong getEarliestIndexedSlot() {
                return earliestIndexedSlot != null ? OptionalLong.of(earliestIndexedSlot) : OptionalLong.empty();
            }
        };
    }

    /** Marks chosen transaction indexes phase-2 invalid, as a real block's invalid_transactions list would. */
    private static final class InvalidMarkingBuilder extends DevnetBlockBuilder {
        private Set<Integer> invalid = Set.of();

        @Override
        protected BlockBodyResult computeBlockBody(List<byte[]> transactions) {
            BlockBodyResult body = super.computeBlockBody(transactions);
            if (invalid.isEmpty()) {
                return body;
            }
            Array invalidTransactions = new Array();
            invalid.stream().sorted().forEach(index -> invalidTransactions.add(new UnsignedInteger(index)));
            return new BlockBodyResult(body.txBodiesArray(), body.txWitnessesArray(), body.auxDataMap(),
                    invalidTransactions, body.bodySize(), body.bodyHash());
        }
    }
}
