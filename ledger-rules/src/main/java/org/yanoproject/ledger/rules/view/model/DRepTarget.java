package org.yanoproject.ledger.rules.view.model;

import com.bloxbean.cardano.client.transaction.spec.governance.DRep;

import java.util.Objects;

/**
 * The target of a stake credential's vote delegation (Haskell {@code DRep}).
 *
 * @param kind       a registered DRep credential or one of the two predefined DReps
 * @param credential the DRep credential when {@code kind} is {@link Kind#CREDENTIAL}, otherwise {@code null}
 */
public record DRepTarget(Kind kind, CredentialKey credential) {

    public enum Kind {
        CREDENTIAL,
        ALWAYS_ABSTAIN,
        ALWAYS_NO_CONFIDENCE
    }

    public static final DRepTarget ALWAYS_ABSTAIN = new DRepTarget(Kind.ALWAYS_ABSTAIN, null);
    public static final DRepTarget ALWAYS_NO_CONFIDENCE = new DRepTarget(Kind.ALWAYS_NO_CONFIDENCE, null);

    public DRepTarget {
        Objects.requireNonNull(kind, "kind");
        if ((kind == Kind.CREDENTIAL) != (credential != null)) {
            throw new IllegalArgumentException("A credential is required exactly for Kind.CREDENTIAL");
        }
    }

    public static DRepTarget credential(CredentialKey credential) {
        return new DRepTarget(Kind.CREDENTIAL, Objects.requireNonNull(credential, "credential"));
    }

    /** Converts a CCL DRep. */
    public static DRepTarget of(DRep drep) {
        Objects.requireNonNull(drep, "drep");
        return switch (drep.getType()) {
            case ADDR_KEYHASH -> credential(CredentialKey.key(drep.getHash()));
            case SCRIPTHASH -> credential(CredentialKey.script(drep.getHash()));
            case ABSTAIN -> ALWAYS_ABSTAIN;
            case NO_CONFIDENCE -> ALWAYS_NO_CONFIDENCE;
        };
    }

    /** @return true when this targets the given registered-DRep credential */
    public boolean isCredential(CredentialKey drep) {
        return kind == Kind.CREDENTIAL && credential.equals(drep);
    }
}
