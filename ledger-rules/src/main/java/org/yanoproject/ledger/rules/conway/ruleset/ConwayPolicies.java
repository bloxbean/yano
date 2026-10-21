package org.yanoproject.ledger.rules.conway.ruleset;

/** The {@link RulePolicy} keys of the Conway rule sets. */
public final class ConwayPolicies {

    /** {@code computeDRepExpiryVersioned} (GovCert.hs:282-292): a new DRep's expiry. */
    public static final PolicyKey<DRepExpiry> DREP_EXPIRY = new PolicyKey<>("GOVCERT.computeDRepExpiry",
            DRepExpiry.class);

    private ConwayPolicies() {
    }
}
