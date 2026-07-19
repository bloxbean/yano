package com.bloxbean.cardano.yano.appchain.config;

import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Release-pinned registry assembled from framework and component metadata.
 *
 * <p>The framework registry is always available. First-party components and custom plugin
 * descriptors can extend it without changing CLI code.</p>
 */
public final class AppChainPropertyRegistry {
    public static final String APP_CHAIN_PREFIX = "yano.app-chain.";
    public static final String OWNER_CORE = "yano-core/appchain";

    private static final Pattern PROFILE_PREFIX = Pattern.compile("^%[^.]+\\.");
    private static final Pattern INDEXED_PATH = Pattern.compile(
            "^yano\\.app-chain\\.chains\\[(\\d+)]\\.(.+)$");

    private static final List<AppChainPropertyDefinition> FRAMEWORK_DEFINITIONS =
            frameworkDefinitions();
    private static final List<DynamicNamespaceDefinition> FRAMEWORK_DYNAMIC_NAMESPACES = List.of(
            dynamic("sinks.", "extension/sinks", "External finalized-data sinks"),
            dynamic("zk.", "extension/zk", "Zero-knowledge circuits and verification"),
            dynamic("machines.", "extension/state-machines", "State-machine configuration"),
            dynamic("sequencer.", "extension/sequencer", "Sequencer strategies"),
            dynamic("membership.", "extension/membership", "Membership strategies"),
            dynamic("observers.", "extension/observers", "L1/external observers"),
            dynamic("transport.", "yano-core/transport", "App-message transport"),
            dynamicFull("effects.result.", "yano-core/effects",
                    "Runtime-owned effect result admission"),
            dynamic("effects.", "extension/effects", "Deterministic effect runtime"));

    private static final AppChainMetadataSource FRAMEWORK_SOURCE = new AppChainMetadataSource(
            OWNER_CORE, FRAMEWORK_DEFINITIONS, FRAMEWORK_DYNAMIC_NAMESPACES);
    private static final AppChainPropertyRegistry FRAMEWORK =
            new AppChainPropertyRegistry(List.of(FRAMEWORK_SOURCE));

    private final List<AppChainMetadataSource> sources;
    private final List<AppChainPropertyDefinition> definitions;
    private final Map<String, AppChainPropertyDefinition> byKey;
    private final List<DynamicNamespaceDefinition> dynamicNamespaces;

    private AppChainPropertyRegistry(List<AppChainMetadataSource> sources) {
        if (sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("at least one metadata source is required");
        }
        var sourceIds = new java.util.HashSet<String>();
        for (AppChainMetadataSource source : sources) {
            if (!sourceIds.add(source.id())) {
                throw new IllegalStateException("Duplicate metadata source id: " + source.id());
            }
        }
        this.sources = List.copyOf(sources);
        List<AppChainPropertyDefinition> properties = sources.stream()
                .flatMap(source -> source.properties().stream())
                .sorted(Comparator.comparing(AppChainPropertyDefinition::key))
                .toList();
        this.byKey = byKey(properties);
        this.definitions = List.copyOf(properties);
        List<DynamicNamespaceDefinition> namespaces = validateNamespaces(sources);
        validateFullOwnership(properties, namespaces);
        this.dynamicNamespaces = namespaces;
    }

    /** Framework metadata shipped with this Yano release. */
    public static AppChainPropertyRegistry framework() {
        return FRAMEWORK;
    }

    /**
     * Extend the framework registry with selected declarative component/plugin metadata.
     * Duplicate property keys or namespace claims are rejected.
     */
    public static AppChainPropertyRegistry withSources(
            List<AppChainMetadataSource> additionalSources) {
        List<AppChainMetadataSource> combined = new ArrayList<>();
        combined.add(FRAMEWORK_SOURCE);
        if (additionalSources != null) {
            combined.addAll(additionalSources);
        }
        return new AppChainPropertyRegistry(combined);
    }

    /** Metadata sources in deterministic registration order. */
    public List<AppChainMetadataSource> sources() {
        return sources;
    }

    /** Immutable definitions sorted by canonical key. */
    public List<AppChainPropertyDefinition> definitions() {
        return definitions;
    }

    /** Open extension namespaces; M0a deliberately reports partial coverage. */
    public List<DynamicNamespaceDefinition> dynamicNamespaces() {
        return dynamicNamespaces;
    }

