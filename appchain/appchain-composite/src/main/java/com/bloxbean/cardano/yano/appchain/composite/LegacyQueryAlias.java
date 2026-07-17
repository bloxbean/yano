package com.bloxbean.cardano.yano.appchain.composite;

/** Compatibility query route resolved to a component-local path. */
public record LegacyQueryAlias(String aliasPath, String componentId, String localPath) {
    public LegacyQueryAlias {
        aliasPath = CompositeValidation.route(aliasPath, "aliasPath");
        componentId = CompositeValidation.id(componentId, "componentId");
        localPath = CompositeValidation.route(localPath, "localPath");
        if (aliasPath.startsWith("components/") || aliasPath.startsWith("composite/")) {
            throw new IllegalArgumentException("aliasPath must not shadow a composite-owned query route");
        }
    }
}
