package org.yanoproject.runtime.blockproducer;

/**
 * Durable record of the highest slot this node has forged a block for, so that a restarted or rolled-back producer
 * never signs a second, different block for a slot it already forged (equivocation).
 *
 * <p>The value only increases and is not part of the chain: rollbacks leave it alone. That is safe because a
 * forged slot stays forged even when its block is rolled back, and the producer never forges a slot that is not
 * after both this value and the chain tip.</p>
 */
public interface ForgedSlotStore {

    /** @return the highest forged slot, or -1 when none was recorded */
    long getLastForgedSlot();

    /**
     * Durably record {@code slot} as forged before its block is stored; a lower value is ignored.
     *
     * @param slot the slot of the block about to be stored
     */
    void storeLastForgedSlot(long slot);
}
