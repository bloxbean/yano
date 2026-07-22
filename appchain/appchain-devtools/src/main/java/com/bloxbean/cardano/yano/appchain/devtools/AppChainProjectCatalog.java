package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.yano.appchain.config.AppChainPropertyRegistry;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Loads and validates the embedded, release-pinned project descriptor catalogs. */
final class AppChainProjectCatalog {
    static final String RESOURCE_DIRECTORY = "appchain-dx/v1alpha1";
    static final String CAPABILITY_RESOURCE = RESOURCE_DIRECTORY
            + "/appchain-capability-catalog.json";
    static final String RECIPE_RESOURCE = RESOURCE_DIRECTORY + "/appchain-recipe-catalog.json";
    static final String BLUEPRINT_SCHEMA_RESOURCE = RESOURCE_DIRECTORY
            + "/appchain-blueprint.schema.json";
    static final String LOCK_SCHEMA_RESOURCE = RESOURCE_DIRECTORY + "/appchain-lock.schema.json";
    static final String RELEASE_INDEX_RESOURCE = RESOURCE_DIRECTORY
            + "/appchain-release-capability-index.json";
    static final String FIRST_PARTY_METADATA_RESOURCE = RESOURCE_DIRECTORY
            + "/appchain-first-party-metadata.json";
    static final String METADATA_TRUST_SCHEMA_RESOURCE = RESOURCE_DIRECTORY
            + "/appchain-metadata-trust.schema.json";
    static final String GITOPS_LOCK_SCHEMA_RESOURCE = RESOURCE_DIRECTORY
            + "/appchain-gitops-lock.schema.json";
    static final String AI_SKILL_DIRECTORY = RESOURCE_DIRECTORY
            + "/skills/configure-yano-appchain";
    static final String AI_SKILL_RESOURCE = AI_SKILL_DIRECTORY + "/SKILL.md";
    static final String AI_SKILL_OPENAI_RESOURCE = AI_SKILL_DIRECTORY
            + "/agents/openai.yaml";
    static final int MAX_RESOURCE_BYTES = 1_048_576;

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9.-]*(?::[a-z][a-z0-9.-]*)?");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z][A-Za-z0-9]{0,63})}");
    private static final Set<String> AVAILABILITY = Set.of(
            "BUNDLED", "FIRST_PARTY_OPTIONAL", "REFERENCE", "EXPERIMENTAL");
    private static final Set<String> MATURITY = Set.of("stable", "preview", "experimental");
    private static final Set<String> SCOPES = Set.of("chain", "node", "distribution");
    private static final Set<String> NATIVE_POSTURES = Set.of(
            "bundled", "build-time-only", "unsupported", "not-applicable");

    private final ObjectMapper json;
    private final byte[] capabilityBytes;
    private final byte[] recipeBytes;
    private final byte[] blueprintSchemaBytes;
    private final byte[] lockSchemaBytes;
    private final byte[] releaseIndexBytes;
    private final byte[] firstPartyMetadataBytes;
    private final byte[] metadataTrustSchemaBytes;
    private final byte[] gitOpsLockSchemaBytes;
    private final byte[] aiSkillBytes;
    private final byte[] aiSkillOpenAiBytes;
    private final AppChainProjectModel.CapabilityCatalog capabilityCatalog;
    private final AppChainProjectModel.RecipeCatalog recipeCatalog;
    private final AppChainProjectModel.ReleaseIndex releaseIndex;
    private final Map<String, AppChainProjectModel.Artifact> artifacts;
    private final Map<String, AppChainProjectModel.Capability> capabilities;
    private final Map<String, AppChainProjectModel.Recipe> recipes;

    AppChainProjectCatalog(AppChainPropertyRegistry properties) throws IOException {
        json = new ObjectMapper()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        capabilityBytes = resource(CAPABILITY_RESOURCE);
        recipeBytes = resource(RECIPE_RESOURCE);
        blueprintSchemaBytes = resource(BLUEPRINT_SCHEMA_RESOURCE);
        lockSchemaBytes = resource(LOCK_SCHEMA_RESOURCE);
        releaseIndexBytes = resource(RELEASE_INDEX_RESOURCE);
        firstPartyMetadataBytes = resource(FIRST_PARTY_METADATA_RESOURCE);
        metadataTrustSchemaBytes = resource(METADATA_TRUST_SCHEMA_RESOURCE);
        gitOpsLockSchemaBytes = resource(GITOPS_LOCK_SCHEMA_RESOURCE);
        aiSkillBytes = resource(AI_SKILL_RESOURCE);
        aiSkillOpenAiBytes = resource(AI_SKILL_OPENAI_RESOURCE);
        capabilityCatalog = json.readValue(
                capabilityBytes, AppChainProjectModel.CapabilityCatalog.class);
        recipeCatalog = json.readValue(recipeBytes, AppChainProjectModel.RecipeCatalog.class);
        releaseIndex = json.readValue(releaseIndexBytes, AppChainProjectModel.ReleaseIndex.class);
        artifacts = indexArtifacts(capabilityCatalog);
        capabilities = indexCapabilities(capabilityCatalog, properties, artifacts);
        recipes = indexRecipes(recipeCatalog, capabilities);
        validateReleaseIndex(releaseIndex, artifacts, recipes.keySet());
    }

    AppChainProjectModel.Capability capability(String id) {
        AppChainProjectModel.Capability capability = capabilities.get(id);
        if (capability == null) {
            throw new IllegalArgumentException("Unknown app-chain capability: " + safeId(id));
        }
        return capability;
    }

    AppChainProjectModel.Recipe recipe(String id) {
        AppChainProjectModel.Recipe recipe = recipes.get(id);
        if (recipe == null) {
            throw new IllegalArgumentException("Unknown app-chain recipe: " + safeId(id));
        }
        return recipe;
    }

    AppChainProjectModel.Artifact artifact(String id) {
        AppChainProjectModel.Artifact artifact = artifacts.get(id);
        if (artifact == null) {
            throw new IllegalArgumentException("Unknown app-chain artifact: " + safeId(id));
        }
        return artifact;
    }

    List<AppChainProjectModel.Recipe> recipes() {
        return recipes.values().stream().toList();
    }

    List<AppChainProjectModel.Capability> capabilities() {
        return capabilities.values().stream().toList();
    }

    AppChainProjectModel.ReleaseIndex releaseIndex() {
        return releaseIndex;
    }

    Map<String, String> digests() {
        Map<String, String> digests = new LinkedHashMap<>();
        digests.put("capabilities", sha256(capabilityBytes));
        digests.put("recipes", sha256(recipeBytes));
        digests.put("blueprintSchema", sha256(blueprintSchemaBytes));
        digests.put("lockSchema", sha256(lockSchemaBytes));
        digests.put("releaseIndex", sha256(releaseIndexBytes));
        digests.put("firstPartyMetadata", sha256(firstPartyMetadataBytes));
        digests.put("metadataTrustSchema", sha256(metadataTrustSchemaBytes));
        digests.put("gitOpsLockSchema", sha256(gitOpsLockSchemaBytes));
        digests.put("aiSkill", sha256(aiSkillBytes));
        digests.put("aiSkillOpenAi", sha256(aiSkillOpenAiBytes));
        return Map.copyOf(digests);
    }

    byte[] capabilityBytes() {
        return capabilityBytes.clone();
    }

    byte[] recipeBytes() {
        return recipeBytes.clone();
    }

    byte[] blueprintSchemaBytes() {
        return blueprintSchemaBytes.clone();
    }

    byte[] lockSchemaBytes() {
        return lockSchemaBytes.clone();
    }

    byte[] releaseIndexBytes() {
        return releaseIndexBytes.clone();
    }

    byte[] firstPartyMetadataBytes() {
        return firstPartyMetadataBytes.clone();
    }

    byte[] metadataTrustSchemaBytes() {
        return metadataTrustSchemaBytes.clone();
    }

    byte[] gitOpsLockSchemaBytes() {
        return gitOpsLockSchemaBytes.clone();
    }

    byte[] aiSkillBytes() {
        return aiSkillBytes.clone();
    }

    byte[] aiSkillOpenAiBytes() {
        return aiSkillOpenAiBytes.clone();
    }

    private static void validateReleaseIndex(
            AppChainProjectModel.ReleaseIndex index,
            Map<String, AppChainProjectModel.Artifact> artifacts,
            Set<String> recipes) {
        Set<String> knownArtifacts = artifacts.keySet();
        Set<String> bundledArtifacts = artifacts.values().stream()
                .filter(artifact -> "BUNDLED".equals(artifact.availability()))
                .map(AppChainProjectModel.Artifact::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (index == null || !"v1alpha1".equals(index.schemaVersion())
                || !"alpha".equals(index.schemaStatus())
                || !"RETAIN_V1ALPHA1".equals(index.stabilizationDecision())
                || index.yanoVersion() == null || index.yanoVersion().isBlank()
                || !Set.copyOf(index.artifacts()).equals(bundledArtifacts)
                || !Set.copyOf(index.recipes()).equals(recipes)
                || !Set.copyOf(index.runtimeTypes()).equals(Set.of("jvm", "native"))
                || index.distributions() == null || index.distributions().isEmpty()) {
            throw new IllegalStateException(
                    "Release capability index must match the embedded catalogs");
        }
        for (AppChainProjectModel.DistributionFlavor flavor : index.distributions()) {
            if (!index.runtimeTypes().contains(flavor.runtimeType())
                    || flavor.id() == null || flavor.archivePattern() == null
                    || flavor.tooling() == null || flavor.platforms() == null
                    || flavor.artifacts() == null
                    || !knownArtifacts.containsAll(flavor.artifacts())
                    || !Set.copyOf(flavor.artifacts()).equals(Set.copyOf(index.artifacts()))) {
                throw new IllegalStateException("Release distribution flavor is invalid");
            }
        }
    }

    private static Map<String, AppChainProjectModel.Capability> indexCapabilities(
            AppChainProjectModel.CapabilityCatalog catalog,
            AppChainPropertyRegistry properties,
            Map<String, AppChainProjectModel.Artifact> artifacts) {
        if (catalog == null || !"v1alpha1".equals(catalog.schemaVersion())
                || catalog.capabilities() == null || catalog.capabilities().isEmpty()) {
            throw new IllegalStateException("Capability catalog must be non-empty v1alpha1");
        }
        Map<String, AppChainProjectModel.Capability> indexed = new LinkedHashMap<>();
        for (AppChainProjectModel.Capability capability : catalog.capabilities()) {
            requireId(capability.id(), "capability");
            if (indexed.putIfAbsent(capability.id(), capability) != null) {
                throw new IllegalStateException("Duplicate capability id: " + capability.id());
            }
            requireNonEmpty(capability.runtimeTypes(), capability.id(), "runtimeTypes");
            requireNonEmpty(capability.deploymentTargets(), capability.id(), "deploymentTargets");
            requireText(capability.name(), capability.id(), "name");
            requireText(capability.category(), capability.id(), "category");
            requireEnum(capability.availability(), AVAILABILITY, capability.id(), "availability");
            requireEnum(capability.maturity(), MATURITY, capability.id(), "maturity");
            requireEnum(capability.effectiveScope(), SCOPES, capability.id(), "scope");
            requireEnum(capability.nativePosture(), NATIVE_POSTURES,
                    capability.id(), "nativePosture");
            requireText(capability.trustStatement(), capability.id(), "trustStatement");
            requireText(capability.description(), capability.id(), "description");
            requireText(capability.documentation(), capability.id(), "documentation");
            requireText(capability.acceptanceScenario(), capability.id(), "acceptanceScenario");
            if ("distribution".equals(capability.effectiveScope())
                    && capability.effectiveSelectable()) {
                throw new IllegalStateException("Distribution capability " + capability.id()
                        + " cannot be blueprint-selectable");
            }
            validateAnswerNames(capability.nonSecretAnswers(), capability.id());
            validateSecretReferences(capability.secretReferences(), capability.id());
            validatePropertyPlaceholders(capability);
            for (String artifact : safeList(capability.artifacts())) {
                if (!artifacts.containsKey(artifact)) {
                    throw new IllegalStateException("Capability " + capability.id()
                            + " references unknown artifact " + artifact);
                }
            }
            if ("BUNDLED".equals(capability.availability())
                    && safeList(capability.artifacts()).stream()
                    .map(artifacts::get)
                    .anyMatch(artifact -> artifact == null
                            || !"BUNDLED".equals(artifact.availability()))) {
                throw new IllegalStateException("Bundled capability " + capability.id()
                        + " depends on a non-bundled artifact");
            }
            for (Map.Entry<String, String> assignment : safeMap(capability.properties()).entrySet()) {
                String key = AppChainPropertyRegistry.APP_CHAIN_PREFIX + assignment.getKey();
                if (properties.find(key).isEmpty() && properties.dynamicNamespace(key).isEmpty()) {
                    throw new IllegalStateException("Capability " + capability.id()
                            + " assigns unknown property " + assignment.getKey());
                }
                if (assignment.getValue() == null
                        || assignment.getValue().toLowerCase(java.util.Locale.ROOT)
                        .contains("password=")) {
                    throw new IllegalStateException("Capability assignments must be non-secret");
                }
            }
            for (Map.Entry<String, String> reference
                    : safeMap(capability.secretReferences()).entrySet()) {
                String key = AppChainPropertyRegistry.APP_CHAIN_PREFIX + reference.getKey();
                if (properties.find(key).isEmpty() && properties.dynamicNamespace(key).isEmpty()) {
                    throw new IllegalStateException("Capability " + capability.id()
                            + " declares a secret reference for unknown property "
                            + reference.getKey());
                }
            }
        }
        for (AppChainProjectModel.Capability capability : indexed.values()) {
            Set<String> references = new LinkedHashSet<>();
            references.addAll(safeList(capability.requires()));
            references.addAll(safeList(capability.implies()));
            references.addAll(safeList(capability.conflicts()));
            for (String reference : references) {
                if (!indexed.containsKey(reference)) {
                    throw new IllegalStateException("Capability " + capability.id()
                            + " references unknown capability " + reference);
                }
            }
        }
        return java.util.Collections.unmodifiableMap(indexed);
    }

    private static Map<String, AppChainProjectModel.Artifact> indexArtifacts(
            AppChainProjectModel.CapabilityCatalog catalog) {
        if (catalog == null || catalog.artifacts() == null || catalog.artifacts().isEmpty()) {
            throw new IllegalStateException("Capability catalog must declare release artifacts");
        }
        Map<String, AppChainProjectModel.Artifact> indexed = new LinkedHashMap<>();
        for (AppChainProjectModel.Artifact artifact : catalog.artifacts()) {
            requireId(artifact.id(), "artifact");
            requireEnum(artifact.availability(), AVAILABILITY, artifact.id(), "availability");
            requireEnum(artifact.nativePosture(), NATIVE_POSTURES,
                    artifact.id(), "nativePosture");
            requireText(artifact.bundleId(), artifact.id(), "bundleId");
            requireNonEmpty(artifact.runtimeTypes(), artifact.id(), "runtimeTypes");
            requireNonEmpty(artifact.deploymentTargets(), artifact.id(), "deploymentTargets");
            if (indexed.putIfAbsent(artifact.id(), artifact) != null) {
                throw new IllegalStateException("Duplicate artifact id: " + artifact.id());
            }
        }
        return java.util.Collections.unmodifiableMap(indexed);
    }

    private static Map<String, AppChainProjectModel.Recipe> indexRecipes(
            AppChainProjectModel.RecipeCatalog catalog,
            Map<String, AppChainProjectModel.Capability> capabilities) {
        if (catalog == null || !"v1alpha1".equals(catalog.schemaVersion())
                || catalog.recipes() == null || catalog.recipes().isEmpty()) {
            throw new IllegalStateException("Recipe catalog must be non-empty v1alpha1");
        }
        Map<String, AppChainProjectModel.Recipe> indexed = new LinkedHashMap<>();
        for (AppChainProjectModel.Recipe recipe : catalog.recipes()) {
            requireId(recipe.id(), "recipe");
            if (indexed.putIfAbsent(recipe.id(), recipe) != null) {
                throw new IllegalStateException("Duplicate recipe id: " + recipe.id());
            }
            requireNonEmpty(recipe.capabilities(), recipe.id(), "capabilities");
            requireNonEmpty(recipe.runtimeTypes(), recipe.id(), "runtimeTypes");
            requireNonEmpty(recipe.deploymentTargets(), recipe.id(), "deploymentTargets");
            requireText(recipe.name(), recipe.id(), "name");
            requireText(recipe.category(), recipe.id(), "category");
            requireEnum(recipe.availability(), AVAILABILITY, recipe.id(), "availability");
            requireEnum(recipe.maturity(), MATURITY, recipe.id(), "maturity");
            requireEnum(recipe.effectiveScope(), SCOPES, recipe.id(), "scope");
            requireEnum(recipe.nativePosture(), NATIVE_POSTURES, recipe.id(), "nativePosture");
            requireText(recipe.trustStatement(), recipe.id(), "trustStatement");
            requireText(recipe.description(), recipe.id(), "description");
            requireText(recipe.documentation(), recipe.id(), "documentation");
            requireText(recipe.acceptanceScenario(), recipe.id(), "acceptanceScenario");
            validateAnswerNames(recipe.nonSecretAnswers(), recipe.id());
            for (String capability : recipe.capabilities()) {
                if (!capabilities.containsKey(capability)) {
                    throw new IllegalStateException("Recipe " + recipe.id()
                            + " references unknown capability " + capability);
                }
            }
            for (String artifact : safeList(recipe.artifacts())) {
                if (!capabilities.values().stream()
                        .flatMap(capability -> safeList(capability.artifacts()).stream())
                        .anyMatch(artifact::equals)) {
                    throw new IllegalStateException("Recipe " + recipe.id()
                            + " references unknown artifact " + artifact);
                }
            }
        }
        return java.util.Collections.unmodifiableMap(indexed);
    }

    private static byte[] resource(String name) throws IOException {
        ClassLoader loader = AppChainProjectCatalog.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(name)) {
            if (input == null) {
                throw new IOException("Missing embedded project metadata: " + name);
            }
            byte[] bytes = input.readNBytes(MAX_RESOURCE_BYTES + 1);
            if (bytes.length > MAX_RESOURCE_BYTES) {
                throw new IOException("Embedded project metadata exceeds size limit: " + name);
            }
            return bytes;
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void requireId(String id, String type) {
        if (id == null || !ID.matcher(id).matches() || id.length() > 128) {
            throw new IllegalStateException(type + " id is invalid");
        }
    }

    private static void requireNonEmpty(List<String> values, String id, String field) {
        if (values == null || values.isEmpty()) {
            throw new IllegalStateException(id + " must declare " + field);
        }
    }

    private static void requireText(String value, String id, String field) {
        if (value == null || value.isBlank() || value.length() > 2_048
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalStateException(id + " must declare a safe " + field);
        }
    }

    private static void requireEnum(
            String value, Set<String> allowed, String id, String field) {
        if (!allowed.contains(value)) {
            throw new IllegalStateException(id + " declares unsupported " + field);
        }
    }

    private static void validateAnswerNames(List<String> names, String owner) {
        Set<String> unique = new LinkedHashSet<>();
        for (String name : safeList(names)) {
            String normalized = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
            if (name == null || !name.matches("[A-Za-z][A-Za-z0-9]{0,63}")
                    || normalized.contains("secret") || normalized.contains("password")
                    || normalized.contains("token") || normalized.contains("private")
                    || normalized.contains("mnemonic")
                    || !unique.add(name)) {
                throw new IllegalStateException(owner + " declares an invalid non-secret answer");
            }
        }
    }

    private static void validatePropertyPlaceholders(
            AppChainProjectModel.Capability capability) {
        Set<String> allowed = new LinkedHashSet<>(safeList(capability.nonSecretAnswers()));
        // The fixed sequencer proposer is resolved from the member topology, not user input.
        allowed.add("proposer");
        for (String value : safeMap(capability.properties()).values()) {
            var matcher = PLACEHOLDER.matcher(value);
            while (matcher.find()) {
                if (!allowed.contains(matcher.group(1))) {
                    throw new IllegalStateException("Capability " + capability.id()
                            + " references undeclared answer " + matcher.group(1));
                }
            }
        }
    }

    private static void validateSecretReferences(Map<String, String> references, String owner) {
        for (Map.Entry<String, String> reference : safeMap(references).entrySet()) {
            if (reference.getKey() == null || reference.getKey().isBlank()
                    || reference.getValue() == null
                    || !reference.getValue().matches("YANO_[A-Z0-9_]{2,120}")) {
                throw new IllegalStateException(owner + " declares an invalid secret reference");
            }
        }
    }

    private static List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static Map<String, String> safeMap(Map<String, String> values) {
        return values == null ? Map.of() : values;
    }

    private static String safeId(String value) {
        if (value == null) return "<missing>";
        return value.codePoints().limit(128)
                .collect(StringBuilder::new,
                        (builder, codePoint) -> builder.appendCodePoint(
                                Character.isISOControl(codePoint) ? '?' : codePoint),
                        StringBuilder::append)
                .toString();
    }
}
