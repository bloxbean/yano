package com.bloxbean.cardano.yano.runtime.config;

import com.bloxbean.cardano.yano.api.NetworkGenesisValues;
import com.bloxbean.cardano.yano.runtime.genesis.ShelleyGenesisData;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Factory that builds {@link NetworkGenesisValues} by combining:
 * <ul>
 *   <li>Parsed genesis config ({@link NetworkGenesisConfig})</li>
 *   <li>Known-network constants (hardfork epochs, initial treasury for preview)</li>
 *   <li>Persisted era metadata for custom-network hardfork boundaries</li>
 * </ul>
 * <p>
 * This is the ONLY place that combines these sources. {@code NetworkGenesisConfig}
 * remains pure parse output; this factory produces the derived values.
 */
@Slf4j
public class NetworkGenesisValuesFactory {

    /**
     * Optional overrides for custom networks — null fields use known-network constants or defaults.
     */
    public record Overrides(
            BigInteger initialUtxo,
            Integer shelleyStartEpoch,
            Integer allegraHardforkEpoch,
            Integer vasilHardforkEpoch
    ) {
        public static final Overrides NONE = new Overrides(null, null, null, null);
    }

    /**
     * Build with no overrides.
     */
    public static NetworkGenesisValues build(NetworkGenesisConfig genesis) {
        return build(genesis, Overrides.NONE);
    }

    /**
     * Build with optional initial UTXO override (backward compat).
     */
    public static NetworkGenesisValues build(NetworkGenesisConfig genesis, BigInteger overrideInitialUtxo) {
        return build(genesis, new Overrides(overrideInitialUtxo, null, null, null));
    }

    /**
     * Build with full overrides for custom networks.
     *
     * @param genesis   parsed genesis config
     * @param overrides optional overrides for initial UTXO and hardfork epochs
     */
    public static NetworkGenesisValues build(NetworkGenesisConfig genesis, Overrides overrides) {
        ShelleyGenesisData shelley = genesis.getShelleyGenesisData();
        int magic = (int) shelley.networkMagic();

        // Hardfork epochs — known-network constants or overrides from era metadata
        int shelleyStartEpoch = resolveWithOverride(
                overrides.shelleyStartEpoch(), resolveShelleyStartEpoch(magic), magic);
        int allegraHardforkEpoch = resolveWithOverride(
                overrides.allegraHardforkEpoch(), resolveAllegraHardforkEpoch(magic), magic);
        int vasilHardforkEpoch = resolveWithOverride(
                overrides.vasilHardforkEpoch(), resolveVasilHardforkEpoch(magic), magic);

        // Unknown+Byron without shelleyStartEpoch override → fail-safe
        if (!KNOWN_MAGICS.contains(magic) && genesis.hasByronGenesis()
                && !genesis.getAllByronBalances().isEmpty()
                && overrides.shelleyStartEpoch() == null) {
            shelleyStartEpoch = ERA_NOT_REACHED;
        }

        // Initial UTXO/reserves/treasury.
        BigInteger shelleyInitialUtxo = computeInitialUtxo(genesis, magic, overrides.initialUtxo());
        BigInteger shelleyInitialTreasury = resolveKnownInitialTreasury(magic);
        BigInteger totalLovelace = BigInteger.valueOf(shelley.maxLovelaceSupply());
        // CF formula: reserves = totalLovelace - initialUtxo (treasury NOT subtracted)
        BigInteger shelleyInitialReserves = totalLovelace.subtract(shelleyInitialUtxo);

        // Bootstrap address amount (mainnet historical constant; 0 for testnets)
        BigInteger bootstrapAmount = resolveBootstrapAddressAmount(magic);

        var values = new Impl(
                magic,
                shelley.maxLovelaceSupply(),
                shelley.epochLength(),
                (int) shelley.securityParam(),
                shelley.activeSlotsCoeff(),
                shelleyInitialReserves,
                shelleyInitialTreasury,
                shelleyInitialUtxo,
                shelleyStartEpoch,
                allegraHardforkEpoch,
                vasilHardforkEpoch,
                bootstrapAmount,
                BigInteger.valueOf(shelley.poolDeposit()),
                shelley.decentralisationParam(),
                shelley.rho(),
                shelley.tau(),
                shelley.a0(),
                shelley.nOpt(),
                // CF NetworkConfig compatibility value. Reward calculations use protocol-param a0,
                // not this field.
                new BigDecimal("0.03")
        );

        log.info("NetworkGenesisValues built: magic={}, epochLength={}, shelleyStartEpoch={}, " +
                        "initialReserves={}, initialTreasury={}, initialUtxo={}, " +
                        "allegraEpoch={}, vasilEpoch={}, bootstrapAmount={}",
                magic, shelley.epochLength(), shelleyStartEpoch,
                shelleyInitialReserves, shelleyInitialTreasury, shelleyInitialUtxo,
                allegraHardforkEpoch, vasilHardforkEpoch, bootstrapAmount);

        return values;
    }

