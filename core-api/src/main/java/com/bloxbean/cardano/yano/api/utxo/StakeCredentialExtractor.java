package com.bloxbean.cardano.yano.api.utxo;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressType;
import com.bloxbean.cardano.client.address.PointerAddress;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

/**
 * Canonical address-to-stake-credential extraction shared by live index writers,
 * index rebuilds and full-scan validation.
 */
public final class StakeCredentialExtractor {
    private StakeCredentialExtractor() {
    }

    /**
     * Extract a non-pointer stake credential. Byron, enterprise, reward and
     * pointer addresses return {@code null}.
     */
    public static StakeCredentialId extractNonPointer(String addressText) {
        Address address = parseAddressOrNull(addressText);
        return extractNonPointer(address);
    }

    /** Extract a non-pointer stake credential from an already parsed address. */
    public static StakeCredentialId extractNonPointer(Address address) {
        if (address == null) return null;
        AddressType type = address.getAddressType();
        if (type == AddressType.Byron || type == AddressType.Ptr || type == AddressType.Reward) {
            return null;
        }
        return extractDelegationCredential(address);
    }

    /** Extract the pointer tuple from a pointer address, otherwise {@code null}. */
    public static PointerAddressId extractPointer(String addressText) {
        Address address = parseAddressOrNull(addressText);
        return extractPointer(address);
    }

    /** Extract the pointer tuple from an already parsed address. */
    public static PointerAddressId extractPointer(Address address) {
        if (address == null || address.getAddressType() != AddressType.Ptr) return null;
        try {
            var pointer = new PointerAddress(address.getBytes()).getPointer();
            if (pointer == null) return null;
            return new PointerAddressId(
                    pointer.getSlot(), pointer.getTxIndex(), pointer.getCertIndex());
        } catch (RuntimeException unresolvable) {
            // Ledger-valid pointer addresses can carry payloads that CCL cannot
            // represent (malformed variable-nat or an index outside int range).
            // Writers must retain the UTXO and record it as unresolvable rather
            // than allowing an address-library exception to abort block apply.
            return null;
        }
    }

    /** Parse every address representation accepted by the UTXO store. */
    public static Address parseAddressOrNull(String addressText) {
        if (addressText == null || addressText.isBlank()) return null;
        try {
            return new Address(addressText);
        } catch (Exception textFailure) {
            try {
                return new Address(HexUtil.decodeHexString(addressText));
            } catch (Exception hexFailure) {
                if (addressText.startsWith("addr1") || addressText.startsWith("addr_test1")) {
                    IllegalArgumentException failure = new IllegalArgumentException(
                            "Malformed Shelley payment address: " + addressText, textFailure);
                    failure.addSuppressed(hexFailure);
                    throw failure;
                }
                // Byron/bootstrap and malformed non-Shelley representations do not
                // contribute stake. All paths share this behavior so scan, rebuild and
                // incremental maintenance cannot disagree.
                return null;
            }
        }
    }

    /** Extract a base-address delegation credential from an already parsed address. */
    public static StakeCredentialId extractDelegationCredential(Address address) {
        if (address == null) return null;
        byte[] hash = address.getDelegationCredentialHash().orElse(null);
        if (hash == null || hash.length != StakeCredentialId.HASH_LENGTH) return null;
        return new StakeCredentialId(stakeCredentialType(address), hash);
    }

    public static int stakeCredentialType(Address address) {
        int typeNibble = (address.getBytes()[0] >>> 4) & 0x0F;
        return switch (typeNibble) {
            case 0, 1, 4, 0x0E -> 0;
            case 2, 3, 5, 0x0F -> 1;
            default -> throw new IllegalArgumentException(
                    "Address type has no supported stake credential: " + typeNibble);
        };
    }
}
