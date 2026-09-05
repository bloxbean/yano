package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationKeys;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Bounded, root-fixed audit reads; never reads the node-local journal. */
final class ObservationQueries {
    static final String PREFIX = "yano/observations/";

    private ObservationQueries() { }

    static byte[] query(String path, byte[] request, AppQueryContext context) {
        byte[] key;
        switch (path) {
            case PREFIX + "subscription" -> {
                requireLength(request, 32);
                key = ObservationKeys.subscription(request);
            }
            case PREFIX + "round" -> {
                requireLength(request, 40);
                long round = ByteBuffer.wrap(request, 32, 8).getLong();
                if (round < 0) throw invalid();
                key = ObservationKeys.round(Arrays.copyOf(request, 32), round);
            }
            case PREFIX + "result" -> {
                requireLength(request, 32);
                key = ObservationKeys.result(request);
            }
            case PREFIX + "profile" -> {
                requireLength(request, 0);
                key = ObservationKeys.profile();
            }
            case PREFIX + "counts" -> {
                requireLength(request, 0);
                key = ObservationKeys.schedulerCounts();
            }
            case PREFIX + "high-water-slot" -> {
                requireLength(request, 0);
                key = ObservationKeys.highWaterSlot();
            }
            default -> throw new AppQueryException(AppQueryException.Code.UNSUPPORTED,
                    "Unsupported observation audit query");
        }
        return context.get(key).orElseGet(() -> new byte[0]);
    }

    private static void requireLength(byte[] request, int length) {
        if (request == null || request.length != length) throw invalid();
    }

    private static AppQueryException invalid() {
        return new AppQueryException(AppQueryException.Code.INVALID_REQUEST,
                "Invalid observation audit query parameters");
    }
}
