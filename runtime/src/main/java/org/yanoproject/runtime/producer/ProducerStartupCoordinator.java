package org.yanoproject.runtime.producer;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.SubscriptionHandle;
import org.yanoproject.api.EpochParamProvider;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.ledgerstate.EpochParamTracker;
import org.yanoproject.runtime.blockproducer.BlockProducerHelper;
import org.yanoproject.runtime.blockproducer.BackfillPolicy;
import org.yanoproject.runtime.blockproducer.DevnetBlockBuilder;
import org.yanoproject.runtime.blockproducer.DevnetBlockProducer;
import org.yanoproject.runtime.blockproducer.EpochNonceEvolver;
import org.yanoproject.runtime.blockproducer.EpochNonceState;
import org.yanoproject.runtime.blockproducer.GenesisConfig;
import org.yanoproject.runtime.blockproducer.NonceEvolutionListener;
import org.yanoproject.runtime.blockproducer.NonceReplayService;
import org.yanoproject.runtime.blockproducer.NonceStateStore;
import org.yanoproject.runtime.blockproducer.ProtocolVersionSupplier;
import org.yanoproject.runtime.blockproducer.BlockBodySizeLimitSupplier;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Objects;

/**
 * Coordinates block-producer startup.
 *
 * <p>This service owns mode selection plus the devnet and slot-leader startup
 * sequencing that used to live inside RuntimeNode. RuntimeNode supplies concrete
 * storage, config, nonce, and factory dependencies through {@link Actions}.</p>
 */
@Slf4j
public final class ProducerStartupCoordinator {
    private final Actions actions;

