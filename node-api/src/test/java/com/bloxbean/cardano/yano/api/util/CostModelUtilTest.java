package com.bloxbean.cardano.yano.api.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CostModelUtilTest {

    @Test
    void mapCostModelIsOrderedLexicographicallyBeforeProjection() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("bBuiltin", 20);
        model.put("aBuiltin", 10);
        model.put("cBuiltin", "30");

        Map<String, Object> costModels = Map.of("PlutusV1", model);

        assertThat(CostModelUtil.canonicalRawCostModels(costModels))
                .containsEntry("PlutusV1", List.of(10L, 20L, 30L));

        assertThat(CostModelUtil.canonicalCostModels(costModels).get("PlutusV1"))
                .isEqualTo(Map.of("000", 10L, "001", 20L, "002", 30L));
    }

    @Test
    void listCostModelKeepsExistingOrder() {
        Map<String, Object> costModels = Map.of("PlutusV2", List.of(3, 1, 2));

        assertThat(CostModelUtil.canonicalRawCostModels(costModels))
                .containsEntry("PlutusV2", List.of(3L, 1L, 2L));
    }
}
