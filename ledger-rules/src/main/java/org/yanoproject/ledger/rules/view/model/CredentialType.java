package org.yanoproject.ledger.rules.view.model;

/**
 * Kind of a ledger credential: a key hash or a script hash.
 *
 * <p>The ordinal order matches the Cardano CBOR tag (0 = key hash, 1 = script hash), which is also
 * the {@code credType} convention of {@code LedgerStateProvider}.</p>
 */
public enum CredentialType {
    KEY,
    SCRIPT;

    /** @return the CBOR / {@code LedgerStateProvider} tag: 0 for {@link #KEY}, 1 for {@link #SCRIPT} */
    public int tag() {
        return ordinal();
    }

    /**
     * @param tag 0 (key hash) or 1 (script hash)
     * @return the matching type
     */
    public static CredentialType fromTag(int tag) {
        return switch (tag) {
            case 0 -> KEY;
            case 1 -> SCRIPT;
            default -> throw new IllegalArgumentException("Unknown credential tag: " + tag);
        };
    }
}
