package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.address.util.AddressUtil;

import java.util.Optional;
import java.util.zip.CRC32;

/**
 * Facts the UTXO rule reads from an address in its binary form ({@code Cardano.Ledger.Address}).
 *
 * <p>Shelley addresses carry a header byte: the high nibble is the address type (0–7 have a payment
 * credential; odd types 1, 3, 5 and 7 have a script payment credential), the low nibble the network id
 * (0 testnet, 1 mainnet). Byron (bootstrap) addresses are CBOR {@code [#6.24(bytes payload), crc32]} with
 * {@code payload = [root, attributes, type]}; the header byte of such an encoding is {@code 0x82}.</p>
 */
public final class AddressBytes {

    /** Haskell's bound on a bootstrap address's attributes (Shelley/Rules/Utxo.hs:544-561). */
    public static final int MAX_BOOTSTRAP_ATTRS_SIZE = 64;

    private static final int NETWORK_TESTNET = 0;
    private static final int NETWORK_MAINNET = 1;

    private AddressBytes() {
    }

    /**
     * @return the bytes of a resolved UTxO entry's address, as CCL holds it (bech32, base58 or hex)
     * @throws IllegalStateException when the view holds an address CCL cannot decode (a broken view, so the
     *                               engine fails closed)
     */
    public static byte[] fromCcl(String address) {
        try {
            return AddressUtil.addressToBytes(address);
        } catch (Exception e) {
            throw new IllegalStateException("cannot decode the UTxO address " + address + ": " + e.getMessage(), e);
        }
    }

    /**
     * Haskell's strict address decoder for outputs from version 9 ({@code fromCborBothAddr} →
     * {@code fromCborRigorousBothAddr False} → {@code decodeAddrStateLenientT False False},
     * cardano-ledger-core Address.hs:459-476, 643-690):
     * <ul>
     *   <li>{@code 0x82} is a Byron address, decoded in full (CRC included) by the Byron decoder;</li>
     *   <li>otherwise no unused header bit may be set ({@code header .&. 0b10001100 == 0}, so types 8–15 and
     *       network ids other than 0/1 fail), then a 28-byte payment hash, then by type a 28-byte stake hash
     *       (base), a pointer of variable-length {@code Word32}/{@code Word16}/{@code Word16} that must fit
     *       (types 4, 5; {@code decodePtr}, :769-778, 832-874), or nothing (enterprise);</li>
     *   <li>no byte may be left over.</li>
     * </ul>
     *
     * @throws TxDecodingException when Haskell would not decode the address
     */
    public static void validate(byte[] address) {
        if (address.length == 0) {
            throw new TxDecodingException("empty address");
        }
        int header = address[0] & 0xff;
        if (header == 0x82) {
            byronAttributes(address);
            return;
        }
        if ((header & 0b1000_1100) != 0) {
            throw new TxDecodingException("Invalid address header, unused bits are set: 0x" + Integer.toHexString(header));
        }
        int offset = 1 + 28;
        if (address.length < offset) {
            throw new TxDecodingException("address too short for its payment credential");
        }
        boolean base = (header & 0b0100_0000) == 0;
        boolean enterprise = (header & 0b0010_0000) != 0;
        if (base) {
            offset += 28;
            if (address.length < offset) {
                throw new TxDecodingException("address too short for its stake credential");
            }
        } else if (!enterprise) {
            int[] cursor = {offset};
            varLength(address, cursor, 5, 0b1111_0000, "SlotNo");
            varLength(address, cursor, 3, 0b1111_1100, "TxIx");
            varLength(address, cursor, 3, 0b1111_1100, "CertIx");
            offset = cursor[0];
        }
        if (offset != address.length) {
            throw new TxDecodingException("Left over bytes in address: " + (address.length - offset));
        }
    }

    /**
     * {@code decodeVariableLengthWord16/32}: 7 bits per byte, most significant first, bit 7 set on every byte but
     * the last; at most {@code maxBytes} bytes, and when all of them are used the first byte must satisfy
     * {@code first & mask == 0x80} so the value fits.
     */
    private static void varLength(byte[] address, int[] cursor, int maxBytes, int mask, String name) {
        int start = cursor[0];
        for (int n = 1; ; n++) {
            if (cursor[0] >= address.length) {
                throw new TxDecodingException("Decoding " + name + ": Not enough bytes for decoding");
            }
            int b = address[cursor[0]++] & 0xff;
            if ((b & 0x80) == 0) {
                if (n == maxBytes && ((address[start] & 0xff) & mask) != 0x80) {
                    throw new TxDecodingException("Decoding " + name + ": value does not fit");
                }
                return;
            }
            if (n == maxBytes) {
                throw new TxDecodingException("Decoding " + name + ": too many bytes.");
            }
        }
    }

