package com.bloxbean.cardano.yano.runtime.genesis;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.crypto.Base58;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import lombok.extern.slf4j.Slf4j;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Optional;
import java.util.zip.CRC32;

/**
 * Converts AVVM (Ada Voucher Vending Machine) addresses from the Byron genesis
 * {@code avvmDistr} section to standard Byron base58 addresses.
 * <p>
 * Ported from yaci-store's AvvmAddressConverter.
 * CDDL reference: https://raw.githubusercontent.com/cardano-foundation/CIPs/master/CIP-0019/CIP-0019-byron-addresses.cddl
 */
@Slf4j
public class AvvmAddressConverter {

    /**
     * Convert a base64url-encoded AVVM public key to a Byron base58 address.
     *
     * @param avvmAddress base64url-encoded Ed25519 public key from avvmDistr
     * @return Byron base58 address, or empty if conversion fails
     */
    public static Optional<String> convertAvvmToByronAddress(String avvmAddress) {
        if (avvmAddress == null) return Optional.empty();
        try {
            // Normalize base64url to standard base64
            String base64 = avvmAddress.replace("-", "+").replace("_", "/");
            byte[] ed25519PubKey = Base64.getDecoder().decode(base64);

            // Spending data: [2, pubkey] (2 = RedeemASD tag)
            Array spendingData = new Array();
            spendingData.add(new UnsignedInteger(2));
            spendingData.add(new ByteString(ed25519PubKey));

            // Address root: [addrType=2, spendingData, attributes={}]
            Array addrRoot = new Array();
            addrRoot.add(new UnsignedInteger(2));
            addrRoot.add(spendingData);
            addrRoot.add(new Map());

            byte[] addrRootBytes = CborSerializationUtil.serialize(addrRoot);

            // Double hash: blake2b_224(sha3_256(addrRootCbor))
            byte[] sha3Hash = sha3_256(addrRootBytes);
            byte[] addrRootHash = Blake2bUtil.blake2bHash224(sha3Hash);

            // Byron payload: [addrRootHash, attributes={}, type=2]
            Array payload = new Array();
            payload.add(new ByteString(addrRootHash));
            payload.add(new Map());
            payload.add(new UnsignedInteger(2)); // Redemption type

            // Final address: [CBOR-tag-24(payload), CRC32]
            byte[] payloadCbor = CborSerializationUtil.serialize(payload);
            CRC32 crc32 = new CRC32();
            crc32.update(payloadCbor);

            Array addressArr = new Array();
            ByteString payloadBytes = new ByteString(payloadCbor);
            payloadBytes.setTag(24);
            addressArr.add(payloadBytes);
            addressArr.add(new UnsignedInteger(crc32.getValue()));

            byte[] addressCbor = CborSerializationUtil.serialize(addressArr);
            return Optional.of(Base58.encode(addressCbor));
        } catch (Exception e) {
            log.error("Error converting AVVM address to Byron address: {}", avvmAddress, e);
            return Optional.empty();
        }
    }

    private static byte[] sha3_256(byte[] input) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA3-256");
        digest.update(input);
        return digest.digest();
    }
}
