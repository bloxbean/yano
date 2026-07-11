package com.bloxbean.cardano.yano.api.appchain.sequencer;

import java.util.Map;

/**
 * Pluggable sequencer (consensus-mode) SPI — ADR app-layer/008.2 §2.7.
 * A mode decides the LIVE path only: who may propose now, and whose proposals
 * are acceptable. It can never weaken finality — threshold certificates,
 * one-vote-per-height vote locks, and catch-up verification (membership at
 * height + cert) are framework-enforced outside this interface.
 *
 * <p>Built-ins: {@code fixed} (ADR-005 S1) and {@code rotating} (S2). Custom
 * modes ship via {@link SequencerModeProvider} (ServiceLoader — plugin jar or
 * library mode) and are selected with {@code yano.app-chain.sequencer.mode}.
 * All members of a chain must run the same mode id (fail-closed).
 */
public interface SequencerMode {

    /** Stable mode id, matched against {@code sequencer.mode}. */
    String id();

    /** Called once before use; fail fast here on bad/missing settings. */
    void init(SequencerContext context);

    /** Whether THIS node may propose the block at {@code height} right now. */
    boolean shouldProposeNow(long height);

    /** Live-path verdict on a received proposal for {@code height}. */
    ProposalEligibility checkProposal(byte[] proposerKey, long height);

    /** Mode-specific status for observability (e.g. current window/proposer). */
    default Map<String, Object> status() {
        return Map.of();
    }

    enum ProposalEligibility {
        /** Proposer is eligible — continue verification. */
        ACCEPT,
        /** Proposer is not eligible — reject fail-closed. */
        REJECT,
        /** Cannot decide yet (e.g. local clock behind) — retry shortly. */
        DEFER
    }
}
