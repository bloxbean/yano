package org.yanoproject.runtime.appchain;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.yanoproject.api.appchain.sequencer.SequencerContext;
import org.yanoproject.api.appchain.sequencer.SequencerMode;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ADR-005 S2 / ADR-008.2: L1-slot-clocked rotating proposership.
 *
 * <pre>
 *   window(slot)      = slot / windowSlots
 *   proposerFor(w, h) = membersAt(h)[ blake2b256(chainId ‖ w) mod n ]   (members sorted)
 * </pre>
 *
 * Live acceptance: a proposal is eligible if its proposer was scheduled for
 * any of the last {@code lookbackWindows} windows (current window = a live
 * proposal; older windows = re-gossiped locked proposals finishing a partial
 * round — proposer authenticity is separately enforced by the envelope
 * signature check, so only GENUINE proposals by legitimately scheduled
 * members can ever pass). No local L1 clock yet → DEFER (retry shortly).
 */
final class RotatingSequencerMode implements SequencerMode {

    static final String ID = "rotating";
    static final long DEFAULT_WINDOW_SLOTS = 60;
    static final int DEFAULT_LOOKBACK_WINDOWS = 64;

    private SequencerContext context;
    private long windowSlots;
    private int lookbackWindows;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void init(SequencerContext context) {
        this.context = context;
        this.windowSlots = parseLong(context.settings().get("sequencer.window-slots"),
                DEFAULT_WINDOW_SLOTS);
        if (windowSlots <= 0) {
            throw new IllegalArgumentException("sequencer.window-slots must be > 0");
        }
        this.lookbackWindows = (int) parseLong(
                context.settings().get("sequencer.lookback-windows"), DEFAULT_LOOKBACK_WINDOWS);
        if (lookbackWindows < 1) {
            throw new IllegalArgumentException("sequencer.lookback-windows must be >= 1");
        }
    }

    @Override
    public boolean shouldProposeNow(long height) {
        long slot = context.currentL1Slot();
        if (slot <= 0) {
            return false; // no shared clock yet
        }
        return context.selfKeyHex().equals(proposerFor(window(slot), height));
    }

    @Override
    public ProposalEligibility checkProposal(byte[] proposerKey, long height) {
        long slot = context.currentL1Slot();
        if (slot <= 0) {
            return ProposalEligibility.DEFER; // our clock hasn't started — retry
        }
        String proposerHex = HexUtil.encodeHexString(proposerKey).toLowerCase(Locale.ROOT);
        long currentWindow = window(slot);
        for (long w = currentWindow; w > currentWindow - lookbackWindows && w >= 0; w--) {
            if (proposerHex.equals(proposerFor(w, height))) {
                return ProposalEligibility.ACCEPT;
            }
        }
        return ProposalEligibility.REJECT;
    }

    @Override
    public Map<String, Object> status() {
        long slot = context.currentL1Slot();
        long w = slot > 0 ? window(slot) : -1;
        return Map.of(
                "mode", ID,
                "windowSlots", windowSlots,
                "currentWindow", w,
                "currentProposer", w >= 0 ? proposerFor(w, Long.MAX_VALUE) : "unknown");
    }

    long window(long slot) {
        return slot / windowSlots;
    }

    /** Deterministic schedule over the SORTED member set active at the height. */
    String proposerFor(long window, long height) {
        List<String> members = context.membersAt(height);
        byte[] seed = Blake2bUtil.blake2bHash256(
                (context.chainId() + "\u0000" + window).getBytes(StandardCharsets.UTF_8));
        int index = new BigInteger(1, seed).mod(BigInteger.valueOf(members.size())).intValue();
        return members.get(index);
    }

    private static long parseLong(String value, long defaultValue) {
        return value != null && !value.isBlank() ? Long.parseLong(value.trim()) : defaultValue;
    }
}
