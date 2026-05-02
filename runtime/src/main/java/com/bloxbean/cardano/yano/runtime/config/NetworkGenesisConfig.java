package com.bloxbean.cardano.yano.runtime.config;

import com.bloxbean.cardano.yano.runtime.genesis.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.math.BigInteger;
import java.util.Collections;
import java.util.Map;

/**
 * Read-only, immutable network genesis configuration.
 * <p>
 * Parses Byron, Shelley, Alonzo, and Conway genesis files once and exposes
 * all values needed for epoch math, reward calculation, governance, and UTXO accounting.
 * <p>
 * This class does NOT mutate genesis files (unlike {@code blockproducer.GenesisConfig}
 * which has {@code resolveAndPersistGenesisTimestamp()}).
 * <p>
 * Classpath/external resolution is handled by the caller — this class receives resolved file paths.
 */
@Slf4j
@Getter
public class NetworkGenesisConfig {

    private final ShelleyGenesisData shelleyGenesisData;
    private final ByronGenesisData byronGenesisData;
    private final AlonzoGenesisData alonzoGenesisData;
    private final ConwayGenesisData conwayGenesisData;

    private NetworkGenesisConfig(ShelleyGenesisData shelleyData, ByronGenesisData byronData,
                                  AlonzoGenesisData alonzoData, ConwayGenesisData conwayData) {
        this.shelleyGenesisData = shelleyData;
        this.byronGenesisData = byronData;
        this.alonzoGenesisData = alonzoData;
        this.conwayGenesisData = conwayData;
    }

    /**
     * Load and parse genesis files. Shelley genesis is required; others are optional.
     *
     * @param shelleyGenesisPath path to shelley-genesis.json (required)
     * @param byronGenesisPath   path to byron-genesis.json (nullable)
     * @param alonzoGenesisPath  path to alonzo-genesis.json (nullable, reserved for future use)
     * @param conwayGenesisPath  path to conway-genesis.json (nullable)
     * @return immutable NetworkGenesisConfig
     * @throws IllegalArgumentException if shelley genesis path is null/blank or file is unreadable
     */
    public static NetworkGenesisConfig load(String shelleyGenesisPath, String byronGenesisPath,
                                             String alonzoGenesisPath, String conwayGenesisPath) {
        // Shelley genesis is always required
        if (shelleyGenesisPath == null || shelleyGenesisPath.isBlank()) {
            throw new IllegalArgumentException("Shelley genesis file path is required");
        }

        ShelleyGenesisData shelleyData = parseShelleyGenesis(shelleyGenesisPath);
        ByronGenesisData byronData = parseByronGenesis(byronGenesisPath);
        AlonzoGenesisData alonzoData = parseAlonzoGenesis(alonzoGenesisPath);
        ConwayGenesisData conwayData = parseConwayGenesis(conwayGenesisPath);

        // Validate protocol magic consistency
        if (byronData != null && shelleyData.networkMagic() != byronData.protocolMagic()) {
            throw new IllegalArgumentException(
                    "Protocol magic mismatch: shelley=" + shelleyData.networkMagic()
                            + " vs byron=" + byronData.protocolMagic());
        }

        log.info("NetworkGenesisConfig loaded: magic={}, epochLength={}, securityParam={}, " +
                        "byronK={}, alonzo={}, conway={}",
                shelleyData.networkMagic(), shelleyData.epochLength(), shelleyData.securityParam(),
                byronData != null ? byronData.k() : "N/A",
                alonzoData != null ? "available" : "none",
                conwayData != null ? "available" : "none");

        return new NetworkGenesisConfig(shelleyData, byronData, alonzoData, conwayData);
    }

