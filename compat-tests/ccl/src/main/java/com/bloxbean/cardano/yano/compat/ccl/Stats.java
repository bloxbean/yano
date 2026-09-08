package com.bloxbean.cardano.yano.compat.ccl;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe counters plus a latency sample set for one load phase. */
public final class Stats {
    private final String phase;
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final Map<String, AtomicLong> byCategory = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Long> latenciesMicros = new ConcurrentLinkedQueue<>();
    private volatile long startedAtMillis;
    private volatile long endedAtMillis;

    public Stats(String phase) {
        this.phase = phase;
    }

    public String phase() {
        return phase;
    }

    public void start() {
        startedAtMillis = System.currentTimeMillis();
    }

    public void end() {
        endedAtMillis = System.currentTimeMillis();
    }

    public void record(YanoClient.SubmitResult r) {
        submitted.incrementAndGet();
        if (r.accepted()) accepted.incrementAndGet();
        else rejected.incrementAndGet();
        byCategory.computeIfAbsent(r.category(), k -> new AtomicLong()).incrementAndGet();
        latenciesMicros.add(r.latencyNanos() / 1000);
    }

    /** Local failure before any submission (build/sign error). */
    public void recordLocalFailure(String reason) {
        submitted.incrementAndGet();
        rejected.incrementAndGet();
        byCategory.computeIfAbsent("LOCAL_" + reason, k -> new AtomicLong()).incrementAndGet();
    }

    public long submitted() {
        return submitted.get();
    }

    public long accepted() {
        return accepted.get();
    }

    public long rejected() {
        return rejected.get();
    }

    public double durationSeconds() {
        long end = endedAtMillis == 0 ? System.currentTimeMillis() : endedAtMillis;
        return Math.max(0.001, (end - startedAtMillis) / 1000.0);
    }

    public double submitTps() {
        return submitted.get() / durationSeconds();
    }

    public double acceptTps() {
        return accepted.get() / durationSeconds();
    }

    public Map<String, Long> categories() {
        Map<String, Long> out = new LinkedHashMap<>();
        byCategory.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.put(e.getKey(), e.getValue().get()));
        return out;
    }

    /** Latency percentiles in milliseconds. */
    public Map<String, Double> latencyMillis() {
        List<Long> sorted = latenciesMicros.stream().sorted(Comparator.naturalOrder()).toList();
        Map<String, Double> out = new LinkedHashMap<>();
        if (sorted.isEmpty()) return out;
        out.put("min", sorted.get(0) / 1000.0);
        out.put("p50", pct(sorted, 50));
        out.put("p90", pct(sorted, 90));
        out.put("p95", pct(sorted, 95));
        out.put("p99", pct(sorted, 99));
        out.put("max", sorted.get(sorted.size() - 1) / 1000.0);
        double mean = sorted.stream().mapToLong(Long::longValue).average().orElse(0);
        out.put("mean", mean / 1000.0);
        return out;
    }

    private static double pct(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1))) / 1000.0;
    }
}
