package com.bloxbean.cardano.yano.catalog;

import java.util.regex.Pattern;

final class CatalogValidation {
    static final int MAX_ID_LENGTH = 160;
    static final int MAX_NAME_LENGTH = 128;
    static final int MAX_PROVIDER_CLASS_LENGTH = 512;
    static final int MAX_DEPENDENCIES = 256;
    static final int MAX_CONTRIBUTIONS = 256;

    private static final String DNS_LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";
    private static final Pattern BUNDLE_ID = Pattern.compile(DNS_LABEL + "(?:\\." + DNS_LABEL + ")+" );
    private static final Pattern CONTRIBUTION_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:+-]{0,127}");
    private static final String CLASS_SEGMENT = "[A-Za-z_$][A-Za-z0-9_$]*";
    private static final Pattern PROVIDER_CLASS = Pattern.compile(CLASS_SEGMENT + "(?:\\." + CLASS_SEGMENT + ")+" );

    private CatalogValidation() {
    }

    static String bundleId(String value, String field) {
        if (value == null || value.length() < 3 || value.length() > MAX_ID_LENGTH
                || !BUNDLE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(field
                    + " must be a 3-160 character lowercase reverse-DNS identifier");
        }
        return value;
    }

    static String contributionName(ContributionKind kind, String value, String field) {
        if (kind == ContributionKind.DOMAIN_API) {
            return bundleId(value, field);
        }
        if (value == null || value.length() > MAX_NAME_LENGTH
                || !CONTRIBUTION_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException(field
                    + " must be a 1-128 character ASCII selector without whitespace");
        }
        if ((kind == ContributionKind.EFFECT_EXECUTOR
                || kind == ContributionKind.FINALIZED_SINK) && value.indexOf('.') >= 0) {
            throw new IllegalArgumentException(field + " for " + kind.manifestKey()
                    + " must not contain '.' because it is a configuration namespace selector");
        }
        if (kind == ContributionKind.SIGNER_PROVIDER && value.indexOf(':') >= 0) {
            throw new IllegalArgumentException(field + " for signer-provider must not contain ':'"
                    + " because ':' separates the key reference");
        }
        return value;
    }

    static String providerClass(String value, String field) {
        if (value == null || value.length() > MAX_PROVIDER_CLASS_LENGTH
                || !PROVIDER_CLASS.matcher(value).matches()) {
            throw new IllegalArgumentException(field
                    + " must be a fully qualified binary Java class name of at most 512 characters");
        }
        return value;
    }
}
