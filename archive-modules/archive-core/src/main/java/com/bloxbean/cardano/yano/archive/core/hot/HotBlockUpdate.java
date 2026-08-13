package com.bloxbean.cardano.yano.archive.core.hot;

import java.util.List;

/** One block's mutations inside an atomic multi-block history batch. */
public record HotBlockUpdate(HotBlockCheckpoint checkpoint, List<HotHistoryMutation> mutations) {
    public HotBlockUpdate { mutations = List.copyOf(mutations); }
}
