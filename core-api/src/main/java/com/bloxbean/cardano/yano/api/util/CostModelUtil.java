package com.bloxbean.cardano.yano.api.util;

import java.lang.reflect.Array;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonicalizes Plutus cost model shapes before persistence and API projection.
 * <p>
 * Genesis files may provide a cost model as an operation-name map. That map must
 * be ordered lexicographically by operation name before it is stored as an
 * ordered list and before it is projected as the ordered cost-model list expected
 * by Blockfrost-compatible APIs.
 */
public final class CostModelUtil {

    private CostModelUtil() {
    }

    public static Map<String, Object> canonicalCostModels(Map<String, Object> costModels) {
        if (costModels == null || costModels.isEmpty()) return null;

        Map<String, Object> result = new LinkedHashMap<>();
        costModels.forEach((language, model) ->
                result.put(language, indexedCostModel(canonicalCostModelList(model))));
        return result;
    }

    public static Map<String, Object> canonicalRawCostModels(Map<String, Object> costModels) {
        if (costModels == null || costModels.isEmpty()) return null;

        Map<String, Object> result = new LinkedHashMap<>();
        costModels.forEach((language, model) ->
                result.put(language, canonicalCostModelList(model)));
        return result;
    }

    public static List<Long> canonicalCostModelList(Object model) {
        if (model instanceof List<?> list) {
            return list.stream().map(CostModelUtil::toLongValue).toList();
        }

        if (model instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .map(Map.Entry::getValue)
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

    private static Map<String, Long> indexedCostModel(List<Long> costs) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (int i = 0; i < costs.size(); i++) {
            result.put(String.format("%03d", i), costs.get(i));
        }
        return result;
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
