package com.bloxbean.cardano.yano.runtime.genesis;

import com.bloxbean.cardano.yaci.core.types.NonNegativeInterval;
import com.bloxbean.cardano.yano.api.util.CostModelUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lightweight parser for standard Cardano alonzo-genesis.json files.
 */
@Slf4j
public class AlonzoGenesisParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static AlonzoGenesisData parse(File file) throws IOException {
        JsonNode root = MAPPER.readTree(file);
        return parseRoot(root);
    }

    private static AlonzoGenesisData parseRoot(JsonNode root) {
        Map<String, Object> costModels = parseCostModels(root.path("costModels"));
        NonNegativeInterval priceMemInterval = parseNonNegativeInterval(root.path("executionPrices").path("prMem"));
        NonNegativeInterval priceStepInterval = parseNonNegativeInterval(root.path("executionPrices").path("prSteps"));
        BigDecimal priceMem = priceMemInterval != null ? priceMemInterval.safeRatio() : null;
        BigDecimal priceStep = priceStepInterval != null ? priceStepInterval.safeRatio() : null;

        BigInteger maxTxExMem = bigInteger(root.path("maxTxExUnits").path("exUnitsMem"));
        BigInteger maxTxExSteps = bigInteger(root.path("maxTxExUnits").path("exUnitsSteps"));
        BigInteger maxBlockExMem = bigInteger(root.path("maxBlockExUnits").path("exUnitsMem"));
        BigInteger maxBlockExSteps = bigInteger(root.path("maxBlockExUnits").path("exUnitsSteps"));
        BigInteger maxValSize = bigInteger(root.path("maxValueSize"));
        Integer collateralPercent = integer(root.path("collateralPercentage"));
        Integer maxCollateralInputs = integer(root.path("maxCollateralInputs"));
        BigInteger coinsPerUtxoWord = bigInteger(root.path("lovelacePerUTxOWord"));

        log.info("Parsed alonzo genesis: costModels={}, maxTxExMem={}, maxBlockExMem={}, coinsPerUtxoWord={}",
                costModels.size(), maxTxExMem, maxBlockExMem, coinsPerUtxoWord);

        return new AlonzoGenesisData(
                costModels, priceMem, priceStep,
                maxTxExMem, maxTxExSteps, maxBlockExMem, maxBlockExSteps,
                maxValSize, collateralPercent, maxCollateralInputs, coinsPerUtxoWord,
                priceMemInterval, priceStepInterval);
    }

    private static Map<String, Object> parseCostModels(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isObject()) {
            return null;
        }
        Map<String, Object> parsed = MAPPER.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {});
        return CostModelUtil.canonicalRawCostModels(parsed);
    }

    private static NonNegativeInterval parseNonNegativeInterval(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        BigInteger numerator = new BigInteger(node.path("numerator").asText("0"));
        BigInteger denominator = new BigInteger(node.path("denominator").asText("1"));
        return new NonNegativeInterval(numerator, denominator);
    }

    private static BigInteger bigInteger(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String value = node.asText(null);
        return value == null || value.isBlank() ? null : new BigInteger(value);
    }

    private static Integer integer(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        return node.asInt();
    }
}
