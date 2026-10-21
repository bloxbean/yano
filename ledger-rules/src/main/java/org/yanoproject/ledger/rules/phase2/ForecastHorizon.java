package org.yanoproject.ledger.rules.phase2;

import org.yanoproject.api.util.EpochSlotCalc;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * How far ahead slots can be converted to time for a Plutus script context (Haskell
 * {@code TimeTranslationPastHorizon}, {@code Alonzo/Plutus/TxInfo.hs:252-274}).
 *
 * <p>The ledger's {@code EpochInfo} comes from the hard-fork combinator's summary of the ledger state. For the
 * current era with no known successor its end is exclusive and lies on the first epoch boundary at or after
 * {@code next(tip) + safeZone} (ouroboros-consensus {@code HardFork/History/Summary.hs:370-404},
 * {@code slotToEpochBound}), with {@code safeZone = 3k/f} for the Shelley-based eras
 * ({@code Shelley/Ledger/Ledger.hs:168-183}). A validity-interval bound at or past that slot cannot be
 * translated.</p>
 */
@FunctionalInterface
public interface ForecastHorizon {

    /**
     * @param validationSlot the slot the transaction is validated at: the slot after the ledger tip
     *                       ({@code next(tip)})
     * @return the first slot that can no longer be converted to time (exclusive upper bound)
     */
    long exclusiveUpperSlot(long validationSlot);

    /**
     * @param stabilityWindow {@code 3k/f}, supplied late (the genesis may be read after start-up)
     * @param epochs          the network's epoch geometry
     */
    static ForecastHorizon of(LongSupplier stabilityWindow, EpochSlotCalc epochs) {
        Objects.requireNonNull(stabilityWindow, "stabilityWindow");
        Objects.requireNonNull(epochs, "epochs");
        return validationSlot -> {
            long hiSlot = validationSlot + stabilityWindow.getAsLong();
            int epoch = epochs.slotToEpoch(hiSlot);
            long start = epochs.epochToStartSlot(epoch);
            return start == hiSlot ? hiSlot : epochs.epochToStartSlot(epoch + 1);
        };
    }
}
