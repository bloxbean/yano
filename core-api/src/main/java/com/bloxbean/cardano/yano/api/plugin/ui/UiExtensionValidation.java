package com.bloxbean.cardano.yano.api.plugin.ui;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Shared bounds and canonical validation for the UI extension API v1. */
public final class UiExtensionValidation {
    public static final int UI_API_VERSION = 1;
    public static final int MAX_ASSETS = 256;
    public static final long MAX_ASSET_BYTES = 4L * 1024 * 1024;
    public static final long MAX_TOTAL_ASSET_BYTES = 16L * 1024 * 1024;
    public static final int MAX_CAPABILITIES = 32;
    public static final int MAX_PERMISSIONS = 16;

    private static final Pattern ID = Pattern.compile("[a-z0-9](?:[a-z0-9.-]{0,62}[a-z0-9])?");
    private static final Pattern CAPABILITY = Pattern.compile("[a-z0-9](?:[a-z0-9._-]{0,126}[a-z0-9])?");

    private UiExtensionValidation() {
    }

    static String identifier(String value, String field, int max) {
        if (value == null || value.length() > max || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    static String displayText(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    static String assetPath(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 240 || value.startsWith("/")
                || value.startsWith("META-INF/") || value.indexOf('\\') >= 0
                || value.contains("//") || value.endsWith("/")) {
            throw new IllegalArgumentException(field + " is not a normalized relative UI path");
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException(field + " is not a normalized relative UI path");
            }
        }
        return value;
    }

    static List<String> capabilities(List<String> values) {
        List<String> result = List.copyOf(Objects.requireNonNull(values, "requiredCapabilities"));
        if (result.size() > MAX_CAPABILITIES) throw new IllegalArgumentException("too many capabilities");
        Set<String> unique = new HashSet<>();
        for (String value : result) {
            if (value == null || !CAPABILITY.matcher(value).matches() || !unique.add(value)) {
                throw new IllegalArgumentException("invalid or duplicate required capability");
            }
        }
        return result;
    }

    static List<UiExtensionPermission> permissions(List<UiExtensionPermission> values) {
        List<UiExtensionPermission> result = List.copyOf(Objects.requireNonNull(values, "permissions"));
        if (result.size() > MAX_PERMISSIONS) throw new IllegalArgumentException("too many UI permissions");
        if (new HashSet<>(result).size() != result.size()) {
            throw new IllegalArgumentException("duplicate UI permission");
        }
        return result;
    }
}
