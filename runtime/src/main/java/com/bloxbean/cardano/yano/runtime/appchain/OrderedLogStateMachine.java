package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.transition.FinalizedMessageIndex;
import com.bloxbean.cardano.yano.api.appchain.transition.TransitionPlans;

/**
 * Built-in default app: an append-only ordered log of opaque messages.
 * For every finalized message it writes
 * {@code key = message-id} → {@code cbor([height, index, topic, sender])}
 * into the state trie — so any consumer can obtain an MPF inclusion proof
 * that a given message was finalized at a given position, verifiable against
 * an anchored state root without trusting the nodes (ADR app-layer/005 D10).
 */
public final class OrderedLogStateMachine implements AppStateMachine {

    public static final String ID = "ordered-log";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void apply(
            AppBlockExecutionContext context,
            AppStateWriter writer,
            AppEffectEmitter effects
    ) {
        TransitionPlans.commit(
                FinalizedMessageIndex.plan(context, FinalizedMessageIndex.InclusionPolicy.ALL),
                writer, effects);
    }
}
