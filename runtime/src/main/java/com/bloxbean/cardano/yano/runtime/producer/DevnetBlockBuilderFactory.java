package com.bloxbean.cardano.yano.runtime.producer;

import com.bloxbean.cardano.client.crypto.BlockProducerKeys;
import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.ledgerstate.EpochParamTracker;
import com.bloxbean.cardano.yano.runtime.blockproducer.DevnetBlockBuilder;
import com.bloxbean.cardano.yano.runtime.blockproducer.EpochNonceEvolver;
import com.bloxbean.cardano.yano.runtime.blockproducer.EpochNonceState;
import com.bloxbean.cardano.yano.runtime.blockproducer.GenesisConfig;
import com.bloxbean.cardano.yano.runtime.blockproducer.NonceReplayService;
import com.bloxbean.cardano.yano.runtime.blockproducer.NonceStateStore;
import com.bloxbean.cardano.yano.runtime.blockproducer.ProtocolVersionSupplier;
import com.bloxbean.cardano.yano.runtime.blockproducer.SignedBlockBuilder;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Builds the devnet block builder selected by runtime producer configuration.
 */
@Slf4j
public final class DevnetBlockBuilderFactory {
    private final YanoConfig config;
    private final GenesisConfig genesisConfig;
    private final Dependencies dependencies;

    public DevnetBlockBuilderFactory(YanoConfig config,
                                     GenesisConfig genesisConfig,
                                     Dependencies dependencies) {
        this.config = Objects.requireNonNull(config, "config");
        this.genesisConfig = Objects.requireNonNull(genesisConfig, "genesisConfig");
        this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
    }

    public DevnetBlockBuilder create(boolean freshStart) {
        if (hasConfiguredProducerKeys(config)) {
            return createSignedBlockBuilder(freshStart);
        }

        log.info("Using DevnetBlockBuilder with dummy signatures (no key files configured)");
        return new DevnetBlockBuilder(dependencies.protocolVersionSupplier().get());
    }

    public static boolean hasConfiguredProducerKeys(YanoConfig config) {
        Objects.requireNonNull(config, "config");
        return hasText(config.getVrfSkeyFile())
                && hasText(config.getKesSkeyFile())
                && hasText(config.getOpCertFile());
    }

    private SignedBlockBuilder createSignedBlockBuilder(boolean freshStart) {
        try {
            var keys = BlockProducerKeys.load(
                    Path.of(config.getVrfSkeyFile()),
                    Path.of(config.getKesSkeyFile()),
                    Path.of(config.getOpCertFile()));

            var shelleyData = genesisConfig.getShelleyGenesisData();
            long slotsPerKESPeriod = shelleyData != null ? shelleyData.slotsPerKESPeriod() : 129600;
            long maxKESEvolutions = shelleyData != null ? shelleyData.maxKESEvolutions() : 60;
            long epochLength = shelleyData != null ? shelleyData.epochLength() : 600;
            long securityParam = shelleyData != null ? shelleyData.securityParam() : 100;
            double activeSlotsCoeff = genesisConfig.getActiveSlotsCoeff() > 0
                    ? genesisConfig.getActiveSlotsCoeff() : 1.0;
            long byronSlotsPerEpoch = genesisConfig.getByronGenesisData() != null
                    ? genesisConfig.getByronGenesisData().epochLength() : Constants.BYRON_SLOTS_PER_EPOCH;

            EpochNonceState nonceState =
                    new EpochNonceState(epochLength, securityParam, activeSlotsCoeff, byronSlotsPerEpoch);
            dependencies.shelleyStartSlotInitializer().accept(nonceState);

            NonceStateStore nonceStore = dependencies.chainState() instanceof NonceStateStore store ? store : null;
            EpochParamProvider effectiveParamProvider = dependencies.effectiveEpochParamProvider().get();
            boolean trackedParams = effectiveParamProvider instanceof EpochParamTracker tracker
                    && tracker.isEnabled();
            long networkMagic = config.getProtocolMagic();
            NonceReplayService replayService = nonceStore != null && !freshStart
                    ? new NonceReplayService(
                            dependencies.chainState(),
                            nonceStore,
                            new EpochNonceEvolver(effectiveParamProvider, trackedParams, networkMagic),
                            dependencies.genesisHash().get())
                    : null;

            dependencies.nonceInitializer().initialize(
                    nonceState,
                    nonceStore,
                    replayService,
                    "signed-devnet-startup",
                    "signed block production");

            log.info("Using SignedBlockBuilder with real VRF/KES crypto and runtime protocol version supplier");
            return new SignedBlockBuilder(
                    keys,
                    slotsPerKESPeriod,
                    maxKESEvolutions,
                    nonceState,
                    nonceStore,
                    dependencies.protocolVersionSupplier().get());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize SignedBlockBuilder with configured producer keys", e);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Runtime collaborators required to create devnet block builders.
     */
    public record Dependencies(
            ChainState chainState,
            Supplier<EpochParamProvider> effectiveEpochParamProvider,
            Supplier<byte[]> genesisHash,
            Consumer<EpochNonceState> shelleyStartSlotInitializer,
            NonceInitializer nonceInitializer,
            Supplier<ProtocolVersionSupplier> protocolVersionSupplier) {
        public Dependencies {
            Objects.requireNonNull(chainState, "chainState");
            Objects.requireNonNull(effectiveEpochParamProvider, "effectiveEpochParamProvider");
            Objects.requireNonNull(genesisHash, "genesisHash");
            Objects.requireNonNull(shelleyStartSlotInitializer, "shelleyStartSlotInitializer");
            Objects.requireNonNull(nonceInitializer, "nonceInitializer");
            Objects.requireNonNull(protocolVersionSupplier, "protocolVersionSupplier");
        }
    }

    /**
     * Initializes or repairs nonce state before a signed producer starts.
     */
    @FunctionalInterface
    public interface NonceInitializer {
        void initialize(EpochNonceState nonceState,
                        NonceStateStore nonceStore,
                        NonceReplayService replayService,
                        String repairReason,
                        String modeDescription);
    }
}
