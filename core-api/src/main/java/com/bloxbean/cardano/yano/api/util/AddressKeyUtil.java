package com.bloxbean.cardano.yano.api.util;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.util.AddressUtil;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;

/**
 * Canonical Shelley credential extraction and the legacy 28-byte address
 * hash used by the core UTXO index. ADR-034 exact-address archive subjects
 * deliberately use the separate collision-checked 32-byte AddressKeyCodec.
 */
public final class AddressKeyUtil {
    private AddressKeyUtil() {
    }

    /** Blake2b-224 of the raw address bytes; falls back to hashing the literal string. */
    public static byte[] addrHash28(String bech32OrHex) {
        try {
            byte[] raw = AddressUtil.addressToBytes(bech32OrHex);
            return Blake2bUtil.blake2bHash224(raw);
        } catch (Exception e) {
            return Blake2bUtil.blake2bHash224(bech32OrHex.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Payment credential hash, or null for non-Shelley/unparseable addresses. */
    public static byte[] paymentCred28(String bech32OrHex) {
        Address address = parse(bech32OrHex);
        if (address == null) return null;
        try {
            return AddressProvider.getPaymentCredentialHash(address).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Stake/delegation credential hash, or null when the address carries none. */
    public static byte[] stakeCred28(String bech32OrHex) {
        Address address = parse(bech32OrHex);
        if (address == null) return null;
        try {
            return AddressProvider.getDelegationCredentialHash(address).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * One scoped hash per available scope, hex-encoded. Scope values match
     * {@code AccountHistoryProvider.ADDR_SCOPE_*}: 0=address-hash,
     * 1=payment-cred, 2=stake-cred. Parses the address ONCE — this runs per
     * touched address on the block-apply path.
     */
    public static List<ScopedHash> deriveScopes(String bech32OrHex) {
        List<ScopedHash> scopes = new ArrayList<>(3);
        byte[] raw = null;
        try {
            raw = AddressUtil.addressToBytes(bech32OrHex);
        } catch (Exception e) {
            // Fall back to hashing the literal string (mirrors addrHash28).
        }
        byte[] addrHash = Blake2bUtil.blake2bHash224(raw != null ? raw
                : bech32OrHex.getBytes(StandardCharsets.UTF_8));
        if (addrHash.length == 28) {
            scopes.add(new ScopedHash(0, HexUtil.encodeHexString(addrHash)));
        }
        if (raw != null) {
            try {
                Address parsed = new Address(raw);
                byte[] paymentCred = AddressProvider.getPaymentCredentialHash(parsed).orElse(null);
                if (paymentCred != null && paymentCred.length == 28) {
                    scopes.add(new ScopedHash(1, HexUtil.encodeHexString(paymentCred)));
                }
                byte[] stakeCred = AddressProvider.getDelegationCredentialHash(parsed).orElse(null);
                if (stakeCred != null && stakeCred.length == 28) {
                    scopes.add(new ScopedHash(2, HexUtil.encodeHexString(stakeCred)));
                }
            } catch (Exception e) {
                // Byron/unparseable: address-hash scope only.
            }
        }
        return scopes;
    }

    public record ScopedHash(int scope, String hash28Hex) {
    }

    private static Address parse(String bech32OrHex) {
        try {
            return new Address(bech32OrHex);
        } catch (Exception e) {
            try {
                return new Address(HexUtil.decodeHexString(bech32OrHex));
            } catch (Exception ex) {
                return null;
            }
        }
    }
}
