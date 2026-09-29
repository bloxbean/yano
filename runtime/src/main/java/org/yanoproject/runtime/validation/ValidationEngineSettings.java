package org.yanoproject.runtime.validation;

import org.yanoproject.api.config.YanoPropertyKeys;
import org.yanoproject.ledger.rules.LedgerValidationEngines;
import org.yanoproject.runtime.ledger.canonical.CanonicalStateGate;
import org.yanoproject.runtime.validation.shadowsync.ShadowSyncSettings;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Engine selection and shadowing settings (ADR-056 §7), read from the runtime globals.
 *
 * @param engine            {@code yano.validation.engine}: the admission engine (default {@code scalus})
 * @param shadowEngines     {@code yano.validation.shadow-engines}: engines run in the shadow of admission
 * @param shadowDumpDir     {@code yano.validation.shadow-dump-dir}: where disagreement bundles go, or
 *                          {@code null} for none
 * @param shadowSync        {@code yano.validation.shadow-sync}: validate every transaction of every applied Conway
 *                          block against its pre-block state, observe only (ADR-056 Phase 7a)
 * @param snapshotMaxAgeMs  {@code yano.validation.snapshot-max-age-ms}: a shadow task older than this is
 *                          cancelled and releases its snapshot (default 30000)
 * @param maxLiveSnapshots  {@code yano.validation.max-live-snapshots} (default 4)
 * @param shadowSyncSettings the {@code yano.validation.shadow-sync-*} settings (read only when shadow sync is on)
 */
public record ValidationEngineSettings(String engine, List<String> shadowEngines, Path shadowDumpDir,
                                       boolean shadowSync, long snapshotMaxAgeMs, int maxLiveSnapshots,
                                       ShadowSyncSettings shadowSyncSettings) {

    public static final String DEFAULT_ENGINE = LedgerValidationEngines.SCALUS;
    public static final long DEFAULT_SNAPSHOT_MAX_AGE_MS = 30_000;

    public ValidationEngineSettings {
        engine = LedgerValidationEngines.normalize(engine);
        if (engine.isEmpty()) {
            engine = DEFAULT_ENGINE;
        }
        shadowEngines = List.copyOf(Objects.requireNonNull(shadowEngines, "shadowEngines"));
        if (snapshotMaxAgeMs <= 0) {
            throw new IllegalArgumentException(YanoPropertyKeys.Validation.SNAPSHOT_MAX_AGE_MS + " must be > 0");
        }
        if (maxLiveSnapshots <= 0) {
            throw new IllegalArgumentException(YanoPropertyKeys.Validation.MAX_LIVE_SNAPSHOTS + " must be > 0");
        }
        if (shadowSyncSettings == null) {
            shadowSyncSettings = ShadowSyncSettings.defaults();
        }
    }

    /** Settings with the default shadow-sync settings. */
    public ValidationEngineSettings(String engine, List<String> shadowEngines, Path shadowDumpDir, boolean shadowSync,
                                    long snapshotMaxAgeMs, int maxLiveSnapshots) {
        this(engine, shadowEngines, shadowDumpDir, shadowSync, snapshotMaxAgeMs, maxLiveSnapshots, null);
    }

    /** The defaults: {@code scalus}, no shadows, no dumps. */
    public static ValidationEngineSettings defaults() {
        return new ValidationEngineSettings(DEFAULT_ENGINE, List.of(), null, false, DEFAULT_SNAPSHOT_MAX_AGE_MS,
                CanonicalStateGate.DEFAULT_MAX_LIVE_SNAPSHOTS);
    }

    /**
     * Reads the settings from runtime globals. A YAML list for {@code shadow-engines} may arrive as a
     * {@link Collection}, a comma-separated string, or indexed keys ({@code shadow-engines[0]}).
     *
     * @throws IllegalStateException when the admission engine is also listed as a shadow
     */
    public static ValidationEngineSettings fromGlobals(Map<String, Object> globals) {
        Map<String, Object> g = globals != null ? globals : Map.of();
        String engine = string(g.get(YanoPropertyKeys.Validation.ENGINE));
        String normalizedEngine = LedgerValidationEngines.normalize(engine).isEmpty() ? DEFAULT_ENGINE
                : LedgerValidationEngines.normalize(engine);
        List<String> shadows = LedgerValidationEngines.parseShadowEngines(shadowList(g), normalizedEngine);
        String dump = string(g.get(YanoPropertyKeys.Validation.SHADOW_DUMP_DIR));
        boolean shadowSync = bool(g.get(YanoPropertyKeys.Validation.SHADOW_SYNC));
        return new ValidationEngineSettings(normalizedEngine, shadows,
                dump == null || dump.isBlank() ? null : Path.of(dump.trim()),
                shadowSync,
                number(g.get(YanoPropertyKeys.Validation.SNAPSHOT_MAX_AGE_MS), DEFAULT_SNAPSHOT_MAX_AGE_MS),
                (int) number(g.get(YanoPropertyKeys.Validation.MAX_LIVE_SNAPSHOTS),
                        CanonicalStateGate.DEFAULT_MAX_LIVE_SNAPSHOTS),
                shadowSync ? ShadowSyncSettings.fromGlobals(g) : ShadowSyncSettings.defaults());
    }

    /**
     * @return true when validation engines are created (any engine but {@code scalus}, any shadow engine, or shadow
     *         sync); false keeps the legacy {@code TransactionValidator} path with nothing else running
     */
    public boolean usesEngineApi() {
        return affectsAdmission() || shadowSync;
    }

    /**
     * @return true when the engines take part in admission: an engine-API admission engine, or shadow engines next
     *         to admission. Shadow sync alone leaves the admission path (and the mempool) exactly as without engines.
     */
    public boolean affectsAdmission() {
        return !engine.equals(DEFAULT_ENGINE) || !shadowEngines.isEmpty();
    }

    /**
     * @return true when admission itself goes through the engine API (any engine but {@code scalus}); with
     *         {@code engine: scalus} admission stays on the legacy validator even when shadows are configured
     */
    public boolean engineAdmission() {
        return !engine.equals(DEFAULT_ENGINE);
    }

    /** @return true when {@code name} is the admission engine or a shadow engine */
    public boolean uses(String name) {
        String key = LedgerValidationEngines.normalize(name);
        return engine.equals(key) || shadowEngines.contains(key);
    }

    private static String shadowList(Map<String, Object> g) {
        Object value = g.get(YanoPropertyKeys.Validation.SHADOW_ENGINES);
        if (value instanceof Collection<?> collection) {
            return String.join(",", collection.stream().map(String::valueOf).toList());
        }
        if (value != null) {
            return String.valueOf(value);
        }
        StringBuilder indexed = new StringBuilder();
        for (int i = 0; ; i++) {
            Object item = g.get(YanoPropertyKeys.Validation.SHADOW_ENGINES + "[" + i + "]");
            if (item == null) {
                break;
            }
            indexed.append(item).append(',');
        }
        return indexed.toString();
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value).trim());
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
