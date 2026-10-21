package org.yanoproject.ledger.rules.util;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.CredentialType;

import java.util.Arrays;
import java.util.Objects;

/**
 * Reward (account) address decoding.
 *
 * <p>CCL gives reward addresses as bech32 ({@code Withdrawal}, {@code ProposalProcedure}) or hex
 * ({@code PoolRegistration}). A reward address is a header byte ({@code 0xE?} key hash,
 * {@code 0xF?} script hash, low nibble = network id) followed by the 28-byte credential.</p>
 */
public final class RewardAddresses {

    private static final int CREDENTIAL_LENGTH = 28;

    private RewardAddresses() {
    }

    /**
     * @param address bech32 ({@code stake...}) or hex reward address
     * @return the raw address bytes
     */
    public static byte[] bytes(String address) {
        Objects.requireNonNull(address, "address");
        if (address.startsWith("stake")) {
            return new Address(address).getBytes();
        }
        return HexUtil.decodeHexString(address);
    }

    /**
     * @param address bech32 or hex reward address
     * @return its stake credential
     * @throws IllegalArgumentException if it is not a reward address
     */
    public static CredentialKey credential(String address) {
        byte[] bytes = bytes(address);
        if (bytes.length != 1 + CREDENTIAL_LENGTH) {
            throw new IllegalArgumentException("Not a reward address (length " + bytes.length + "): " + address);
        }
        CredentialType type = switch ((bytes[0] & 0xf0) >>> 4) {
            case 0xE -> CredentialType.KEY;
            case 0xF -> CredentialType.SCRIPT;
            default -> throw new IllegalArgumentException("Not a reward address header: " + address);
        };
        return new CredentialKey(type, HexUtil.encodeHexString(Arrays.copyOfRange(bytes, 1, bytes.length)));
    }

    /** @return the network id nibble of a reward address */
    public static int networkId(String address) {
        return bytes(address)[0] & 0x0f;
    }
}
