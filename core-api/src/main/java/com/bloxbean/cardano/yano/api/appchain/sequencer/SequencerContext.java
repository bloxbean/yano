package com.bloxbean.cardano.yano.api.appchain.sequencer;

import java.util.List;
import java.util.Map;

/**
 * What the framework hands a {@link SequencerMode} (ADR app-layer/008.2 §2.7).
 */
public interface SequencerContext {

    String chainId();

    /** This node's member public key (lowercase hex). */
    String selfKeyHex();

    /** Member keys (lowercase hex) active at the given height, SORTED — the
     *  deterministic basis for any schedule. Composes with membership epochs
     *  (admin rotation today, chain-governed per ADR 008.3). */
    List<String> membersAt(long height);

    /** Newest L1 slot this node has observed (the shared clock); 0 = none yet. */
    long currentL1Slot();

    /** Chain settings, suffix-keyed (e.g. {@code sequencer.window-slots}). */
    Map<String, String> settings();
}
