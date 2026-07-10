package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerContext;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerMode.ProposalEligibility;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR app-layer/008.2 §2.1–2.3: the rotating schedule is deterministic across
 * members, acceptance covers the lookback window range (grace + re-gossiped
 * partial rounds), an unknown clock defers, and out-of-schedule proposers are
 * rejected fail-closed.
 */
class RotatingSequencerModeTest {

    private static final String M1 = "11".repeat(32);
    private static final String M2 = "22".repeat(32);
    private static final String M3 = "33".repeat(32);
    private static final List<String> MEMBERS = List.of(M1, M2, M3); // already sorted

    private static class Ctx implements SequencerContext {
        long slot;
        String self = M1;

        @Override public String chainId() { return "rot-chain"; }
        @Override public String selfKeyHex() { return self; }
        @Override public List<String> membersAt(long height) { return MEMBERS; }
        @Override public long currentL1Slot() { return slot; }
        @Override public Map<String, String> settings() {
            return Map.of("sequencer.window-slots", "10", "sequencer.lookback-windows", "4");
        }
    }

    private static RotatingSequencerMode mode(Ctx ctx) {
        RotatingSequencerMode mode = new RotatingSequencerMode();
        mode.init(ctx);
        return mode;
    }

    @Test
    void schedule_isDeterministicAcrossInstances() {
        Ctx a = new Ctx();
        Ctx b = new Ctx();
        RotatingSequencerMode modeA = mode(a);
        RotatingSequencerMode modeB = mode(b);
        for (long w = 0; w < 50; w++) {
            assertThat(modeA.proposerFor(w, 1)).isEqualTo(modeB.proposerFor(w, 1));
            assertThat(MEMBERS).contains(modeA.proposerFor(w, 1));
        }
        // The hash-shuffle actually rotates: not everyone is the same proposer
        long distinct = java.util.stream.LongStream.range(0, 50)
                .mapToObj(w -> modeA.proposerFor(w, 1)).distinct().count();
        assertThat(distinct).isGreaterThan(1);
    }

    @Test
    void shouldProposeNow_onlyForTheScheduledMemberOfTheCurrentWindow() {
        Ctx ctx = new Ctx();
        RotatingSequencerMode mode = mode(ctx);
        ctx.slot = 25; // window 2
        String scheduled = mode.proposerFor(2, 1);

        ctx.self = scheduled;
        assertThat(mode.shouldProposeNow(1)).isTrue();
        ctx.self = MEMBERS.stream().filter(m -> !m.equals(scheduled)).findFirst().orElseThrow();
        assertThat(mode.shouldProposeNow(1)).isFalse();

        ctx.slot = 0; // no clock yet
        ctx.self = scheduled;
        assertThat(mode.shouldProposeNow(1)).isFalse();
    }

    @Test
    void checkProposal_currentPreviousAndLookbackWindowsAccepted_beyondRejected() {
        Ctx ctx = new Ctx();
        RotatingSequencerMode mode = mode(ctx);
        ctx.slot = 100; // window 10, lookback 4 → windows 7..10 acceptable

        for (long w = 7; w <= 10; w++) {
            byte[] proposer = HexUtil.decodeHexString(mode.proposerFor(w, 1));
            assertThat(mode.checkProposal(proposer, 1))
                    .as("window " + w).isEqualTo(ProposalEligibility.ACCEPT);
        }
        // A member scheduled ONLY outside the lookback range must be rejected.
        // Find one: scan old windows for a proposer not scheduled in 7..10.
        java.util.Set<String> recent = new java.util.HashSet<>();
        for (long w = 7; w <= 10; w++) {
            recent.add(mode.proposerFor(w, 1));
        }
        String outsider = MEMBERS.stream().filter(m -> !recent.contains(m)).findFirst().orElse(null);
        if (outsider != null) { // exists unless all 3 rotated through in 4 windows
            assertThat(mode.checkProposal(HexUtil.decodeHexString(outsider), 1))
                    .isEqualTo(ProposalEligibility.REJECT);
        }
    }

    @Test
    void checkProposal_defersWithoutAClock() {
        Ctx ctx = new Ctx();
        RotatingSequencerMode mode = mode(ctx);
        ctx.slot = 0;
        assertThat(mode.checkProposal(HexUtil.decodeHexString(M1), 1))
                .isEqualTo(ProposalEligibility.DEFER);
    }

    @Test
    void invalidSettings_failFast() {
        Ctx bad = new Ctx() {
            @Override public Map<String, String> settings() {
                return Map.of("sequencer.window-slots", "0");
            }
        };
        assertThatThrownBy(() -> mode(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window-slots");
    }
}
