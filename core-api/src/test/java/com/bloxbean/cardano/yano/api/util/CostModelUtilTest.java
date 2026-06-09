package com.bloxbean.cardano.yano.api.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

class CostModelUtilTest {

    @Test
    void namedMapCostModelPreservesInputOrder() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("bBuiltin", 20);
        model.put("aBuiltin", 10);
        model.put("cBuiltin", "30");

        Map<String, Object> costModels = Map.of("UnknownPlutus", model);

        assertThat(CostModelUtil.canonicalRawCostModels(costModels))
                .containsEntry("UnknownPlutus", List.of(20L, 10L, 30L));

        assertThat(CostModelUtil.canonicalCostModels(costModels).get("PlutusV1"))
                .isNull();
        assertThat(CostModelUtil.canonicalCostModelsTyped(costModels).get("UnknownPlutus"))
                .containsExactly(
                        Map.entry("bBuiltin", 20L),
                        Map.entry("aBuiltin", 10L),
                        Map.entry("cBuiltin", 30L));
    }

    @Test
    void listCostModelKeepsExistingOrder() {
        Map<String, Object> costModels = Map.of("PlutusV2", List.of(3, 1, 2));

        assertThat(CostModelUtil.canonicalRawCostModels(costModels))
                .containsEntry("PlutusV2", List.of(3L, 1L, 2L));
    }

    @Test
    void knownLanguageFullListProjectsToOperationNames() {
        List<Long> costs = LongStream.range(0, CostModelOpNames.forLanguage("PlutusV3").size())
                .boxed()
                .toList();
        Map<String, Object> costModels = Map.of("PlutusV3", costs);

        LinkedHashMap<String, Long> projected =
                CostModelUtil.canonicalCostModelsTyped(costModels).get("PlutusV3");

        assertThat(projected).hasSize(350);
        assertThat(new ArrayList<>(projected.keySet()))
                .startsWith("addInteger-cpu-arguments-intercept",
                        "addInteger-cpu-arguments-slope")
                .endsWith("scaleValue-cpu-arguments-slope",
                        "scaleValue-memory-arguments-intercept",
                        "scaleValue-memory-arguments-slope");
    }

    @Test
    void knownLanguageFullNamedMapUsesProtocolOrderNotInputOrder() {
        List<String> names = CostModelOpNames.forLanguage("PlutusV3");
        Map<String, Object> model = new LinkedHashMap<>();
        for (int i = names.size() - 1; i >= 0; i--) {
            model.put(names.get(i), i);
        }

        Map<String, Object> costModels = Map.of("PlutusV3", model);

        assertThat(CostModelUtil.canonicalRawCostModelsTyped(costModels).get("PlutusV3"))
                .startsWith(0L, 1L, 2L)
                .endsWith(347L, 348L, 349L);
        assertThat(new ArrayList<>(CostModelUtil.canonicalCostModelsTyped(costModels).get("PlutusV3").entrySet()))
                .startsWith(
                        Map.entry("addInteger-cpu-arguments-intercept", 0L),
                        Map.entry("addInteger-cpu-arguments-slope", 1L),
                        Map.entry("addInteger-memory-arguments-intercept", 2L));
    }

    @Test
    void knownLanguageLengthMismatchFallsBackToNumericKeys() {
        Map<String, Object> costModels = Map.of("PlutusV1", List.of(3, 1, 2));

        assertThat(CostModelUtil.canonicalCostModelsTyped(costModels).get("PlutusV1"))
                .containsExactly(
                        Map.entry("000", 3L),
                        Map.entry("001", 1L),
                        Map.entry("002", 2L));
    }

    @Test
    void registryLoadsKnownLanguageCounts() {
        assertThat(CostModelOpNames.forLanguage("PlutusV1")).hasSize(332);
        assertThat(CostModelOpNames.forLanguage("PlutusV2")).hasSize(332);
        assertThat(CostModelOpNames.forLanguage("PlutusV3")).hasSize(350);
    }
}
