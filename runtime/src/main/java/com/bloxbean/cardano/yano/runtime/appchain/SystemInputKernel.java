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
    record Result(FxKernel.Result effects, ObservationKernel.Result observations) {
    }

    private final ConsensusProfileGuard consensusProfileGuard;
    private final ObservationProfileGuard observationProfileGuard;
    private final FxKernel effects;
    private final ObservationKernel observations;

    SystemInputKernel(EffectsSettings effectsSettings,
                      ConsensusProfileGuard consensusProfileGuard,
                      ObservationProfileGuard observationProfileGuard) {
        this.consensusProfileGuard = Objects.requireNonNull(
                consensusProfileGuard, "consensusProfileGuard");
        this.observationProfileGuard = Objects.requireNonNull(
                observationProfileGuard, "observationProfileGuard");
        this.effects = new FxKernel(Objects.requireNonNull(effectsSettings, "effectsSettings"));
        this.observations = null;
    }

    SystemInputKernel(EffectsSettings effectsSettings,
                      ConsensusProfileGuard consensusProfileGuard,
                      ObservationProfileGuard observationProfileGuard,
                      ObservationKernel observations) {
        this.consensusProfileGuard = Objects.requireNonNull(
                consensusProfileGuard, "consensusProfileGuard");
        this.observationProfileGuard = Objects.requireNonNull(
                observationProfileGuard, "observationProfileGuard");
        this.effects = new FxKernel(Objects.requireNonNull(effectsSettings, "effectsSettings"));
        this.observations = Objects.requireNonNull(observations, "observations");
    }

    Result apply(AppStateMachine machine, AppBlockExecutionContext context,
                 AppStateWriter state, FxKernel.FxReader reader) {
        return apply(machine, context, state, reader, null);
    }

    Result apply(AppStateMachine machine, AppBlockExecutionContext context,
                 AppStateWriter state, FxKernel.FxReader reader,
                 ObservationKernel.Reader observationReader) {
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
        if (observations == null) {
            AppObservationEmitter rejecting = AppObservationEmitter.rejecting(
                    "Generic observations are disabled for this chain");
            return new Result(effects.apply(machine, context, state, reader, rejecting),
                    ObservationKernel.Result.NONE);
        }
        ObservationKernel.BlockSession session = observations.begin(
                machine, context, state, Objects.requireNonNull(
                        observationReader, "observationReader"));
        FxKernel.Result effectResult = effects.apply(machine, context, state, reader, session);
        return new Result(effectResult, session.finish());
    }

    byte[] observationProfileDigest() {
        return observationProfileGuard.digest();
    }
}
