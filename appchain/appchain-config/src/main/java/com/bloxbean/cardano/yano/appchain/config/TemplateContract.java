package com.bloxbean.cardano.yano.appchain.config;

import java.util.HashSet;
import java.util.List;

/** Versioned declaration of values supplied by a launcher or deployment overlay. */
public record TemplateContract(
        int schemaVersion,
        String id,
        List<TemplateContractRequirement> suppliedProperties) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public TemplateContract {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported template contract schemaVersion: "
                    + schemaVersion);
        }
        if (id == null || !id.matches("^[A-Za-z0-9][A-Za-z0-9._/-]{0,199}$")) {
            throw new IllegalArgumentException("template contract id is invalid");
        }
        suppliedProperties = suppliedProperties == null
                ? List.of() : List.copyOf(suppliedProperties);
        var patterns = new HashSet<String>();
        for (TemplateContractRequirement requirement : suppliedProperties) {
            if (!patterns.add(requirement.propertyPattern())) {
                throw new IllegalArgumentException("duplicate template-contract propertyPattern: "
                        + requirement.propertyPattern());
            }
        }
    }
}
