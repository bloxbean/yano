package com.bloxbean.cardano.yano.runtime.blockproducer;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Stake data provider backed by Shelley genesis staking and initialFunds.
 * This is intended for local devnets whose initial stake distribution is fixed
 * in genesis and available before any indexer has caught up.
 */
@Slf4j
public class GenesisStakeDataProvider implements StakeDataProvider {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int KEY_HASH_HEX_LENGTH = 56;
    private static final int BASE_ADDRESS_HEX_LENGTH = 114;
    private static final int REWARD_ADDRESS_HEX_LENGTH = 58;

    private final Map<String, BigInteger> stakeByPool;
    private final BigInteger totalStake;

    public GenesisStakeDataProvider(Path shelleyGenesisFile) throws IOException {
        JsonNode root = MAPPER.readTree(shelleyGenesisFile.toFile());
        Map<String, String> delegationByStakeKey = parseDelegations(root.path("staking").path("stake"));
        this.stakeByPool = Collections.unmodifiableMap(parseStakeByPool(root.path("initialFunds"), delegationByStakeKey));
        this.totalStake = stakeByPool.values().stream()
                .reduce(BigInteger.ZERO, BigInteger::add);

        log.info("Loaded genesis stake data from {}: pools={}, totalStake={}",
                shelleyGenesisFile, stakeByPool.size(), totalStake);
    }

    @Override
    public BigInteger getPoolStake(String poolHash, int epoch) {
        if (poolHash == null) return null;
        return stakeByPool.getOrDefault(normalizeHex(poolHash), BigInteger.ZERO);
    }

    @Override
    public BigInteger getTotalStake(int epoch) {
        return totalStake;
    }

    private static Map<String, String> parseDelegations(JsonNode stakeNode) {
        if (stakeNode == null || !stakeNode.isObject()) {
            return Collections.emptyMap();
        }

        Map<String, String> delegations = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = stakeNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String stakeKeyHash = normalizeHex(entry.getKey());
            String poolHash = parsePoolHash(entry.getValue());
            if (stakeKeyHash.length() == KEY_HASH_HEX_LENGTH && poolHash != null) {
                delegations.put(stakeKeyHash, poolHash);
            }
        }
        return delegations;
    }

    private static String parsePoolHash(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return normalizeHex(node.asText());
        }
        JsonNode poolHash = node.path("poolHash");
        if (!poolHash.isMissingNode() && !poolHash.isNull()) {
            return normalizeHex(poolHash.asText());
        }
        JsonNode keyHash = node.path("keyHash");
        if (!keyHash.isMissingNode() && !keyHash.isNull()) {
            return normalizeHex(keyHash.asText());
        }
        return null;
    }

    private static Map<String, BigInteger> parseStakeByPool(JsonNode initialFunds,
                                                            Map<String, String> delegationByStakeKey) {
        if (initialFunds == null || !initialFunds.isObject() || delegationByStakeKey.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, BigInteger> stakeByPool = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = initialFunds.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String stakeKeyHash = extractStakeCredentialHash(entry.getKey());
            if (stakeKeyHash == null) {
                continue;
            }

            String poolHash = delegationByStakeKey.get(stakeKeyHash);
            if (poolHash == null) {
                continue;
            }

            BigInteger amount = parseAmount(entry.getValue());
            if (amount.signum() <= 0) {
                continue;
            }
            stakeByPool.merge(poolHash, amount, BigInteger::add);
        }
        return stakeByPool;
    }

    private static BigInteger parseAmount(JsonNode node) {
        if (node == null || node.isNull()) {
            return BigInteger.ZERO;
        }
        if (node.isNumber()) {
            return node.bigIntegerValue();
        }
        String value = node.asText("0");
        return value == null || value.isBlank() ? BigInteger.ZERO : new BigInteger(value);
    }

    private static String extractStakeCredentialHash(String addressHex) {
        String normalized = normalizeHex(addressHex);
        if (normalized.length() < 2) {
            return null;
        }

        byte[] bytes;
        try {
            bytes = HexUtil.decodeHexString(normalized);
        } catch (Exception e) {
            return null;
        }
        if (bytes.length == 0) {
            return null;
        }

        int addressType = (bytes[0] & 0xF0) >>> 4;
        if (normalized.length() >= BASE_ADDRESS_HEX_LENGTH && addressType >= 0 && addressType <= 3) {
            return normalized.substring(58, 58 + KEY_HASH_HEX_LENGTH);
        }
        if (normalized.length() == REWARD_ADDRESS_HEX_LENGTH && (addressType == 14 || addressType == 15)) {
            return normalized.substring(2);
        }
        return null;
    }

    private static String normalizeHex(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("0x") ? normalized.substring(2) : normalized;
    }
}
