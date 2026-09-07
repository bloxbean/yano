package com.bloxbean.cardano.yano.api.utxo.index;

import com.bloxbean.cardano.yano.api.chain.ChainPoint;
import com.bloxbean.cardano.yano.api.genesis.GenesisUtxo;
import java.util.List;

/** Synchronous participant. Persist undo explicitly; do not mutate authoritative caches. */
public interface UtxoIndexContributor extends AutoCloseable {
    void stageApply(UtxoChanges changes, IndexWriter writer);
    void stageRollback(ChainPoint target, IndexWriter writer);
    default void stageGenesis(String identity, List<GenesisUtxo> outputs, IndexWriter writer) { }
    default void stagePruneUndo(ChainPoint point, IndexWriter writer) { }
    /** Processing is paused; reacquire any host-owned read services, never retain native handles. */
    default void reinitialize() { }
    @Override default void close() { }
}
