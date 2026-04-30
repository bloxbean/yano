package com.bloxbean.cardano.yano.runtime.era;

import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yano.api.era.EraProvider;
import com.bloxbean.cardano.yano.api.util.EpochSlotCalc;
import com.bloxbean.cardano.yano.runtime.chain.DirectRocksDBChainState;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * Small reusable helper for era metadata queries.
 * Reads persisted era-start slots from {@link DirectRocksDBChainState}
 * and converts to epochs using {@link EpochSlotCalc}.
 * <p>
 * Implements {@link EraProvider} so ledger-state consumers can use it
 * without depending on node-runtime.
 * <p>
 * This class answers metadata questions only — it does not decide pointer policy,
 * governance business rules, or bootstrap logic.
 */
public final class EraProviderImpl implements EraProvider {

    private final DirectRocksDBChainState chainState;
    private final EpochSlotCalc epochSlotCalc;

    public EraProviderImpl(DirectRocksDBChainState chainState, EpochSlotCalc epochSlotCalc) {
        this.chainState = chainState;
        this.epochSlotCalc = epochSlotCalc;
    }

    /**
     * Get the persisted start slot for a given era.
     */
    public OptionalLong getStartSlot(Era era) {
        return chainState.getEraStartSlot(era.getValue());
    }

    /**
     * Get the start epoch for a given era, converting from the persisted start slot.
     */
    public Optional<Integer> getStartEpoch(Era era) {
        var slot = chainState.getEraStartSlot(era.getValue());
        if (slot.isEmpty()) return Optional.empty();
        return Optional.of(epochSlotCalc.slotToEpoch(slot.getAsLong()));
    }

    /**
     * Get the era value of the earliest known era with persisted metadata.
     * Scans all known era values from the Era enum, plus values up to 10 to cover future eras.
     *
     * @return the era value of the earliest known era, or empty if none
     */
    public OptionalLong getEarliestKnownEraValue() {
        int minEraValue = -1;
        long minSlot = Long.MAX_VALUE;
        // Scan Era enum values
        for (Era era : Era.values()) {
            var slot = chainState.getEraStartSlot(era.getValue());
            if (slot.isPresent() && slot.getAsLong() < minSlot) {
                minSlot = slot.getAsLong();
                minEraValue = era.getValue();
            }
        }
        // Also scan beyond the enum for future eras (e.g., Dijkstra = 8+)
        for (int ev = Era.Conway.getValue() + 1; ev <= 10; ev++) {
            var slot = chainState.getEraStartSlot(ev);
            if (slot.isPresent() && slot.getAsLong() < minSlot) {
                minSlot = slot.getAsLong();
                minEraValue = ev;
            }
        }
        return minEraValue >= 0 ? OptionalLong.of(minEraValue) : OptionalLong.empty();
    }

    /**
     * Find the earliest known era with persisted metadata (as an Era enum value).
     */
    public Optional<Era> getEarliestKnownEra() {
        var eraValue = getEarliestKnownEraValue();
        if (eraValue.isEmpty()) return Optional.empty();
        for (Era era : Era.values()) {
            if (era.getValue() == (int) eraValue.getAsLong()) return Optional.of(era);
        }
        // Era value exists but not in enum (future era) — return empty for enum, but startsInOrAfter still works
        return Optional.empty();
    }

    /**
     * Check if the chain starts in or after the given era.
     * True if the earliest known era value is >= the given era's value.
     */
    public boolean startsInOrAfter(Era era) {
        var earliestValue = getEarliestKnownEraValue();
        return earliestValue.isPresent() && earliestValue.getAsLong() >= era.getValue();
    }

    @Override
    public boolean isConwayOrLater(int epoch) {
        return isEraOrLater(epoch, Era.Conway.getValue());
    }

    @Override
    public boolean isEraOrLater(int epoch, int eraValue) {
        Integer first = resolveFirstEpochOrNull(eraValue);
        return first != null && epoch >= first;
    }

    @Override
    public Integer resolveFirstEpochOrNull(int eraValue) {
        var eraSlot = chainState.getEraStartSlot(eraValue);
        if (eraSlot.isPresent()) {
            return epochSlotCalc.slotToEpoch(eraSlot.getAsLong());
        }

        var earliestValue = getEarliestKnownEraValue();
        if (earliestValue.isPresent() && earliestValue.getAsLong() >= eraValue) {
            return 0;
        }

        return null;
    }

    /**
     * Resolve the first Conway epoch, or null if Conway has not been reached.
     * <p>
     * Rules:
     * <ul>
     *   <li>If Conway start slot is persisted → return its epoch</li>
     *   <li>If the chain starts in Conway or after Conway (e.g. Dijkstra devnet) → return 0</li>
     *   <li>Otherwise → null (Conway not reached yet)</li>
     * </ul>
     */
    @Override
    public Integer resolveFirstConwayEpochOrNull() {
        return resolveFirstEpochOrNull(Era.Conway.getValue());
    }
}
