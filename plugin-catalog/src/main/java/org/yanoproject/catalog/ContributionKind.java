package org.yanoproject.catalog;

import org.yanoproject.api.appchain.AppStateMachineProvider;
import org.yanoproject.api.appchain.authmap.AuthenticatedMapValueValidatorFactory;
import org.yanoproject.api.appchain.effects.AppEffectExecutorFactory;
import org.yanoproject.api.appchain.l1view.L1EpochObserverProvider;
import org.yanoproject.api.appchain.l1view.L1ObserverProvider;
import org.yanoproject.api.appchain.observation.ObservationProviderFactory;
import org.yanoproject.api.appchain.sequencer.SequencerModeProvider;
import org.yanoproject.api.appchain.signer.SignerProviderFactory;
import org.yanoproject.api.appchain.sink.FinalizedStreamSinkFactory;
import org.yanoproject.api.plugin.NodePlugin;
import org.yanoproject.api.plugin.domain.DomainApiProvider;
import org.yanoproject.api.plugin.domain.LocalReadModelProvider;
import org.yanoproject.api.plugin.operations.PluginHealthProvider;
import org.yanoproject.api.plugin.operations.PluginMetricsProvider;
import org.yanoproject.api.utxo.index.UtxoIndexContributorProvider;

import java.util.Arrays;

/** Supported ServiceLoader contribution kinds in a bundle manifest. */
public enum ContributionKind {
    /** General node lifecycle plugin. */
    NODE_PLUGIN("node-plugin", NodePlugin.class, false),
    /** Deterministic app-chain state-machine provider. */
    APP_STATE_MACHINE("app-state-machine", AppStateMachineProvider.class, false),
    /** Genesis-pinned authenticated-map consensus value validator. */
    AUTHENTICATED_MAP_VALIDATOR(
            "authenticated-map-validator", AuthenticatedMapValueValidatorFactory.class, true),
    /** App-chain sequencer-mode provider. */
    SEQUENCER_MODE("sequencer-mode", SequencerModeProvider.class, false),
    /** App-chain L1 observation provider. */
    L1_OBSERVER("l1-observer", L1ObserverProvider.class, false),
    /** App-chain epoch-boundary L1 observation provider. */
    L1_EPOCH_OBSERVER("l1-epoch-observer", L1EpochObserverProvider.class, false),
    /** Generic external observation acquisition provider. */
    OBSERVATION_PROVIDER("observation-provider", ObservationProviderFactory.class, false),
    /** Local signer provider factory. */
    SIGNER_PROVIDER("signer-provider", SignerProviderFactory.class, false),
    /** Local app-effect executor factory. */
    EFFECT_EXECUTOR("effect-executor", AppEffectExecutorFactory.class, false),
    /** Finalized app-chain stream sink factory. */
    FINALIZED_SINK("finalized-sink", FinalizedStreamSinkFactory.class, false),
    /** Host-dispatched domain API; schema v1 requires an owning bundle manifest. */
    DOMAIN_API("domain-api", DomainApiProvider.class, true),
    /** Lifecycle-owned node-local derived model; an owning manifest is mandatory. */
    LOCAL_READ_MODEL("local-read-model", LocalReadModelProvider.class, true),
    /** Transactional UTxO-derived index; requires an owning manifest. */
    UTXO_INDEX_CONTRIBUTOR("utxo-index-contributor", UtxoIndexContributorProvider.class, true),
    /** Cached health source; schema v1 requires an owning bundle manifest. */
    HEALTH("health", PluginHealthProvider.class, true),
    /** Cached custom-metrics source; schema v1 requires an owning bundle manifest. */
    METRICS("metrics", PluginMetricsProvider.class, true);

    private final String manifestKey;
    private final Class<?> serviceType;
    private final boolean manifestRequired;

    ContributionKind(String manifestKey, Class<?> serviceType, boolean manifestRequired) {
        this.manifestKey = manifestKey;
        this.serviceType = serviceType;
        this.manifestRequired = manifestRequired;
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
     * Whether this contribution has no synthetic legacy representation.
     *
     * @return true when an owning bundle manifest is mandatory
     */
    public boolean manifestRequired() {
        return manifestRequired;
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
