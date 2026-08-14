package com.bloxbean.cardano.yano.appchain.config;

import java.util.HashSet;
import java.util.List;

/**
 * Declarative metadata contributed by the framework, a first-party component,
 * or a custom plugin descriptor.
 */
public record AppChainMetadataSource(
        String id,
        List<AppChainPropertyDefinition> properties,
        List<DynamicNamespaceDefinition> dynamicNamespaces) {

    public AppChainMetadataSource {
        if (id == null || !id.matches("^[A-Za-z0-9][A-Za-z0-9._/-]{0,199}$")) {
            throw new IllegalArgumentException("metadata source id is invalid");
        }
        properties = properties == null ? List.of() : List.copyOf(properties);
        dynamicNamespaces = dynamicNamespaces == null
                ? List.of() : List.copyOf(dynamicNamespaces);
        var propertyKeys = new HashSet<String>();
        for (AppChainPropertyDefinition property : properties) {
            if (!propertyKeys.add(property.key())) {
                throw new IllegalArgumentException("duplicate property in metadata source '"
                        + id + "': " + property.key());
            }
        }
        var namespacePrefixes = new HashSet<String>();
        for (DynamicNamespaceDefinition namespace : dynamicNamespaces) {
            if (!namespacePrefixes.add(namespace.prefix())) {
                throw new IllegalArgumentException("duplicate namespace in metadata source '"
                        + id + "': " + namespace.prefix());
            }
        }
    }
}
