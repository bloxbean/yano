package org.yanoproject.runtime.blockproducer;

import com.bloxbean.cardano.yaci.core.storage.ChainTip;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Whether the local chain is caught up, so that a block forged on its tip extends the chain the network sees.
 */
@FunctionalInterface
public interface ForgingReadiness {

    /** A producer without an upstream: its own chain is the best chain. */
    ForgingReadiness STANDALONE = localTip -> true;

    /**
     * @param localTip the local (body) tip, or {@code null} before the first block
     * @return true when the producer may forge on {@code localTip}
     */
    boolean isCaughtUp(ChainTip localTip);

    /**
     * A producer that follows an upstream is caught up when initial sync is complete, no received header is
     * waiting for its body, and the local tip has reached the tip upstream last announced. It never forges during
     * initial sync, whatever the distance to the wall clock.
     *
     * <p>No header may wait for its body because the local store is linear by block number: a forged block stored
     * beside a pending upstream header of the same height would corrupt the chain. Together with the producer's own
     * rules (the slot is after the tip and after the last forged slot) this is the minimal safe condition. The
     * upstream-tip term is redundant in steady state, where the announced tip's header arrives with it; it keeps
     * the producer off while the upstream has announced a tip whose header is not stored yet.</p>
     *
     * @param initialSyncComplete whether initial sync has completed
     * @param headerTip           the best header received, or {@code null}
     * @param upstreamTipSlot     the slot of the tip upstream last announced, or a negative value when none is known
     */
    static ForgingReadiness upstream(BooleanSupplier initialSyncComplete, Supplier<ChainTip> headerTip,
                                     LongSupplier upstreamTipSlot) {
        Objects.requireNonNull(initialSyncComplete, "initialSyncComplete");
        Objects.requireNonNull(headerTip, "headerTip");
        Objects.requireNonNull(upstreamTipSlot, "upstreamTipSlot");
        return localTip -> {
            if (localTip == null || !initialSyncComplete.getAsBoolean()) {
                return false;
            }
            ChainTip header = headerTip.get();
            if (header != null && (header.getSlot() > localTip.getSlot()
                    || header.getSlot() == localTip.getSlot()
                    && !Arrays.equals(header.getBlockHash(), localTip.getBlockHash()))) {
                return false;
            }
            return upstreamTipSlot.getAsLong() <= localTip.getSlot();
        };
    }
}
