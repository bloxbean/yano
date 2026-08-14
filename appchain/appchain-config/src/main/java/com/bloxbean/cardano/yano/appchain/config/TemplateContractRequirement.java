package com.bloxbean.cardano.yano.appchain.config;

/** A property or indexed property pattern supplied outside a shared template. */
public record TemplateContractRequirement(
        String propertyPattern,
        String suppliedBy,
        boolean requiredBeforeStartup,
        String description) {

    public TemplateContractRequirement {
        if (propertyPattern == null || !propertyPattern.matches(
                "^yano\\.app-chain\\.(?:chains\\[\\*]\\.)?"
                        + "[a-z0-9][a-z0-9_-]*(?:\\.[a-z0-9][a-z0-9_-]*)*$")) {
            throw new IllegalArgumentException(
                    "propertyPattern must be a canonical key with optional chains[*]");
        }
        if (suppliedBy == null
                || !suppliedBy.matches("^[A-Za-z0-9][A-Za-z0-9._/-]{0,199}$")) {
            throw new IllegalArgumentException("suppliedBy is invalid");
        }
        if (description == null || description.isBlank() || description.length() > 1024
                || description.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("description must be safe, non-blank text");
        }
    }
}
