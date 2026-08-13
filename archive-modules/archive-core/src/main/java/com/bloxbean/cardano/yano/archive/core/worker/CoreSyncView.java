package com.bloxbean.cardano.yano.archive.core.worker;

/** Read-only core sync coordinates used only to yield optional bulk history work. */
public interface CoreSyncView {
    long localBlock();
    long targetBlock();

    default long lag() { return Math.max(0, targetBlock() - localBlock()); }
}
