package com.bloxbean.cardano.yano.archive.core.worker;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ArchiveWorkerMetrics {
    private final Map<Key, ArchiveWorkerStatus> statuses = new ConcurrentHashMap<>();

    public void update(ArchiveDatasetId dataset, ArchiveTrack track, ArchiveWorkerStatus.State state,
                       long coordinate, long lag, String detail) {
        statuses.put(new Key(dataset, track), new ArchiveWorkerStatus(dataset, track, state,
                coordinate, Math.max(0, lag), detail == null ? "" : detail, Instant.now()));
    }

    public Map<ArchiveTrack, ArchiveWorkerStatus> dataset(ArchiveDatasetId dataset) {
        EnumMap<ArchiveTrack, ArchiveWorkerStatus> result = new EnumMap<>(ArchiveTrack.class);
        statuses.forEach((key, status) -> { if (key.dataset == dataset) result.put(key.track, status); });
        return Map.copyOf(result);
    }

    private record Key(ArchiveDatasetId dataset, ArchiveTrack track) { }
}
