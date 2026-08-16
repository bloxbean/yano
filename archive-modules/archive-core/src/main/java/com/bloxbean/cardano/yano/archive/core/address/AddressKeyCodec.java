package com.bloxbean.cardano.yano.archive.core.address;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** One canonical raw-address path with bounded fail-closed collision detection. */
public final class AddressKeyCodec {
    private static final int COLLISION_CACHE_SIZE = 4_096;
    // Backend address dimensions provide durable key/raw validation. This
    // bounded cache also catches collisions in exact-address projections
    // without retaining every address in the JVM for a full-chain backfill.
    private final Map<Key, byte[]> observed = new LinkedHashMap<>(COLLISION_CACHE_SIZE, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Key, byte[]> eldest) {
            return size() > COLLISION_CACHE_SIZE;
        }
    };

    public synchronized byte[] key(byte[] canonicalAddressBytes) {
        if (canonicalAddressBytes == null || canonicalAddressBytes.length == 0) {
            throw new IllegalArgumentException("canonical address bytes are required");
        }
        byte[] key = Blake2bUtil.blake2bHash256(canonicalAddressBytes);
        byte[] previous = observed.putIfAbsent(new Key(key), canonicalAddressBytes.clone());
        if (previous != null && !Arrays.equals(previous, canonicalAddressBytes)) {
            throw new ArchiveStoreException("address-key collision for " + HexFormat.of().formatHex(key));
        }
        return key;
    }

    private record Key(byte[] bytes) {
        private Key { bytes = bytes.clone(); }
        @Override public boolean equals(Object other) { return other instanceof Key that && Arrays.equals(bytes, that.bytes); }
        @Override public int hashCode() { return Arrays.hashCode(bytes); }
    }
}
