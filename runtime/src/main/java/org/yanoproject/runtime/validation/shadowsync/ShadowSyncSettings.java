package org.yanoproject.runtime.validation.shadowsync;

import org.yanoproject.api.config.YanoPropertyKeys;
import org.yanoproject.ledger.rules.LedgerValidationEngines;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Shadow-sync settings (ADR-056 §7, Phase 7a), read from the runtime globals.
 *
 * @param engines          {@code yano.validation.shadow-sync-engines}: engines that validate every applied Conway
 *                         block (default {@code java})
 * @param reportFile       {@code yano.validation.shadow-sync-report}: JSONL file, one line per disagreement or engine
 *                         failure, or {@code null} for none
 * @param dumpDir          {@code yano.validation.shadow-sync-dump-dir}: replay bundles, or {@code null} for none
 * @param maxDumps         {@code yano.validation.shadow-sync-max-dumps}: at most this many bundles (default 1000)
 * @param maxInFlight      {@code yano.validation.shadow-sync-max-in-flight}: blocks validated or queued at once, each
 *                         holding its own snapshot; when all are taken the apply thread waits (default 8)
 * @param threads          {@code yano.validation.shadow-sync-threads}: validation threads (0 or unset: half the
 *                         processors, 1 to 4)
 * @param maxWaitMs        {@code yano.validation.shadow-sync-max-wait-ms}: longest the apply thread waits for a free
 *                         slot; a block that still finds none is skipped and reported (default 30000). The wait holds
 *                         the canonical write lock (see {@link ShadowSyncValidator})
 * @param summarySeconds   {@code yano.validation.shadow-sync-summary-seconds}: INFO summary interval, 0 for none
 *                         (default 60)
 */
public record ShadowSyncSettings(List<String> engines, Path reportFile, Path dumpDir, int maxDumps, int maxInFlight,
                                 int threads, long maxWaitMs, long summarySeconds) {

    public static final List<String> DEFAULT_ENGINES = List.of(LedgerValidationEngines.JAVA);
    public static final int DEFAULT_MAX_DUMPS = 1000;
    public static final int DEFAULT_MAX_IN_FLIGHT = 8;
    public static final long DEFAULT_MAX_WAIT_MS = 30_000;
    public static final long DEFAULT_SUMMARY_SECONDS = 60;

    public ShadowSyncSettings {
        engines = List.copyOf(Objects.requireNonNull(engines, "engines"));
        if (engines.isEmpty()) {
            throw new IllegalArgumentException(YanoPropertyKeys.Validation.SHADOW_SYNC_ENGINES + " must name an engine");
        }
        if (maxDumps < 0) {
            throw new IllegalArgumentException(YanoPropertyKeys.Validation.SHADOW_SYNC_MAX_DUMPS + " must be >= 0");
        }
        if (maxInFlight < 1) {
            throw new IllegalArgumentException(YanoPropertyKeys.Validation.SHADOW_SYNC_MAX_IN_FLIGHT + " must be >= 1");
        }
        if (threads < 1) {
            throw new IllegalArgumentException(YanoPropertyKeys.Validation.SHADOW_SYNC_THREADS + " must be >= 1");
        }
        if (maxWaitMs < 1) {
            throw new IllegalArgumentException(YanoPropertyKeys.Validation.SHADOW_SYNC_MAX_WAIT_MS + " must be >= 1");
        }
        if (summarySeconds < 0) {
            throw new IllegalArgumentException(YanoPropertyKeys.Validation.SHADOW_SYNC_SUMMARY_SECONDS
                    + " must be >= 0");
        }
    }

    /** @return the default thread count: half the processors, at least 1 and at most 4 */
    public static int defaultThreads() {
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
    }

    /** The defaults: the java engine, no report file, no dumps. */
    public static ShadowSyncSettings defaults() {
        return new ShadowSyncSettings(DEFAULT_ENGINES, null, null, DEFAULT_MAX_DUMPS, DEFAULT_MAX_IN_FLIGHT,
                defaultThreads(), DEFAULT_MAX_WAIT_MS, DEFAULT_SUMMARY_SECONDS);
    }

    /** Reads the settings; the engine list may be a collection, a comma-separated string or indexed keys. */
    public static ShadowSyncSettings fromGlobals(Map<String, Object> globals) {
        Map<String, Object> g = globals != null ? globals : Map.of();
        List<String> engines = engines(g);
        return new ShadowSyncSettings(engines.isEmpty() ? DEFAULT_ENGINES : engines,
                path(g.get(YanoPropertyKeys.Validation.SHADOW_SYNC_REPORT)),
                path(g.get(YanoPropertyKeys.Validation.SHADOW_SYNC_DUMP_DIR)),
                (int) number(g.get(YanoPropertyKeys.Validation.SHADOW_SYNC_MAX_DUMPS), DEFAULT_MAX_DUMPS),
                (int) number(g.get(YanoPropertyKeys.Validation.SHADOW_SYNC_MAX_IN_FLIGHT), DEFAULT_MAX_IN_FLIGHT),
                threads((int) number(g.get(YanoPropertyKeys.Validation.SHADOW_SYNC_THREADS), 0)),
                number(g.get(YanoPropertyKeys.Validation.SHADOW_SYNC_MAX_WAIT_MS), DEFAULT_MAX_WAIT_MS),
                number(g.get(YanoPropertyKeys.Validation.SHADOW_SYNC_SUMMARY_SECONDS), DEFAULT_SUMMARY_SECONDS));
    }

    private static int threads(int configured) {
        return configured <= 0 ? defaultThreads() : configured;
    }

    private static List<String> engines(Map<String, Object> g) {
        String key = YanoPropertyKeys.Validation.SHADOW_SYNC_ENGINES;
        Object value = g.get(key);
        List<String> raw = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            collection.forEach(item -> raw.add(String.valueOf(item)));
        } else if (value != null) {
            raw.add(String.valueOf(value));
        } else {
            for (int i = 0; ; i++) {
                Object item = g.get(key + "[" + i + "]");
                if (item == null) {
                    break;
                }
                raw.add(String.valueOf(item));
            }
        }
        Set<String> names = new LinkedHashSet<>();
        for (String entry : raw) {
            String trimmed = entry.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                trimmed = trimmed.substring(1, trimmed.length() - 1);
            }
            for (String part : trimmed.split(",")) {
                String name = LedgerValidationEngines.normalize(part.replace("\"", "").replace("'", ""));
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return List.copyOf(names);
    }

    private static Path path(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : Path.of(s);
    }

    private static long number(Object value, long defaultValue) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("not an integer: " + s, e);
            }
        }
        return defaultValue;
    }
}
