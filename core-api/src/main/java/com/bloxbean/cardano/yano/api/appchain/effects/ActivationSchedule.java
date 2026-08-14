package com.bloxbean.cardano.yano.api.appchain.effects;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Height-gated activation of transition-logic changes (ADR app-layer/010.1).
 * Parsed from chain settings — keys of the form
 * {@code machines.<machine-id>.activations.<change-name>=<height>} — which are
 * consensus-affecting and must be identical on every member.
 * <p>
 * A change that is absent from configuration is NEVER active, so deploying a
 * jar whose new branches are all gated is safe before the config lands
 * (010.1-D2). Replay selects the branch by the block's height, which is
 * deterministic on every node forever.
 */
public final class ActivationSchedule {

    private static final ActivationSchedule EMPTY = new ActivationSchedule(Map.of());

    private final Map<String, Long> activations;

    private ActivationSchedule(Map<String, Long> activations) {
        this.activations = Map.copyOf(activations);
    }

    /**
     * Parse every {@code machines.<machineId>.activations.<name>} entry from a
     * chain's settings map (the {@code AppStateMachineContext.settings()} map).
     */
    public static ActivationSchedule from(Map<String, String> settings, String machineId) {
        return fromPrefix(settings, "machines." + machineId + ".activations.");
    }

    /** Parse activations under an explicit prefix (framework use: {@code effects.activations.}). */
    public static ActivationSchedule fromPrefix(Map<String, String> settings, String prefix) {
        if (settings == null || settings.isEmpty()) {
            return EMPTY;
        }
        Map<String, Long> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }
            String name = entry.getKey().substring(prefix.length());
            if (name.isBlank()) {
                continue;
            }
            long height = Long.parseLong(entry.getValue().trim());
            if (height <= 0) {
                throw new IllegalArgumentException(
                        "Activation height for '" + name + "' must be positive, got: " + height);
            }
            parsed.put(name, height);
        }
        return parsed.isEmpty() ? EMPTY : new ActivationSchedule(parsed);
    }

    public static ActivationSchedule empty() {
        return EMPTY;
    }

    /**
     * True when the named change is active at this height. Missing name ⇒
     * inactive ⇒ old behavior (the load-bearing safety default, 010.1 §2.2).
     */
    public boolean isActive(String changeName, long height) {
        Long activation = activations.get(changeName);
        return activation != null && height >= activation;
    }

    /** Configured names → activation heights, for status/diagnostic surfaces. */
    public Map<String, Long> entries() {
        return activations;
    }
}
