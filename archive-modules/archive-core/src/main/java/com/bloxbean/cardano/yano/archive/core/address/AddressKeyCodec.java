package com.bloxbean.cardano.yano.archive.core.address;

import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** One canonical raw-address path with fail-closed digest collision detection. */
public final class AddressKeyCodec {
    private final Map<Key, byte[]> observed = new ConcurrentHashMap<>();

    public byte[] key(byte[] canonicalAddressBytes) {
        if (canonicalAddressBytes == null || canonicalAddressBytes.length == 0) {
            throw new IllegalArgumentException("canonical address bytes are required");
        }
        try {
            byte[] key = MessageDigest.getInstance("SHA-256").digest(canonicalAddressBytes);
            byte[] previous = observed.putIfAbsent(new Key(key), canonicalAddressBytes.clone());
            if (previous != null && !Arrays.equals(previous, canonicalAddressBytes)) {
                throw new ArchiveStoreException("address-key collision for " + HexFormat.of().formatHex(key));
            }
            return key;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record Key(byte[] bytes) {
        private Key { bytes = bytes.clone(); }
        @Override public boolean equals(Object other) { return other instanceof Key that && Arrays.equals(bytes, that.bytes); }
        @Override public int hashCode() { return Arrays.hashCode(bytes); }
    }
}
