package com.bloxbean.cardano.yano.runtime.genesis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AlonzoGenesisParserTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesObjectCostModelsInLexicographicOperationOrder() throws Exception {
        Path file = tempDir.resolve("alonzo-genesis.json");
        Files.writeString(file, """
                {
                  "costModels": {
                    "PlutusV1": {
                      "bBuiltin": 20,
                      "aBuiltin": 10,
                      "cBuiltin": 30
                    }
                  },
                  "executionPrices": {
                    "prMem": {"numerator": 577, "denominator": 10000},
                    "prSteps": {"numerator": 721, "denominator": 10000000}
                  },
                  "maxTxExUnits": {"exUnitsMem": 10000000, "exUnitsSteps": 10000000000},
                  "maxBlockExUnits": {"exUnitsMem": 50000000, "exUnitsSteps": 40000000000},
                  "maxValueSize": 5000,
                  "collateralPercentage": 150,
                  "maxCollateralInputs": 3,
                  "lovelacePerUTxOWord": 34482
                }
                """);

        AlonzoGenesisData data = AlonzoGenesisParser.parse(file.toFile());

        assertThat(data.costModels()).containsEntry("PlutusV1", List.of(10L, 20L, 30L));
    }
}
