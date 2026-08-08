package com.bloxbean.cardano.yano.appchain.eutxo.demo;

import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * ADR-UTXO-009: the record of WHICH one-shot seeds a public-network
 * settlement identity was built from.
 *
 * <p>On devnet the identity is a pure function of a known genesis UTxO, so a
 * re-run recomputes it and {@code bootstrapped()} short-circuits. On a public
 * network the operator's seed UTxOs are arbitrary and are SPENT by the mints
 * — so a naive re-run after a partial failure would select different UTxOs,
 * derive a different identity, and deploy a second one alongside the first.
 * Persisting the chosen outpoints on first use makes the deploy resumable and
 * gives {@code chain add} the addresses it must splice into the config.
 *
 * <p>Holds no secrets: only outpoints and derived public addresses.
 */
public record SettlementDeploymentRecord(
        String chainId,
        String network,
        String profileId,
        EutxoOutpoint rootSeed,
        EutxoOutpoint shardSeed,
        String operatorAddress,
        String vaultAddress,
        String shardAddress,
        String rootAddress
) {
    private static final String FILE_PREFIX = "settlement-deployment-";

    /** The record's location for {@code chainId}, beside the operator key. */
    public static Path path(Path directory, String chainId) {
        return directory.resolve(FILE_PREFIX + chainId + ".properties");
    }

    public static Optional<SettlementDeploymentRecord> load(
            Path directory, String chainId) {
        Path file = path(directory, chainId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "cannot read settlement deployment record: " + file, failure);
        }
        return Optional.of(new SettlementDeploymentRecord(
                require(properties, "chain-id", file),
                require(properties, "network", file),
                require(properties, "profile-id", file),
                outpoint(require(properties, "root-seed", file), file),
                outpoint(require(properties, "shard-seed", file), file),
                require(properties, "operator-address", file),
                require(properties, "vault-address", file),
                require(properties, "shard-address", file),
                require(properties, "root-address", file)));
    }

    public void save(Path directory) {
        Properties properties = new Properties();
        properties.setProperty("chain-id", chainId);
        properties.setProperty("network", network);
        properties.setProperty("profile-id", profileId);
        properties.setProperty("root-seed", rootSeed.toString());
        properties.setProperty("shard-seed", shardSeed.toString());
        properties.setProperty("operator-address", operatorAddress);
        properties.setProperty("vault-address", vaultAddress);
        properties.setProperty("shard-address", shardAddress);
        properties.setProperty("root-address", rootAddress);
        Path file = path(directory, chainId);
        try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            properties.store(writer, "Yano settlement deployment (no secrets); "
                    + "delete only to deploy a NEW identity");
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "cannot write settlement deployment record: " + file, failure);
        }
    }

    /** Guard against a record written for a different chain or network. */
    public void requireMatches(String expectedChainId, String expectedNetwork) {
        if (!chainId.equals(expectedChainId) || !network.equals(expectedNetwork)) {
            throw new IllegalStateException("settlement deployment record is for"
                    + " chain '" + chainId + "' on '" + network + "', not '"
                    + expectedChainId + "' on '" + expectedNetwork + "'");
        }
    }

    private static String require(Properties properties, String key, Path file) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "settlement deployment record " + file + " is missing '"
                            + key + "'");
        }
        return value.trim();
    }

    private static EutxoOutpoint outpoint(String value, Path file) {
        int separator = value.lastIndexOf('#');
        if (separator <= 0) {
            throw new IllegalStateException("settlement deployment record "
                    + file + " has a malformed outpoint: " + value);
        }
        try {
            return new EutxoOutpoint(value.substring(0, separator),
                    Integer.parseInt(value.substring(separator + 1)));
        } catch (RuntimeException failure) {
            throw new IllegalStateException("settlement deployment record "
                    + file + " has a malformed outpoint: " + value, failure);
        }
    }
}
