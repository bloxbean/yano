package com.bloxbean.cardano.yano.runtime.ledger;

import com.bloxbean.cardano.yano.ledgerstate.EpochBoundaryProcessor;
import com.bloxbean.cardano.yano.ledgerstate.EpochBoundaryTelemetry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class EpochCalcStatusMapper {
    private EpochCalcStatusMapper() {
    }

    static Map<String, Object> map(EpochBoundaryProcessor.VerificationError error,
                                   EpochBoundaryTelemetry.BoundarySummary boundary) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", error == null ? "OK" : "ERROR");
        if (error != null) {
            status.put("epoch", error.epoch());
            status.put("expectedTreasury", error.expectedTreasury().toString());
            status.put("actualTreasury", error.actualTreasury().toString());
            status.put("treasuryDiff", error.treasuryDiff().toString());
            status.put("expectedReserves", error.expectedReserves().toString());
            status.put("actualReserves", error.actualReserves().toString());
            status.put("reservesDiff", error.reservesDiff().toString());
        }
        status.put("lastBoundary", boundary != null ? boundary(boundary) : null);
        return status;
    }

    private static Map<String, Object> boundary(EpochBoundaryTelemetry.BoundarySummary summary) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("previousEpoch", summary.previousEpoch());
        result.put("newEpoch", summary.newEpoch());
        result.put("success", summary.success());
        result.put("wallMillis", nanosToMillis(summary.wallNanos()));
        result.put("gcMetricsAvailable", gcAvailable(summary));
        result.put("start", snapshot(summary.start()));
        result.put("end", snapshot(summary.end()));
        result.put("peak", snapshot(summary.peak()));

        List<Map<String, Object>> phases = new ArrayList<>(summary.phases().size());
        for (EpochBoundaryTelemetry.PhaseSummary phase : summary.phases()) {
            phases.add(phase(phase));
        }
        result.put("phases", phases);
        return result;
    }

    private static Map<String, Object> phase(EpochBoundaryTelemetry.PhaseSummary phase) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("phase", phase.phase());
        result.put("path", phase.path());
        result.put("wallMillis", nanosToMillis(phase.wallNanos()));
        result.put("processCpuMillis", nanosToMillis(phase.cpuNanos()));
        result.put("threadCpuMillis", nanosToMillis(phase.threadCpuNanos()));
        result.put("before", snapshot(phase.before()));
        result.put("after", snapshot(phase.after()));
        if (phase.gcCountDelta() >= 0 && phase.gcTimeMillisDelta() >= 0) {
            result.put("gcCountDelta", phase.gcCountDelta());
            result.put("gcTimeMillisDelta", phase.gcTimeMillisDelta());
        }
        return result;
    }

    private static Map<String, Object> snapshot(EpochBoundaryTelemetry.ResourceSnapshot snapshot) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("heapUsedBytes", snapshot.heapUsedBytes());
        result.put("heapCommittedBytes", snapshot.heapCommittedBytes());
        result.put("heapMaxBytes", snapshot.heapMaxBytes());
        result.put("rssBytes", snapshot.rssBytes());
        result.put("rocksDb", rocksDb(snapshot.rocksDb()));
        return result;
    }

    private static Map<String, Object> rocksDb(EpochBoundaryTelemetry.RocksDbMemory memory) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("blockCacheBytes", memory.blockCacheBytes());
        result.put("pinnedBlockCacheBytes", memory.pinnedBlockCacheBytes());
        result.put("memtableBytes", memory.memtableBytes());
        result.put("tableReaderBytes", memory.tableReaderBytes());
        result.put("pendingCompactionBytes", memory.pendingCompactionBytes());
        result.put("sstFileCount", memory.sstFileCount());
        return result;
    }

    private static boolean gcAvailable(EpochBoundaryTelemetry.BoundarySummary summary) {
        return summary.start().gcCount() >= 0
                && summary.start().gcTimeMillis() >= 0
                && summary.end().gcCount() >= 0
                && summary.end().gcTimeMillis() >= 0;
    }

    private static long nanosToMillis(long nanos) {
        return nanos < 0 ? -1 : nanos / 1_000_000L;
    }
}
