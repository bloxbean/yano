package com.bloxbean.cardano.yano.appchain.config;

import java.util.Objects;

/** Coverage declaration for an open app-chain extension namespace. */
public record DynamicNamespaceDefinition(
        String prefix,
        String owner,
        ValidationCoverage coverage,
        String description) {

    public DynamicNamespaceDefinition {
        if (prefix == null || !prefix.matches(
                "^[a-z0-9][a-z0-9_-]*(?:\\.[a-z0-9][a-z0-9_-]*)*\\.$")) {
            throw new IllegalArgumentException(
                    "dynamic namespace prefix must be lowercase and end with '.'");
        }
        owner = Objects.requireNonNull(owner, "owner");
        if (!owner.matches("^[A-Za-z0-9][A-Za-z0-9._/-]{0,199}$")) {
            throw new IllegalArgumentException("dynamic namespace owner is invalid");
        }
        coverage = Objects.requireNonNull(coverage, "coverage");
        description = Objects.requireNonNull(description, "description");
        if (description.isBlank() || description.length() > 1024
                || description.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("dynamic namespace description is invalid");
        }
    }
}
