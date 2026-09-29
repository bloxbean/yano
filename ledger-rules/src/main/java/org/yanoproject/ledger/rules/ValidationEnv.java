package org.yanoproject.ledger.rules;

import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.util.HexUtil;

import java.util.Arrays;
import java.util.Objects;

/**
 * The validation environment that is not ledger state (Haskell {@code LedgerEnv} / {@code Globals}).
 *
 * <p>Ledger state, including the epoch-effective protocol parameters (deposits,
 * {@code govActionLifetime}, {@code drepActivity}, cost models), comes from the request's
 * {@link org.yanoproject.ledger.rules.view.LedgerView#protocolParams()}. Keeping parameters in the
 * (ticked) view means an epoch boundary changes them together with the rest of the state.
 * {@link #protocolMajor()} and {@link #protocolMinor()} repeat the view's protocol version so
 * protocol-version gates and the re-application rule (§6) can read it without a lookup; they must
 * agree with the view.</p>
 *
 * @param currentSlot     the slot the transaction is validated for (the next slot for admission,
 *                        the block slot for selection and sync)
 * @param currentEpoch    the epoch of {@code currentSlot}
 * @param protocolMajor   the ticked protocol major version
 * @param protocolMinor   the ticked protocol minor version
 * @param networkId       the network the node runs on
 * @param slotConfig      slot-to-time conversion for validity intervals and Plutus script contexts
 * @param phase2EnvDigest hash of what a phase-2 verdict depends on besides the resolved inputs
 *                        (§6); copied on the way in and out
 * @param forecastBasisSlot the slot the hard-fork combinator's forecast horizon is computed from: {@code next(tip)}
 *                        of the state the transaction is applied to (ADR-056 Phase 3a "Horizon basis", Phase 6
 *                        dependency). The mempool passes the slot after the canonical tip, which differs from
 *                        {@code currentSlot} in a producer's boundary window; block validation passes the slot after
 *                        the previous block. The seven-argument constructor uses {@code currentSlot}.
 */
public record ValidationEnv(long currentSlot, long currentEpoch, int protocolMajor, int protocolMinor,
                            NetworkId networkId, SlotConfig slotConfig, byte[] phase2EnvDigest,
                            long forecastBasisSlot) {

    public ValidationEnv {
        if (currentSlot < 0 || currentEpoch < 0) {
            throw new IllegalArgumentException("slot and epoch must be >= 0");
        }
        if (forecastBasisSlot < 0) {
            throw new IllegalArgumentException("forecastBasisSlot must be >= 0");
        }
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(slotConfig, "slotConfig");
        phase2EnvDigest = Objects.requireNonNull(phase2EnvDigest, "phase2EnvDigest").clone();
    }

    /** An environment whose forecast horizon is based on {@code currentSlot}. */
    public ValidationEnv(long currentSlot, long currentEpoch, int protocolMajor, int protocolMinor,
                         NetworkId networkId, SlotConfig slotConfig, byte[] phase2EnvDigest) {
        this(currentSlot, currentEpoch, protocolMajor, protocolMinor, networkId, slotConfig, phase2EnvDigest,
                currentSlot);
    }

    /** @return this environment with the forecast horizon based on {@code slot} */
    public ValidationEnv withForecastBasisSlot(long slot) {
        return slot == forecastBasisSlot ? this : new ValidationEnv(currentSlot, currentEpoch, protocolMajor,
                protocolMinor, networkId, slotConfig, phase2EnvDigest, slot);
    }

    @Override
    public byte[] phase2EnvDigest() {
        return phase2EnvDigest.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ValidationEnv other
                && currentSlot == other.currentSlot
                && currentEpoch == other.currentEpoch
                && protocolMajor == other.protocolMajor
                && protocolMinor == other.protocolMinor
                && networkId == other.networkId
                && slotConfig.equals(other.slotConfig)
                && Arrays.equals(phase2EnvDigest, other.phase2EnvDigest)
                && forecastBasisSlot == other.forecastBasisSlot;
    }

    @Override
    public int hashCode() {
        return Objects.hash(currentSlot, currentEpoch, protocolMajor, protocolMinor, networkId, slotConfig,
                Arrays.hashCode(phase2EnvDigest), forecastBasisSlot);
    }

    @Override
    public String toString() {
        return "ValidationEnv[slot=" + currentSlot + ", epoch=" + currentEpoch + ", pv=" + protocolMajor + "."
                + protocolMinor + ", network=" + networkId + ", phase2EnvDigest="
                + HexUtil.encodeHexString(phase2EnvDigest)
                + (forecastBasisSlot != currentSlot ? ", forecastBasisSlot=" + forecastBasisSlot : "") + "]";
    }
}
