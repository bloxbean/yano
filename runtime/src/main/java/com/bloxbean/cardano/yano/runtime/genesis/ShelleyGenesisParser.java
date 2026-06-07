package com.bloxbean.cardano.yano.runtime.genesis;

import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.genesis.GenesisDelegation;
import com.bloxbean.cardano.yano.api.genesis.GenesisPool;
import com.bloxbean.cardano.yano.api.genesis.GenesisRelay;
import com.bloxbean.cardano.yano.api.genesis.ShelleyGenesisBootstrap;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lightweight parser for standard Cardano shelley-genesis.json files.
 * Extracts network metadata, initial funds, and protocol parameters.
 */
@Slf4j
public class ShelleyGenesisParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final int DEFAULT_PROTOCOL_MAJOR_VERSION = 10;
    public static final int DEFAULT_PROTOCOL_MINOR_VERSION = 0;

    public static ShelleyGenesisData parse(File file) throws IOException {
        JsonNode root = MAPPER.readTree(file);
        return parseRoot(root);
    }

    public static ShelleyGenesisData parse(InputStream in) throws IOException {
        JsonNode root = MAPPER.readTree(in);
        return parseRoot(root);
    }

    private static ShelleyGenesisData parseRoot(JsonNode root) {
        Map<String, BigInteger> initialFunds = parseEffectiveInitialFunds(root);
        long networkMagic = root.path("networkMagic").asLong(0);
        long epochLength = root.path("epochLength").asLong(0);
        double slotLength = root.path("slotLength").asDouble(1.0);
        String systemStart = root.path("systemStart").asText(null);
        long maxLovelaceSupply = root.path("maxLovelaceSupply").asLong(0);
        double activeSlotsCoeff = root.path("activeSlotsCoeff").asDouble(0.05);
        long securityParam = root.path("securityParam").asLong(0);
        long maxKESEvolutions = root.path("maxKESEvolutions").asLong(0);
        long slotsPerKESPeriod = root.path("slotsPerKESPeriod").asLong(0);
        long updateQuorum = root.path("updateQuorum").asLong(0);

        // Parse protocolParams section
        JsonNode protoParams = root.path("protocolParams");
        JsonNode protoVersion = protoParams.path("protocolVersion");
        long protocolMajor = protoVersion.path("major").asLong(DEFAULT_PROTOCOL_MAJOR_VERSION);
        long protocolMinor = protoVersion.path("minor").asLong(DEFAULT_PROTOCOL_MINOR_VERSION);

        BigDecimal rho = decimal(protoParams, "rho", "0.003");
        BigDecimal tau = decimal(protoParams, "tau", "0.2");
        BigDecimal a0 = decimal(protoParams, "a0", "0.3");
        int nOpt = protoParams.path("nOpt").asInt(150);
        long minPoolCost = protoParams.path("minPoolCost").asLong(340000000);
        long keyDeposit = protoParams.path("keyDeposit").asLong(2000000);
        long poolDeposit = protoParams.path("poolDeposit").asLong(500000000);
        BigDecimal decentralisationParam = decimal(protoParams, "decentralisationParam", "1.0");
        int minFeeA = protoParams.path("minFeeA").asInt(44);
        int minFeeB = protoParams.path("minFeeB").asInt(155381);
        int maxBlockBodySize = protoParams.path("maxBlockBodySize").asInt(65536);
        int maxTxSize = protoParams.path("maxTxSize").asInt(16384);
        int maxBlockHeaderSize = protoParams.path("maxBlockHeaderSize").asInt(1100);
        int eMax = protoParams.path("eMax").asInt(18);
        String extraEntropy = parseExtraEntropy(protoParams.path("extraEntropy"));
        long minUTxOValue = protoParams.path("minUTxOValue").asLong(0);
        ShelleyGenesisBootstrap bootstrap = parseBootstrap(root, initialFunds, networkMagic,
                maxLovelaceSupply, keyDeposit, poolDeposit);

        log.info("Parsed shelley genesis: networkMagic={}, initialFunds={} entries, epochLength={}, " +
                        "systemStart={}, activeSlotsCoeff={}, protocolVersion={}.{}, rho={}, tau={}, a0={}, nOpt={}, stakingPools={}, stakingDelegations={}",
                networkMagic, initialFunds.size(), epochLength, systemStart,
                activeSlotsCoeff, protocolMajor, protocolMinor, rho, tau, a0, nOpt,
                bootstrap.pools().size(), bootstrap.delegations().size());

        return new ShelleyGenesisData(initialFunds, networkMagic, epochLength, slotLength,
                systemStart, maxLovelaceSupply, activeSlotsCoeff,
                securityParam, maxKESEvolutions, slotsPerKESPeriod, updateQuorum,
                protocolMajor, protocolMinor,
                rho, tau, a0, nOpt, minPoolCost, keyDeposit, poolDeposit, decentralisationParam,
                minFeeA, minFeeB, maxBlockBodySize, maxTxSize, maxBlockHeaderSize, eMax,
                extraEntropy, minUTxOValue, bootstrap);
    }

    /**
     * Update the systemStart field in a shelley-genesis.json file.
     *
     * @param file        the genesis file to update
     * @param systemStart ISO-8601 timestamp (e.g. "2026-03-05T14:30:00Z")
     */
    public static void updateSystemStart(File file, String systemStart) throws IOException {
        ObjectNode root = (ObjectNode) MAPPER.readTree(file);
        root.put("systemStart", systemStart);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(file, root);
        log.info("Updated systemStart in {}: {}", file.getPath(), systemStart);
    }

    private static Map<String, BigInteger> parseInitialFunds(JsonNode fundsNode) {
        if (fundsNode == null || fundsNode.isNull() || !fundsNode.isObject()) {
            return Collections.emptyMap();
        }

        Map<String, BigInteger> funds = new LinkedHashMap<>();
        var fields = fundsNode.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            funds.put(entry.getKey(), new BigInteger(entry.getValue().asText("0")));
        }
        return Collections.unmodifiableMap(funds);
    }

    private static Map<String, BigInteger> parseEffectiveInitialFunds(JsonNode root) {
        Map<String, BigInteger> legacyInitialFunds = parseInitialFunds(root.get("initialFunds"));
        JsonNode extraInitialFunds = root.path("extraConfig").path("initialFunds");
        if (extraInitialFunds == null || extraInitialFunds.isMissingNode() || extraInitialFunds.isNull()) {
            return legacyInitialFunds;
        }
        if (!extraInitialFunds.isObject()) {
            throw new IllegalArgumentException("Shelley genesis extraConfig.initialFunds must be an object");
        }

        JsonNode fileNode = extraInitialFunds.get("file");
        JsonNode dataNode = extraInitialFunds.get("data");
        boolean hasFile = fileNode != null && !fileNode.isNull();
        boolean hasData = dataNode != null && !dataNode.isNull();
        if (hasFile && hasData) {
            throw new IllegalArgumentException("Shelley genesis extraConfig.initialFunds cannot specify both file and data");
        }
        if (hasFile) {
            if (!legacyInitialFunds.isEmpty()) {
                throw new IllegalArgumentException("Shelley genesis cannot specify both initialFunds and extraConfig.initialFunds");
            }
            throw new IllegalArgumentException("Shelley genesis extraConfig.initialFunds file injection is not supported");
        }
        if (!hasData) {
            throw new IllegalArgumentException("Shelley genesis extraConfig.initialFunds must specify data or file");
        }
        if (!legacyInitialFunds.isEmpty()) {
            throw new IllegalArgumentException("Shelley genesis cannot specify both initialFunds and extraConfig.initialFunds");
        }
        return parseInitialFunds(dataNode);
    }

    private static ShelleyGenesisBootstrap parseBootstrap(JsonNode root,
                                                          Map<String, BigInteger> initialFunds,
                                                          long networkMagic,
                                                          long maxLovelaceSupply,
                                                          long keyDeposit,
                                                          long poolDeposit) {
        JsonNode staking = root.path("staking");
        List<GenesisPool> pools = parsePools(staking.path("pools"), networkMagic);
        List<GenesisDelegation> delegations = parseDelegations(staking.path("stake"));
        return new ShelleyGenesisBootstrap(initialFunds,
                BigInteger.valueOf(maxLovelaceSupply),
                BigInteger.valueOf(keyDeposit),
                BigInteger.valueOf(poolDeposit),
                pools,
                delegations);
    }

    private static List<GenesisPool> parsePools(JsonNode poolsNode, long networkMagic) {
        if (poolsNode == null || poolsNode.isMissingNode() || poolsNode.isNull() || !poolsNode.isObject()) {
            return List.of();
        }

        List<GenesisPool> pools = new ArrayList<>();
        var fields = poolsNode.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String poolHash = normalizeHex(entry.getKey());
            JsonNode node = entry.getValue();
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("Shelley genesis staking pool " + poolHash + " must be an object");
            }

            String bodyPoolHash = normalizeHex(firstText(node, "poolId", "publicKey"));
            if (bodyPoolHash == null) {
                throw new IllegalArgumentException("Shelley genesis staking pool " + poolHash
                        + " is missing poolId/publicKey");
            }
            if (!bodyPoolHash.equals(poolHash)) {
                throw new IllegalArgumentException("Shelley genesis staking pool body id " + bodyPoolHash
                        + " does not match map key " + poolHash);
            }

            UnitInterval margin = ConwayGenesisParser.decimalToUnitInterval(
                    requiredDecimal(node, "margin", poolHash));
            BigInteger marginNum = margin.getNumerator();
            BigInteger marginDen = margin.getDenominator();

            String vrf = normalizeHex(requiredText(node, "vrf", poolHash));
            BigInteger pledge = parseRequiredBigInteger(node, "pledge", poolHash);
            BigInteger cost = parseRequiredBigInteger(node, "cost", poolHash);
            String rewardAccount = parseRequiredRewardAccount(requiredAny(node, poolHash,
                    "accountAddress", "rewardAccount"), networkMagic, poolHash);
            Set<String> owners = parseOwners(required(node, "owners", poolHash));
            List<GenesisRelay> relays = parseRelays(required(node, "relays", poolHash));
            JsonNode metadata = requiredAllowingNull(node, "metadata", poolHash);
            String metadataUrl = parseMetadataUrl(metadata);
            String metadataHash = parseMetadataHash(metadata);

            pools.add(new GenesisPool(poolHash, vrf,
                    pledge, cost, marginNum, marginDen, rewardAccount, owners,
                    relays, metadataUrl, metadataHash));
        }

        pools.sort(Comparator.comparing(GenesisPool::poolHash, Comparator.nullsLast(String::compareTo)));
        return List.copyOf(pools);
    }

    private static List<GenesisDelegation> parseDelegations(JsonNode stakeNode) {
        if (stakeNode == null || stakeNode.isMissingNode() || stakeNode.isNull() || !stakeNode.isObject()) {
            return List.of();
        }

        List<GenesisDelegation> delegations = new ArrayList<>();
        var fields = stakeNode.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            delegations.add(new GenesisDelegation(
                    normalizeHex(entry.getKey()),
                    normalizeHex(entry.getValue().asText(null))));
        }
        delegations.sort(Comparator
                .comparingInt(GenesisDelegation::stakeCredentialType)
                .thenComparing(GenesisDelegation::stakeCredentialHash, Comparator.nullsLast(String::compareTo))
                .thenComparing(GenesisDelegation::poolHash, Comparator.nullsLast(String::compareTo)));
        return List.copyOf(delegations);
    }

    private static String parseRewardAccount(JsonNode rewardNode, long networkMagic) {
        if (rewardNode == null || rewardNode.isMissingNode() || rewardNode.isNull()) {
            return null;
        }

        JsonNode credential = rewardNode.path("credential");
        String keyHash = normalizeHex(credential.path("keyHash").asText(null));
        String scriptHash = normalizeHex(credential.path("scriptHash").asText(null));
        if (keyHash != null && scriptHash != null) {
            throw new IllegalArgumentException("reward account credential cannot contain both keyHash and scriptHash");
        }
        int credentialType = keyHash != null ? GenesisDelegation.KEY_HASH : GenesisDelegation.SCRIPT_HASH;
        String hash = keyHash != null ? keyHash : scriptHash;
        if (hash == null) return null;

        int networkId = networkId(rewardNode.path("network").asText(null), networkMagic);
        int addressType = credentialType == GenesisDelegation.KEY_HASH ? 0xE0 : 0xF0;
        byte[] hashBytes = HexUtil.decodeHexString(hash);
        if (hashBytes.length != 28) {
            throw new IllegalArgumentException("reward account credential hash must be 28 bytes");
        }
        byte[] address = new byte[1 + hashBytes.length];
        address[0] = (byte) (addressType | networkId);
        System.arraycopy(hashBytes, 0, address, 1, hashBytes.length);
        return HexUtil.encodeHexString(address);
    }

    private static String parseRequiredRewardAccount(JsonNode rewardNode, long networkMagic, String poolHash) {
        try {
            String rewardAccount = parseRewardAccount(rewardNode, networkMagic);
            if (rewardAccount == null) {
                throw new IllegalArgumentException("missing reward account credential");
            }
            return rewardAccount;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Shelley genesis staking pool " + poolHash
                    + " has invalid accountAddress/rewardAccount", e);
        }
    }

    private static int networkId(String network, long networkMagic) {
        if ("Mainnet".equalsIgnoreCase(network)) return 1;
        if ("Testnet".equalsIgnoreCase(network)) return 0;
        if ("1".equals(network)) return 1;
        if ("0".equals(network)) return 0;
        return networkMagic == 764824073L ? 1 : 0;
    }

    private static Set<String> parseOwners(JsonNode ownersNode) {
        if (ownersNode == null || ownersNode.isMissingNode() || ownersNode.isNull() || !ownersNode.isArray()) {
            return Set.of();
        }
        Set<String> owners = new HashSet<>();
        for (JsonNode owner : ownersNode) {
            String hash = normalizeHex(owner.asText(null));
            if (hash != null) owners.add(hash);
        }
        return Set.copyOf(owners);
    }

    private static List<GenesisRelay> parseRelays(JsonNode relaysNode) {
        if (relaysNode == null || relaysNode.isMissingNode() || relaysNode.isNull() || !relaysNode.isArray()) {
            return List.of();
        }
        List<GenesisRelay> relays = new ArrayList<>();
        for (JsonNode relayNode : relaysNode) {
            if (!relayNode.isObject() || relayNode.size() == 0) continue;
            var fields = relayNode.fields();
            var entry = fields.next();
            String type = entry.getKey();
            JsonNode body = entry.getValue();
            String host = firstText(body, "dnsName", "IPv4", "IPv6");
            Integer port = body.hasNonNull("port") ? body.path("port").asInt() : null;
            relays.add(new GenesisRelay(type, host, port));
        }
        return List.copyOf(relays);
    }

    private static String parseMetadataUrl(JsonNode metadata) {
        if (metadata == null || metadata.isMissingNode() || metadata.isNull()) return null;
        if (metadata.isTextual()) return metadata.asText();
        return metadata.path("url").isMissingNode() ? null : metadata.path("url").asText(null);
    }

    private static String parseMetadataHash(JsonNode metadata) {
        if (metadata == null || metadata.isMissingNode() || metadata.isNull()) return null;
        return normalizeHex(metadata.path("hash").asText(null));
    }

    private static String firstText(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull()) return value.asText(null);
        }
        return null;
    }

    private static BigInteger parseBigInteger(JsonNode root, String field, BigInteger defaultValue) {
        JsonNode node = root.path(field);
        if (node == null || node.isMissingNode() || node.isNull()) return defaultValue;
        try {
            return new BigInteger(node.asText());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static BigInteger parseRequiredBigInteger(JsonNode root, String field, String poolHash) {
        JsonNode node = required(root, field, poolHash);
        try {
            return new BigInteger(node.asText());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Shelley genesis staking pool " + poolHash
                    + " has invalid " + field + ": " + node.asText(), e);
        }
    }

    private static BigDecimal requiredDecimal(JsonNode root, String field, String poolHash) {
        JsonNode node = required(root, field, poolHash);
        try {
            return node.decimalValue();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Shelley genesis staking pool " + poolHash
                    + " has invalid " + field + ": " + node.asText(), e);
        }
    }

    private static String requiredText(JsonNode root, String field, String poolHash) {
        String value = normalizeHex(required(root, field, poolHash).asText(null));
        if (value == null) {
            throw new IllegalArgumentException("Shelley genesis staking pool " + poolHash
                    + " has blank " + field);
        }
        return value;
    }

    private static JsonNode required(JsonNode root, String field, String poolHash) {
        JsonNode node = root.get(field);
        if (node == null || node.isMissingNode() || node.isNull()) {
            throw new IllegalArgumentException("Shelley genesis staking pool " + poolHash
                    + " is missing required field " + field);
        }
        return node;
    }

    private static JsonNode requiredAllowingNull(JsonNode root, String field, String poolHash) {
        if (!root.has(field)) {
            throw new IllegalArgumentException("Shelley genesis staking pool " + poolHash
                    + " is missing required field " + field);
        }
        return root.get(field);
    }

    private static JsonNode requiredAny(JsonNode root, String poolHash, String... fields) {
        for (String field : fields) {
            JsonNode node = root.get(field);
            if (node != null && !node.isMissingNode() && !node.isNull()) {
                return node;
            }
        }
        throw new IllegalArgumentException("Shelley genesis staking pool " + poolHash
                + " is missing required field " + String.join("/", fields));
    }

    private static String normalizeHex(String value) {
        if (value == null || value.isBlank()) return null;
        return value.toLowerCase(Locale.ROOT);
    }

    private static String parseExtraEntropy(JsonNode extraEntropyNode) {
        if (extraEntropyNode == null || extraEntropyNode.isMissingNode() || extraEntropyNode.isNull()) {
            return null;
        }
        String tag = extraEntropyNode.path("tag").asText(null);
        if ("NeutralNonce".equals(tag)) {
            return null;
        }
        JsonNode hash = extraEntropyNode.path("hash");
        if (!hash.isMissingNode() && !hash.isNull()) {
            return hash.asText(null);
        }
        return extraEntropyNode.isTextual() ? extraEntropyNode.asText() : extraEntropyNode.toString();
    }

    private static BigDecimal decimal(JsonNode parent, String field, String defaultValue) {
        JsonNode node = parent.path(field);
        if (node == null || node.isMissingNode() || node.isNull()) {
            return new BigDecimal(defaultValue);
        }
        return node.decimalValue();
    }
}