    // --- Known-network constants ---

    private static final java.util.Set<Integer> KNOWN_MAGICS = java.util.Set.of(764824073, 1, 2);

    /**
     * For known networks, always use the constant (override ignored).
     * For unknown networks, prefer override if provided, else use default.
     */
    private static int resolveWithOverride(Integer override, int knownOrDefault, int magic) {
        if (KNOWN_MAGICS.contains(magic)) return knownOrDefault; // known → constant
        if (override != null) return override;                   // unknown + override
        return knownOrDefault;                                   // unknown + no override → default (0)
    }

    /**
     * For unknown/custom networks, Integer.MAX_VALUE means "era not reached / unknown".
     * Consumers check: epoch >= allegraHardforkEpoch — MAX_VALUE means "never triggers".
     */
    private static final int ERA_NOT_REACHED = Integer.MAX_VALUE;

    private static int resolveShelleyStartEpoch(int magic) {
        return switch (magic) {
            case 764824073 -> 208;         // mainnet
            case 1 -> 4;                   // preprod
            case 2 -> 1;                   // preview
            default -> 0;                  // custom devnet without Byron: Shelley from epoch 0
        };
    }

    private static int resolveAllegraHardforkEpoch(int magic) {
        return switch (magic) {
            case 764824073 -> 236;         // mainnet
            case 1 -> 5;                   // preprod
            case 2 -> 1;                   // preview
            default -> ERA_NOT_REACHED;    // custom: must derive from era metadata
        };
    }

    private static int resolveVasilHardforkEpoch(int magic) {
        return switch (magic) {
            case 764824073 -> 365;  // mainnet
            case 1 -> 12;           // preprod
            case 2 -> 3;            // preview
            default -> ERA_NOT_REACHED; // custom: must derive from era metadata
        };
    }

    /**
     * Compute initial UTXO at Shelley start.
     * <p>
     * For known public networks: use validated CF constants.
     * For custom no-Byron networks: sum genesis distributions.
     * For custom Byron-history networks: use persisted boundary override, or throw.
     */
    private static BigInteger computeInitialUtxo(NetworkGenesisConfig genesis, int magic,
                                                  BigInteger overrideInitialUtxo) {
        // Known networks: use CF constants (these account for Byron-era UTXO changes)
        return switch (magic) {
            case 764824073 -> new BigInteger("31111977147073356"); // mainnet
            case 1 -> new BigInteger("30000000000000000");          // preprod
            case 2 -> new BigInteger("30009000000000000");          // preview
            default -> {
                // Custom network
                if (!genesis.hasByronGenesis() || genesis.getAllByronBalances().isEmpty()) {
                    // No Byron history: sum genesis distributions
                    BigInteger total = BigInteger.ZERO;
                    for (var amt : genesis.getInitialFunds().values()) {
                        total = total.add(amt);
                    }
                    for (var amt : genesis.getAllByronBalances().values()) {
                        total = total.add(amt);
                    }
                    yield total;
                }

                // Custom + Byron history: must have persisted boundary override
                if (overrideInitialUtxo != null) {
                    log.info("Using persisted Shelley-start UTXO total for custom Byron network: {}",
                            overrideInitialUtxo);
                    yield overrideInitialUtxo;
                }

                throw new IllegalStateException(
                        "Custom network (magic=" + magic + ") with Byron history: " +
                        "Shelley-start UTXO total not available. " +
                        "Sync from genesis to capture boundary state, or provide explicit override.");
            }
        };
    }

    private static BigInteger resolveKnownInitialTreasury(int magic) {
        return switch (magic) {
            case 764824073, 1 -> BigInteger.ZERO;                       // mainnet, preprod
            case 2 -> new BigInteger("9000000000000");                  // preview
            default -> BigInteger.ZERO;                                  // custom devnet
        };
    }

    private static BigInteger resolveBootstrapAddressAmount(int magic) {
        return switch (magic) {
            case 764824073 -> new BigInteger("318200635000000"); // mainnet
            default -> BigInteger.ZERO;                           // all testnets/devnets
        };
    }

    // --- Impl record ---

    private record Impl(
            int networkMagic,
            long totalLovelaceLong,
            long expectedSlotsPerEpoch,
            int securityParam,
            double activeSlotsCoeff,
            BigInteger shelleyInitialReserves,
            BigInteger shelleyInitialTreasury,
            BigInteger shelleyInitialUtxo,
            int shelleyStartEpoch,
            int allegraHardforkEpoch,
            int vasilHardforkEpoch,
            BigInteger bootstrapAddressAmount,
            BigInteger poolDeposit,
            BigDecimal decentralisationParam,
            BigDecimal monetaryExpansion,
            BigDecimal treasuryGrowth,
            BigDecimal poolPledgeInfluence,
            int optimalPoolCount,
            BigDecimal cfPoolOwnerInfluence
    ) implements NetworkGenesisValues {
        @Override public long totalLovelace() { return totalLovelaceLong; }
    }
}
