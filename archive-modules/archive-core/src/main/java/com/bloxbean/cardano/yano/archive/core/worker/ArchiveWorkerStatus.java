package com.bloxbean.cardano.yano.archive.core.worker;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;

import java.time.Instant;

public record ArchiveWorkerStatus(ArchiveDatasetId dataset, ArchiveTrack track,
                                  State state, long coordinate, long lag,
                                  String detail, Instant observedAt) {
    public enum State { DISABLED, IDLE, RUNNING, PAUSED_CORE_LAG, DEGRADED, FAILED }
}
