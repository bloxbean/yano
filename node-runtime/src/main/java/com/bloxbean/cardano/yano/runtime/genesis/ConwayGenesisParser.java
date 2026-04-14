package com.bloxbean.cardano.yano.runtime.genesis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;

/**
 * Parser for standard Cardano conway-genesis.json files.
 * Extracts Conway-era governance parameters.
 */
@Slf4j
public class ConwayGenesisParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ConwayGenesisData parse(File file) throws IOException {
        JsonNode root = MAPPER.readTree(file);
        return parseRoot(root);
    }

    public static ConwayGenesisData parse(InputStream in) throws IOException {
        JsonNode root = MAPPER.readTree(in);
        return parseRoot(root);
    }

    private static ConwayGenesisData parseRoot(JsonNode root) {
        int govActionLifetime = root.path("govActionLifetime").asInt(0);
        BigInteger govActionDeposit = parseBigInteger(root, "govActionDeposit", BigInteger.ZERO);
        BigInteger dRepDeposit = parseBigInteger(root, "dRepDeposit", BigInteger.ZERO);
        int dRepActivity = root.path("dRepActivity").asInt(0);
        int committeeMinSize = root.path("committeeMinSize").asInt(0);
        int committeeMaxTermLength = root.path("committeeMaxTermLength").asInt(0);

        log.info("Parsed conway genesis: govActionLifetime={}, govActionDeposit={}, dRepDeposit={}, " +
                        "dRepActivity={}, committeeMinSize={}, committeeMaxTermLength={}",
                govActionLifetime, govActionDeposit, dRepDeposit,
                dRepActivity, committeeMinSize, committeeMaxTermLength);

        return new ConwayGenesisData(govActionLifetime, govActionDeposit, dRepDeposit,
                dRepActivity, committeeMinSize, committeeMaxTermLength);
    }

    private static BigInteger parseBigInteger(JsonNode root, String field, BigInteger defaultValue) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) return defaultValue;
        try {
            return new BigInteger(node.asText());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
