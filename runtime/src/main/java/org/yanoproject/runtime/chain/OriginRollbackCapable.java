package org.yanoproject.runtime.chain;

/**
 * Clears all chain-state data back to origin.
 */
public interface OriginRollbackCapable {
    void rollbackToOrigin();
}
