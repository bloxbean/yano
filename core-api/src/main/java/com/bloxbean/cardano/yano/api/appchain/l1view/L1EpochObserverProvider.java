package com.bloxbean.cardano.yano.api.appchain.l1view;

import java.util.Map;

/** Plugin factory selected by {@code observers.<id>.type}. */
public interface L1EpochObserverProvider {
    String type();

    L1EpochObserver create(String observerId, Map<String, String> settings);
}
