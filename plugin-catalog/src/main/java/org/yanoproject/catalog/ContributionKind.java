package org.yanoproject.catalog;

import org.yanoproject.api.plugin.NodePlugin;
import org.yanoproject.api.plugin.PluginTrustTier;
import org.yanoproject.api.plugin.operations.PluginHealthProvider;
import org.yanoproject.api.plugin.operations.PluginMetricsProvider;
import org.yanoproject.api.utxo.index.UtxoIndexContributorProvider;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One supported ServiceLoader contribution kind of a bundle manifest.
 *
 * <p>Yano defines the four built-in kinds below. Trusted host artifacts add
 * further kinds through descriptors (see {@link ContributionKindRegistry}).
 * Two kinds are equal when their manifest keys are equal.</p>
 */
public final class ContributionKind {
    private static final Pattern MANIFEST_KEY = Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)*");
    private static final int MAX_MANIFEST_KEY_LENGTH = 64;

    /** General node lifecycle plugin. */
    public static final ContributionKind NODE_PLUGIN = new ContributionKind(
            "node-plugin", NodePlugin.class.getName(), false, PluginTrustTier.REQUIRED,
            1, false, "", Set.of(), false);
    /** Transactional UTxO-derived index; requires an owning manifest. */
    public static final ContributionKind UTXO_INDEX_CONTRIBUTOR = new ContributionKind(
            "utxo-index-contributor", UtxoIndexContributorProvider.class.getName(), true,
            PluginTrustTier.PRIVILEGED_LOCAL, CatalogValidation.MAX_CONTRIBUTIONS, false, "",
            Set.of("wallet"), false);
    /** Cached health source; schema v1 requires an owning bundle manifest. */
    public static final ContributionKind HEALTH = new ContributionKind(
            "health", PluginHealthProvider.class.getName(), true, PluginTrustTier.AUXILIARY_LOCAL,
            1, true, "", Set.of(), false);
    /** Cached custom-metrics source; schema v1 requires an owning bundle manifest. */
    public static final ContributionKind METRICS = new ContributionKind(
            "metrics", PluginMetricsProvider.class.getName(), true, PluginTrustTier.AUXILIARY_LOCAL,
            1, true, "", Set.of(), false);

    private final String manifestKey;
    private final String serviceInterface;
    private final boolean manifestRequired;
    private final PluginTrustTier trustTier;
    private final int maxPerBundle;
    private final boolean nameIsBundleId;
    private final String forbiddenNameChars;
    private final Set<String> reservedSystemNames;
    private final boolean operationsTracked;

    ContributionKind(
            String manifestKey,
            String serviceInterface,
            boolean manifestRequired,
            PluginTrustTier trustTier,
            int maxPerBundle,
            boolean nameIsBundleId,
            String forbiddenNameChars,
            Set<String> reservedSystemNames,
            boolean operationsTracked
    ) {
        if (manifestKey == null || manifestKey.length() > MAX_MANIFEST_KEY_LENGTH
                || !MANIFEST_KEY.matcher(manifestKey).matches()) {
            throw new IllegalArgumentException(
                    "key must be a 1-64 character lowercase hyphenated identifier");
        }
        this.manifestKey = manifestKey;
        this.serviceInterface = CatalogValidation.providerClass(serviceInterface, "serviceInterface");
        this.manifestRequired = manifestRequired;
        this.trustTier = Objects.requireNonNull(trustTier, "trustTier must be present");
        if (maxPerBundle < 1 || maxPerBundle > CatalogValidation.MAX_CONTRIBUTIONS) {
            throw new IllegalArgumentException("maxPerBundle must be between 1 and "
                    + CatalogValidation.MAX_CONTRIBUTIONS);
        }
        this.maxPerBundle = maxPerBundle;
        this.nameIsBundleId = nameIsBundleId;
        this.forbiddenNameChars = Objects.requireNonNull(
                forbiddenNameChars, "forbiddenNameChars must be present");
        this.reservedSystemNames = Set.copyOf(Objects.requireNonNull(
                reservedSystemNames, "reservedSystemNames must be present"));
        this.operationsTracked = operationsTracked;
        if (nameIsBundleId && (!forbiddenNameChars.isEmpty() || !this.reservedSystemNames.isEmpty())) {
            throw new IllegalArgumentException(
                    "nameIsBundleId conflicts with forbiddenNameChars and reservedSystemNames");
        }
        this.reservedSystemNames.forEach(name ->
                CatalogValidation.contributionName(this, name, "reservedSystemNames[]"));
    }

    /**
     * Returns the stable manifest discriminator.
     *
     * @return lowercase manifest kind key
     */
    public String manifestKey() {
        return manifestKey;
    }

    /**
     * Returns the binary name of the ServiceLoader SPI associated with this kind.
     *
     * @return supported service interface name
     */
    public String serviceInterface() {
        return serviceInterface;
    }

    /**
     * Whether this contribution has no synthetic legacy representation.
     *
     * @return true when an owning bundle manifest is mandatory
     */
    public boolean manifestRequired() {
        return manifestRequired;
    }

    /**
     * Returns the failure and isolation class of this kind's contributions.
     *
     * @return trust tier
     */
    public PluginTrustTier trustTier() {
        return trustTier;
    }

    /**
     * Returns how many contributions of this kind one bundle may declare.
     *
     * @return positive per-bundle limit
     */
    public int maxPerBundle() {
        return maxPerBundle;
    }

    /**
     * Whether a contribution name must equal its containing bundle id.
     *
     * @return true for bundle-owned singleton kinds
     */
    public boolean nameIsBundleId() {
        return nameIsBundleId;
    }

    /**
     * Returns the characters a contribution name of this kind must not contain.
     *
     * @return forbidden characters, possibly empty
     */
    public String forbiddenNameChars() {
        return forbiddenNameChars;
    }

    /**
     * Returns the names reserved for host SYSTEM contributions of this kind.
     *
     * @return immutable reserved names
     */
    public Set<String> reservedSystemNames() {
        return reservedSystemNames;
    }

    /**
     * Whether request operations of this kind are recorded in plugin operations data.
     *
     * @return true when operations are tracked
     */
    public boolean operationsTracked() {
        return operationsTracked;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ContributionKind kind && manifestKey.equals(kind.manifestKey);
    }

    @Override
    public int hashCode() {
        return manifestKey.hashCode();
    }

    @Override
    public String toString() {
        return manifestKey;
    }
}
