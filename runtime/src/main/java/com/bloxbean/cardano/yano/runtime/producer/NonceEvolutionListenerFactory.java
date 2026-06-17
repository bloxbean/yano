package com.bloxbean.cardano.yano.runtime.producer;

import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.events.api.support.AnnotationListenerRegistrar;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.runtime.blockproducer.EpochNonceState;
import com.bloxbean.cardano.yano.runtime.blockproducer.NonceEvolutionListener;
import com.bloxbean.cardano.yano.runtime.blockproducer.NonceReplayService;
import com.bloxbean.cardano.yano.runtime.blockproducer.NonceStateStore;
import com.bloxbean.cardano.yano.runtime.blockproducer.SignedBlockBuilder;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

/**
 * Creates and registers nonce listeners needed by block-production strategies.
 */
@Slf4j
public final class NonceEvolutionListenerFactory {
    private static final long MAINNET_MAGIC = 764824073L;

    private NonceEvolutionListenerFactory() {
    }

    public static NonceEvolutionListener registerSlotLeader(EventBus eventBus,
                                                            EpochNonceState nonceState,
                                                            NonceStateStore nonceStore,
                                                            SignedBlockBuilder signedBlockBuilder,
                                                            EpochParamProvider effectiveParamProvider,
                                                            boolean trackedParams,
                                                            long networkMagic,
                                                            NonceEvolutionListener.NonceCursorResolver cursorResolver,
                                                            NonceReplayService replayService) {
        Objects.requireNonNull(signedBlockBuilder, "signedBlockBuilder");
        String issuerVkeyHex = signedBlockBuilder.getIssuerVkeyHex();
        logTrackingMode(trackedParams, networkMagic);
        NonceEvolutionListener listener = create(
                nonceState,
                nonceStore,
                issuerVkeyHex,
                effectiveParamProvider,
                trackedParams,
                networkMagic,
                cursorResolver,
                replayService);
        register(eventBus, listener);
        log.info("NonceEvolutionListener registered (skipping own blocks with issuerVkey={})", issuerVkeyHex);
        return listener;
    }

    public static void logTrackingMode(boolean trackedParams, long networkMagic) {
        if (trackedParams) {
            log.info("Nonce tracking wired with EpochParamTracker");
        } else if (networkMagic == MAINNET_MAGIC) {
            log.warn("EpochParamTracker not wired; historical on-chain extraEntropy updates "
                    + "will be treated as NeutralNonce. Affects mainnet epoch 259 nonce computation.");
        } else {
            log.info("Nonce tracking wired without EpochParamTracker; "
                    + "on-chain extraEntropy updates unavailable");
        }
    }

    static NonceEvolutionListener create(EpochNonceState nonceState,
                                         NonceStateStore nonceStore,
                                         String ownIssuerVkey,
                                         EpochParamProvider effectiveParamProvider,
                                         boolean trackedParams,
                                         long networkMagic,
                                         NonceEvolutionListener.NonceCursorResolver cursorResolver,
                                         NonceReplayService replayService) {
        Objects.requireNonNull(nonceState, "nonceState");
        return new NonceEvolutionListener(
                nonceState,
                nonceStore,
                ownIssuerVkey,
                effectiveParamProvider,
                trackedParams,
                networkMagic,
                cursorResolver,
                replayService);
    }

    private static void register(EventBus eventBus, NonceEvolutionListener listener) {
        Objects.requireNonNull(eventBus, "eventBus");
        AnnotationListenerRegistrar.register(
                eventBus,
                listener,
                SubscriptionOptions.builder().build());
    }
}
