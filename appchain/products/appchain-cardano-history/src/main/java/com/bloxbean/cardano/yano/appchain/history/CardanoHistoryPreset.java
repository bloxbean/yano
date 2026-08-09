package com.bloxbean.cardano.yano.appchain.history;

import java.util.Arrays;

/** Immutable released Cardano History product presets. */
public enum CardanoHistoryPreset {
    PARAMS_ONLY("params-only-v1", false, false),
    PARAMS_STAKE("params-stake-v1", true, false),
    PARAMS_GOVERNANCE("params-governance-v1", false, true),
    FULL("full-v1", true, true);

    private final String id;
    private final boolean stake;
    private final boolean governance;

    CardanoHistoryPreset(String id, boolean stake, boolean governance) {
        this.id = id;
        this.stake = stake;
        this.governance = governance;
    }

    public String id() { return id; }
    public boolean stake() { return stake; }
    public boolean governance() { return governance; }

    public static CardanoHistoryPreset require(String id) {
        return Arrays.stream(values()).filter(value -> value.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unsupported Cardano History preset: " + id));
    }
}
