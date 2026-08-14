package com.bloxbean.cardano.yano.appchain.config;

import java.util.Objects;
import java.util.Set;

/** Typed, source-backed metadata for one framework or component app-chain property. */
public record AppChainPropertyDefinition(
        String key,
        String owner,
        PropertyType type,
        String defaultValue,
        Long minimum,
        Long maximum,
        Integer minimumUtf8Bytes,
        Integer maximumUtf8Bytes,
        Integer maximumItems,
        Set<String> allowedValues,
        PropertyScope scope,
        ChangePolicy changePolicy,
        boolean secret,
        boolean indexed,
        ConstraintProvenance constraintProvenance,
        ValidationCoverage coverage,
        String description) {

    public AppChainPropertyDefinition {
        if (key == null
                || !key.matches("^yano\\.app-chain\\.[a-z0-9][a-z0-9_-]*"
                + "(?:\\.[a-z0-9][a-z0-9_-]*)*$")) {
            throw new IllegalArgumentException("key must be a canonical lowercase property under "
                    + AppChainPropertyRegistry.APP_CHAIN_PREFIX);
        }
        owner = requireIdentifier(owner, "owner");
        type = Objects.requireNonNull(type, "type");
        allowedValues = allowedValues == null ? Set.of() : Set.copyOf(allowedValues);
        if (defaultValue != null) {
            requireSafeText(defaultValue, "defaultValue", 1024);
        }
        for (String allowedValue : allowedValues) {
            requireSafeText(allowedValue, "allowedValue", 1024);
        }
        scope = Objects.requireNonNull(scope, "scope");
        changePolicy = Objects.requireNonNull(changePolicy, "changePolicy");
        constraintProvenance = Objects.requireNonNull(
                constraintProvenance, "constraintProvenance");
        coverage = Objects.requireNonNull(coverage, "coverage");
        description = requireSafeText(description, "description", 1024);
        if (minimum != null && maximum != null && minimum > maximum) {
            throw new IllegalArgumentException("minimum must be <= maximum for " + key);
        }
        if ((minimumUtf8Bytes != null && minimumUtf8Bytes < 0)
                || (maximumUtf8Bytes != null && maximumUtf8Bytes < 0)
                || (minimumUtf8Bytes != null && maximumUtf8Bytes != null
                && minimumUtf8Bytes > maximumUtf8Bytes)) {
            throw new IllegalArgumentException("invalid UTF-8 byte bounds for " + key);
        }
        if (maximumItems != null && maximumItems < 0) {
            throw new IllegalArgumentException("maximumItems must be non-negative for " + key);
        }
        if (secret != (scope == PropertyScope.SECRET)) {
            throw new IllegalArgumentException(
                    "SECRET scope and secret=true must be declared together: " + key);
        }
        if (secret && defaultValue != null) {
            throw new IllegalArgumentException("secret properties cannot declare defaults: " + key);
        }
        if ((minimum != null || maximum != null)
                && type != PropertyType.INTEGER && type != PropertyType.LONG) {
            throw new IllegalArgumentException("numeric bounds require INTEGER or LONG: " + key);
        }
        if ((minimumUtf8Bytes != null || maximumUtf8Bytes != null)
                && type != PropertyType.STRING) {
            throw new IllegalArgumentException("UTF-8 bounds require STRING: " + key);
        }
        if (maximumItems != null && type != PropertyType.STRING_LIST) {
            throw new IllegalArgumentException("maximumItems requires STRING_LIST: " + key);
        }
        if (!allowedValues.isEmpty() && type != PropertyType.STRING) {
            throw new IllegalArgumentException("allowedValues requires STRING: " + key);
        }
    }

    private static String requireIdentifier(String value, String label) {
        String candidate = requireSafeText(value, label, 200);
        if (!candidate.matches("^[A-Za-z0-9][A-Za-z0-9._/-]*$")) {
            throw new IllegalArgumentException(label + " contains unsupported characters");
        }
        return candidate;
    }

    private static String requireSafeText(String value, String label, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(label + " must be safe, non-blank text");
        }
        return value;
    }

    /** Path relative to {@code yano.app-chain.}. */
    public String suffix() {
        return key.substring(AppChainPropertyRegistry.APP_CHAIN_PREFIX.length());
    }

    /** True when numeric/enum constraints may fail validation in M0a. */
    public boolean constraintsEnforceable() {
        return constraintProvenance.enforceable();
    }
}
