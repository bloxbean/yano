package com.bloxbean.cardano.yano.appchain.composite;

/** Exact identity of one planned component implementation generation. */
public record ComponentGeneration(String componentId, String semanticVersion, long fromHeight) {
    public ComponentGeneration {
        componentId = CompositeValidation.id(componentId, "componentId");
        semanticVersion = CompositeValidation.printable(semanticVersion, "semanticVersion");
        if (fromHeight < 1) {
            throw new IllegalArgumentException("fromHeight must be >= 1");
        }
    }
}
