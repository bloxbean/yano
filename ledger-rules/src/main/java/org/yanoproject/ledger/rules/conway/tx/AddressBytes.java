package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.address.ByronAddress;
import com.bloxbean.cardano.client.address.util.AddressUtil;
import com.bloxbean.cardano.client.crypto.exception.AddressFormatException;

import java.util.Optional;

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

    // The decoder's check of an address, AddressBytes.validate, moved to CCL's ledger-cbor with the Haskell-strict
    // policy (ADR 0005 D3). What the rules read from an address stays here.

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
            return byron(address).getProtocolMagic().isPresent() ? NETWORK_TESTNET : NETWORK_MAINNET;
        }
        // headerNetworkId (Address.hs:556-559): bit 0 alone; the decoder leaves bit 1 unchecked
        return address[0] & 0x01;
    }

    /**
     * Haskell {@code bootstrapAddressAttrsSize} (Address.hs:334-340): the length of the HD derivation-path
     * payload (attribute 1) plus the lengths of the unknown attributes' values.
     */
    public static int bootstrapAttrsSize(byte[] address) {
        ByronAddress byron = byron(address);
        int size = byron.getDerivationPath().map(path -> path.length).orElse(0);
        for (byte[] value : byron.getUnknownAttributes().values()) {
            size += value.length;
        }
        return size;
    }

    /**
     * Haskell {@code bootstrapKeyHash} (Address.hs): a bootstrap address's root, the 28-byte hash of its spending
     * data, which the address's bootstrap witness must reproduce.
     */
    public static byte[] bootstrapRoot(byte[] address) {
        return byron(address).getRoot();
    }

    /**
     * A Byron address, decoded by CCL's {@link ByronAddress} as the Byron ledger decodes it ({@code DecCBOR Address}):
     * the envelope and its CRC, a 28-byte root, attributes with definite lengths and strictly increasing keys, a
     * canonical derivation path and protocol magic with nothing after them, and an address type of 0 or 2.
     */
    private static ByronAddress byron(byte[] address) {
        try {
            return new ByronAddress(address);
        } catch (AddressFormatException e) {
            throw new TxDecodingException(e.getMessage(), e);
        }
    }
}
