package com.bloxbean.cardano.yano.appchain.composite;

import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;

/** Capability-limited views for only the participant generations declared by a workflow. */
public interface CompositeWorkflowContext {
    enum ClaimResult {
        CLAIMED,
        EXACT_REPLAY,
        CONFLICT
    }

    AppStateWriter state(ComponentGeneration participant);

    AppEffectEmitter effects(ComponentGeneration owner);

    /**
     * Claim one stable workflow operation id inside the current atomic block.
     * The existing claim is never overwritten. Callers must treat both replay
     * outcomes as deterministic business no-ops rather than throwing from
     * block application and repeatedly poisoning proposer selection.
     */
    ClaimResult claim(String operationId, byte[] commandHash);
}
