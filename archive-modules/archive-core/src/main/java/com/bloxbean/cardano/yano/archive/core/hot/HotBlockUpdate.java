package com.bloxbean.cardano.yano.archive.core.hot;

import java.util.List;

/** One block's semantic operations inside an atomic multi-block history batch. */
public record HotBlockUpdate(HotBlockCheckpoint checkpoint, List<HotHistoryOperation> operations) {
    public HotBlockUpdate { operations = List.copyOf(operations); }
}
