package com.bloxbean.cardano.yano.appchain.eutxo.indexer.jdbc;

import com.bloxbean.cardano.yano.api.plugin.operations.PluginCounterValue;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginGaugeValue;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricDescriptor;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricSnapshot;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricType;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricsContext;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricsProvider;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricsSource;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginTimerValue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded aggregate metrics replacing the former Quarkus-owned indexer registrations. */
public final class EutxoIndexerMetricsProvider implements PluginMetricsProvider {
    private static final List<PluginMetricDescriptor> DESCRIPTORS = List.of(
            gauge("indexed-height", "eutxo.indexer.indexed.height",
                    "Total indexed height across local EUTxO chains", "blocks"),
            gauge("lag-blocks", "eutxo.indexer.lag.blocks",
                    "Total finalized index lag", "blocks"),
            gauge("queue-depth", "eutxo.indexer.queue.depth",
                    "Total queued index updates", "entries"),
            gauge("rebuild-progress", "eutxo.indexer.rebuild.progress",
                    "Mean index rebuild progress", "ratio"),
            gauge("database-bytes", "eutxo.indexer.database.bytes",
                    "Total SQLite database, WAL, and shared-memory bytes", "bytes"),
            timer("apply", "eutxo.indexer.apply", "Index apply time"),
            timer("query", "eutxo.indexer.query", "Index query time"),
            counter("failures", "eutxo.indexer.failures", "Index failures"),
            counter("rollbacks", "eutxo.indexer.rollbacks", "Index rollbacks"));

    @Override
    public String id() {
        return EutxoLifecycleIndexerProvider.ID;
    }

    @Override
    public PluginMetricsSource create(PluginMetricsContext context) {
        return new PluginMetricsSource() {
            @Override
            public List<PluginMetricDescriptor> descriptors() {
                return DESCRIPTORS;
            }

            @Override
            public PluginMetricSnapshot snapshot() {
                List<EutxoIndexerTelemetry.Sample> samples = EutxoIndexerTelemetry.samples();
                Map<String, com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricValue>
                        values = new LinkedHashMap<>();
                values.put("indexed-height", new PluginGaugeValue(samples.stream()
                        .mapToLong(sample -> sample.coordinator().health()
                                .checkpoint().source().appHeight()).sum()));
                values.put("lag-blocks", new PluginGaugeValue(samples.stream()
                        .mapToLong(sample -> sample.coordinator().health().lagBlocks()).sum()));
                values.put("queue-depth", new PluginGaugeValue(samples.stream()
                        .mapToLong(sample -> sample.coordinator().queueDepth()).sum()));
                values.put("rebuild-progress", new PluginGaugeValue(samples.isEmpty() ? 1d
                        : samples.stream().mapToDouble(
                                EutxoIndexerMetricsProvider::rebuildProgress).average().orElse(1d)));
                values.put("database-bytes", new PluginGaugeValue(samples.stream()
                        .mapToLong(sample -> databaseBytes(sample.database())).sum()));
                values.put("apply", new PluginTimerValue(
                        samples.stream().mapToLong(sample -> sample.metrics().applyCount()).sum(),
                        samples.stream().mapToLong(sample -> sample.metrics().applyNanos()).sum()));
                values.put("query", new PluginTimerValue(
                        samples.stream().mapToLong(sample -> sample.metrics().queryCount()).sum(),
                        samples.stream().mapToLong(sample -> sample.metrics().queryNanos()).sum()));
                values.put("failures", new PluginCounterValue(samples.stream()
                        .mapToLong(sample -> sample.metrics().failures()).sum()));
                values.put("rollbacks", new PluginCounterValue(samples.stream()
                        .mapToLong(sample -> sample.metrics().rollbacks()).sum()));
                return new PluginMetricSnapshot(values);
            }

            @Override
            public void close() {
            }
        };
    }

    private static PluginMetricDescriptor gauge(
            String id, String name, String description, String unit
    ) {
        return new PluginMetricDescriptor(
                id, name, PluginMetricType.GAUGE, description, unit);
    }

    private static PluginMetricDescriptor timer(
            String id, String name, String description
    ) {
        return new PluginMetricDescriptor(
                id, name, PluginMetricType.TIMER, description, "nanoseconds");
    }

    private static PluginMetricDescriptor counter(
            String id, String name, String description
    ) {
        return new PluginMetricDescriptor(
                id, name, PluginMetricType.COUNTER, description, "events");
    }

    private static double rebuildProgress(EutxoIndexerTelemetry.Sample sample) {
        long finalizedHeight = sample.coordinator().health().finalizedHeight();
        return finalizedHeight == 0 ? 1d : Math.min(1d,
                (double) sample.coordinator().health().checkpoint().source().appHeight()
                        / (double) finalizedHeight);
    }

    private static long databaseBytes(Path database) {
        return size(database) + size(Path.of(database + "-wal"))
                + size(Path.of(database + "-shm"));
    }

    private static long size(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.size(path) : 0;
        } catch (IOException ignored) {
            return 0;
        }
    }
}
