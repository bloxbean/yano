package com.bloxbean.cardano.yano.runtime.config;

import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.runtime.genesis.ConwayGenesisData;
import com.bloxbean.cardano.yano.runtime.genesis.ShelleyGenesisData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Default {@link EpochParamProvider} that reads protocol params from genesis files.
 * <p>
 * Preferred construction: {@link #fromNetworkGenesisConfig(NetworkGenesisConfig, long)}
 * which reads all values from parsed genesis. Legacy constructors are deprecated.
 */
public class DefaultEpochParamProvider implements EpochParamProvider {

    private static final Logger log = LoggerFactory.getLogger(DefaultEpochParamProvider.class);

    // Hardcoded defaults (same across mainnet/preprod/preview) — used only by legacy constructors
    private static final BigInteger DEFAULT_KEY_DEPOSIT = BigInteger.valueOf(2_000_000);
    private static final BigInteger DEFAULT_POOL_DEPOSIT = BigInteger.valueOf(500_000_000);

    private static final long DEFAULT_SECURITY_PARAM = 2160;
    private static final double DEFAULT_ACTIVE_SLOTS_COEFF = 0.05;

    /**
     * Known first non-Byron slot values per network magic.
     * These are historical facts, not derivable from genesis files alone.
     * Only used at config/bootstrap edges.
     */
    private static final long MAINNET_FIRST_NON_BYRON_SLOT = 4492800;   // epoch 208 * 21600
    private static final long PREPROD_FIRST_NON_BYRON_SLOT = 86400;     // epoch 4 * 21600

    private final BigInteger keyDeposit;
    private final BigInteger poolDeposit;
    private final long epochLength;
    private final long byronSlotsPerEpoch;
    private final long shelleyStartSlot;
    private final long securityParam;
    private final double activeSlotsCoeff;

    // Genesis protocol params for reward calculation
    private final int genesisNOpt;
    private final BigDecimal genesisDecentralization;
    private final BigDecimal genesisRho;
    private final BigDecimal genesisTau;
    private final BigDecimal genesisA0;
    private final int genesisProtocolMajor;
    private final int genesisProtocolMinor;
    private final BigInteger genesisMinPoolCost;

    // Conway governance params
    private final int genesisGovActionLifetime;
    private final int genesisDRepActivity;
    private final BigInteger genesisGovActionDeposit;
    private final BigInteger genesisDRepDeposit;
    private final int genesisCommitteeMinSize;
    private final int genesisCommitteeMaxTermLength;

    // --- Factory method (preferred) ---

    /**
     * Create a provider from a parsed {@link NetworkGenesisConfig} and a resolved firstNonByronSlot.
     * <p>
     * This is the preferred production construction path. All values come from genesis — no hardcoded
     * network-specific switches.
     *
     * @param config             parsed genesis config
     * @param firstNonByronSlot  first non-Byron era slot for epoch math (NOT CF shelleyStartEpoch)
     */
    public static DefaultEpochParamProvider fromNetworkGenesisConfig(
            NetworkGenesisConfig config, long firstNonByronSlot) {
        ShelleyGenesisData shelley = config.getShelleyGenesisData();
        ConwayGenesisData conway = config.getConwayGenesisData();

        var provider = new DefaultEpochParamProvider(
                shelley.epochLength(),
                config.getByronSlotsPerEpoch(),
                firstNonByronSlot,
                shelley.securityParam(),
                shelley.activeSlotsCoeff(),
                BigInteger.valueOf(shelley.keyDeposit()),
                BigInteger.valueOf(shelley.poolDeposit()),
                shelley.nOpt(),
                BigDecimal.valueOf(shelley.decentralisationParam()),
                BigDecimal.valueOf(shelley.rho()),
                BigDecimal.valueOf(shelley.tau()),
                BigDecimal.valueOf(shelley.a0()),
                (int) shelley.protocolMajor(),
                (int) shelley.protocolMinor(),
                BigInteger.valueOf(shelley.minPoolCost()),
                conway
        );

        log.info("DefaultEpochParamProvider built from genesis: epochLength={}, byronSlotsPerEpoch={}, " +
                        "firstNonByronSlot={}, securityParam={}, nOpt={}, conway={}",
                shelley.epochLength(), config.getByronSlotsPerEpoch(), firstNonByronSlot,
                shelley.securityParam(), shelley.nOpt(), conway != null ? "available" : "none");

        return provider;
    }

    /**
     * Resolve the first non-Byron slot for a given network.
     * <p>
     * Order: known-network constants → no Byron genesis means 0 → unknown + Byron = error.
     * <p>
     * TODO (Phase 6): Add persisted era metadata lookup for restart/custom network support.
     * Currently only uses known-network constants, which covers all production networks.
     *
     * @param protocolMagic   network magic
     * @param hasByronGenesis whether a Byron genesis file is configured
     * @return first non-Byron slot
     * @throws IllegalStateException if unknown network has Byron genesis but no persisted metadata
     */
    public static long resolveFirstNonByronSlot(long protocolMagic, boolean hasByronGenesis) {
        // Known networks
        return switch ((int) protocolMagic) {
            case 764824073 -> MAINNET_FIRST_NON_BYRON_SLOT;
            case 1 -> PREPROD_FIRST_NON_BYRON_SLOT;
            case 2, 4 -> 0; // preview, sanchonet: no Byron era for epoch math
            default -> {
                if (!hasByronGenesis) {
                    yield 0; // No Byron genesis → assume no Byron era
                }
                throw new IllegalStateException(
                        "Unknown network (magic=" + protocolMagic + ") with Byron genesis configured. "
                                + "Cannot determine first non-Byron slot. Either sync from genesis "
                                + "or provide explicit first-non-byron-slot configuration.");
            }
        };
    }

    // --- Full constructor (internal) ---

    private DefaultEpochParamProvider(long epochLength, long byronSlotsPerEpoch, long shelleyStartSlot,
                                      long securityParam, double activeSlotsCoeff,
                                      BigInteger keyDeposit, BigInteger poolDeposit,
                                      int nOpt, BigDecimal decentralization,
                                      BigDecimal rho, BigDecimal tau, BigDecimal a0,
                                      int protoMajor, int protoMinor, BigInteger minPoolCost,
                                      ConwayGenesisData conway) {
        this.epochLength = epochLength;
        this.byronSlotsPerEpoch = byronSlotsPerEpoch;
        this.shelleyStartSlot = shelleyStartSlot;
        this.securityParam = securityParam;
        this.activeSlotsCoeff = activeSlotsCoeff;
        this.keyDeposit = keyDeposit;
        this.poolDeposit = poolDeposit;
        this.genesisNOpt = nOpt;
        this.genesisDecentralization = decentralization;
        this.genesisRho = rho;
        this.genesisTau = tau;
        this.genesisA0 = a0;
        this.genesisProtocolMajor = protoMajor;
        this.genesisProtocolMinor = protoMinor;
        this.genesisMinPoolCost = minPoolCost;

        // Conway params from genesis or defaults
        if (conway != null) {
            this.genesisGovActionLifetime = conway.govActionLifetime();
            this.genesisDRepActivity = conway.dRepActivity();
            this.genesisGovActionDeposit = conway.govActionDeposit();
            this.genesisDRepDeposit = conway.dRepDeposit();
            this.genesisCommitteeMinSize = conway.committeeMinSize();
            this.genesisCommitteeMaxTermLength = conway.committeeMaxTermLength();
        } else {
            // Interface defaults
            this.genesisGovActionLifetime = 6;
            this.genesisDRepActivity = 20;
            this.genesisGovActionDeposit = new BigInteger("100000000000");
            this.genesisDRepDeposit = new BigInteger("500000000");
            this.genesisCommitteeMinSize = 7;
            this.genesisCommitteeMaxTermLength = 146;
        }
    }

    // --- Legacy constructors (deprecated — use fromNetworkGenesisConfig) ---

    /** @deprecated Use {@link #fromNetworkGenesisConfig(NetworkGenesisConfig, long)} */
    @Deprecated
    public DefaultEpochParamProvider() {
        this(null);
    }

    /** @deprecated Use {@link #fromNetworkGenesisConfig(NetworkGenesisConfig, long)} */
    @Deprecated
    public DefaultEpochParamProvider(String protocolParamJsonPath) {
        this(protocolParamJsonPath, null, 432000, 21600, 0);
    }

    /** @deprecated Use {@link #fromNetworkGenesisConfig(NetworkGenesisConfig, long)} */
    @Deprecated
    public DefaultEpochParamProvider(String protocolParamJsonPath, String shelleyGenesisJsonPath) {
        this(protocolParamJsonPath, shelleyGenesisJsonPath, 432000, 21600,
                deriveShelleyStartSlot(shelleyGenesisJsonPath));
    }

    /**
     * Derive shelleyStartSlot from the network magic in the shelley genesis file.
     * @deprecated Only used by legacy constructors.
     */
    @Deprecated
    private static long deriveShelleyStartSlot(String shelleyGenesisJsonPath) {
        if (shelleyGenesisJsonPath == null) return 0;
        try {
            Path path = Path.of(shelleyGenesisJsonPath);
            if (!Files.exists(path)) return 0;
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(path.toFile());
            JsonNode magicNode = root.get("networkMagic");
            if (magicNode == null) return 0;
            long magic = magicNode.asLong();
            return switch ((int) magic) {
                case 764824073 -> 4492800;
                case 1 -> 86400;
                case 2, 4 -> 0;
                default -> 0;
            };
        } catch (Exception e) {
            return 0;
        }
    }

    /** @deprecated Use {@link #fromNetworkGenesisConfig(NetworkGenesisConfig, long)} */
    @Deprecated
    public DefaultEpochParamProvider(String protocolParamJsonPath,
                                     long epochLength, long byronSlotsPerEpoch, long shelleyStartSlot) {
        this(protocolParamJsonPath, null, epochLength, byronSlotsPerEpoch, shelleyStartSlot);
    }

    /** @deprecated Use {@link #fromNetworkGenesisConfig(NetworkGenesisConfig, long)} */
    @Deprecated
    public DefaultEpochParamProvider(String protocolParamJsonPath, String shelleyGenesisJsonPath,
                                     long epochLength, long byronSlotsPerEpoch, long shelleyStartSlot) {
        this.epochLength = epochLength;
        this.byronSlotsPerEpoch = byronSlotsPerEpoch;
        this.shelleyStartSlot = shelleyStartSlot;

        BigInteger parsedKeyDeposit = null;
        BigInteger parsedPoolDeposit = null;

        if (protocolParamJsonPath != null) {
            try {
                Path path = Path.of(protocolParamJsonPath);
                if (Files.exists(path)) {
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode root = mapper.readTree(path.toFile());
                    parsedKeyDeposit = readBigInteger(root,
                            "key_deposit", "keyDeposit", "stakeAddressDeposit");
                    parsedPoolDeposit = readBigInteger(root,
                            "pool_deposit", "poolDeposit", "stakePoolDeposit");

                    if (parsedKeyDeposit != null || parsedPoolDeposit != null) {
                        log.info("Protocol params loaded from {}: keyDeposit={}, poolDeposit={}",
                                protocolParamJsonPath,
                                parsedKeyDeposit != null ? parsedKeyDeposit : "default",
                                parsedPoolDeposit != null ? parsedPoolDeposit : "default");
                    }
                } else {
                    log.debug("Protocol param file not found: {}", protocolParamJsonPath);
                }
            } catch (Exception e) {
                log.warn("Failed to parse protocol param file {}: {}", protocolParamJsonPath, e.getMessage());
            }
        }

        this.keyDeposit = parsedKeyDeposit != null ? parsedKeyDeposit : DEFAULT_KEY_DEPOSIT;
        this.poolDeposit = parsedPoolDeposit != null ? parsedPoolDeposit : DEFAULT_POOL_DEPOSIT;

        // Parse shelley-genesis.json for infrastructure params AND genesis protocol params
        long parsedSecurityParam = DEFAULT_SECURITY_PARAM;
        double parsedActiveSlotsCoeff = DEFAULT_ACTIVE_SLOTS_COEFF;
        int parsedNOpt = 500;
        BigDecimal parsedDecentralization = BigDecimal.ZERO;
        BigDecimal parsedRho = new BigDecimal("0.003");
        BigDecimal parsedTau = new BigDecimal("0.2");
        BigDecimal parsedA0 = new BigDecimal("0.3");
        int parsedProtoMajor = 9;
        int parsedProtoMinor = 0;
        BigInteger parsedMinPoolCost = new BigInteger("170000000");

        if (shelleyGenesisJsonPath != null) {
            try {
                Path path = Path.of(shelleyGenesisJsonPath);
                if (Files.exists(path)) {
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode root = mapper.readTree(path.toFile());
                    JsonNode secNode = root.get("securityParam");
                    if (secNode != null && !secNode.isNull()) {
                        parsedSecurityParam = secNode.asLong(DEFAULT_SECURITY_PARAM);
                    }
                    JsonNode ascNode = root.get("activeSlotsCoeff");
                    if (ascNode != null && !ascNode.isNull()) {
                        parsedActiveSlotsCoeff = ascNode.asDouble(DEFAULT_ACTIVE_SLOTS_COEFF);
                    }

                    // Genesis protocol params (under "protocolParams" object)
                    JsonNode pp = root.get("protocolParams");
                    if (pp != null) {
                        JsonNode n = pp.get("nOpt");
                        if (n != null && !n.isNull()) parsedNOpt = n.asInt(500);
                        JsonNode d = pp.get("decentralisationParam");
                        if (d != null && !d.isNull()) parsedDecentralization = new BigDecimal(d.asText());
                        JsonNode r = pp.get("rho");
                        if (r != null && !r.isNull()) parsedRho = new BigDecimal(r.asText());
                        JsonNode t = pp.get("tau");
                        if (t != null && !t.isNull()) parsedTau = new BigDecimal(t.asText());
                        JsonNode a = pp.get("a0");
                        if (a != null && !a.isNull()) parsedA0 = new BigDecimal(a.asText());
                        JsonNode pv = pp.get("protocolVersion");
                        if (pv != null && !pv.isNull()) {
                            JsonNode maj = pv.get("major");
                            JsonNode min = pv.get("minor");
                            if (maj != null) parsedProtoMajor = maj.asInt(9);
                            if (min != null) parsedProtoMinor = min.asInt(0);
                        }
                        JsonNode mpc = pp.get("minPoolCost");
                        if (mpc != null && !mpc.isNull()) parsedMinPoolCost = new BigInteger(mpc.asText());
                    }

                    log.info("Shelley genesis params loaded from {}: securityParam={}, activeSlotsCoeff={}, " +
                                    "nOpt={}, d={}, rho={}, tau={}, a0={}",
                            shelleyGenesisJsonPath, parsedSecurityParam, parsedActiveSlotsCoeff,
                            parsedNOpt, parsedDecentralization, parsedRho, parsedTau, parsedA0);
                } else {
                    log.debug("Shelley genesis file not found: {}", shelleyGenesisJsonPath);
                }
            } catch (Exception e) {
                log.warn("Failed to parse shelley genesis file {}: {}", shelleyGenesisJsonPath, e.getMessage());
            }
        }
        this.securityParam = parsedSecurityParam;
        this.activeSlotsCoeff = parsedActiveSlotsCoeff;
        this.genesisNOpt = parsedNOpt;
        this.genesisDecentralization = parsedDecentralization;
        this.genesisRho = parsedRho;
        this.genesisTau = parsedTau;
        this.genesisA0 = parsedA0;
        this.genesisProtocolMajor = parsedProtoMajor;
        this.genesisProtocolMinor = parsedProtoMinor;
        this.genesisMinPoolCost = parsedMinPoolCost;

        // Conway defaults for legacy constructors (overridden by fromNetworkGenesisConfig)
        this.genesisGovActionLifetime = 6;
        this.genesisDRepActivity = 20;
        this.genesisGovActionDeposit = new BigInteger("100000000000");
        this.genesisDRepDeposit = new BigInteger("500000000");
        this.genesisCommitteeMinSize = 7;
        this.genesisCommitteeMaxTermLength = 146;
    }

    @Override
    public BigInteger getKeyDeposit(long epoch) {
        return keyDeposit;
    }

    @Override
    public BigInteger getPoolDeposit(long epoch) {
        return poolDeposit;
    }

    @Override
    public long getEpochLength() {
        return epochLength;
    }

    @Override
    public long getByronSlotsPerEpoch() {
        return byronSlotsPerEpoch;
    }

    @Override
    public long getShelleyStartSlot() {
        return shelleyStartSlot;
    }

    @Override
    public long getSecurityParam() {
        return securityParam;
    }

    @Override
    public double getActiveSlotsCoeff() {
        return activeSlotsCoeff;
    }

    @Override
    public int getNOpt(long epoch) {
        return genesisNOpt;
    }

    @Override
    public BigDecimal getDecentralization(long epoch) {
        return genesisDecentralization;
    }

    @Override
    public BigDecimal getRho(long epoch) {
        return genesisRho;
    }

    @Override
    public BigDecimal getTau(long epoch) {
        return genesisTau;
    }

    @Override
    public BigDecimal getA0(long epoch) {
        return genesisA0;
    }

    @Override
    public int getProtocolMajor(long epoch) {
        return genesisProtocolMajor;
    }

    @Override
    public int getProtocolMinor(long epoch) {
        return genesisProtocolMinor;
    }

    @Override
    public BigInteger getMinPoolCost(long epoch) {
        return genesisMinPoolCost;
    }

    // --- Conway governance parameter overrides ---

    @Override
    public int getGovActionLifetime(long epoch) {
        return genesisGovActionLifetime;
    }

    @Override
    public int getDRepActivity(long epoch) {
        return genesisDRepActivity;
    }

    @Override
    public BigInteger getGovActionDeposit(long epoch) {
        return genesisGovActionDeposit;
    }

    @Override
    public BigInteger getDRepDeposit(long epoch) {
        return genesisDRepDeposit;
    }

    @Override
    public int getCommitteeMinSize(long epoch) {
        return genesisCommitteeMinSize;
    }

    @Override
    public int getCommitteeMaxTermLength(long epoch) {
        return genesisCommitteeMaxTermLength;
    }

    /**
     * Try multiple field names; supports both flat format (key_deposit) and Cardano API format (stakeAddressDeposit).
     */
    private static BigInteger readBigInteger(JsonNode root, String... fieldNames) {
        for (String name : fieldNames) {
            JsonNode node = root.get(name);
            if (node != null && !node.isNull()) {
                try {
                    if (node.isTextual()) {
                        return new BigInteger(node.asText());
                    } else if (node.isNumber()) {
                        return BigInteger.valueOf(node.asLong());
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }
}