    /** @return true for a Byron (bootstrap) address */
    public static boolean isBootstrap(byte[] address) {
        return address.length > 0 && (address[0] & 0xf0) == 0x80;
    }

    /**
     * @return true when the payment credential is a key hash, or the address is a bootstrap address
     *         (Alonzo/Rules/Utxo.hs:252-266, {@code isKeyHashAddr}); false for script-locked addresses
     */
    public static boolean isVKeyLocked(byte[] address) {
        if (isBootstrap(address)) {
            return true;
        }
        int type = (address[0] & 0xf0) >>> 4;
        return type <= 7 && (type & 1) == 0;
    }

    /** @return the payment script hash when the payment credential is a script */
    public static Optional<byte[]> paymentScriptHash(byte[] address) {
        if (isBootstrap(address) || address.length < 29) {
            return Optional.empty();
        }
        int type = (address[0] & 0xf0) >>> 4;
        if (type > 7 || (type & 1) == 0) {
            return Optional.empty();
        }
        byte[] hash = new byte[28];
        System.arraycopy(address, 1, hash, 0, 28);
        return Optional.of(hash);
    }

    /**
     * Haskell {@code getNetwork} (Address.hs): the header nibble of a Shelley address; for a bootstrap address
     * {@code Mainnet} unless its attributes carry a network magic ({@code NetworkTestnet}).
     *
     * @return 0 testnet, 1 mainnet
     */
    public static int network(byte[] address) {
        if (isBootstrap(address)) {
            return byronAttributes(address).networkMagicPresent() ? NETWORK_TESTNET : NETWORK_MAINNET;
        }
        // headerNetworkId (Address.hs:556-559): bit 0 alone; the decoder leaves bit 1 unchecked
        return address[0] & 0x01;
    }

    /**
     * Haskell {@code bootstrapAddressAttrsSize} (Address.hs:334-340): the length of the HD derivation-path
     * payload (attribute 1) plus the lengths of the unknown attributes' values.
     */
    public static int bootstrapAttrsSize(byte[] address) {
        return byronAttributes(address).attrsSize();
    }

    /**
     * Haskell {@code bootstrapKeyHash} (Address.hs): a bootstrap address's root, the 28-byte hash of its spending
     * data, which the address's bootstrap witness must reproduce.
     */
    public static byte[] bootstrapRoot(byte[] address) {
        CborReader outer = new CborReader(address);
        outer.readArrayHeader();
        outer.readTag();
        CborReader payload = new CborReader(outer.readBytes());
        payload.readArrayHeader();
        byte[] root = payload.readBytes();
        if (root.length != 28) {
            throw new TxDecodingException("a bootstrap address root is 28 bytes");
        }
        return root;
    }

    /** The parts of a Byron address's attributes that the ledger reads. */
    record ByronAttributes(boolean networkMagicPresent, int attrsSize) {
    }

    /**
     * Decodes a Byron address's attributes ({@code decCBORAttributes}, Byron Attributes.hs:213-235,
     * AddrAttributes.hs:121-143): a map from a byte key to a byte string; key 1 holds the CBOR-encoded
     * derivation path, key 2 the network magic, every other key is unparsed and counted by size.
     */
    static ByronAttributes byronAttributes(byte[] address) {
        CborReader outer = new CborReader(address);
        if (outer.readArrayHeader() != 2) {
            throw new TxDecodingException("a bootstrap address is a two-element array");
        }
        if (outer.readTag() != 24) {
            throw new TxDecodingException("a bootstrap address wraps its payload in tag 24");
        }
        byte[] payload = outer.readBytes();
        long crc = outer.readUnsignedLong();
        if (!outer.atEnd()) {
            throw new TxDecodingException("trailing bytes after a bootstrap address");
        }
        CRC32 expected = new CRC32();
        expected.update(payload);
        if (expected.getValue() != crc) {
            throw new TxDecodingException("bootstrap address CRC mismatch");
        }
        CborReader reader = new CborReader(payload);
        if (reader.readArrayHeader() != 3) {
            throw new TxDecodingException("a bootstrap address payload is [root, attributes, type]");
        }
        if (reader.readBytes().length != 28) {
            throw new TxDecodingException("a bootstrap address root is 28 bytes");
        }
        long entries = reader.readMapHeader();
        boolean magic = false;
        int size = 0;
        for (long i = 0; reader.hasNext(entries, i); i++) {
            long key = reader.readUnsignedLong();
            byte[] value = reader.readBytes();
            if (key == 1) {
                size += new CborReader(value).readBytes().length;
            } else if (key == 2) {
                magic = true;
            } else {
                size += value.length;
            }
        }
        long type = reader.readUnsignedLong();
        if (type > 2 || !reader.atEnd()) {
            throw new TxDecodingException("bad bootstrap address type or trailing bytes");
        }
        return new ByronAttributes(magic, size);
    }
}
