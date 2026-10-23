package org.yanoproject.runtime.appchain;

import org.yanoproject.api.appchain.AppBlock;
import org.yanoproject.api.appchain.AppBlockExecutionContext;
import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateWriter;
import org.yanoproject.api.appchain.observation.AppObservationEmitter;
import org.yanoproject.api.appchain.observation.ObservationTopics;
import org.yanoproject.api.appchain.topics.TopicCommandRules;

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
    /** The topic registry (ADR app-layer/039); null on chains that cannot enable topics. */
    private final TopicRegistryKernel topics;

    SystemInputKernel(EffectsSettings effectsSettings,
                      ConsensusProfileGuard consensusProfileGuard,
                      ObservationProfileGuard observationProfileGuard) {
        this(effectsSettings, consensusProfileGuard, observationProfileGuard, null, null);
    }

    SystemInputKernel(EffectsSettings effectsSettings,
                      ConsensusProfileGuard consensusProfileGuard,
                      ObservationProfileGuard observationProfileGuard,
                      ObservationKernel observations) {
        this(effectsSettings, consensusProfileGuard, observationProfileGuard,
                Objects.requireNonNull(observations, "observations"), null);
    }

    /**
     * @param observations null when generic observations are disabled
     * @param topics       null on chains that cannot enable topics
     */
    SystemInputKernel(EffectsSettings effectsSettings,
                      ConsensusProfileGuard consensusProfileGuard,
                      ObservationProfileGuard observationProfileGuard,
                      ObservationKernel observations,
                      TopicRegistryKernel topics) {
        this.consensusProfileGuard = Objects.requireNonNull(
                consensusProfileGuard, "consensusProfileGuard");
        this.observationProfileGuard = Objects.requireNonNull(
                observationProfileGuard, "observationProfileGuard");
        this.effects = new FxKernel(Objects.requireNonNull(effectsSettings, "effectsSettings"));
        this.observations = observations;
        this.topics = topics;
    }

    Result apply(AppStateMachine machine, AppBlockExecutionContext context,
                 AppStateWriter state, FxKernel.FxReader reader) {
        return apply(machine, context, state, reader, null);
    }

    Result apply(AppStateMachine machine, AppBlockExecutionContext context,
                 AppStateWriter state, FxKernel.FxReader reader,
                 ObservationKernel.Reader observationReader) {
        AppBlock block = context.block();
        long height = block.height();
        block.messages().forEach(message -> {
            String topic = message.getTopic();
            if (topics == null && (TopicCommandRules.TOPIC.equals(topic)
                    || TopicCommandRules.GOVERNANCE_TOPIC.equals(topic))) {
                throw new IllegalArgumentException("topic registry is not available on this chain: " + topic);
            }
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
        AppBlockExecutionContext machineContext = topics != null ? topics.beforeMachine(context, state) : context;
        Result result;
        if (observations == null) {
            AppObservationEmitter rejecting = AppObservationEmitter.rejecting(
                    "Generic observations are disabled for this chain");
            result = new Result(effects.apply(machine, machineContext, state, reader, rejecting),
                    ObservationKernel.Result.NONE);
        } else {
            ObservationKernel.BlockSession session = observations.begin(
                    machine, machineContext, state, Objects.requireNonNull(
                            observationReader, "observationReader"));
            FxKernel.Result effectResult = effects.apply(machine, machineContext, state, reader, session);
            result = new Result(effectResult, session.finish());
        }
        if (topics != null) {
            topics.afterMachine(height, block.timestamp(), state);
        }
        return result;
    }

    byte[] observationProfileDigest() {
        return observationProfileGuard.digest();
    }
}
