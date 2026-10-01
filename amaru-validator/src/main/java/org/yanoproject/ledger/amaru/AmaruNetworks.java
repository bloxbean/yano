package org.yanoproject.ledger.amaru;

import org.yanoproject.ledger.amaru.AmaruNetworkParameters.EraBound;
import org.yanoproject.ledger.amaru.AmaruNetworkParameters.EraSummary;
import org.yanoproject.ledger.amaru.AmaruNetworkParameters.GlobalParameters;
import org.yanoproject.ledger.rules.NetworkParameters;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds {@link AmaruNetworkParameters} from the node's genesis-derived {@link NetworkParameters} (ADR-057
 * "What step 1d must provide").
 *
 * <ul>
 *   <li><b>Global parameters</b>: k, 1/f, max lovelace supply, KES period and evolutions, the system start
 *       and {@code epochLength / (k × 1/f)} (at least 1; 10 on the public networks).</li>
 *   <li><b>Stability window</b>: {@code 3k/f}.</li>
 *   <li><b>Era history</b>: the public networks' hard forks are fixed, so mainnet, preprod and preview get
 *       their full Byron → Conway history (the same bounds as Amaru's {@code MAINNET_ERA_HISTORY},
 *       {@code PREPROD_ERA_HISTORY} and {@code PREVIEW_ERA_HISTORY}; Amaru's preview table starts Allegra at
 *       epoch 5, an obvious slip for 0, which is used here). Any other magic is a Yano devnet, which starts
 *       in Conway: one open-ended Conway era ({@link AmaruNetworkParameters#singleConwayEra}), preceded by a
 *       Byron era when the network has a Byron period.</li>
 * </ul>
 */
public final class AmaruNetworks {

    public static final long MAINNET_MAGIC = 764824073L;
    public static final long PREPROD_MAGIC = 1L;
    public static final long PREVIEW_MAGIC = 2L;

    /** First epoch of Shelley, Allegra, Mary, Alonzo, Babbage and Conway. */
    private static final long[] MAINNET_FORKS = {208, 236, 251, 290, 365, 507};
    private static final long[] PREPROD_FORKS = {4, 5, 6, 7, 12, 163};
    private static final long[] PREVIEW_FORKS = {0, 0, 0, 0, 3, 646};

    private static final int BYRON = 1;
    private static final int SHELLEY = 2;
    private static final long DEFAULT_BYRON_SLOT_MS = 20_000;

    private AmaruNetworks() {
    }

    public static AmaruNetworkParameters from(NetworkParameters network) {
        Objects.requireNonNull(network, "network");
        long inverse = network.activeSlotsCoeffInverse();
        long scale = Math.max(1, network.epochLength() / (network.securityParam() * inverse));
        GlobalParameters global = new GlobalParameters(network.securityParam(), scale, inverse,
                network.maxLovelaceSupply(), network.slotsPerKesPeriod(), network.maxKesEvolutions(),
                network.systemStartMs());
        long[] forks = forks(network.networkMagic());
        if (forks == null) {
            return devnet(network, global);
        }
        return new AmaruNetworkParameters(network.networkMagic(), network.stabilityWindow(),
                publicEras(network, forks), global);
    }

    private static long[] forks(long magic) {
        if (magic == MAINNET_MAGIC) {
            return MAINNET_FORKS;
        }
        if (magic == PREPROD_MAGIC) {
            return PREPROD_FORKS;
        }
        if (magic == PREVIEW_MAGIC) {
            return PREVIEW_FORKS;
        }
        return null;
    }

    private static AmaruNetworkParameters devnet(NetworkParameters network, GlobalParameters global) {
        if (network.firstNonByronSlot() == 0) {
            return AmaruNetworkParameters.singleConwayEra(network.networkMagic(), network.epochLength(),
                    network.slotLengthMs(), global);
        }
        EraBound byronEnd = new EraBound(network.firstNonByronSlot() * network.byronSlotLengthMs(),
                network.firstNonByronSlot(), network.byronEpochs());
        List<EraSummary> eras = List.of(
                new EraSummary(new EraBound(0, 0, 0), byronEnd, network.byronEpochLength(),
                        network.byronSlotLengthMs(), BYRON),
                new EraSummary(byronEnd, null, network.epochLength(), network.slotLengthMs(), EraSummary.CONWAY));
        return new AmaruNetworkParameters(network.networkMagic(), network.stabilityWindow(), eras, global);
    }

    private static List<EraSummary> publicEras(NetworkParameters network, long[] forks) {
        long byronEpochs = forks[0];
        long byronEpochLength = network.byronEpochLength() > 0 ? network.byronEpochLength()
                : network.securityParam() * 10;
        long byronSlotMs = network.byronSlotLengthMs() > 0 ? network.byronSlotLengthMs() : DEFAULT_BYRON_SLOT_MS;
        EraBound byronEnd = new EraBound(byronEpochs * byronEpochLength * byronSlotMs, byronEpochs * byronEpochLength,
                byronEpochs);
        List<EraSummary> eras = new ArrayList<>();
        eras.add(new EraSummary(new EraBound(0, 0, 0), byronEnd, byronEpochLength, byronSlotMs, BYRON));
        for (int i = 0; i < forks.length; i++) {
            EraBound start = bound(byronEnd, forks[i], network);
            EraBound end = i + 1 < forks.length ? bound(byronEnd, forks[i + 1], network) : null;
            eras.add(new EraSummary(start, end, network.epochLength(), network.slotLengthMs(), SHELLEY + i));
        }
        return eras;
    }

    private static EraBound bound(EraBound byronEnd, long epoch, NetworkParameters network) {
        long epochs = epoch - byronEnd.epoch();
        long slot = byronEnd.slot() + epochs * network.epochLength();
        long time = byronEnd.timeMs() + BigInteger.valueOf(epochs).multiply(BigInteger.valueOf(network.epochLength()))
                .multiply(BigInteger.valueOf(network.slotLengthMs())).longValueExact();
        return new EraBound(time, slot, epoch);
    }
}