    /** Find an exact flat or {@code chains[i]} property definition. */
    public Optional<PropertyMatch> find(String requestedKey) {
        String normalized = normalizeKey(requestedKey);
        Matcher indexed = INDEXED_PATH.matcher(normalized);
        if (indexed.matches()) {
            String canonical = APP_CHAIN_PREFIX + indexed.group(2);
            AppChainPropertyDefinition definition = byKey.get(canonical);
            if (definition == null) {
                return Optional.empty();
            }
            Integer chainIndex = parseChainIndex(indexed.group(1));
            if (chainIndex == null) {
                return Optional.empty();
            }
            return Optional.of(new PropertyMatch(
                    requestedKey, normalized, canonical, chainIndex,
                    definition));
        }
        AppChainPropertyDefinition definition = byKey.get(normalized);
        if (definition == null) {
            return Optional.empty();
        }
        return Optional.of(new PropertyMatch(requestedKey, normalized, normalized, null, definition));
    }

    /** Return the partial extension namespace owning an otherwise non-exact key. */
    public Optional<DynamicNamespaceDefinition> dynamicNamespace(String requestedKey) {
        String normalized = normalizeKey(requestedKey);
        Matcher indexed = INDEXED_PATH.matcher(normalized);
        String suffix;
        if (indexed.matches()) {
            suffix = indexed.group(2);
        } else if (normalized.startsWith(APP_CHAIN_PREFIX)) {
            suffix = normalized.substring(APP_CHAIN_PREFIX.length());
        } else {
            return Optional.empty();
        }
        return dynamicNamespaces.stream()
                .filter(namespace -> suffix.startsWith(namespace.prefix()))
                .max(Comparator.comparingInt(namespace -> namespace.prefix().length()));
    }

    /** Normalize a suffix, profile-qualified key, or canonical key. */
    public static String normalizeKey(String requestedKey) {
        Objects.requireNonNull(requestedKey, "requestedKey");
        String normalized = requestedKey.trim();
        normalized = PROFILE_PREFIX.matcher(normalized).replaceFirst("");
        if (!normalized.startsWith(APP_CHAIN_PREFIX)) {
            normalized = APP_CHAIN_PREFIX + normalized;
        }
        return normalized;
    }

    /** Closest canonical property for a typo diagnostic. */
    public Optional<String> nearestKey(String requestedKey) {
        String normalized = normalizeKey(requestedKey);
        Matcher indexed = INDEXED_PATH.matcher(normalized);
        String canonical = indexed.matches() ? APP_CHAIN_PREFIX + indexed.group(2) : normalized;
        return definitions.stream()
                .map(AppChainPropertyDefinition::key)
                .min(Comparator.comparingInt(candidate -> editDistance(canonical, candidate)))
                .filter(candidate -> editDistance(canonical, candidate) <= 4);
    }

