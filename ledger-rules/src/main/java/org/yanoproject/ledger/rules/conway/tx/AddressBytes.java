package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.address.util.AddressUtil;
import com.bloxbean.cardano.client.common.cbor.CborSpan;

import java.util.List;
import java.util.Map;
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
    private static final int BYRON_HEADER = 0x82;
    private static final int BYRON_ROOT_LENGTH = 28;
    private static final long ATTR_DERIVATION_PATH = 1;
    private static final long ATTR_PROTOCOL_MAGIC = 2;
    private static final long BYRON_TYPE_VERKEY = 0;
    private static final long BYRON_TYPE_REDEEM = 2;
    private static final long MAX_WORD8 = 0xFF;
    private static final long MAX_WORD16 = 0xFFFF;
    private static final long MAX_WORD32 = 0xFFFF_FFFFL;

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
            byron(address);
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
            return byron(address).protocolMagic() ? NETWORK_TESTNET : NETWORK_MAINNET;
        }
        // headerNetworkId (Address.hs:556-559): bit 0 alone; the decoder leaves bit 1 unchecked
        return address[0] & 0x01;
    }

    /**
     * Haskell {@code bootstrapAddressAttrsSize} (Address.hs:334-340): the length of the HD derivation-path
     * payload (attribute 1) plus the lengths of the unknown attributes' values.
     */
    public static int bootstrapAttrsSize(byte[] address) {
        return byron(address).attrsSize();
    }

    /**
     * Haskell {@code bootstrapKeyHash} (Address.hs): a bootstrap address's root, the 28-byte hash of its spending
     * data, which the address's bootstrap witness must reproduce.
     */
    public static byte[] bootstrapRoot(byte[] address) {
        return byron(address).root();
    }

    /** What the ledger reads from a Byron address: its root, whether it names a network, its attributes' size. */
    private record Byron(byte[] root, boolean protocolMagic, int attrsSize) {
    }

    /**
     * A Byron address, decoded as the Byron ledger decodes it ({@code DecCBOR Address}) and as CCL's
     * {@code ByronAddress} does, with the same outcome: the envelope {@code [#6.24(bytes), crc32]} and its CRC, a
     * payload {@code [root, attributes, type]} with a 28-byte root, attributes with definite lengths and strictly
     * increasing keys up to 255, a canonical derivation path (1) and protocol magic (2, a {@code Word32}) with nothing
     * after them, and a canonical address type of 0 or 2; every array, map and byte string definite.
     *
     * <p>A workaround: CCL's {@code ByronAddress(byte[])} encodes the address in Base58 when it is built, in time
     * quadratic in its length, so an output with 16 KB of attributes took most of a second to read
     * (cardano-client-lib#757). Once a CCL release builds it lazily, read it with {@code ByronAddress} again;
     * {@code AddressBytesTest} fails then.
     */
    private static Byron byron(byte[] address) {
        if (address.length == 0 || (address[0] & 0xff) != BYRON_HEADER) {
            throw invalid("it does not start with 0x82");
        }
        List<CborSpan> parts = definiteArray(StrictCbor.span(address), 2);
        if (parts.get(0).tag() != 24) {
            throw invalid("the payload is not in tag 24");
        }
        byte[] payload = StrictCbor.definiteBytes(parts.get(0).untag());
        CRC32 crc = new CRC32();
        crc.update(payload);
        if (crc.getValue() != uint(parts.get(1), MAX_WORD32)) {
            throw invalid("CRC32 checksum mismatch");
        }
        List<CborSpan> fields = definiteArray(StrictCbor.span(payload), 3);
        byte[] root = StrictCbor.definiteBytes(fields.get(0));
        if (root.length != BYRON_ROOT_LENGTH) {
            throw invalid("the root is " + root.length + " bytes");
        }
        CborSpan attributes = fields.get(1);
        List<Map.Entry<CborSpan, CborSpan>> entries = StrictCbor.map(attributes);
        if (attributes.isIndefinite()) {
            throw invalid("indefinite-length attributes");
        }
        boolean protocolMagic = false;
        int size = 0;
        long previous = -1;
        for (Map.Entry<CborSpan, CborSpan> entry : entries) {
            long key = uint(entry.getKey(), MAX_WORD8);
            if (key <= previous) {
                throw invalid("attribute keys are not strictly increasing");
            }
            previous = key;
            byte[] value = StrictCbor.definiteBytes(entry.getValue());
            if (key == ATTR_DERIVATION_PATH) {
                CborSpan path = StrictCbor.span(value);
                int length = StrictCbor.definiteLength(path);
                canonical(path, length);
                size += length;
            } else if (key == ATTR_PROTOCOL_MAGIC) {
                CborSpan magic = StrictCbor.span(value);
                canonical(magic, uint(magic, MAX_WORD32));
                protocolMagic = true;
            } else {
                size += value.length;
            }
        }
        long type = uint(fields.get(2), MAX_WORD8);
        canonical(fields.get(2), type);
        if (type != BYRON_TYPE_VERKEY && type != BYRON_TYPE_REDEEM) {
            throw invalid("unknown address type " + type);
        }
        return new Byron(root, protocolMagic, size);
    }

    private static List<CborSpan> definiteArray(CborSpan item, int size) {
        List<CborSpan> items = StrictCbor.array(item);
        if (item.isIndefinite() || items.size() != size) {
            throw invalid("expected a definite array of " + size + " items at " + item.offset());
        }
        return items;
    }

    private static long uint(CborSpan item, long max) {
        long value = StrictCbor.word64(item);
        if (Long.compareUnsigned(value, max) > 0) {
            throw invalid("unsigned integer out of range at " + item.offset());
        }
        return value;
    }

    /** An integer or a byte string's head is as short as its argument, {@code 0 <= argument <= 2^32 - 1}, allows. */
    private static void canonical(CborSpan item, long argument) {
        int shortest = argument < 24 ? 1 : argument <= MAX_WORD8 ? 2 : argument <= MAX_WORD16 ? 3 : 5;
        if (item.headerLength() != shortest) {
            throw invalid("non-canonical encoding at " + item.offset());
        }
    }

    private static TxDecodingException invalid(String reason) {
        return new TxDecodingException("Invalid Byron address: " + reason);
    }
}
