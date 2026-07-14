package com.bloxbean.cardano.yano.api.plugin;

/** Version constants for the public Yano plugin API. */
public final class PluginApiVersion {

    /** Current plugin API major understood by this Yano release. */
    public static final int CURRENT_MAJOR = 1;

    /**
     * Current global additive plugin API level.
     *
     * <p>This value increases whenever a release adds a public plugin API
     * symbol or contribution kind. It is monotonic across major-version
     * changes and therefore never resets when {@link #CURRENT_MAJOR} changes.</p>
     */
    public static final int CURRENT_LEVEL = 1;

    private PluginApiVersion() {
    }
}
