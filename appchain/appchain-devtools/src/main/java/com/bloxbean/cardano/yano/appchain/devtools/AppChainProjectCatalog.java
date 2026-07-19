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
    static final int MAX_RESOURCE_BYTES = 1_048_576;

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9.-]*(?::[a-z][a-z0-9.-]*)?");

    private final ObjectMapper json;
    private final byte[] capabilityBytes;
    private final byte[] recipeBytes;
    private final byte[] blueprintSchemaBytes;
    private final byte[] lockSchemaBytes;
    private final AppChainProjectModel.CapabilityCatalog capabilityCatalog;
    private final AppChainProjectModel.RecipeCatalog recipeCatalog;
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
        capabilityCatalog = json.readValue(
                capabilityBytes, AppChainProjectModel.CapabilityCatalog.class);
        recipeCatalog = json.readValue(recipeBytes, AppChainProjectModel.RecipeCatalog.class);
        artifacts = indexArtifacts(capabilityCatalog);
        capabilities = indexCapabilities(capabilityCatalog, properties, artifacts);
        recipes = indexRecipes(recipeCatalog, capabilities);
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

    Map<String, String> digests() {
        Map<String, String> digests = new LinkedHashMap<>();
        digests.put("capabilities", sha256(capabilityBytes));
        digests.put("recipes", sha256(recipeBytes));
        digests.put("blueprintSchema", sha256(blueprintSchemaBytes));
        digests.put("lockSchema", sha256(lockSchemaBytes));
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
            for (String artifact : safeList(capability.artifacts())) {
                if (!artifacts.containsKey(artifact)) {
                    throw new IllegalStateException("Capability " + capability.id()
                            + " references unknown artifact " + artifact);
                }
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
            for (String capability : recipe.capabilities()) {
                if (!capabilities.containsKey(capability)) {
                    throw new IllegalStateException("Recipe " + recipe.id()
                            + " references unknown capability " + capability);
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
