package org.yanoproject.ledger.rules;

import com.bloxbean.cardano.client.spec.NetworkId;

import java.math.BigInteger;
import java.util.Objects;

/**
 * The network facts an engine needs besides ledger state, taken from the node's genesis files. They are
 * fixed for a running node (ADR-056 §7, step 1d).
 *
 * @param networkMagic         the protocol magic (764824073 mainnet, 1 preprod, 2 preview)
 * @param networkId            mainnet or testnet
 * @param securityParam        Shelley genesis {@code securityParam} (k)
 * @param activeSlotsCoeff     Shelley genesis {@code activeSlotsCoeff} (f)
 * @param maxLovelaceSupply    Shelley genesis {@code maxLovelaceSupply}
 * @param slotsPerKesPeriod    Shelley genesis {@code slotsPerKESPeriod}
 * @param maxKesEvolutions     Shelley genesis {@code maxKESEvolutions}
 * @param systemStartMs        POSIX milliseconds of slot 0 (the network start, including any Byron period)
 * @param byronEpochLength     slots per Byron epoch (Byron genesis {@code k × 10}); ignored when
 *                             {@code firstNonByronSlot} is 0
 * @param byronSlotLengthMs    Byron slot length in milliseconds; ignored when {@code firstNonByronSlot} is 0
 * @param firstNonByronSlot    the first Shelley-based slot (0 when the network starts after Byron)
 * @param epochLength          slots per Shelley-based epoch
 * @param slotLengthMs         Shelley-based slot length in milliseconds
 */
public record NetworkParameters(long networkMagic, NetworkId networkId, long securityParam, double activeSlotsCoeff,
                                BigInteger maxLovelaceSupply, long slotsPerKesPeriod, int maxKesEvolutions,
                                long systemStartMs, long byronEpochLength, long byronSlotLengthMs,
                                long firstNonByronSlot, long epochLength, long slotLengthMs) {

    public NetworkParameters {
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(maxLovelaceSupply, "maxLovelaceSupply");
        if (securityParam <= 0 || !(activeSlotsCoeff > 0 && activeSlotsCoeff <= 1)) {
            throw new IllegalArgumentException("securityParam must be > 0 and activeSlotsCoeff in (0, 1]");
        }
        if (epochLength <= 0 || slotLengthMs <= 0) {
            throw new IllegalArgumentException("epochLength and slotLengthMs must be > 0");
        }
        if (firstNonByronSlot < 0) {
            throw new IllegalArgumentException("firstNonByronSlot must be >= 0");
        }
        if (firstNonByronSlot > 0 && (byronEpochLength <= 0 || byronSlotLengthMs <= 0)) {
            throw new IllegalArgumentException("a Byron period needs its epoch length and slot length");
        }
    }

    /** @return {@code 1/f}, rounded to the nearest integer (20 on the public networks) */
    public long activeSlotsCoeffInverse() {
        return Math.round(1.0 / activeSlotsCoeff);
    }

    /** @return {@code 3k/f} slots (129600 on mainnet and preprod) */
    public long stabilityWindow() {
        return 3 * securityParam * activeSlotsCoeffInverse();
    }

    /** @return the number of Byron epochs before {@link #firstNonByronSlot()} */
    public long byronEpochs() {
        return firstNonByronSlot == 0 ? 0 : firstNonByronSlot / byronEpochLength;
    }
}
