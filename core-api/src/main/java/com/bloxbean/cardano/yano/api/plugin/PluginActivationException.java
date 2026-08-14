package com.bloxbean.cardano.yano.api.plugin;

import java.util.Objects;

/**
 * Stable startup-fatal boundary for a selected plugin provider or one of its
 * configured products that cannot be activated safely.
 *
 * <p>Catalog-backed failures retain the complete contribution identity so an
 * application can fail closed while still producing an actionable diagnostic.</p>
 */
public class PluginActivationException extends IllegalStateException {
    private final String bundleId;
    private final String contributionKind;
    private final String selector;
    private final String providerClass;

    public PluginActivationException(String message, Throwable cause) {
        this(message, null, null, null, null, cause);
    }

    public PluginActivationException(
            String message,
            String bundleId,
            String contributionKind,
            String selector,
            String providerClass,
            Throwable cause
    ) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.bundleId = bundleId;
        this.contributionKind = contributionKind;
        this.selector = selector;
        this.providerClass = providerClass;
    }

    public String bundleId() {
        return bundleId;
    }

    public String contributionKind() {
        return contributionKind;
    }

    public String selector() {
        return selector;
    }

    public String providerClass() {
        return providerClass;
    }
}
