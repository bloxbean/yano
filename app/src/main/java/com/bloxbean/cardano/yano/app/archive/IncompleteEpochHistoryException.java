package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;

import java.util.List;
import java.util.Map;

/** Actionable fail-closed response when an epoch query would cross incomplete coverage. */
public final class IncompleteEpochHistoryException extends IllegalStateException {
    private final ArchiveDatasetId dataset;
    private final String reason;
    private final List<Map<String, Object>> missingRanges;
    private final Map<String, Object> coverageStatus;

    public IncompleteEpochHistoryException(ArchiveDatasetId dataset, String reason,
                                           List<Map<String, Object>> missingRanges) {
        this(dataset, reason, missingRanges, Map.of());
    }

    public IncompleteEpochHistoryException(ArchiveDatasetId dataset, String reason,
                                           List<Map<String, Object>> missingRanges,
                                           Map<String, Object> coverageStatus) {
        super(dataset.logicalName() + " history is incomplete: " + reason
                + "; inspect /history/coverage");
        this.dataset = dataset;
        this.reason = reason;
        this.missingRanges = List.copyOf(missingRanges);
        this.coverageStatus = Map.copyOf(coverageStatus);
    }

    public Map<String, Object> response() {
        var response = new java.util.LinkedHashMap<String, Object>();
        response.put("error", "INCOMPLETE_EPOCH_HISTORY");
        response.put("dataset", dataset.logicalName());
        response.put("reason", reason);
        response.put("missingRanges", missingRanges);
        response.put("coverageStatus", coverageStatus);
        response.put("coverage", "/history/coverage?dataset=" + dataset.logicalName());
        return Map.copyOf(response);
    }
}