    /**
     * Build from in-memory genesis data (devnet mode — no file I/O).
     *
     * @param shelley Shelley genesis data (required)
     * @param byron   Byron genesis data (nullable)
     * @param conway  Conway genesis data (nullable)
     * @return immutable NetworkGenesisConfig
     */
    public static NetworkGenesisConfig fromInMemory(ShelleyGenesisData shelley, ByronGenesisData byron,
                                                     ConwayGenesisData conway) {
        if (shelley == null) {
            throw new IllegalArgumentException("ShelleyGenesisData is required");
        }
        if (byron != null && shelley.networkMagic() != byron.protocolMagic()) {
            throw new IllegalArgumentException(
                    "Protocol magic mismatch: shelley=" + shelley.networkMagic()
                            + " vs byron=" + byron.protocolMagic());
        }

        log.info("NetworkGenesisConfig built from in-memory: magic={}, epochLength={}, securityParam={}, " +
                        "byronK={}, conway={}",
                shelley.networkMagic(), shelley.epochLength(), shelley.securityParam(),
                byron != null ? byron.k() : "N/A",
                conway != null ? "available" : "none");

        return new NetworkGenesisConfig(shelley, byron, null, conway);
    }

    private static ShelleyGenesisData parseShelleyGenesis(String path) {
        File file = new File(path);
        if (!file.exists() || !file.canRead()) {
            throw new IllegalArgumentException("Shelley genesis file not found or unreadable: " + path);
        }
        try {
            return ShelleyGenesisParser.parse(file);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse shelley genesis from " + path, e);
        }
    }

    private static ByronGenesisData parseByronGenesis(String path) {
        if (path == null || path.isBlank()) return null;
        File file = new File(path);
        if (!file.exists() || !file.canRead()) {
            throw new IllegalArgumentException("Byron genesis file configured but not found or unreadable: " + path);
        }
        try {
            return ByronGenesisParser.parse(file);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse byron genesis from " + path, e);
        }
    }

    private static AlonzoGenesisData parseAlonzoGenesis(String path) {
        if (path == null || path.isBlank()) return null;
        File file = new File(path);
        if (!file.exists() || !file.canRead()) {
            throw new IllegalArgumentException("Alonzo genesis file configured but not found or unreadable: " + path);
        }
        try {
            return AlonzoGenesisParser.parse(file);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse alonzo genesis from " + path, e);
        }
    }

    private static ConwayGenesisData parseConwayGenesis(String path) {
        if (path == null || path.isBlank()) return null;
        File file = new File(path);
        if (!file.exists() || !file.canRead()) {
            throw new IllegalArgumentException(
                    "Conway genesis file configured but not found or unreadable: " + path);
        }
        try {
            return ConwayGenesisParser.parse(file);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse conway genesis from " + path, e);
        }
    }

    // --- Convenience accessors ---

    public long getNetworkMagic() {
        return shelleyGenesisData.networkMagic();
    }

    public long getEpochLength() {
        return shelleyGenesisData.epochLength();
    }

    public long getSecurityParam() {
        return shelleyGenesisData.securityParam();
    }

    public double getActiveSlotsCoeff() {
        return shelleyGenesisData.activeSlotsCoeff();
    }

    public long getMaxLovelaceSupply() {
        return shelleyGenesisData.maxLovelaceSupply();
    }

    /**
     * Byron slots per epoch = k * 10.
     * Falls back to Shelley securityParam * 10 if no Byron genesis available.
     */
    public long getByronSlotsPerEpoch() {
        if (byronGenesisData != null) {
            return byronGenesisData.epochLength();
        }
        // Fallback: use Shelley securityParam (same k applies)
        return shelleyGenesisData.securityParam() * 10;
    }

    /**
     * @return true if Byron genesis is present (network has/had a Byron era)
     */
    public boolean hasByronGenesis() {
        return byronGenesisData != null;
    }

    /**
     * @return true if Conway genesis is present
     */
    public boolean hasConwayGenesis() {
        return conwayGenesisData != null;
    }

    /**
     * @return true if Alonzo genesis is present
     */
    public boolean hasAlonzoGenesis() {
        return alonzoGenesisData != null;
    }

    /**
     * All Byron genesis balances (AVVM + non-AVVM), or empty map if no Byron genesis.
     */
    public Map<String, BigInteger> getAllByronBalances() {
        return byronGenesisData != null
                ? byronGenesisData.getAllByronBalances()
                : Collections.emptyMap();
    }

    /**
     * Shelley initial funds, or empty map.
     */
    public Map<String, BigInteger> getInitialFunds() {
        return shelleyGenesisData.initialFunds() != null
                ? shelleyGenesisData.initialFunds()
                : Collections.emptyMap();
    }
}