    public ProducerStartupCoordinator(Actions actions) {
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    public void start() {
        actions.wireBlockProducerHelpers();
        ProducerStartupPlan startupPlan = actions.startupPlan();
        if (startupPlan.deferredUntilGenesisShift()) {
            actions.deferPastTimeTravelBlockProducer();
            return;
        }
        switch (startupPlan.mode()) {
            case SLOT_LEADER -> startSlotLeaderBlockProducer();
            case DEVNET -> startDevnetBlockProducer();
            default -> throw new IllegalStateException(
                    "Producer startup mode " + startupPlan.mode() + " must be deferred until genesis shift");
        }
    }

    private void startDevnetBlockProducer() {
        log.info("Starting block producer (devnet mode)...");

        actions.loadAndPropagateGenesisConfig();
        actions.autoDeriveBlockTimeMillis();
        actions.autoDeriveSlotLengthMillis();

        boolean freshStart = actions.chainTip() == null;
        YanoConfig config = actions.config();
        GenesisConfig genesisConfig = actions.genesisConfig();

        if (freshStart && config.getStartEpoch() > 0 && config.getGenesisTimestamp() <= 0) {
            var shelleyData = genesisConfig.getShelleyGenesisData();
            if (shelleyData != null) {
                long shiftMillis = actions.computeEpochShiftMillis(config.getStartEpoch());
                config.setGenesisTimestamp(System.currentTimeMillis() - shiftMillis);
                log.info("Epoch fast-forward: shifted genesis timestamp back by {}ms for startEpoch={}",
                        shiftMillis, config.getStartEpoch());
            }
        }

        actions.setResolvedGenesisTimestamp(genesisConfig.resolveAndPersistGenesisTimestamp(
                config.getGenesisTimestamp(), freshStart, config.getShelleyGenesisFile()));
        actions.refreshGenesisBootstrapDataFromGenesis();

        DevnetBlockBuilder blockBuilder = actions.createDevnetBlockBuilder(freshStart);
        actions.configureGenesisProducerPoolHash(blockBuilder);
        actions.setConwayEraStartIfFreshStart(freshStart);

        DevnetBlockProducer producer = actions.createLiveDevnetProducer(blockBuilder);
        actions.storeGenesisUtxosIfNeeded(freshStart);
        producer.start();

        if (freshStart && config.getStartEpoch() > 0) {
            long catchUpToSlot = (System.currentTimeMillis() - actions.resolvedGenesisTimestamp())
                    / config.getSlotLengthMillis();
            if (catchUpToSlot > 1) {
                log.info("Epoch fast-forward: producing empty blocks from slot 1 to {}", catchUpToSlot);
                int produced = producer.produceEmptyBlocksToSlot(catchUpToSlot);
                log.info("Epoch fast-forward complete: {} blocks produced", produced);
            }
        }

        log.info("Block producer started (devnet mode)");
    }

    private void startSlotLeaderBlockProducer() {
        log.info("Starting block producer (slot-leader mode)...");

        actions.loadAndPropagateGenesisConfig();
        actions.autoDeriveSlotLengthMillis();

        YanoConfig config = actions.config();
        GenesisConfig genesisConfig = actions.genesisConfig();
        var shelleyData = genesisConfig.getShelleyGenesisData();
        if (shelleyData == null) {
            throw new IllegalStateException("Shelley genesis data required for slot-leader mode");
        }

        boolean freshStart = actions.chainTip() == null;
        if (config.isDevMode()) {
            if (freshStart && config.getStartEpoch() > 0 && config.getGenesisTimestamp() <= 0) {
                long shiftMillis = actions.computeEpochShiftMillis(config.getStartEpoch());
                config.setGenesisTimestamp(System.currentTimeMillis() - shiftMillis);
                log.info("Epoch fast-forward: shifted genesis timestamp back by {}ms for startEpoch={}",
                        shiftMillis, config.getStartEpoch());
            }

            actions.setResolvedGenesisTimestamp(genesisConfig.resolveAndPersistGenesisTimestamp(
                    config.getGenesisTimestamp(), freshStart, config.getShelleyGenesisFile()));
            actions.refreshGenesisBootstrapDataFromGenesis();
        } else {
            actions.setResolvedGenesisTimestamp(genesisConfig.getSystemStartEpochMillis());
            if (actions.resolvedGenesisTimestamp() <= 0) {
                throw new IllegalStateException("systemStart required in shelley-genesis.json for slot-leader mode");
            }
        }

        long epochLength = shelleyData.epochLength();
        long securityParam = shelleyData.securityParam();
        double activeSlotsCoeff = genesisConfig.getActiveSlotsCoeff();
        long slotsPerKESPeriod = shelleyData.slotsPerKESPeriod();
        long maxKESEvolutions = shelleyData.maxKESEvolutions();

        try {
            SlotLeaderKeyMaterial keyMaterial = SlotLeaderKeyMaterial.load(config);
            String poolHash = keyMaterial.poolHash();
            log.info("Pool hash: {}", poolHash);

            long byronSlotsPerEpoch = genesisConfig.getByronGenesisData() != null
                    ? genesisConfig.getByronGenesisData().epochLength() : Constants.BYRON_SLOTS_PER_EPOCH;
            EpochNonceState epochNonceState = new EpochNonceState(
                    epochLength, securityParam, activeSlotsCoeff, byronSlotsPerEpoch);
            actions.setEpochNonceState(epochNonceState);
            actions.initializeNonceShelleyStartSlot(epochNonceState);

            NonceStateStore nonceStore = actions.nonceStoreOrNull();
            EpochParamProvider effectiveParamProvider = actions.effectiveEpochParamProvider();
            boolean trackedParams = effectiveParamProvider instanceof EpochParamTracker tracker
                    && tracker.isEnabled();
            long networkMagic = config.getProtocolMagic();
            ChainState chainState = actions.chainState();
            NonceReplayService replayService = nonceStore != null
                    ? new NonceReplayService(chainState, nonceStore,
                            new EpochNonceEvolver(effectiveParamProvider, trackedParams, networkMagic),
                            actions.resolveGenesisHash())
                    : null;

            actions.initializeProducerNonceState(
                    epochNonceState, nonceStore, replayService,
                    "block-producer-startup", "slot-leader mode");

            ProtocolVersionSupplier protocolVersionSupplier = actions.createBlockProtocolVersionSupplier();
            var signingComponents = SlotLeaderSigningComponents.create(
                    keyMaterial,
                    slotsPerKESPeriod,
                    maxKESEvolutions,
                    epochNonceState,
                    nonceStore,
                    protocolVersionSupplier,
                    BlockBodySizeLimitSupplier.fromEpochParams(actions::effectiveEpochParamProvider),
                    activeSlotsCoeff);
            var signedBlockBuilder = signingComponents.signedBlockBuilder();
            var slotLeaderCheck = signingComponents.slotLeaderCheck();
            actions.configureGenesisProducerPoolHash(signedBlockBuilder);

            var nonceRegistration = NonceEvolutionListenerFactory.registerSlotLeader(
                    actions.eventBus(),
                    epochNonceState,
                    nonceStore,
                    signedBlockBuilder,
                    effectiveParamProvider,
                    trackedParams,
                    networkMagic,
                    actions.nonceCursorResolver(),
                    replayService);
            actions.replaceNonceListenerSubscriptions(nonceRegistration.subscriptionHandles());

            var stakeDataProvider = StakeDataProviderFactory.createLiveSlotLeaderProvider(config);

            if (config.isDevMode() && freshStart) {
                actions.storeGenesisUtxosIfNeeded(freshStart);
                var genesisResult = signedBlockBuilder.buildBlock(0, 0, null, java.util.List.of());
                try {
                    BlockProducerHelper.publishGenesisBlockEvent(
                            actions.eventBus(), genesisResult, "slot-leader-genesis");
                } catch (RuntimeException | Error e) {
                    signedBlockBuilder.rollbackPendingNonceState();
                    throw e;
                }
                BlockProducerHelper.storeProducedBlock(chainState, signedBlockBuilder, genesisResult);
                log.info("Genesis block produced (slot-leader devnet): hash={}",
                        HexUtil.encodeHexString(genesisResult.blockHash()));

                actions.setConwayEraStartIfFreshStart(freshStart);

                BlockProducerHelper.publishEvent(
                        actions.eventBus(), genesisResult, 0, "slot-leader-genesis", false);
                actions.notifyServeNewDataAvailable();
            }

            if (config.isDevMode() && freshStart && config.getStartEpoch() > 0) {
                long initialTarget = (System.currentTimeMillis() - actions.resolvedGenesisTimestamp())
                        / config.getSlotLengthMillis();
                if (initialTarget > 1) {
                    log.info("Epoch fast-forward (slot-leader): catching up to wall-clock slot ~{}",
                            initialTarget);
                    int produced = 0;
                    long slot = 1;
                    int backfillInterval = BackfillPolicy.resolveInterval(config.getBackfillBlockIntervalSlots(),
                            securityParam, activeSlotsCoeff, true);
                    long forecastWindow = BackfillPolicy.forecastWindowSlots(securityParam, activeSlotsCoeff);
                    while (true) {
                        long currentWallClockSlot =
                                (System.currentTimeMillis() - actions.resolvedGenesisTimestamp())
                                        / config.getSlotLengthMillis();
                        ChainTip previousTip = chainState.getTip();
                        if (backfillInterval > 1 && previousTip != null) {
                            long nextEpoch = epochNonceState.firstSlotOfEpoch(
                                    epochNonceState.epochForSlot(previousTip.getSlot()) + 1);
                            slot = Math.max(slot, Math.min(previousTip.getSlot() + backfillInterval, nextEpoch));
                        }
                        if (slot > currentWallClockSlot) break;
                        if (backfillInterval > 1 && previousTip != null
                                && slot - previousTip.getSlot() >= forecastWindow) {
                            throw new IllegalStateException("Startup backfill found no eligible block within forecast window");
                        }

                        byte[] epochNonce = epochNonceState.previewEpochNonceForSlot(slot);
                        var vrfResult = slotLeaderCheck.checkAndProve(slot, epochNonce, java.math.BigDecimal.ONE);
                        if (vrfResult != null) {
                            ChainTip tip = chainState.getTip();
                            BlockProducerHelper.prepareEpochTransitionBeforeBlock(
                                    actions.eventBus(), slot, tip.getBlockNumber() + 1, "slot-leader-catch-up");
                            var result = signedBlockBuilder.buildBlock(
                                    tip.getBlockNumber() + 1,
                                    slot,
                                    tip.getBlockHash(),
                                    java.util.List.of(),
                                    vrfResult);
                            BlockProducerHelper.storeProducedBlock(chainState, signedBlockBuilder, result);
                            BlockProducerHelper.publishEvent(
                                    actions.eventBus(), result, 0, "slot-leader-catch-up");
                            produced++;
                        }
                        if (produced > 0 && produced % 1000 == 0) {
                            log.info("Epoch fast-forward progress: {} blocks produced, current slot={}",
                                    produced, slot);
                        }
                        slot++;
                    }
                    actions.notifyServeNewDataAvailable();
                    log.info("Epoch fast-forward complete (slot-leader): {} blocks produced, tip slot={}",
                            produced, chainState.getTip() != null ? chainState.getTip().getSlot() : -1);
                }
            }

            actions.slotLeaderProducerFactory().startLive(
                    signedBlockBuilder,
                    epochNonceState,
                    slotLeaderCheck,
                    stakeDataProvider,
                    poolHash,
                    actions.resolvedGenesisTimestamp(),
                    config.getSlotLengthMillis());
        } catch (Exception e) {
            throw new RuntimeException("Failed to start slot-leader block producer", e);
        }
    }

    /**
     * Runtime-owned concrete producer operations selected by this coordinator.
     */
    public interface Actions {
        void wireBlockProducerHelpers();

        ProducerStartupPlan startupPlan();

        default YanoConfig config() {
            throw unsupported();
        }

        default ChainState chainState() {
            throw unsupported();
        }

        default EventBus eventBus() {
            throw unsupported();
        }

        default GenesisConfig genesisConfig() {
            throw unsupported();
        }

        default ChainTip chainTip() {
            throw unsupported();
        }

        default void loadAndPropagateGenesisConfig() {
            throw unsupported();
        }

        default void autoDeriveBlockTimeMillis() {
            throw unsupported();
        }

        default void autoDeriveSlotLengthMillis() {
            throw unsupported();
        }

        default long computeEpochShiftMillis(int epochs) {
            throw unsupported();
        }

        default void setResolvedGenesisTimestamp(long timestampMillis) {
            throw unsupported();
        }

        default long resolvedGenesisTimestamp() {
            throw unsupported();
        }

        default void refreshGenesisBootstrapDataFromGenesis() {
            throw unsupported();
        }

        default DevnetBlockBuilder createDevnetBlockBuilder(boolean freshStart) {
            throw unsupported();
        }

        default void configureGenesisProducerPoolHash(DevnetBlockBuilder blockBuilder) {
            throw unsupported();
        }

        default void setConwayEraStartIfFreshStart(boolean freshStart) {
            throw unsupported();
        }

        default DevnetBlockProducer createLiveDevnetProducer(DevnetBlockBuilder blockBuilder) {
            throw unsupported();
        }

        default void storeGenesisUtxosIfNeeded(boolean freshStart) {
            throw unsupported();
        }

        default void notifyServeNewDataAvailable() {
            throw unsupported();
        }

        default void setEpochNonceState(EpochNonceState epochNonceState) {
            throw unsupported();
        }

        default void initializeNonceShelleyStartSlot(EpochNonceState epochNonceState) {
            throw unsupported();
        }

        default NonceStateStore nonceStoreOrNull() {
            throw unsupported();
        }

        default EpochParamProvider effectiveEpochParamProvider() {
            throw unsupported();
        }

        default byte[] resolveGenesisHash() {
            throw unsupported();
        }

        default void initializeProducerNonceState(EpochNonceState nonceState,
                                                  NonceStateStore nonceStore,
                                                  NonceReplayService replayService,
                                                  String operation,
                                                  String modeDescription) {
            throw unsupported();
        }

        default ProtocolVersionSupplier createBlockProtocolVersionSupplier() {
            throw unsupported();
        }

        default NonceEvolutionListener.NonceCursorResolver nonceCursorResolver() {
            throw unsupported();
        }

        default void replaceNonceListenerSubscriptions(List<SubscriptionHandle> subscriptionHandles) {
            throw unsupported();
        }

        default SlotLeaderProducerFactory slotLeaderProducerFactory() {
            throw unsupported();
        }

        void deferPastTimeTravelBlockProducer();

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("Producer startup action not configured for this mode");
        }
    }
}
