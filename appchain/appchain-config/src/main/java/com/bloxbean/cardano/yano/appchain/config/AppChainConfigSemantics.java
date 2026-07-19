package com.bloxbean.cardano.yano.appchain.config;

import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Startup-safe framework rules shared by the runtime and resolved-mode tooling. */
public final class AppChainConfigSemantics {
    private AppChainConfigSemantics() {
    }

    /** Validate the side-effect-free subset of runtime startup and return normalized members. */
    public static Set<String> validate(AppChainConfig config) {
        if (config.signingKeyHex() == null || config.signingKeyHex().isBlank()) {
            throw new IllegalArgumentException("app-chain signing key is required");
        }
        validateSigningKeySpec(config.signingKeyHex());
        Set<String> members = normalizeMemberKeys(config.memberKeysHex());
        if (config.threshold() < 1 || config.threshold() > members.size()) {
            throw new IllegalArgumentException("Finality threshold " + config.threshold()
                    + " must be in [1, " + members.size() + "]");
        }
        String proposer = config.proposerKeyHex().toLowerCase(Locale.ROOT);
        if (!proposer.isEmpty() && !members.contains(proposer)) {
            throw new IllegalArgumentException("Configured proposer is not in the member list");
        }
        if (config.anchor() != null && config.anchor().enabled()
                && config.anchor().signingKeyHex().isBlank()) {
            throw new IllegalArgumentException(
                    "anchor.signing-key is required when anchoring is enabled");
        }
        return members;
    }

    public static Set<String> normalizeMemberKeys(Set<String> keys) {
        Set<String> normalized = new LinkedHashSet<>();
        if (keys != null) {
            for (String key : keys) {
                String candidate = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
                if (!candidate.matches("[0-9a-f]{64}")) {
                    throw new IllegalArgumentException(
                            "App-chain member key must be a 32-byte hex Ed25519 public key");
                }
                normalized.add(candidate);
            }
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("App-chain member list must not be empty");
        }
        return Set.copyOf(normalized);
    }

    private static void validateSigningKeySpec(String value) {
        String spec = value.trim();
        int colon = spec.indexOf(':');
        if (colon >= 0) {
            // The selected SignerProviderFactory owns reference syntax.
            return;
        }
        if (!spec.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(
                    "App-chain signing key must be a 32-byte Ed25519 seed or scheme:reference");
        }
    }
}
