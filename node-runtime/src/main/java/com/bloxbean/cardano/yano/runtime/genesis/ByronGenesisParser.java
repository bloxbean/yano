package com.bloxbean.cardano.yano.runtime.genesis;

import com.bloxbean.cardano.client.crypto.Base58;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lightweight parser for standard Cardano byron-genesis.json files.
 * Extracts both nonAvvmBalances and avvmDistr (converting AVVM keys to Byron base58 addresses).
 */
@Slf4j
public class ByronGenesisParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ByronGenesisData parse(File file) throws IOException {
        JsonNode root = MAPPER.readTree(file);
        return parseRoot(root);
    }

    public static ByronGenesisData parse(InputStream in) throws IOException {
        JsonNode root = MAPPER.readTree(in);
        return parseRoot(root);
    }

    private static ByronGenesisData parseRoot(JsonNode root) {
        Map<String, BigInteger> nonAvvmBalances = parseNonAvvmBalances(root.get("nonAvvmBalances"));
        Map<String, BigInteger> avvmBalances = parseAvvmBalances(root.get("avvmDistr"));
        long startTime = root.path("startTime").asLong(0);

        // Extract k from protocolConsts
        long k = root.path("protocolConsts").path("k").asLong(0);

        // Extract protocolMagic from protocolConsts
        long protocolMagic = 0;
        JsonNode protoConsts = root.get("protocolConsts");
        if (protoConsts != null && protoConsts.has("protocolMagic")) {
            protocolMagic = protoConsts.get("protocolMagic").asLong(0);
        }

        // Extract slot duration from blockVersionData.slotDuration (in milliseconds)
        long slotDurationMs = root.path("blockVersionData").path("slotDuration").asLong(0);
        long slotDuration = slotDurationMs > 0 ? slotDurationMs / 1000 : 0;

        log.info("Parsed byron genesis: nonAvvmBalances={} entries, avvmBalances={} entries, startTime={}, protocolMagic={}, slotDuration={}s, k={}",
                nonAvvmBalances.size(), avvmBalances.size(), startTime, protocolMagic, slotDuration, k);

        return new ByronGenesisData(nonAvvmBalances, avvmBalances, startTime, protocolMagic, slotDuration, k);
    }

    private static Map<String, BigInteger> parseNonAvvmBalances(JsonNode balancesNode) {
        if (balancesNode == null || balancesNode.isNull() || !balancesNode.isObject()) {
            return Collections.emptyMap();
        }

        Map<String, BigInteger> balances = new LinkedHashMap<>();
        var fields = balancesNode.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            BigInteger amount = new BigInteger(entry.getValue().asText("0"));
            if (amount.compareTo(BigInteger.ZERO) > 0) {
                balances.put(entry.getKey(), amount);
            }
        }
        return Collections.unmodifiableMap(balances);
    }

    /**
     * Parse avvmDistr section: converts AVVM base64url public keys to Byron base58 addresses.
     * The genesis tx hash for each entry is blake2b_256(Base58.decode(byronAddress)).
     */
    private static Map<String, BigInteger> parseAvvmBalances(JsonNode avvmNode) {
        if (avvmNode == null || avvmNode.isNull() || !avvmNode.isObject()) {
            return Collections.emptyMap();
        }

        Map<String, BigInteger> balances = new LinkedHashMap<>();
        var fields = avvmNode.fields();
        int skipped = 0;
        while (fields.hasNext()) {
            var entry = fields.next();
            String avvmKey = entry.getKey();
            BigInteger amount = new BigInteger(entry.getValue().asText("0"));
            if (amount.compareTo(BigInteger.ZERO) <= 0) continue;

            var byronAddr = AvvmAddressConverter.convertAvvmToByronAddress(avvmKey);
            if (byronAddr.isPresent()) {
                balances.put(byronAddr.get(), amount);
            } else {
                skipped++;
                log.warn("Failed to convert AVVM address, skipping: {}", avvmKey);
            }
        }
        if (skipped > 0) {
            log.warn("Skipped {} AVVM entries due to conversion failures", skipped);
        }
        return Collections.unmodifiableMap(balances);
    }

    /**
     * Compute the genesis tx hash for a Byron address (used for genesis UTXO tracking).
     * Follows the Cardano convention: txHash = blake2b_256(Base58.decode(byronAddress)).
     */
    public static String genesisUtxoTxHash(String byronBase58Address) {
        byte[] decoded = Base58.decode(byronBase58Address);
        byte[] hash = Blake2bUtil.blake2bHash256(decoded);
        return HexUtil.encodeHexString(hash);
    }
}
