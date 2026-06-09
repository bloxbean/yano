package com.bloxbean.cardano.yano.api.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ordered Plutus cost-model operation names.
 */
public final class CostModelOpNames {

    private static final List<String> LANGUAGES = List.of("PlutusV1", "PlutusV2", "PlutusV3");
    private static final Map<String, List<String>> OP_NAMES = loadAll();

    private CostModelOpNames() {
    }

    public static List<String> forLanguage(String language) {
        return OP_NAMES.get(language);
    }

    public static boolean hasExactNames(String language, int size) {
        List<String> names = forLanguage(language);
        return names != null && names.size() == size;
    }

    public static Map<String, List<String>> all() {
        return OP_NAMES;
    }

    private static Map<String, List<String>> loadAll() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String language : LANGUAGES) {
            result.put(language, load(language));
        }
        return Collections.unmodifiableMap(result);
    }

    private static List<String> load(String language) {
        String resource = "/cost-model-opnames/" + language + ".txt";
        try (InputStream is = CostModelOpNames.class.getResourceAsStream(resource)) {
            if (is == null) {
                throw new IllegalStateException("Cost model operation names resource not found: " + resource);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty())
                        .toList();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load cost model operation names: " + resource, e);
        }
    }
}
