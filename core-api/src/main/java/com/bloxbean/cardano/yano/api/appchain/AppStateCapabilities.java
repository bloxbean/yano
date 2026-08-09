package com.bloxbean.cardano.yano.api.appchain;

import com.bloxbean.cardano.yano.api.appchain.snapshot.AuthenticatedSnapshotPlanCollector;
import com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotSeriesHandle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable, namespace-aware capability registry exposed during one transition. */
public final class AppStateCapabilities {
    private static final AppStateCapabilities EMPTY =
            new AppStateCapabilities("", Map.of(), AuthenticatedSnapshotPlanCollector.disabled());

    private final String namespace;
    private final Map<String, SnapshotSeriesHandle> snapshotSeries;
    private final AuthenticatedSnapshotPlanCollector collector;

    private AppStateCapabilities(String namespace,
                                 Map<String, SnapshotSeriesHandle> snapshotSeries,
                                 AuthenticatedSnapshotPlanCollector collector) {
        this.namespace = namespace;
        this.snapshotSeries = Map.copyOf(snapshotSeries);
        this.collector = collector;
    }

    public static AppStateCapabilities empty() {
        return EMPTY;
    }

    /** Runtime factory. Application code cannot manufacture enabled handles. */
    public static AppStateCapabilities enabled(
            Map<String, SnapshotSeriesHandle> series,
            AuthenticatedSnapshotPlanCollector collector) {
        Objects.requireNonNull(series, "series");
        Objects.requireNonNull(collector, "collector");
        return series.isEmpty() ? EMPTY : new AppStateCapabilities("", series, collector);
    }

    public boolean authenticatedSnapshotsEnabled() {
        return !snapshotSeries.isEmpty();
    }

    public Optional<SnapshotSeriesHandle> snapshotSeries(String localSeriesId) {
        String scoped = scopeId(localSeriesId);
        SnapshotSeriesHandle handle = snapshotSeries.get(scoped);
        return Optional.ofNullable(handle);
    }

    /** Return a view which can resolve only series owned by the component. */
    public AppStateCapabilities scope(String componentId) {
        String component = requireId(componentId, "componentId");
        String prefix = namespace.isEmpty() ? component : namespace + "." + component;
        Map<String, SnapshotSeriesHandle> scoped = new LinkedHashMap<>();
        String boundary = prefix + ".";
        snapshotSeries.forEach((id, handle) -> {
            if (id.startsWith(boundary)) {
                scoped.put(id, handle);
            }
        });
        return scoped.isEmpty() ? EMPTY : new AppStateCapabilities(prefix, scoped, collector);
    }

    public AuthenticatedSnapshotPlanCollector snapshotPlans() {
        return collector;
    }

    private String scopeId(String localSeriesId) {
        String local = requireId(localSeriesId, "seriesId");
        return namespace.isEmpty() ? local : namespace + "." + local;
    }

    private static String requireId(String value, String name) {
        String id = Objects.requireNonNull(value, name);
        if (!id.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException(name + " must be a canonical identifier");
        }
        return id;
    }
}
