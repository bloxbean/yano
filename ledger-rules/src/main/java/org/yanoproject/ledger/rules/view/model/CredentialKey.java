package org.yanoproject.ledger.rules.view.model;

import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeCredential;
import com.bloxbean.cardano.client.util.HexUtil;

import java.util.Objects;

/**
 * A typed credential (stake, DRep, committee cold or hot) used as a ledger-view key.
 *
 * @param type    key hash or script hash
 * @param hashHex the 28-byte hash, lowercase hex
 */
public record CredentialKey(CredentialType type, String hashHex) {

    public CredentialKey {
        Objects.requireNonNull(type, "type");
        hashHex = HexStrings.normalize(hashHex, "credential hash", HexStrings.HASH28);
    }

    public static CredentialKey key(String hashHex) {
        return new CredentialKey(CredentialType.KEY, hashHex);
    }

    public static CredentialKey script(String hashHex) {
        return new CredentialKey(CredentialType.SCRIPT, hashHex);
    }

    /** Converts a CCL address-module credential (DRep, committee cold/hot, voter). */
    public static CredentialKey of(Credential credential) {
        Objects.requireNonNull(credential, "credential");
        CredentialType type = switch (credential.getType()) {
            case Key -> CredentialType.KEY;
            case Script -> CredentialType.SCRIPT;
        };
        return new CredentialKey(type, HexUtil.encodeHexString(credential.getBytes()));
    }

    /** Converts a CCL stake credential (certificates). */
    public static CredentialKey of(StakeCredential credential) {
        Objects.requireNonNull(credential, "credential");
        CredentialType type = switch (credential.getType()) {
            case ADDR_KEYHASH -> CredentialType.KEY;
            case SCRIPTHASH -> CredentialType.SCRIPT;
        };
        return new CredentialKey(type, HexUtil.encodeHexString(credential.getHash()));
    }

    @Override
    public String toString() {
        return (type == CredentialType.KEY ? "key:" : "script:") + hashHex;
    }
}
