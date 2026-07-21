package com.bloxbean.cardano.yano.api.plugin.operations;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Canonical, bounded health reports returned by one source sample. */
public record PluginHealthSnapshot(List<PluginHealthReport> reports) {
    public PluginHealthSnapshot {
        reports = reports == null ? List.of() : reports.stream()
                .map(report -> Objects.requireNonNull(
                        report, "reports must not contain null"))
                .sorted(Comparator.comparing(PluginHealthReport::checkId))
                .toList();
        if (reports.size() > PluginHealthCheckDescriptor.MAX_CHECKS_PER_BUNDLE) {
            throw new IllegalArgumentException("reports must contain at most 16 entries");
        }
        Set<String> ids = new HashSet<>();
        for (PluginHealthReport report : reports) {
            if (!ids.add(report.checkId())) {
                throw new IllegalArgumentException(
                        "reports must not contain duplicate check ids");
            }
        }
    }
}
