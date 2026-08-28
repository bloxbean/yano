package com.bloxbean.cardano.yano.archive.core.address;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.ByronAddress;
import com.bloxbean.cardano.client.address.PointerAddress;
import com.bloxbean.cardano.client.address.util.AddressUtil;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.util.AddressKeyUtil;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;

import java.util.Locale;
import java.util.Objects;

/** Stateless canonical address decomposition shared by projection contributors. */
public final class ArchiveAddressParser {

    private ArchiveAddressParser() {
    }

    /** Resolve a pre-Conway pointer coordinate to its stake credential, or {@code null}. */
    @FunctionalInterface
    public interface PointerLookup {
        ResolvedStakeCredential resolve(PointerCoordinate coordinate);
    }

    public record PointerCoordinate(long slot, int txIndex, int certIndex) {
    }

    public record ResolvedStakeCredential(String type, byte[] hash) {
        public ResolvedStakeCredential {
            Objects.requireNonNull(type, "type");
            hash = hash == null ? null : hash.clone();
        }

        @Override
        public byte[] hash() {
            return hash == null ? null : hash.clone();
        }
    }

    public record AddressParts(byte[] raw, byte[] addressKey, String displayAddress,
                               byte[] paymentCredential, String stakeCredentialType,
                               byte[] stakeCredential) {
        public AddressParts {
            raw = raw.clone();
            addressKey = addressKey.clone();
            paymentCredential = paymentCredential == null ? null : paymentCredential.clone();
            stakeCredential = stakeCredential == null ? null : stakeCredential.clone();
        }

        @Override
        public byte[] raw() {
            return raw.clone();
        }

        @Override
        public byte[] addressKey() {
            return addressKey.clone();
        }

        @Override
        public byte[] paymentCredential() {
            return paymentCredential == null ? null : paymentCredential.clone();
        }

        @Override
        public byte[] stakeCredential() {
            return stakeCredential == null ? null : stakeCredential.clone();
        }
    }

    /** Decompose a Shelley or Byron address into the archive index representation. */
    public static AddressParts parse(String display, int era, AddressKeyCodec addressKeys,
                                     PointerLookup pointers) {
        Objects.requireNonNull(display, "display");
        Objects.requireNonNull(addressKeys, "addressKeys");
        Objects.requireNonNull(pointers, "pointers");
        try {
            byte[] raw;
            try {
                raw = AddressUtil.addressToBytes(display);
            } catch (Exception notTextEncoded) {
                raw = HexUtil.decodeHexString(display);
            }
            if (raw.length == 0) {
                throw new IllegalArgumentException("empty address");
            }

            Address parsed;
            try {
                parsed = new Address(raw);
            } catch (Exception notShelleyAddress) {
                ByronAddress byron = new ByronAddress(raw);
                return new AddressParts(byron.getBytes(), addressKeys.key(byron.getBytes()), display,
                        null, null, null);
            }

            byte[] stake = AddressKeyUtil.stakeCred28(display);
            String stakeType = parsed.getDelegationCredential()
                    .map(value -> value.getType().name().toLowerCase(Locale.ROOT))
                    .orElse(null);
            if (parsed.getAddressType().name().equalsIgnoreCase("ptr")) {
                if (era >= Era.Conway.getValue()) {
                    stake = null;
                } else {
                    var pointer = new PointerAddress(raw).getPointer();
                    var credential = pointers.resolve(new PointerCoordinate(pointer.getSlot(),
                            pointer.getTxIndex(), pointer.getCertIndex()));
                    stake = credential == null ? null : credential.hash();
                    stakeType = credential == null ? null : credential.type();
                }
            }

            String normalized;
            try {
                normalized = AddressUtil.bytesToAddress(raw);
            } catch (Exception ignored) {
                normalized = display;
            }
            return new AddressParts(raw, addressKeys.key(raw), normalized,
                    AddressKeyUtil.paymentCred28(display), stakeType, stake);
        } catch (Exception e) {
            throw new ArchiveStoreException("cannot decode canonical output address", e);
        }
    }
}
