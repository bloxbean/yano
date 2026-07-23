package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.yano.appchain.config.AppChainMetadataSource;
import com.bloxbean.cardano.yano.appchain.config.AppChainPropertyDefinition;
import com.bloxbean.cardano.yano.appchain.config.AppChainPropertyRegistry;
import com.bloxbean.cardano.yano.appchain.config.DynamicNamespaceDefinition;
import com.bloxbean.cardano.yano.appchain.config.PropertyType;
import com.bloxbean.cardano.yano.appchain.config.ValidationCoverage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic export of the release-pinned runtime schema and property catalog. */
public final class AppChainMetadataExporter {
    public static final String RESOURCE_DIRECTORY = "appchain-dx/v1";
    public static final String RUNTIME_SCHEMA = "appchain-runtime.schema.json";
    public static final String PROPERTY_CATALOG = "appchain-property-catalog.json";

    private final ObjectMapper json = new ObjectMapper();

    public String runtimeSchemaJson(AppChainPropertyRegistry registry)
            throws JsonProcessingException {
        ObjectNode schema = json.createObjectNode();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("$id", "https://yano.dev/schema/appchain-runtime/v1");
        schema.put("title", "Yano app-chain runtime configuration");
        schema.put("description", "M0a structural schema; semantic coverage is PARTIAL");
        schema.put("type", "object");

        ObjectNode rootProperties = schema.putObject("properties");
        ObjectNode yano = objectSchema(rootProperties, "yano");
        yano.put("additionalProperties", true);
        ObjectNode appChain = objectSchema(yano.withObject("properties"), "app-chain");
        appChain.put("additionalProperties", false);
        appChain.put("x-yano-validation-coverage", "PARTIAL");

        ObjectNode flatProperties = appChain.withObject("properties");
        ObjectNode chainSchema = json.createObjectNode();
        chainSchema.put("type", "object");
        chainSchema.put("additionalProperties", false);
        chainSchema.put("x-yano-validation-coverage", "PARTIAL");
        ObjectNode chainProperties = chainSchema.putObject("properties");

        for (AppChainPropertyDefinition definition : registry.definitions()) {
            if ("chains".equals(definition.suffix())) {
                continue;
            }
            addDefinition(flatProperties, definition.suffix(), definition);
            if (definition.indexed()) {
                addDefinition(chainProperties, definition.suffix(), definition);
            }
        }
        for (DynamicNamespaceDefinition namespace : registry.dynamicNamespaces()) {
            String suffix = namespace.prefix().substring(0, namespace.prefix().length() - 1);
            enableDynamicNamespace(flatProperties, suffix, namespace);
            enableDynamicNamespace(chainProperties, suffix, namespace);
        }

        ObjectNode indexedChains = appChain.putObject("patternProperties");
        indexedChains.set("^chains\\[[0-9]+]$", chainSchema);
        schema.put("additionalProperties", true);
        return pretty(schema);
    }

    public String propertyCatalogJson(AppChainPropertyRegistry registry)
            throws JsonProcessingException {
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("schemaVersion", 1);
        catalog.put("validationCoverage", "PARTIAL");
        catalog.put("sources", registry.sources().stream()
                .map(AppChainMetadataSource::id).toList());
        catalog.put("properties", registry.definitions().stream()
                .map(AppChainMetadataExporter::catalogProperty).toList());
        catalog.put("dynamicNamespaces", registry.dynamicNamespaces());
        return pretty(json.valueToTree(catalog));
    }

