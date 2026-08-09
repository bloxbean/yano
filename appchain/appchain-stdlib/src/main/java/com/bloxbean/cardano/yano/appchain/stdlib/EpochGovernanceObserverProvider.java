package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochObserver;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochObserverProvider;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochGovernanceContract;

import java.util.Map;

/** Plugin provider for the out-of-box governance-history observer. */
public final class EpochGovernanceObserverProvider implements L1EpochObserverProvider {
    @Override public String type() { return EpochGovernanceContract.OBSERVER_TYPE; }
    @Override public L1EpochObserver create(String observerId, Map<String, String> settings) {
        boolean proposals = strictBoolean(settings, "include-proposals", true);
        boolean dreps = strictBoolean(settings, "include-drep-distribution", false);
        int chunks;
        try { chunks = Integer.parseInt(settings.getOrDefault("drep-chunk-entries",
                Integer.toString(EpochGovernanceContract.DEFAULT_DREP_CHUNK_ENTRIES))); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("drep-chunk-entries must be an integer", e); }
        return new EpochGovernanceObserver(observerId, proposals, dreps, chunks);
    }
    private static boolean strictBoolean(Map<String, String> settings, String key, boolean fallback) {
        String value = settings.get(key); if (value == null) return fallback;
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException(key + " must be true or false");
    }
}
