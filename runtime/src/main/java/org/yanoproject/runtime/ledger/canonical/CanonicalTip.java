package org.yanoproject.runtime.ledger.canonical;

/**
 * The canonical ledger tip published by {@link CanonicalStateGate} (ADR-056 §3).
 *
 * <p>{@code generation} increases by one for every completed canonical write section that changed
 * (or may have changed) canonical state: a forward block, a rollback, a producer's epoch-boundary
 * section (which advances ledger state without moving the chain tip), and also a block whose apply
 * failed and was compensated (the tip is then unchanged, but the generation still moves forward;
 * this is harmless because generations only need to be monotonic and distinct per state). Two
 * snapshots with the same generation see the same canonical state.</p>
 *
 * <p>Two epochs are published because block producers apply the epoch boundary in a section of its
 * own, before selecting the block that crosses it: between those sections the ledger state is
 * already in epoch {@code E+1} while the chain tip slot is still in {@code E}. Epoch-effective
 * values (protocol parameters, treasury, active pool parameters) are read by {@link #ledgerEpoch()}.</p>
 *
 * @param generation   monotonic canonical generation; 0 before the first write section of this process
 * @param slot         chain tip slot, or -1 for an empty chain or when the tip could not be read
 * @param blockHash    chain tip hash (lowercase hex), or {@code null}
 * @param tipSlotEpoch epoch of the tip slot, or -1 when unknown (empty chain or no epoch calculator yet)
 * @param ledgerEpoch  epoch the ledger state is in: the later of {@code tipSlotEpoch} and the last
 *                     completed epoch boundary; -1 when unknown
 */
public record CanonicalTip(long generation, long slot, String blockHash, int tipSlotEpoch, int ledgerEpoch) {

    /** The tip before anything is known. */
    public static final CanonicalTip UNKNOWN = new CanonicalTip(0, -1, null, -1, -1);

    /** @return true when the chain tip is unknown or the chain is empty */
    public boolean isEmpty() {
        return slot < 0 || blockHash == null;
    }
}
