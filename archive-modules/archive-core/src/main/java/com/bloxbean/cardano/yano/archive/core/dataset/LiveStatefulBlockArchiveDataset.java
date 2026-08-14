package com.bloxbean.cardano.yano.archive.core.dataset;

import com.bloxbean.cardano.yano.archive.core.hot.HotHistoryMutation;

import java.util.List;

/** Stateful dataset whose private resolver and live rows commit atomically. */
public interface LiveStatefulBlockArchiveDataset<B> extends StatefulBlockArchiveDataset<B> {
    void commitLiveBatch(List<List<HotHistoryMutation>> rowMutations);
}
