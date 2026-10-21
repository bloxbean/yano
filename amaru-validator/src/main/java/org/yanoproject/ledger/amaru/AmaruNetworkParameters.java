package org.yanoproject.ledger.amaru;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/**
 * The network facts an Amaru request carries besides ledger state (INTERFACE.md request keys 3–5): the
 * network magic, the era history and Amaru's {@code GlobalParameters}. They are fixed for a running node,
 * so the engine takes them at construction.
 *
 * <p><b>Populating it from Yano configuration (step 1d).</b> Everything comes from the genesis files and
 * the network's hard-fork history, which Yano already loads:</p>
 * <ul>
 *   <li>{@code networkMagic}: {@code yaci.node.remote.protocol-magic} / the Shelley genesis
 *       {@code networkMagic}.</li>
 *   <li>{@link GlobalParameters}: Shelley genesis {@code securityParam} (k), {@code activeSlotsCoeff}
 *       (as its inverse, e.g. 0.05 → 20), {@code maxLovelaceSupply}, {@code slotsPerKESPeriod},
 *       {@code maxKESEvolutions}, {@code systemStart} (as POSIX milliseconds), and
 *       {@code epochLengthScaleFactor = epochLength / (k × activeSlotsCoeffInverse)} (10 on the public
 *       networks).</li>
 *   <li>{@code stabilityWindow}: {@code 3k/f} (Amaru's {@code GlobalParameters::stability_window};
 *       129600 on mainnet and preprod).</li>
 *   <li>{@link EraSummary eras}: one entry per era from Byron (or the first era the network starts in)
 *       to Conway, with the start/end bounds (relative time, slot, epoch) of each hard fork, the epoch
 *       length and slot length in force. The public networks' boundaries are fixed (mainnet: Byron
 *       21600-slot epochs of 20 s until epoch 208, then 432000-slot epochs of 1 s). A devnet that starts in
 *       Conway has a single open-ended era: start {@code [0, 0, 0]}, its {@code epochLength} and
 *       {@code slotLength}. The last era must be open-ended (Amaru's forecast horizon is the stability
 *       window past the tip).</li>
 * </ul>
 *
 * @param networkMagic    764824073 mainnet, 1 preprod, 2 preview, anything else a testnet
 * @param stabilityWindow {@code 3k/f} slots
 * @param eras            the era history, oldest first
 * @param global          Amaru's global parameters
 */
public record AmaruNetworkParameters(long networkMagic, long stabilityWindow, List<EraSummary> eras,
                                     GlobalParameters global) {

    public AmaruNetworkParameters {
        eras = List.copyOf(Objects.requireNonNull(eras, "eras"));
        if (eras.isEmpty()) {
            throw new IllegalArgumentException("the era history needs at least one era");
        }
        Objects.requireNonNull(global, "global");
        if (networkMagic < 0 || networkMagic > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException("network magic is not a u32: " + networkMagic);
        }
    }

    /**
     * A single-era Conway network from slot 0, as a Yano devnet runs.
     *
     * @param epochLength slots per epoch
     * @param slotLengthMs slot length in milliseconds
     */
    public static AmaruNetworkParameters singleConwayEra(long networkMagic, long epochLength, long slotLengthMs,
                                                         GlobalParameters global) {
        return new AmaruNetworkParameters(networkMagic,
                3 * global.securityParam() * global.activeSlotCoeffInverse(),
                List.of(new EraSummary(new EraBound(0, 0, 0), null, epochLength, slotLengthMs, EraSummary.CONWAY)),
                global);
    }

    /**
     * @param start          the era's first slot
     * @param end            the era's end, or null when it is open-ended
     * @param epochSizeSlots slots per epoch
     * @param slotLengthMs   slot length in milliseconds
     * @param eraTag         Amaru's {@code EraName}: 1 Byron, 2 Shelley, 3 Allegra, 4 Mary, 5 Alonzo,
     *                       6 Babbage, 7 Conway, 8 Dijkstra
     */
    public record EraSummary(EraBound start, EraBound end, long epochSizeSlots, long slotLengthMs, int eraTag) {
        public static final int CONWAY = 7;

        public EraSummary {
            Objects.requireNonNull(start, "start");
            if (eraTag < 1 || eraTag > 8) {
                throw new IllegalArgumentException("unknown era tag " + eraTag);
            }
        }
    }

    /** @param timeMs milliseconds since the system start */
    public record EraBound(long timeMs, long slot, long epoch) {
    }

    /**
     * Amaru's {@code GlobalParameters}.
     *
     * @param securityParam          k
     * @param epochLengthScaleFactor epoch length / (k / f)
     * @param activeSlotCoeffInverse 1/f
     * @param maxLovelaceSupply      in lovelace
     * @param slotsPerKesPeriod      slots per KES period
     * @param maxKesEvolution        KES evolutions (fits a u8)
     * @param systemStartMs          POSIX milliseconds of the system start
     */
    public record GlobalParameters(long securityParam, long epochLengthScaleFactor, long activeSlotCoeffInverse,
                                   BigInteger maxLovelaceSupply, long slotsPerKesPeriod, int maxKesEvolution,
                                   long systemStartMs) {
        public GlobalParameters {
            Objects.requireNonNull(maxLovelaceSupply, "maxLovelaceSupply");
            if (maxKesEvolution < 0 || maxKesEvolution > 255) {
                throw new IllegalArgumentException("maxKesEvolution must fit a u8: " + maxKesEvolution);
            }
        }
    }
}
