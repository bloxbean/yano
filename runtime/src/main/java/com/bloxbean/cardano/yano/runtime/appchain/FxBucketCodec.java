package com.bloxbean.cardano.yano.runtime.appchain;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Wire format of an expiry bucket in the {@code app_fx_records} CF: a
 * concatenation of fixed 12-byte {@code height(8BE) + ordinal(4BE)} entries
 * (ADR app-layer/010 F3). Encoded by the kernel at apply time, decoded by the
 * ledger's read surface.
 */
final class FxBucketCodec {

    private FxBucketCodec() {
    }

    static byte[] encode(List<long[]> entries) {
        ByteBuffer buffer = ByteBuffer.allocate(entries.size() * 12);
        for (long[] entry : entries) {
            buffer.putLong(entry[0]).putInt((int) entry[1]);
        }
        return buffer.array();
    }

    static List<long[]> decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new ArrayList<>();
        }
        List<long[]> entries = new ArrayList<>(bytes.length / 12);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.remaining() >= 12) {
            entries.add(new long[]{buffer.getLong(), buffer.getInt()});
        }
        return entries;
    }
}
