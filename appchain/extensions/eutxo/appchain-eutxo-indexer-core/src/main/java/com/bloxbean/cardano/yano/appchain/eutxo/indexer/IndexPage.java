package com.bloxbean.cardano.yano.appchain.eutxo.indexer;

import java.util.List;

public record IndexPage<T>(List<T> items, long nextBefore, boolean hasMore) {
    public IndexPage {
        items = List.copyOf(items);
        if (nextBefore < 0) {
            throw new IllegalArgumentException("nextBefore cannot be negative");
        }
    }
}
