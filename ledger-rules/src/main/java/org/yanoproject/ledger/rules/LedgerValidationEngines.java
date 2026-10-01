package org.yanoproject.ledger.rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Discovers {@link LedgerValidationEngineFactory} implementations and resolves configured engine names
 * (ADR-056 §7). Every configuration problem is an {@link IllegalStateException} with a message an operator
 * can act on; the node refuses to start rather than fall back to another engine.
 */
public final class LedgerValidationEngines {

    /** The legacy Scalus validator; {@code yano.validation.engine=scalus} restores it for admission. */
    public static final String SCALUS = "scalus";
    /**
     * The Java Conway engine of ADR-056 Phases 3–5 with the julc phase-2 evaluator (Phase 7c): the default admission
     * engine ({@code yano.validation.engine}, ADR-056 Phase 8) and the Java engine wherever one is defaulted.
     */
    public static final String JAVA_JULC = "java-julc";
    /** The optional Amaru WebAssembly engine (ADR-057), Amaru for both phases. */
    public static final String AMARU = "amaru";
    /** The optional Amaru WebAssembly engine (ADR-057) for phase one, with Plutus scripts on Scalus. */
    public static final String AMARU_SCALUS = "amaru-scalus";
    /**
     * The Java Conway engine with the Scalus phase-2 evaluator instead of julc (ADR-056 Phase 7c), for users who need
     * it; shadow sync can run both side by side ({@code shadow-sync-engines: java-julc,java-scalus}).
     */
    public static final String JAVA_SCALUS = "java-scalus";

    private final Map<String, LedgerValidationEngineFactory> factories;

    private LedgerValidationEngines(Map<String, LedgerValidationEngineFactory> factories) {
        this.factories = Map.copyOf(factories);
    }

    /** Discovers the factories visible to {@code classLoader}. */
    public static LedgerValidationEngines discover(ClassLoader classLoader) {
        Map<String, LedgerValidationEngineFactory> found = new LinkedHashMap<>();
        ServiceLoader<LedgerValidationEngineFactory> loader = ServiceLoader.load(LedgerValidationEngineFactory.class,
                classLoader);
        try {
            for (LedgerValidationEngineFactory factory : loader) {
                register(found, factory);
            }
        } catch (ServiceConfigurationError e) {
            throw new IllegalStateException("Cannot load a validation engine: " + e.getMessage(), e);
        }
        return new LedgerValidationEngines(found);
    }

    /** A fixed set of factories (tests, embedders). */
    public static LedgerValidationEngines of(List<? extends LedgerValidationEngineFactory> factories) {
        Map<String, LedgerValidationEngineFactory> found = new LinkedHashMap<>();
        factories.forEach(f -> register(found, f));
        return new LedgerValidationEngines(found);
    }

    private static void register(Map<String, LedgerValidationEngineFactory> found,
                                 LedgerValidationEngineFactory factory) {
        String name = normalize(factory.name());
        LedgerValidationEngineFactory previous = found.putIfAbsent(name, factory);
        if (previous != null && previous.getClass() != factory.getClass()) {
            throw new IllegalStateException("Two validation engines are named '" + name + "': "
                    + previous.getClass().getName() + " and " + factory.getClass().getName());
        }
    }

    /** @return the names of the engines on the classpath */
    public Set<String> available() {
        return Set.copyOf(factories.keySet());
    }

    /**
     * @param name a configured engine name (case-insensitive)
     * @return its factory
     * @throws IllegalStateException when the engine is not available, with the reason
     */
    public LedgerValidationEngineFactory factory(String name) {
        String key = normalize(name);
        LedgerValidationEngineFactory factory = factories.get(key);
        if (factory != null) {
            return factory;
        }
        throw new IllegalStateException(unavailableMessage(key));
    }

    private String unavailableMessage(String key) {
        return switch (key) {
            case JAVA_JULC -> "Validation engine 'java-julc' is not on the classpath: its factory (ledger-rules, "
                    + "JavaJulcEngineFactory) was not found. Use yano.validation.engine=scalus, or amaru or "
                    + "amaru-scalus in a build with -PwithAmaru=true.";
            case JAVA_SCALUS -> "Validation engine 'java-scalus' is not on the classpath: its factory (ledger-rules, "
                    + "JavaScalusEngineFactory) was not found.";
            case AMARU, AMARU_SCALUS -> "Validation engine '" + key + "' is configured but the amaru-validator "
                    + "module is not on the classpath. Build Yano with -PwithAmaru=true (ADR-057), or select another "
                    + "engine and remove " + key + " from yano.validation.shadow-engines.";
            default -> "Unknown validation engine '" + key + "'. Available: " + String.join(", ",
                    factories.keySet().stream().sorted().toList());
        };
    }

    /**
     * Parses {@code yano.validation.shadow-engines}: a comma-separated list (a YAML list arrives as
     * {@code a,b}), with optional brackets. Blank entries are ignored, duplicates removed.
     *
     * @param primary the admission engine, which may not also run as a shadow
     * @throws IllegalStateException when the admission engine is listed as a shadow
     */
    public static List<String> parseShadowEngines(String value, String primary) {
        if (value == null) {
            return List.of();
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        Set<String> names = new LinkedHashSet<>();
        for (String part : trimmed.split(",")) {
            String name = normalize(part.replace("\"", "").replace("'", ""));
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        String primaryName = normalize(primary);
        if (names.contains(primaryName)) {
            throw new IllegalStateException("yano.validation.shadow-engines lists the admission engine '"
                    + primaryName + "'" + (JAVA_JULC.equals(primaryName)
                    ? " (the default when yano.validation.engine is unset)" : "")
                    + "; a shadow engine must differ from yano.validation.engine");
        }
        return new ArrayList<>(names);
    }

    /** @return the name trimmed and lowercased; empty for null */
    public static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return "LedgerValidationEngines" + factories.keySet();
    }

    /** @return true when {@code name} is one of the known engine names, available or not */
    public static boolean isKnownName(String name) {
        String key = normalize(name);
        return Objects.equals(key, SCALUS) || Objects.equals(key, JAVA_JULC) || Objects.equals(key, JAVA_SCALUS)
                || Objects.equals(key, AMARU) || Objects.equals(key, AMARU_SCALUS);
    }
}
