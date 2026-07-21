package com.bloxbean.cardano.yano.appchain.config;

import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Side-effect-free parser used by both the runtime and offline tooling.
 * Input keys are relative to {@code yano.app-chain.}; for example
 * {@code chain-id} and {@code effects.enabled}.
 */
public final class AppChainConfigParser {
    private static final List<String> FRAMEWORK_SUFFIXES = List.of(
            "chain-id", "signing-key", "members", "peers",
            "max-message-bytes", "max-ttl-seconds", "default-ttl-seconds",
            "sequencer.proposer", "threshold", "block.interval-ms",
            "block.max-messages", "block.max-bytes", "state-machine",
            "anchor.enabled", "anchor.signing-key", "anchor.every-blocks",
            "anchor.max-interval-minutes", "anchor.metadata-label",
            "anchor.validity-slots", "anchor.fallback-fee-lovelace",
            "anchor.mode", "anchor.script.validator", "anchor.script.thread-policy",
            "l1.stability-depth", "webhooks", "retention.enabled",
            "retention.keep-blocks", "pool.max-messages",
            "message.enforce-sender-seq");

    private static final List<String> DYNAMIC_PREFIXES = List.of(
            "sinks.", "zk.", "machines.", "sequencer.", "membership.",
            "observers.", "transport.", "effects.");

    /*
     * Narrow ownership domains whose complete key set is parsed and parity-tested by Yano.
     * Open plugin namespaces deliberately stay out of this list.
     */
    private static final Map<String, Set<String>> STRICT_OWNERSHIP_DOMAINS = Map.of(
            "effects.result.", Set.of("effects.result.signers"));

    private AppChainConfigParser() {
    }

    /** Exact framework suffixes read by the runtime adapter. */
    public static List<String> frameworkSuffixes() {
        return FRAMEWORK_SUFFIXES;
    }

    /** Extension namespaces forwarded to app-chain providers. */
    public static List<String> dynamicPrefixes() {
        return DYNAMIC_PREFIXES;
    }

    /** Prefixes where strict unknown-key rejection is safe for this runtime release. */
    public static Set<String> strictOwnershipDomains() {
        return STRICT_OWNERSHIP_DOMAINS.keySet();
    }

    /** Complete exact property set accepted inside strict ownership domains. */
    public static Set<String> strictProperties() {
        return STRICT_OWNERSHIP_DOMAINS.values().stream()
                .flatMap(Set::stream)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Reject unknown keys only in FULL, runtime-owned domains. */
    public static void validateStrict(Map<String, ?> settings) {
        if (settings == null) {
            throw new IllegalArgumentException("settings must not be null");
        }
        for (String key : settings.keySet()) {
            for (Map.Entry<String, Set<String>> domain : STRICT_OWNERSHIP_DOMAINS.entrySet()) {
                if (key.startsWith(domain.getKey()) && !domain.getValue().contains(key)) {
                    throw new IllegalArgumentException("Unknown app-chain property in strict "
                            + "runtime-owned domain: " + key);
                }
            }
        }
    }

    /** Parse one flat or indexed chain after its canonical prefix has been removed. */
    public static AppChainConfig parse(Map<String, ?> settings) {
        if (settings == null) {
            throw new IllegalArgumentException("settings must not be null");
        }
        Set<String> memberKeys = commaSeparatedSet(settings.get("members"));
        List<AppChainConfig.AppPeer> peers = new ArrayList<>();
        for (String peer : commaSeparatedList(settings.get("peers"))) {
            peers.add(AppChainConfig.AppPeer.parse(peer));
        }
        List<String> webhookUrls = commaSeparatedList(settings.get("webhooks"));
        boolean anchorEnabled = booleanOf(settings.get("anchor.enabled"), false);

        return new AppChainConfig(
                stringOf(settings.get("chain-id"), ""),
                stringOf(settings.get("signing-key"), ""),
                memberKeys,
                peers,
                (int) parseLong(settings.get("max-message-bytes"),
                        AppChainConfig.DEFAULT_MAX_MESSAGE_BYTES),
                parseLong(settings.get("max-ttl-seconds"),
                        AppChainConfig.DEFAULT_MAX_TTL_SECONDS),
                parseLong(settings.get("default-ttl-seconds"),
                        AppChainConfig.DEFAULT_DEFAULT_TTL_SECONDS),
                stringOf(settings.get("sequencer.proposer"), ""),
                (int) parseLong(settings.get("threshold"), 1),
                parseLong(settings.get("block.interval-ms"),
                        AppChainConfig.DEFAULT_BLOCK_INTERVAL_MS),
                (int) parseLong(settings.get("block.max-messages"),
                        AppChainConfig.DEFAULT_MAX_BLOCK_MESSAGES),
                parseLong(settings.get("block.max-bytes"),
                        AppChainConfig.DEFAULT_BLOCK_MAX_BYTES),
                stringOf(settings.get("state-machine"), AppChainConfig.DEFAULT_STATE_MACHINE),
                null,
                anchorEnabled
                        ? new AppChainConfig.AnchorConfig(
                                true,
                                stringOf(settings.get("anchor.signing-key"), ""),
                                parseLong(settings.get("anchor.every-blocks"), 10),
                                parseLong(settings.get("anchor.max-interval-minutes"), 60),
                                parseLong(settings.get("anchor.metadata-label"), 7014),
                                parseLong(settings.get("anchor.validity-slots"),
                                        AppChainConfig.AnchorConfig.DEFAULT_VALIDITY_SLOTS),
                                parseLong(settings.get("anchor.fallback-fee-lovelace"),
                                        AppChainConfig.AnchorConfig.DEFAULT_FALLBACK_FEE_LOVELACE),
                                stringOf(settings.get("anchor.mode"),
                                        AppChainConfig.AnchorConfig.MODE_METADATA),
                                new AppChainConfig.AnchorScriptConfig(
                                        stringOf(settings.get("anchor.script.validator"), ""),
                                        stringOf(settings.get("anchor.script.thread-policy"), "")))
                        : null,
                (int) parseLong(settings.get("l1.stability-depth"), 0),
                webhookUrls,
                booleanOf(settings.get("retention.enabled"), false),
                (int) parseLong(settings.get("retention.keep-blocks"), 0),
                (int) parseLong(settings.get("pool.max-messages"),
                        AppChainConfig.DEFAULT_POOL_MAX_MESSAGES),
                booleanOf(settings.get("message.enforce-sender-seq"), false),
                pluginSettings(settings));
    }

    /** Return only the runtime-forwarded extension settings. */
    public static Map<String, String> pluginSettings(Map<String, ?> settings) {
        Map<String, String> result = new LinkedHashMap<>();
        settings.forEach((key, value) -> {
            if (value != null && DYNAMIC_PREFIXES.stream().anyMatch(key::startsWith)) {
                result.put(key, String.valueOf(value));
            }
        });
        return Map.copyOf(result);
    }

    private static Set<String> commaSeparatedSet(Object value) {
        return Set.copyOf(new LinkedHashSet<>(commaSeparatedList(value)));
    }

    private static List<String> commaSeparatedList(Object value) {
        List<String> result = new ArrayList<>();
        for (String item : stringOf(value, "").split(",")) {
            if (!item.isBlank()) {
                result.add(item.trim());
            }
        }
        return List.copyOf(result);
    }

    private static String stringOf(Object value, String defaultValue) {
        return value != null && !String.valueOf(value).isBlank()
                ? String.valueOf(value).trim() : defaultValue;
    }

    private static boolean booleanOf(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static long parseLong(Object value, long defaultValue) {
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value).trim());
    }
}
