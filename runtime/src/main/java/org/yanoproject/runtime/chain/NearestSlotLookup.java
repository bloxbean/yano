package org.yanoproject.runtime.chain;

/**
 * Resolves rollback targets to stored block slots.
 */
public interface NearestSlotLookup {
    Long findNearestSlotAtOrBefore(long targetSlot);
}
