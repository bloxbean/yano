package com.bloxbean.cardano.yano.api.util;

import java.lang.reflect.Array;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonicalizes Plutus cost model shapes before persistence and API projection.
 */
public final class CostModelUtil {

    private CostModelUtil() {
    }

    public static Map<String, Object> canonicalCostModels(Map<String, Object> costModels) {
        Map<String, LinkedHashMap<String, Long>> typed = canonicalCostModelsTyped(costModels);
        if (typed == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        typed.forEach(result::put);
        return result;
    }

    public static Map<String, Object> canonicalRawCostModels(Map<String, Object> costModels) {
        Map<String, List<Long>> typed = canonicalRawCostModelsTyped(costModels);
        if (typed == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        typed.forEach(result::put);
        return result;
    }

    public static Map<String, LinkedHashMap<String, Long>> canonicalCostModelsTyped(Map<String, ?> costModels) {
        if (costModels == null || costModels.isEmpty()) return null;

        Map<String, LinkedHashMap<String, Long>> result = new LinkedHashMap<>();
        costModels.forEach((language, model) ->
                result.put(language, canonicalCostModelMap(language, model)));
        return result;
    }

    public static Map<String, List<Long>> canonicalRawCostModelsTyped(Map<String, ?> costModels) {
        if (costModels == null || costModels.isEmpty()) return null;

        Map<String, List<Long>> result = new LinkedHashMap<>();
        costModels.forEach((language, model) ->
                result.put(language, canonicalCostModelList(language, model)));
        return result;
    }

    public static List<Long> canonicalCostModelList(Object model) {
        return canonicalCostModelList(null, model);
    }

    public static List<Long> canonicalCostModelList(String language, Object model) {
        if (model instanceof List<?> list) {
            return list.stream().map(CostModelUtil::toLongValue).toList();
        }

        if (model instanceof Map<?, ?> map) {
            if (numericKeyed(map)) {
                return map.entrySet().stream()
                        .sorted(Comparator.comparingInt(entry -> numericIndex(entry.getKey())))
                        .map(Map.Entry::getValue)
                        .map(CostModelUtil::toLongValue)
                        .toList();
            }

            List<String> names = CostModelOpNames.forLanguage(language);
            if (names != null && map.keySet().containsAll(names)) {
                return names.stream()
                        .map(map::get)
                        .map(CostModelUtil::toLongValue)
                        .toList();
            }

            return map.values().stream()
                    .map(CostModelUtil::toLongValue)
                    .toList();
        }

        if (model != null && model.getClass().isArray()) {
            int length = Array.getLength(model);
            return java.util.stream.IntStream.range(0, length)
                    .mapToObj(i -> Array.get(model, i))
                    .map(CostModelUtil::toLongValue)
                    .toList();
        }

        throw new IllegalArgumentException("Unsupported cost model value: " + model);
    }

    public static LinkedHashMap<String, Long> canonicalCostModelMap(String language, Object model) {
        if (model instanceof Map<?, ?> map && !numericKeyed(map)) {
            List<String> names = CostModelOpNames.forLanguage(language);
            if (names != null && map.keySet().containsAll(names)) {
                LinkedHashMap<String, Long> result = new LinkedHashMap<>();
                for (String name : names) {
                    result.put(name, toLongValue(map.get(name)));
                }
                return result;
            }

            LinkedHashMap<String, Long> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), toLongValue(value)));
            return result;
        }

        List<Long> costs = canonicalCostModelList(language, model);
        List<String> names = CostModelOpNames.forLanguage(language);
        if (names != null && names.size() == costs.size()) {
            LinkedHashMap<String, Long> result = new LinkedHashMap<>();
            for (int i = 0; i < costs.size(); i++) {
                result.put(names.get(i), costs.get(i));
            }
            return result;
        }

        return indexedCostModel(costs);
    }

    private static LinkedHashMap<String, Long> indexedCostModel(List<Long> costs) {
        LinkedHashMap<String, Long> result = new LinkedHashMap<>();
        for (int i = 0; i < costs.size(); i++) {
            result.put(String.format("%03d", i), costs.get(i));
        }
        return result;
    }

    private static boolean numericKeyed(Map<?, ?> map) {
        return !map.isEmpty() && map.keySet().stream().allMatch(CostModelUtil::isNumericKey);
    }

    private static boolean isNumericKey(Object key) {
        try {
            Integer.parseInt(String.valueOf(key));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static int numericIndex(Object key) {
        return Integer.parseInt(String.valueOf(key));
    }

    private static Long toLongValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String string) {
            return Long.parseLong(string);
        }
        throw new IllegalArgumentException("Unsupported cost model entry value: " + value);
    }
}
