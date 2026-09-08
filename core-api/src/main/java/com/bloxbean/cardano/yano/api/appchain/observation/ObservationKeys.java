package com.bloxbean.cardano.yano.api.appchain.observation;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Frozen generic-observation state namespace and canonical record keys. */
public final class ObservationKeys {
    private static final byte[] PREFIX = "~yano/obs/".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PROFILE = "~yano/obs/profile/v1".getBytes(StandardCharsets.US_ASCII);

    private ObservationKeys() {
    }

    public static byte[] profile() {
        return PROFILE.clone();
    }

    /** Profile logicalTimeVersion=2 monotone verified-slot summary. */
    public static byte[] highWaterSlot() {
        return "~yano/obs/high-water-slot/v2".getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] schedulerCounts() {
        return "~yano/obs/scheduler-counts/v2".getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] subscription(byte[] subscriptionId) {
        return suffix("subscription/", subscriptionId);
    }

    public static byte[] round(byte[] subscriptionId, long roundNumber) {
        byte[] id = ObservationCbor.fixed(subscriptionId, 32, "subscription id");
        ByteBuffer value = ByteBuffer.allocate(PREFIX.length + 6 + 32 + 1 + Long.BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        value.put(PREFIX).put("round/".getBytes(StandardCharsets.US_ASCII)).put(id)
                .put((byte) '/').putLong(roundNumber);
        return value.array();
    }

    public static byte[] result(byte[] resultId) {
        return suffix("result/", resultId);
    }

    public static boolean isReserved(byte[] key) {
        if (key == null || key.length < PREFIX.length) {
            return false;
        }
        for (int index = 0; index < PREFIX.length; index++) {
            if (key[index] != PREFIX[index]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] suffix(String kind, byte[] id) {
        byte[] fixed = ObservationCbor.fixed(id, 32, kind + "id");
        byte[] segment = kind.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer result = ByteBuffer.allocate(PREFIX.length + segment.length + fixed.length);
        result.put(PREFIX).put(segment).put(fixed);
        return result.array();
    }
}
