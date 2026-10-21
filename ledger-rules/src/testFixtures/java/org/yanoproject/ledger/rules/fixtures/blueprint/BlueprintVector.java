package org.yanoproject.ledger.rules.fixtures.blueprint;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/**
 * One cardano-blueprint ledger conformance vector: a Haskell Imp-test run dumped as
 * {@code [config, initial NewEpochState, final NewEpochState, [event], title]}, with
 * {@code event = [0, tx, success, slot] / [1, slots] / [2, epochs]} (the format of Amaru's
 * {@code evaluate_ledger_states.rs} at the pinned tag).
 *
 * @param id           the path under {@code eras/}, e.g. {@code conway/fail-gov-proposaldepositincorrect/0}
 * @param group        the first path segment: the Imp spec the test comes from ({@code shelley} … {@code conway});
 *                     every vector runs in the Conway era
 * @param config       the test's {@code Globals}
 * @param initialState the {@code NewEpochState} before the first event
 * @param finalState   the {@code NewEpochState} after the last event
 * @param events       the events in order
 * @param title        the Imp test's path, e.g. {@code Conway/Imp/ConwayImpSpec - Version 10/GOV/...}
 */
public record BlueprintVector(String id, String group, Config config, byte[] initialState, byte[] finalState,
                              List<Event> events, String title) {

    public BlueprintVector {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(config, "config");
        initialState = initialState.clone();
        finalState = finalState.clone();
        events = List.copyOf(events);
    }

    @Override
    public byte[] initialState() {
        return initialState.clone();
    }

    @Override
    public byte[] finalState() {
        return finalState.clone();
    }

    /** @return the transaction events */
    public List<Event.Tx> transactions() {
        return events.stream().filter(e -> e instanceof Event.Tx).map(e -> (Event.Tx) e).toList();
    }

    @Override
    public String toString() {
        return id;
    }

    /**
     * The Imp test's {@code Globals}, as the vector encodes them: the current slot and epoch, then the genesis-derived
     * constants. Imp tests use a fixed epoch size and a one-second slot ({@code ImpTest.hs}, {@code sgSlotLength = 1},
     * {@code fixedEpochInfo}), so slots map to epochs linearly and a validity bound never meets a forecast horizon.
     *
     * @param slot                   {@code impCurSlotNo} when the dump starts
     * @param epoch                  the epoch of {@code slot}
     * @param epochSize              slots per epoch
     * @param slotsPerKesPeriod      slots per KES period
     * @param stabilityWindow        {@code 3k/f}
     * @param randomnessWindow       {@code 4k/f}
     * @param securityParameter      {@code k}
     * @param maxKesEvolutions       maximum KES evolutions
     * @param quorum                 update quorum
     * @param maxLovelaceSupply      maximum lovelace supply
     * @param activeSlotCoeff        {@code f}, as {@code [numerator, denominator]}
     * @param networkId              0 testnet, 1 mainnet
     * @param systemStartEpochMillis the system start, in milliseconds since the Unix epoch
     */
    public record Config(long slot, long epoch, long epochSize, long slotsPerKesPeriod, long stabilityWindow,
                         long randomnessWindow, long securityParameter, long maxKesEvolutions, long quorum,
                         BigInteger maxLovelaceSupply, BigInteger[] activeSlotCoeff, int networkId,
                         long systemStartEpochMillis) {

        /** Slot length of the Imp tests' genesis ({@code sgSlotLength = 1}). */
        public static final int SLOT_LENGTH_MILLIS = 1000;

        /**
         * @return the epoch of {@code s}: the Imp tests' epoch info is fixed from slot 0 ({@code fixedEpochInfo}), so
         *         it is {@code s / epochSize}
         */
        public long epochOf(long s) {
            if (s < 0) {
                throw new IllegalArgumentException("negative slot " + s);
            }
            return s / epochSize;
        }

        /**
         * @return true when the config's slot and epoch agree under {@link #epochOf(long)}; a vector whose config does
         *         not is refused (the harness would otherwise place its transactions in the wrong epoch)
         */
        public boolean consistent() {
            return epochSize > 0 && epochOf(slot) == epoch;
        }
    }

    /** A vector event. */
    public sealed interface Event permits Event.Tx, Event.PassTick, Event.PassEpoch {

        /**
         * A transaction submitted through the Imp test's {@code LEDGER} rule.
         *
         * @param cbor    the transaction bytes
         * @param success whether Haskell applied it (for {@code is_valid = false}: whether the collateral was taken)
         * @param slot    the slot it was applied at
         */
        record Tx(byte[] cbor, boolean success, long slot) implements Event {
            public Tx {
                cbor = cbor.clone();
            }

            @Override
            public byte[] cbor() {
                return cbor.clone();
            }
        }

        /** Slots passed through the {@code TICK} rule ({@code passTick}). */
        record PassTick(long slots) implements Event {
        }

        /** Epochs passed ({@code passEpoch}: the epoch boundary rules ran). */
        record PassEpoch(long epochs) implements Event {
        }
    }
}
