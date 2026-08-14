package com.bloxbean.cardano.yano.appchain.config;

import java.util.List;

/** Versioned declarative configuration metadata embedded by an app-chain component. */
public record AppChainMetadataDescriptor(
        int schemaVersion,
        String id,
        List<AppChainPropertyDefinition> properties,
        List<DynamicNamespaceDefinition> dynamicNamespaces) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String RESOURCE_PATH =
            "META-INF/yano/appchain-config-metadata-v1.json";

    public AppChainMetadataDescriptor {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported metadata schemaVersion: "
                    + schemaVersion);
        }
        if (id == null || !id.matches("^[A-Za-z0-9][A-Za-z0-9._/-]{0,199}$")) {
            throw new IllegalArgumentException("metadata descriptor id is invalid");
        }
        properties = properties == null ? List.of() : List.copyOf(properties);
        dynamicNamespaces = dynamicNamespaces == null
                ? List.of() : List.copyOf(dynamicNamespaces);
        for (AppChainPropertyDefinition property : properties) {
            if (!id.equals(property.owner())) {
                throw new IllegalArgumentException("property owner '" + property.owner()
                        + "' must match metadata descriptor id '" + id + "'");
            }
        }
        for (DynamicNamespaceDefinition namespace : dynamicNamespaces) {
            if (!id.equals(namespace.owner())) {
                throw new IllegalArgumentException("namespace owner '" + namespace.owner()
                        + "' must match metadata descriptor id '" + id + "'");
            }
        }
    }

    public AppChainMetadataSource toSource() {
        return new AppChainMetadataSource(id, properties, dynamicNamespaces);
    }
}
