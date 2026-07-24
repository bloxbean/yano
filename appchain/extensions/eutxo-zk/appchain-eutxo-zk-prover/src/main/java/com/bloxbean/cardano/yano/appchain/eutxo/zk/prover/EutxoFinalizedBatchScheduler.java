package com.bloxbean.cardano.yano.appchain.eutxo.zk.prover;

import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoValidityTransition;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchProfile;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Bounded FIFO scheduler that never changes an immutable circuit batch size. */
public final class EutxoFinalizedBatchScheduler {
    private final EutxoZkBatchProfile profile;
    private final int maximumQueuedTransactions;
    private final ArrayDeque<FinalizedItem> queue =
            new ArrayDeque<>();

    public EutxoFinalizedBatchScheduler(
            EutxoZkBatchProfile profile,
            int maximumQueuedBatches
    ) {
        this.profile = Objects.requireNonNull(profile, "profile");
        if (maximumQueuedBatches < 1 || maximumQueuedBatches > 1_024) {
            throw new IllegalArgumentException(
                    "maximum queued batches must be within 1-1024");
        }
        this.maximumQueuedTransactions = Math.multiplyExact(
                profile.maximumTransactions(), maximumQueuedBatches);
    }

    public synchronized void offer(EutxoValidityTransition transition) {
        offer(FinalizedItem.from(transition));
    }

    public synchronized void offer(FinalizedItem item) {
        Objects.requireNonNull(item, "item");
        if (queue.size() >= maximumQueuedTransactions) {
            throw new IllegalStateException(
                    "EUTxO proof queue is at its immutable capacity");
        }
        queue.addLast(item);
    }

    public synchronized List<FinalizedItem> drainBatch() {
        if (queue.isEmpty()) {
            return List.of();
        }
        int count = Math.min(
                profile.maximumTransactions(), queue.size());
        List<FinalizedItem> batch = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            batch.add(queue.removeFirst());
        }
        return List.copyOf(batch);
    }

    public synchronized int queuedTransactions() {
        return queue.size();
    }

    public synchronized int queuedBatches() {
        int bound = profile.maximumTransactions();
        return (queue.size() + bound - 1) / bound;
    }

    public EutxoZkBatchProfile profile() {
        return profile;
    }

    public record FinalizedItem(String transitionDigest, byte[] witness) {
        public FinalizedItem {
            Objects.requireNonNull(witness, "witness");
            witness = witness.clone();
            if (witness.length < 1 || witness.length > 1024 * 1024) {
                throw new IllegalArgumentException(
                        "finalized witness is empty or too large");
            }
            String computed = HexFormat.of().formatHex(
                    Blake2bUtil.blake2bHash256(witness));
            if (!computed.equals(transitionDigest)) {
                throw new IllegalArgumentException(
                        "finalized witness digest mismatch");
            }
        }

        public static FinalizedItem from(
                EutxoValidityTransition transition
        ) {
            Objects.requireNonNull(transition, "transition");
            return new FinalizedItem(
                    HexFormat.of().formatHex(transition.digest()),
                    transition.canonicalBytes());
        }

        @Override
        public byte[] witness() {
            return witness.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof FinalizedItem item
                    && transitionDigest.equals(item.transitionDigest)
                    && Arrays.equals(witness, item.witness);
        }

        @Override
        public int hashCode() {
            return 31 * transitionDigest.hashCode()
                    + Arrays.hashCode(witness);
        }
    }
}