    static Map<String, Object> catalogProperty(AppChainPropertyDefinition property) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("key", property.key());
        value.put("owner", property.owner());
        value.put("type", property.type());
        value.put("defaultValue", property.defaultValue());
        value.put("minimum", property.minimum());
        value.put("maximum", property.maximum());
        value.put("minimumUtf8Bytes", property.minimumUtf8Bytes());
        value.put("maximumUtf8Bytes", property.maximumUtf8Bytes());
        value.put("maximumItems", property.maximumItems());
        value.put("allowedValues", property.allowedValues().stream().sorted().toList());
        value.put("scope", property.scope());
        value.put("changePolicy", property.changePolicy());
        value.put("secret", property.secret());
        value.put("indexed", property.indexed());
        value.put("constraintProvenance", property.constraintProvenance());
        value.put("coverage", property.coverage());
        value.put("description", property.description());
        return value;
    }

    private void addDefinition(
            ObjectNode properties,
            String dottedPath,
            AppChainPropertyDefinition definition) {
        String[] segments = dottedPath.split("\\.");
        ObjectNode current = properties;
        for (int index = 0; index < segments.length - 1; index++) {
            ObjectNode container = objectSchema(current, segments[index]);
            current = container.withObject("properties");
        }
        current.set(segments[segments.length - 1], definitionSchema(definition));
    }

    private ObjectNode definitionSchema(AppChainPropertyDefinition definition) {
        ObjectNode node = json.createObjectNode();
        switch (definition.type()) {
            case BOOLEAN -> node.put("type", "boolean");
            case INTEGER -> node.put("type", "integer");
            case LONG -> node.put("type", "integer");
            case STRING -> node.put("type", "string");
            case STRING_LIST -> {
                ArrayNode variants = node.putArray("oneOf");
                variants.addObject().put("type", "string");
                ObjectNode array = variants.addObject();
                array.put("type", "array");
                array.putObject("items").put("type", "string");
            }
            case OBJECT -> node.put("type", "object");
        }
        if (definition.defaultValue() != null && !definition.secret()) {
            putDefault(node, definition);
        }
        if (definition.minimum() != null) {
            node.put("minimum", definition.minimum());
        }
        if (definition.maximum() != null) {
            node.put("maximum", definition.maximum());
        }
        if (definition.minimumUtf8Bytes() != null) {
            node.put("x-yano-min-utf8-bytes", definition.minimumUtf8Bytes());
        }
        if (definition.maximumUtf8Bytes() != null) {
            node.put("x-yano-max-utf8-bytes", definition.maximumUtf8Bytes());
        }
        if (definition.maximumItems() != null) {
            node.put("maxItems", definition.maximumItems());
            node.put("x-yano-max-comma-separated-items", definition.maximumItems());
        }
        if (!definition.allowedValues().isEmpty()) {
            ArrayNode values = node.putArray("enum");
            definition.allowedValues().stream().sorted().forEach(values::add);
        }
        node.put("description", definition.description());
        node.put("x-yano-owner", definition.owner());
        node.put("x-yano-scope", definition.scope().name());
        node.put("x-yano-change-policy", definition.changePolicy().name());
        node.put("x-yano-secret", definition.secret());
        node.put("x-yano-constraint-provenance", definition.constraintProvenance().name());
        node.put("x-yano-validation-coverage", definition.coverage().name());
        return node;
    }

    private static void putDefault(ObjectNode node, AppChainPropertyDefinition definition) {
        String value = definition.defaultValue();
        if (definition.type() == PropertyType.BOOLEAN) {
            node.put("default", Boolean.parseBoolean(value));
        } else if (definition.type() == PropertyType.INTEGER
                || definition.type() == PropertyType.LONG) {
            node.put("default", Long.parseLong(value));
        } else {
            node.put("default", value);
        }
    }

    private static ObjectNode objectSchema(ObjectNode properties, String name) {
        if (properties.get(name) instanceof ObjectNode existing) {
            return existing;
        }
        ObjectNode object = properties.putObject(name);
        object.put("type", "object");
        object.putObject("properties");
        object.put("additionalProperties", false);
        return object;
    }

    private static void enableDynamicNamespace(
            ObjectNode properties,
            String dottedPath,
            DynamicNamespaceDefinition namespace) {
        String[] segments = dottedPath.split("\\.");
        ObjectNode current = properties;
        ObjectNode container = null;
        for (String segment : segments) {
            container = objectSchema(current, segment);
            current = container.withObject("properties");
        }
        container.put("additionalProperties", namespace.coverage() != ValidationCoverage.FULL);
        container.put("x-yano-owner", namespace.owner());
        container.put("x-yano-validation-coverage", namespace.coverage().name());
        container.put("description", namespace.description());
    }

    private String pretty(com.fasterxml.jackson.databind.JsonNode node)
            throws JsonProcessingException {
        return json.writerWithDefaultPrettyPrinter().writeValueAsString(node)
                + System.lineSeparator();
    }
}
