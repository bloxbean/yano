package org.yanoproject.api.appchain.topics;

import org.yanoproject.api.appchain.topics.TopicWire.KeySet;
import org.yanoproject.api.appchain.topics.TopicWire.Settings;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code topics:} block of a chain's configuration (ADR app-layer/039 D1, D4, D14). It is the
 * genesis seed only: after genesis the committed {@code ~yano/topics/v1/config} record is the authority.
 * Registrar keys are a comma-separated list of 32-byte hex keys.
 */
public final class TopicChainSettings {
    public static final String PREFIX = "topics.";
    public static final String MODE = "topics.mode";
    public static final String GOVERNANCE = "topics.creation.governance";
    public static final String REGISTRAR_THRESHOLD = "topics.creation.registrars.threshold";
    public static final String REGISTRAR_KEYS = "topics.creation.registrars.keys";
    public static final String OPEN_CLAIMS = "topics.creation.open-claims";
    public static final String UNREGISTERED_TOPICS = "topics.unregistered-topics";
    public static final String MAX_DELEGATION_MS = "topics.max-delegation-ms";
    public static final String MAX_COMMAND_MS = "topics.max-command-ms";
    public static final String MAX_PENDING_PER_ENTRY = "topics.max-pending-per-entry";
    public static final String MAX_ENTRIES = "topics.max-entries";
    public static final String MAX_ENTRIES_PER_NAMESPACE = "topics.max-entries-per-namespace";
    public static final String ACTIVATION_LAG = "topics.activation-lag";
    public static final String GOVERNANCE_WINDOW_BLOCKS = "topics.governance-window-blocks";

    /** Every key of the strict, core-owned {@code topics.} domain. */
    public static final Set<String> KEYS = Set.of(MODE, GOVERNANCE, REGISTRAR_THRESHOLD, REGISTRAR_KEYS,
            OPEN_CLAIMS, UNREGISTERED_TOPICS, MAX_DELEGATION_MS, MAX_COMMAND_MS, MAX_PENDING_PER_ENTRY,
            MAX_ENTRIES, MAX_ENTRIES_PER_NAMESPACE, ACTIVATION_LAG, GOVERNANCE_WINDOW_BLOCKS);

    private TopicChainSettings() {
    }

    /** The genesis seed when {@code topics.mode=enabled}; empty when the mode is absent or disabled. */
    public static Optional<Settings> genesisSeed(Map<String, String> settings) {
        Map<String, String> values = settings != null ? settings : Map.of();
        String mode = values.getOrDefault(MODE, "disabled").trim().toLowerCase(Locale.ROOT);
        if (mode.equals("disabled")) {
            return Optional.empty();
        }
        if (!mode.equals("enabled")) {
            throw new IllegalArgumentException(MODE + " must be 'enabled' or 'disabled'");
        }
        String unregistered = values.getOrDefault(UNREGISTERED_TOPICS, "reject").trim().toLowerCase(Locale.ROOT);
        if (!unregistered.equals("reject") && !unregistered.equals("open")) {
            throw new IllegalArgumentException(UNREGISTERED_TOPICS + " must be 'reject' or 'open'");
        }
        return Optional.of(new Settings(
                bool(values, GOVERNANCE, true),
                registrars(values),
                bool(values, OPEN_CLAIMS, false),
                unregistered.equals("reject"),
                positive(values, MAX_DELEGATION_MS, 3_600_000),
                positive(values, MAX_COMMAND_MS, 604_800_000),
                positive(values, MAX_PENDING_PER_ENTRY, 4),
                positive(values, MAX_ENTRIES, 100_000),
                positive(values, MAX_ENTRIES_PER_NAMESPACE, 1_000),
                positive(values, ACTIVATION_LAG, 10),
                positive(values, GOVERNANCE_WINDOW_BLOCKS, Settings.DEFAULT_GOVERNANCE_WINDOW_BLOCKS)));
    }

    private static KeySet registrars(Map<String, String> values) {
        String keys = values.getOrDefault(REGISTRAR_KEYS, "").trim();
        if (keys.isEmpty() || keys.equalsIgnoreCase("none")) {
            if (values.containsKey(REGISTRAR_THRESHOLD)) {
                throw new IllegalArgumentException(REGISTRAR_THRESHOLD + " needs " + REGISTRAR_KEYS);
            }
            return null;
        }
        List<String> sorted = Arrays.stream(keys.split("\\s*,\\s*"))
                .map(key -> key.toLowerCase(Locale.ROOT)).sorted().toList();
        long threshold = positive(values, REGISTRAR_THRESHOLD, 1);
        if (threshold > TopicWire.MAX_KEYS) {
            throw new IllegalArgumentException(REGISTRAR_THRESHOLD + " must be at most " + TopicWire.MAX_KEYS);
        }
        return new KeySet((int) threshold, sorted);
    }

    private static boolean bool(Map<String, String> values, String key, boolean fallback) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(key + " must be true or false");
        };
    }

    private static long positive(Map<String, String> values, String key, long fallback) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed < 1) {
                throw new IllegalArgumentException(key + " must be positive");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer", e);
        }
    }
}
