package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationHashes;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationProfileV1;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/** Coordinator-owned, bounded FIFO re-diffusion with content dedup and a ten-second retry interval. */
final class ObservationDiffusionLimiter {
    private record Item(String topic, byte[] body) { }
    private final Map<String, Item> pending = new LinkedHashMap<>();
    private final Map<String, Long> recent = new LinkedHashMap<>();
    private final int countLimit;
    private final long byteLimit;
    private final LongSupplier clock;
    private final BiConsumer<String, byte[]> diffusion;
    private long queuedBytes;
    private long window;
    private int sent;
    private long sentBytes;

    ObservationDiffusionLimiter(ObservationProfileV1 profile, LongSupplier clock,
                                BiConsumer<String, byte[]> diffusion) {
        this(profile.maxDiffusionsPerSecond(), Math.min(16L * 1024 * 1024,
                Math.max(profile.maxCertificateBytes(), profile.maxResultBytesPerBlock())), clock, diffusion);
    }

    ObservationDiffusionLimiter(int countLimit, long byteLimit, LongSupplier clock,
                                BiConsumer<String, byte[]> diffusion) {
        this.countLimit = countLimit;
        this.byteLimit = byteLimit;
        this.clock = clock;
        this.diffusion = diffusion;
        this.window = clock.getAsLong();
    }

    void offer(String topic, byte[] body) {
        long now = clock.getAsLong();
        String key = topic + HexUtil.encodeHexString(ObservationHashes.digest(body));
        Long previous = recent.get(key);
        if (previous != null && now - previous < 10_000_000_000L) return;
        if (pending.containsKey(key) || pending.size() >= 4096 || queuedBytes + body.length > byteLimit) return;
        pending.put(key, new Item(topic, body.clone()));
        queuedBytes += body.length;
        drain();
    }

    void drain() {
        long now = clock.getAsLong();
        if (now - window >= 1_000_000_000L) {
            window = now;
            sent = 0;
            sentBytes = 0;
        }
        var iterator = pending.entrySet().iterator();
        while (iterator.hasNext() && sent < countLimit) {
            var entry = iterator.next();
            Item item = entry.getValue();
            if (sentBytes + item.body().length > byteLimit) break;
            sent++;
            sentBytes += item.body().length;
            queuedBytes -= item.body().length;
            iterator.remove();
            recent.remove(entry.getKey());
            recent.put(entry.getKey(), now);
            if (recent.size() > 4096) recent.remove(recent.keySet().iterator().next());
            diffusion.accept(item.topic(), item.body());
        }
    }
}