    /** Canonical key constants currently exposed by {@link YanoPropertyKeys.AppChain}. */
    public static Set<String> runtimeKeyConstants() {
        var keys = new java.util.TreeSet<String>();
        for (Field field : YanoPropertyKeys.AppChain.class.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers())
                    && Modifier.isStatic(field.getModifiers())
                    && field.getType() == String.class) {
                try {
                    keys.add((String) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("Cannot read public app-chain key " + field, e);
                }
            }
        }
        return Set.copyOf(keys);
    }

    private static List<AppChainPropertyDefinition> frameworkDefinitions() {
        List<AppChainPropertyDefinition> definitions = new ArrayList<>();
        definitions.add(plain(YanoPropertyKeys.AppChain.ENABLED, PropertyType.BOOLEAN, "false",
                PropertyScope.NODE_LOCAL, ChangePolicy.RESTART_REQUIRED, false,
                "Enable app-chain participation on this node"));
        definitions.add(plain(YanoPropertyKeys.AppChain.CHAINS, PropertyType.OBJECT, null,
                PropertyScope.CLUSTER_SHARED, ChangePolicy.NEW_CHAIN_REQUIRED, false,
                "Container for indexed multi-chain configuration"));
        definitions.add(boundedText(YanoPropertyKeys.AppChain.CHAIN_ID, null,
                1, AppChainConfig.MAX_CHAIN_ID_BYTES,
                PropertyScope.CONSENSUS_SHARED, ChangePolicy.NEW_CHAIN_REQUIRED, true,
                "Stable app-chain identity"));
        definitions.add(secret(YanoPropertyKeys.AppChain.SIGNING_KEY,
                "Member Ed25519 signing-key seed or secret reference", true));
        definitions.add(boundedList(YanoPropertyKeys.AppChain.MEMBERS,
                AppChainConfig.MAX_MEMBERS, PropertyScope.CONSENSUS_SHARED,
                ChangePolicy.GOVERNED_ACTIVATION, true, "App-chain member public keys"));
        definitions.add(plain(YanoPropertyKeys.AppChain.PEERS, PropertyType.STRING_LIST, null,
                PropertyScope.NODE_LOCAL, ChangePolicy.RESTART_REQUIRED, true,
                "App-group peer endpoints"));
        definitions.add(bounded(YanoPropertyKeys.AppChain.MAX_MESSAGE_BYTES,
                PropertyType.INTEGER, Integer.toString(AppChainConfig.DEFAULT_MAX_MESSAGE_BYTES),
                null, (long) AppChainConfig.MAX_MESSAGE_BYTES,
                PropertyScope.CONSENSUS_SHARED, ChangePolicy.NEW_CHAIN_REQUIRED,
                "Maximum opaque application-message body size"));
        definitions.add(plain(YanoPropertyKeys.AppChain.MAX_TTL_SECONDS, PropertyType.LONG,
                Long.toString(AppChainConfig.DEFAULT_MAX_TTL_SECONDS),
                PropertyScope.CONSENSUS_SHARED, ChangePolicy.NEW_CHAIN_REQUIRED, true,
                "Maximum accepted message time-to-live"));
        definitions.add(plain(YanoPropertyKeys.AppChain.DEFAULT_TTL_SECONDS, PropertyType.LONG,
                Long.toString(AppChainConfig.DEFAULT_DEFAULT_TTL_SECONDS),
                PropertyScope.CONSENSUS_SHARED, ChangePolicy.NEW_CHAIN_REQUIRED, true,
                "Default locally submitted message time-to-live"));
        definitions.add(plain(YanoPropertyKeys.AppChain.SEQUENCER_PROPOSER, PropertyType.STRING,
                null, PropertyScope.CONSENSUS_SHARED, ChangePolicy.GOVERNED_ACTIVATION, true,
                "Fixed sequencer member public key"));
        definitions.add(plain(YanoPropertyKeys.AppChain.THRESHOLD, PropertyType.INTEGER, "1",
                PropertyScope.CONSENSUS_SHARED, ChangePolicy.GOVERNED_ACTIVATION, true,
                "Finality certificate signature threshold"));
        definitions.add(plain(YanoPropertyKeys.AppChain.TRANSPORT_MODE, PropertyType.STRING,
                "shared", PropertyScope.NODE_LOCAL, ChangePolicy.RESTART_REQUIRED, true,
                "Shared or dedicated app-message transport selection"));
        definitions.add(plain(YanoPropertyKeys.AppChain.BLOCK_INTERVAL_MS, PropertyType.LONG,
                Long.toString(AppChainConfig.DEFAULT_BLOCK_INTERVAL_MS),
                PropertyScope.CONSENSUS_SHARED, ChangePolicy.NEW_CHAIN_REQUIRED, true,
                "Sequencer proposal interval in milliseconds"));
        definitions.add(bounded(YanoPropertyKeys.AppChain.BLOCK_MAX_MESSAGES,
                PropertyType.INTEGER, Integer.toString(AppChainConfig.DEFAULT_MAX_BLOCK_MESSAGES),
                null, (long) AppChainConfig.MAX_BLOCK_MESSAGES,
                PropertyScope.CONSENSUS_SHARED, ChangePolicy.NEW_CHAIN_REQUIRED,
                "Maximum messages verified in one app block"));
        definitions.add(bounded(YanoPropertyKeys.AppChain.BLOCK_MAX_BYTES,
                PropertyType.LONG, Long.toString(AppChainConfig.DEFAULT_BLOCK_MAX_BYTES),
                null, AppChainConfig.MAX_BLOCK_BYTES,
                PropertyScope.CONSENSUS_SHARED, ChangePolicy.NEW_CHAIN_REQUIRED,
                "Maximum serialized app-block size"));
        definitions.add(plain(YanoPropertyKeys.AppChain.STATE_MACHINE, PropertyType.STRING,
                AppChainConfig.DEFAULT_STATE_MACHINE,
                PropertyScope.CONSENSUS_SHARED, ChangePolicy.NEW_CHAIN_REQUIRED, true,
                "State-machine or committed composite-profile identifier"));
        definitions.add(plain(YanoPropertyKeys.AppChain.ANCHOR_ENABLED, PropertyType.BOOLEAN,
                "false", PropertyScope.CONSENSUS_SHARED, ChangePolicy.GOVERNED_ACTIVATION, true,
                "Enable Cardano L1 anchoring"));
        definitions.add(secret(YanoPropertyKeys.AppChain.ANCHOR_SIGNING_KEY,
                "Anchor wallet signing-key seed or secret reference", true));
        definitions.add(plain(YanoPropertyKeys.AppChain.ANCHOR_EVERY_BLOCKS, PropertyType.LONG,
                "10", PropertyScope.CONSENSUS_SHARED, ChangePolicy.GOVERNED_ACTIVATION, true,
                "Anchor after this many finalized app blocks"));
        definitions.add(plain(YanoPropertyKeys.AppChain.ANCHOR_MAX_INTERVAL_MINUTES,
                PropertyType.LONG, "60", PropertyScope.CONSENSUS_SHARED,
                ChangePolicy.GOVERNED_ACTIVATION, true,
                "Maximum pending-anchor interval"));
        definitions.add(plain(YanoPropertyKeys.AppChain.ANCHOR_METADATA_LABEL, PropertyType.LONG,
                "7014", PropertyScope.CONSENSUS_SHARED, ChangePolicy.NEW_CHAIN_REQUIRED, true,
                "Cardano metadata label for metadata anchors"));
        definitions.add(plain(YanoPropertyKeys.AppChain.ANCHOR_VALIDITY_SLOTS, PropertyType.LONG,
                Long.toString(AppChainConfig.AnchorConfig.DEFAULT_VALIDITY_SLOTS),
                PropertyScope.CONSENSUS_SHARED, ChangePolicy.GOVERNED_ACTIVATION, true,
                "Anchor transaction validity window"));
        definitions.add(plain(YanoPropertyKeys.AppChain.ANCHOR_FALLBACK_FEE_LOVELACE,
                PropertyType.LONG,
                Long.toString(AppChainConfig.AnchorConfig.DEFAULT_FALLBACK_FEE_LOVELACE),
                PropertyScope.NODE_LOCAL, ChangePolicy.RESTART_REQUIRED, true,
                "Fallback anchor transaction fee"));
        definitions.add(enumerated(YanoPropertyKeys.AppChain.ANCHOR_MODE,
                AppChainConfig.AnchorConfig.MODE_METADATA,
                Set.of(AppChainConfig.AnchorConfig.MODE_METADATA,
                        AppChainConfig.AnchorConfig.MODE_SCRIPT),
                PropertyScope.CONSENSUS_SHARED, ChangePolicy.NEW_CHAIN_REQUIRED,
                "Metadata or script anchor mode"));
        definitions.add(plain(YanoPropertyKeys.AppChain.ANCHOR_SCRIPT_VALIDATOR,
                PropertyType.STRING, null, PropertyScope.CONSENSUS_SHARED,
                ChangePolicy.NEW_CHAIN_REQUIRED, true,
                "Script-anchor validator artifact reference"));
        definitions.add(plain(YanoPropertyKeys.AppChain.ANCHOR_SCRIPT_THREAD_POLICY,
                PropertyType.STRING, null, PropertyScope.CONSENSUS_SHARED,
                ChangePolicy.NEW_CHAIN_REQUIRED, true,
                "Script-anchor thread-policy artifact reference"));
        definitions.add(plain(YanoPropertyKeys.AppChain.L1_STABILITY_DEPTH,
                PropertyType.INTEGER, "0", PropertyScope.CONSENSUS_SHARED,
                ChangePolicy.NEW_CHAIN_REQUIRED, true,
                "Minimum stable L1 depth referenced by app blocks"));
        definitions.add(plain(YanoPropertyKeys.AppChain.WEBHOOKS, PropertyType.STRING_LIST, null,
                PropertyScope.INFRASTRUCTURE, ChangePolicy.RESTART_REQUIRED, true,
                "Finalized-block webhook sink URLs"));
        definitions.add(plain(YanoPropertyKeys.AppChain.RETENTION_ENABLED, PropertyType.BOOLEAN,
                "false", PropertyScope.NODE_LOCAL, ChangePolicy.RESTART_REQUIRED, true,
                "Prune eligible finalized message bodies"));
        definitions.add(plain(YanoPropertyKeys.AppChain.RETENTION_KEEP_BLOCKS,
                PropertyType.INTEGER, "0", PropertyScope.NODE_LOCAL,
                ChangePolicy.RESTART_REQUIRED, true,
                "Recent block bodies retained regardless of anchor horizon"));
        definitions.add(plain(YanoPropertyKeys.AppChain.POOL_MAX_MESSAGES, PropertyType.INTEGER,
                Integer.toString(AppChainConfig.DEFAULT_POOL_MAX_MESSAGES),
                PropertyScope.NODE_LOCAL, ChangePolicy.RESTART_REQUIRED, true,
                "Pending-message pool capacity"));
        definitions.add(plain(YanoPropertyKeys.AppChain.MESSAGE_ENFORCE_SENDER_SEQ,
                PropertyType.BOOLEAN, "false", PropertyScope.CONSENSUS_SHARED,
                ChangePolicy.NEW_CHAIN_REQUIRED, true,
                "Consensus-visible per-sender sequence enforcement"));
        definitions.add(plain(YanoPropertyKeys.AppChain.API_AUTH_ENABLED, PropertyType.BOOLEAN,
                "false", PropertyScope.NODE_LOCAL, ChangePolicy.RESTART_REQUIRED, false,
                "Enable broad READ/SUBMIT API-key authentication"));
        definitions.add(secret(YanoPropertyKeys.AppChain.API_KEYS,
                "API keys and optional topic scopes", false));
        definitions.add(runtimeDefined(YanoPropertyKeys.AppChain.VALIDATION_STRICT,
                PropertyType.BOOLEAN, "false", PropertyScope.NODE_LOCAL,
                "Reject unknown keys only in FULL runtime-owned namespaces"));
        definitions.add(runtimeDefined(YanoPropertyKeys.AppChain.DX_RESOLVED_CONFIG_DIGEST,
                PropertyType.STRING, null, PropertyScope.NODE_LOCAL,
                "Generated project resolved-configuration identity"));
        definitions.add(runtimeDefined(YanoPropertyKeys.AppChain.DX_RELEASE_CATALOG_DIGEST,
                PropertyType.STRING, null, PropertyScope.NODE_LOCAL,
                "Generated project release-catalog identity"));
        definitions.add(runtimeParsed("effects.enabled", PropertyType.BOOLEAN, "false",
                null, null, Set.of(), PropertyScope.CONSENSUS_SHARED,
                "Enable deterministic effect intents and results"));
        definitions.add(runtimeParsed("effects.max-per-block", PropertyType.INTEGER,
                Integer.toString(AppChainEffectsConfig.DEFAULT_MAX_PER_BLOCK), 1L,
                (long) com.bloxbean.cardano.yano.api.appchain.effects.FxKeys.MAX_EFFECTS_PER_BLOCK,
                Set.of(), PropertyScope.CONSENSUS_SHARED,
                "Maximum effect intents emitted in one app block"));
        definitions.add(runtimeParsed("effects.max-payload-bytes", PropertyType.INTEGER,
                Integer.toString(AppChainEffectsConfig.DEFAULT_MAX_PAYLOAD_BYTES), 1L,
                (long) AppChainEffectsConfig.MAX_PAYLOAD_BYTES, Set.of(),
                PropertyScope.CONSENSUS_SHARED, "Maximum inline effect payload size"));
        definitions.add(runtimeParsed("effects.max-expiry-blocks", PropertyType.LONG,
                Long.toString(AppChainEffectsConfig.DEFAULT_MAX_EXPIRY_BLOCKS), 1L, null,
                Set.of(), PropertyScope.CONSENSUS_SHARED,
                "Maximum requested effect expiry horizon"));
        definitions.add(runtimeParsed("effects.result-window-blocks", PropertyType.LONG,
                Long.toString(AppChainEffectsConfig.DEFAULT_RESULT_WINDOW_BLOCKS), 1L, null,
                Set.of(), PropertyScope.CONSENSUS_SHARED,
                "Deterministic result incorporation window"));
        definitions.add(runtimeParsed("effects.default-gate", PropertyType.STRING,
                "app-final", null, null,
                Set.of("app-final", "l1-anchored", "zk-settled"),
                PropertyScope.CONSENSUS_SHARED, "Default finality gate for effects"));
        definitions.add(runtimeParsed("effects.outcome-commitment", PropertyType.STRING,
                "per-effect", null, null, Set.of("per-effect", "per-block"),
                PropertyScope.CONSENSUS_SHARED, "Effect outcome commitment strategy"));
        definitions.add(runtimeParsed("effects.strict-reserved-prefix", PropertyType.BOOLEAN,
                "true", null, null, Set.of(), PropertyScope.CONSENSUS_SHARED,
                "Reserve the internal effect state prefix even when effects are disabled"));
        definitions.add(new AppChainPropertyDefinition(
                APP_CHAIN_PREFIX + "effects.result.signers", "yano-core/effects",
                PropertyType.STRING_LIST, null, null, null, null, null,
                AppChainConfig.MAX_MEMBERS, Set.of(), PropertyScope.CONSENSUS_SHARED,
                ChangePolicy.NEW_CHAIN_REQUIRED, false, true,
                ConstraintProvenance.RUNTIME_PARSER_TEST, ValidationCoverage.FULL,
                "Optional member keys allowed to attest effect results"));
        definitions.sort(Comparator.comparing(AppChainPropertyDefinition::key));
        return List.copyOf(definitions);
    }

    private static AppChainPropertyDefinition runtimeParsed(
            String suffix,
            PropertyType type,
            String defaultValue,
            Long minimum,
            Long maximum,
            Set<String> allowedValues,
            PropertyScope scope,
            String description) {
        return new AppChainPropertyDefinition(
                APP_CHAIN_PREFIX + suffix, "yano-core/effects", type, defaultValue,
                minimum, maximum, null, null, null, allowedValues, scope,
                ChangePolicy.NEW_CHAIN_REQUIRED, false, true,
                ConstraintProvenance.RUNTIME_PARSER_TEST, ValidationCoverage.FULL, description);
    }

    private static AppChainPropertyDefinition runtimeDefined(
            String key,
            PropertyType type,
            String defaultValue,
            PropertyScope scope,
            String description) {
        return new AppChainPropertyDefinition(
                key, OWNER_CORE, type, defaultValue, null, null, null, null, null,
                Set.of(), scope, ChangePolicy.RESTART_REQUIRED, false, false,
                ConstraintProvenance.PUBLIC_RUNTIME_DEFINITION,
                ValidationCoverage.FULL, description);
    }

    private static AppChainPropertyDefinition plain(
            String key, PropertyType type, String defaultValue, PropertyScope scope,
            ChangePolicy changePolicy, boolean indexed, String description) {
        return definition(key, type, defaultValue, null, null,
                null, null, null, Set.of(), scope,
                changePolicy, false, indexed, ConstraintProvenance.NOT_APPLICABLE, description);
    }

    private static AppChainPropertyDefinition secret(
            String key, String description, boolean indexed) {
        return definition(key, PropertyType.STRING, null, null, null,
                null, null, null, Set.of(),
                PropertyScope.SECRET, ChangePolicy.SECRET_ROTATION, true, indexed,
                ConstraintProvenance.NOT_APPLICABLE, description);
    }

    private static AppChainPropertyDefinition bounded(
            String key, PropertyType type, String defaultValue, Long minimum, Long maximum,
            PropertyScope scope, ChangePolicy changePolicy, String description) {
        return definition(key, type, defaultValue, minimum, maximum,
                null, null, null, Set.of(), scope,
                changePolicy, false, true,
                ConstraintProvenance.PUBLIC_RUNTIME_DEFINITION, description);
    }

    private static AppChainPropertyDefinition enumerated(
            String key, String defaultValue, Set<String> allowedValues, PropertyScope scope,
            ChangePolicy changePolicy, String description) {
        return definition(key, PropertyType.STRING, defaultValue, null, null,
                null, null, null, allowedValues,
                scope, changePolicy, false, true,
                ConstraintProvenance.PUBLIC_RUNTIME_DEFINITION, description);
    }

    private static AppChainPropertyDefinition boundedText(
            String key, String defaultValue, Integer minimumUtf8Bytes,
            Integer maximumUtf8Bytes, PropertyScope scope, ChangePolicy changePolicy,
            boolean indexed, String description) {
        return definition(key, PropertyType.STRING, defaultValue, null, null,
                minimumUtf8Bytes, maximumUtf8Bytes, null, Set.of(), scope,
                changePolicy, false, indexed,
                ConstraintProvenance.PUBLIC_RUNTIME_DEFINITION, description);
    }

    private static AppChainPropertyDefinition boundedList(
            String key, Integer maximumItems, PropertyScope scope, ChangePolicy changePolicy,
            boolean indexed, String description) {
        return definition(key, PropertyType.STRING_LIST, null, null, null,
                null, null, maximumItems, Set.of(), scope, changePolicy, false, indexed,
                ConstraintProvenance.PUBLIC_RUNTIME_DEFINITION, description);
    }

    private static AppChainPropertyDefinition definition(
            String key, PropertyType type, String defaultValue, Long minimum, Long maximum,
            Integer minimumUtf8Bytes, Integer maximumUtf8Bytes, Integer maximumItems,
            Set<String> allowedValues, PropertyScope scope, ChangePolicy changePolicy,
            boolean secret, boolean indexed, ConstraintProvenance provenance,
            String description) {
        return new AppChainPropertyDefinition(key, OWNER_CORE, type, defaultValue,
                minimum, maximum, minimumUtf8Bytes, maximumUtf8Bytes, maximumItems,
                allowedValues, scope, changePolicy, secret, indexed,
                provenance, ValidationCoverage.PARTIAL, description);
    }

    private static DynamicNamespaceDefinition dynamic(
            String prefix, String owner, String description) {
        return new DynamicNamespaceDefinition(
                prefix, owner, ValidationCoverage.PARTIAL, description);
    }

    private static DynamicNamespaceDefinition dynamicFull(
            String prefix, String owner, String description) {
        return new DynamicNamespaceDefinition(
                prefix, owner, ValidationCoverage.FULL, description);
    }

    private static Map<String, AppChainPropertyDefinition> byKey(
            List<AppChainPropertyDefinition> definitions) {
        Map<String, AppChainPropertyDefinition> result = new LinkedHashMap<>();
        for (AppChainPropertyDefinition definition : definitions) {
            if (result.put(definition.key(), definition) != null) {
                throw new IllegalStateException("Duplicate property definition: " + definition.key());
            }
        }
        return Map.copyOf(result);
    }

    private static List<DynamicNamespaceDefinition> validateNamespaces(
            List<AppChainMetadataSource> sources) {
        Map<String, DynamicNamespaceDefinition> result = new LinkedHashMap<>();
        for (AppChainMetadataSource source : sources) {
            for (DynamicNamespaceDefinition namespace : source.dynamicNamespaces()) {
                DynamicNamespaceDefinition previous = result.putIfAbsent(
                        namespace.prefix(), namespace);
                if (previous != null) {
                    throw new IllegalStateException("Dynamic namespace '" + namespace.prefix()
                            + "' is claimed by both " + previous.owner() + " and "
                            + namespace.owner());
                }
            }
        }
        return result.values().stream()
                .sorted(Comparator.comparing(DynamicNamespaceDefinition::prefix))
                .toList();
    }

    private static void validateFullOwnership(
            List<AppChainPropertyDefinition> properties,
            List<DynamicNamespaceDefinition> namespaces) {
        for (DynamicNamespaceDefinition full : namespaces) {
            if (full.coverage() != ValidationCoverage.FULL) continue;
            for (DynamicNamespaceDefinition candidate : namespaces) {
                if (candidate != full && candidate.prefix().startsWith(full.prefix())
                        && !candidate.owner().equals(full.owner())) {
                    throw new IllegalStateException("FULL namespace '" + full.prefix()
                            + "' owned by " + full.owner() + " cannot be extended by "
                            + candidate.owner());
                }
            }
            String canonicalPrefix = APP_CHAIN_PREFIX + full.prefix();
            for (AppChainPropertyDefinition property : properties) {
                if (property.key().startsWith(canonicalPrefix)
                        && !property.owner().equals(full.owner())) {
                    throw new IllegalStateException("FULL namespace '" + full.prefix()
                            + "' owned by " + full.owner() + " cannot contain property from "
                            + property.owner());
                }
            }
        }
    }

    private static int editDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int replacement = previous[j - 1]
                        + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), replacement);
            }
            previous = current;
        }
        return previous[right.length()];
    }

    private static Integer parseChainIndex(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    /** Match result retaining the concrete indexed path supplied by the user. */
    public record PropertyMatch(
            String requestedKey,
            String normalizedKey,
            String canonicalKey,
            Integer chainIndex,
            AppChainPropertyDefinition definition) {

        public boolean indexed() {
            return chainIndex != null;
        }
    }
}
