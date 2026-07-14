package com.bloxbean.cardano.yano.catalog;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutorFactory;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1ObserverProvider;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerModeProvider;
import com.bloxbean.cardano.yano.api.appchain.signer.SignerProviderFactory;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSinkFactory;
import com.bloxbean.cardano.yano.api.plugin.NodePlugin;

import java.util.Arrays;

/** Supported ServiceLoader contribution kinds in a bundle manifest. */
public enum ContributionKind {
    /** General node lifecycle plugin. */
    NODE_PLUGIN("node-plugin", NodePlugin.class),
    /** Deterministic app-chain state-machine provider. */
    APP_STATE_MACHINE("app-state-machine", AppStateMachineProvider.class),
    /** App-chain sequencer-mode provider. */
    SEQUENCER_MODE("sequencer-mode", SequencerModeProvider.class),
    /** App-chain L1 observation provider. */
    L1_OBSERVER("l1-observer", L1ObserverProvider.class),
    /** Local signer provider factory. */
    SIGNER_PROVIDER("signer-provider", SignerProviderFactory.class),
    /** Local app-effect executor factory. */
    EFFECT_EXECUTOR("effect-executor", AppEffectExecutorFactory.class),
    /** Finalized app-chain stream sink factory. */
    FINALIZED_SINK("finalized-sink", FinalizedStreamSinkFactory.class);

    private final String manifestKey;
    private final Class<?> serviceType;

    ContributionKind(String manifestKey, Class<?> serviceType) {
        this.manifestKey = manifestKey;
        this.serviceType = serviceType;
    }

    /**
     * Returns the stable manifest discriminator.
     *
     * @return lowercase manifest kind key
     */
    public String manifestKey() {
        return manifestKey;
    }

    /**
     * Returns the ServiceLoader SPI associated with this kind.
     *
     * @return supported service interface
     */
    public Class<?> serviceType() {
        return serviceType;
    }

    /**
     * Resolves a strict manifest kind key.
     *
     * @param manifestKey manifest discriminator
     * @return corresponding contribution kind
     * @throws IllegalArgumentException if the key is unsupported
     */
    public static ContributionKind fromManifestKey(String manifestKey) {
        return Arrays.stream(values())
                .filter(kind -> kind.manifestKey.equals(manifestKey))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("contribution kind is not supported"));
    }
}
