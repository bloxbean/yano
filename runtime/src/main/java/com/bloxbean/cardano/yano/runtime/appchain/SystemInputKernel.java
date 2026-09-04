package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.observation.AppObservationEmitter;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationTopics;

import java.util.Objects;

/**
 * Single deterministic framework pipeline shared by production and
 * conformance. Phase 0 installs both profile markers and preserves effect
 * ordering; later phases add result incorporation and scheduling here.
 */
final class SystemInputKernel {
    private final ConsensusProfileGuard consensusProfileGuard;
    private final ObservationProfileGuard observationProfileGuard;
    private final FxKernel effects;

    SystemInputKernel(EffectsSettings effectsSettings,
                      ConsensusProfileGuard consensusProfileGuard,
                      ObservationProfileGuard observationProfileGuard) {
        this.consensusProfileGuard = Objects.requireNonNull(
                consensusProfileGuard, "consensusProfileGuard");
        this.observationProfileGuard = Objects.requireNonNull(
                observationProfileGuard, "observationProfileGuard");
        this.effects = new FxKernel(Objects.requireNonNull(effectsSettings, "effectsSettings"));
    }

    FxKernel.Result apply(AppStateMachine machine, AppBlockExecutionContext context,
                          AppStateWriter state, FxKernel.FxReader reader) {
        long height = context.block().height();
        context.block().messages().forEach(message -> {
            String topic = message.getTopic();
            if (ObservationTopics.isReserved(topic)) {
                if (!ObservationTopics.isAllowed(topic)) {
                    throw new IllegalArgumentException("unknown reserved observation topic: " + topic);
                }
                if (!observationProfileGuard.profile().enabled()) {
                    throw new IllegalArgumentException(
                            "observation system input is disabled for this chain");
                }
            }
        });
        consensusProfileGuard.apply(height, state);
        observationProfileGuard.apply(height, state);
        AppObservationEmitter observations = AppObservationEmitter.rejecting(
                "Generic observations are disabled for this chain");
        return effects.apply(machine, context, state, reader, observations);
    }

    byte[] observationProfileDigest() {
        return observationProfileGuard.digest();
    }
}
