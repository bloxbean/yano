package org.yanoproject.api.plugin;

/** Failure/isolation class derived by the platform from contribution kind. */
public enum PluginTrustTier {
    REQUIRED,
    CONSENSUS,
    PRIVILEGED_LOCAL,
    AUXILIARY_LOCAL
}
