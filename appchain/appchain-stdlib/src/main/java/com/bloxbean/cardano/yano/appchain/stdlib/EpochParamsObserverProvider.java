package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochObserver;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochObserverProvider;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochParamsContract;

import java.util.Map;

/** Plugin provider for the out-of-box epoch-params observer. */
public final class EpochParamsObserverProvider implements L1EpochObserverProvider {
    @Override public String type() { return EpochParamsContract.OBSERVER_TYPE; }
    @Override public L1EpochObserver create(String observerId, Map<String, String> settings) {
        return new EpochParamsObserver(observerId);
    }
}
