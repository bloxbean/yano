package org.yanoproject.ledger.rules.view.model;

import java.math.BigInteger;
import java.util.Objects;

/**
 * A registered DRep (Haskell {@code DRepState}).
 *
 * @param credential  the DRep credential
 * @param deposit     the deposit recorded at registration (the refund on deregistration)
 * @param expiryEpoch the last epoch the DRep counts as active ({@code drepExpiry}). Required: a view
 *                    that cannot supply it must answer {@code Lookup.Unavailable},
 *                    because dormant-period bumps and activity refreshes are applied to it
 */
public record DRepState(CredentialKey credential, BigInteger deposit, long expiryEpoch) {

    public DRepState {
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(deposit, "deposit");
    }

    public DRepState withExpiryEpoch(long epoch) {
        return new DRepState(credential, deposit, epoch);
    }
}
