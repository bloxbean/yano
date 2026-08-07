package com.bloxbean.cardano.yano.runtime.utxo;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.util.AddressUtil;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class UtxoKeyUtil {
    private UtxoKeyUtil() {}

    static byte[] outpointKey(String txHashHex, int index) {
        byte[] hash = HexUtil.decodeHexString(txHashHex);
        ByteBuffer bb = ByteBuffer.allocate(hash.length + 2).order(ByteOrder.BIG_ENDIAN);
        bb.put(hash);
        bb.putShort((short) (index & 0xffff));
        return bb.array();
    }

    static byte[] addrHash28(String bech32OrHex) {
        // Canonical implementation shared with the account-history address-tx
        // index — both indexes must agree on what an address hashes to.
        return com.bloxbean.cardano.yano.api.util.AddressKeyUtil.addrHash28(bech32OrHex);
    }

    static byte[] addressIndexKey(byte[] addrKey28, long slot, String txHashHex, int index) {
        byte[] hash = HexUtil.decodeHexString(txHashHex);
        ByteBuffer bb = ByteBuffer.allocate(28 + 8 + hash.length + 2).order(ByteOrder.BIG_ENDIAN);
        bb.put(addrKey28);
        bb.putLong(slot);
        bb.put(hash);
        bb.putShort((short) (index & 0xffff));
        return bb.array();
    }

    static byte[] paymentCred28(String bech32OrHex) {
        // Canonical implementation shared with the account-history address-tx index.
        return com.bloxbean.cardano.yano.api.util.AddressKeyUtil.paymentCred28(bech32OrHex);
    }

    static boolean prefixMatches(byte[] key, byte[] prefix, int len) {
        if (key.length < len) return false;
        for (int i = 0; i < len; i++) {
            if (key[i] != prefix[i]) return false;
        }
        return true;
    }

    /** Extract tx hash hex from an outpoint key (32 bytes hash + 2 bytes index). */
    static String txHashFromOutpointKey(byte[] outpointKey) {
        byte[] hash = new byte[32];
        System.arraycopy(outpointKey, 0, hash, 0, 32);
        return HexUtil.encodeHexString(hash);
    }

    /** Extract output index from an outpoint key (last 2 bytes). */
    static int outputIndexFromOutpointKey(byte[] outpointKey) {
        return ByteBuffer.wrap(outpointKey, 32, 2).order(ByteOrder.BIG_ENDIAN).getShort() & 0xffff;
    }

    static byte[] hex28(String hex) {
        if (hex == null) return null;
        byte[] bytes;
        try {
            bytes = HexUtil.decodeHexString(hex);
        } catch (Exception e) {
            return null;
        }
        if (bytes.length == 28) return bytes;
        return null;
    }
}
