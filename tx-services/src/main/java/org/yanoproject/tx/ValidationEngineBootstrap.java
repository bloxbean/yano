package org.yanoproject.tx;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.client.spec.NetworkId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yanoproject.api.config.YanoPropertyKeys;
import org.yanoproject.api.util.EpochSlotCalc;
import org.yanoproject.ledger.rules.EngineContext;
import org.yanoproject.ledger.rules.EpochProtocolParamsSupplier;
import org.yanoproject.ledger.rules.LedgerValidationEngines;
import org.yanoproject.ledger.rules.NetworkParameters;
import org.yanoproject.ledger.rules.SlotConfigSupplier;
import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.phase2.ForecastHorizon;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseEvaluator;
import org.yanoproject.ledger.scripteval.phase2.JulcScriptPhaseEvaluator;
import org.yanoproject.ledger.rules.util.Phase2EnvDigest;
import org.yanoproject.runtime.blockproducer.GenesisConfig;
import org.yanoproject.runtime.genesis.ShelleyGenesisData;
import org.yanoproject.runtime.validation.ShadowValidationRunner;
import org.yanoproject.runtime.validation.ValidationEngineConfigurationException;
import org.yanoproject.runtime.validation.ValidationEngineSettings;
import org.yanoproject.runtime.validation.ValidationEngines;
import org.yanoproject.runtime.validation.ValidationEnvFactory;
import org.yanoproject.scalusbridge.ScalusScriptPhaseEvaluator;

import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Creates the validation engines of ADR-056 §7 from the runtime globals, when
 * {@link ValidationEngineSettings#usesEngineApi()}: engines are discovered with {@code ServiceLoader}
 * ({@code scalus} from scalus-bridge, {@code amaru} from the optional amaru-validator module), and each gets an
 * {@link EngineContext} with the node's configuration, genesis-derived network facts, slot timing and the
 * phase-2 evaluators: julc (engine {@code java-julc}) and Scalus (engines {@code java-scalus} and {@code amaru} with
 * {@code phase2: scalus}; ADR-056 Phase 7c).
 */
final class ValidationEngineBootstrap {

    private static final Logger log = LoggerFactory.getLogger(ValidationEngineBootstrap.class);

    /**
     * One admission validates at a time (the mempool's mutation lane serialises admission), one mempool rebuild
     * worker folds off the lane next to it (ADR-056 §6), plus the shadow workers:
     * {@code yano.validation.amaru.pool-size: 0} resolves to this.
     */
    static final int VALIDATION_THREADS = 2 + ShadowValidationRunner.DEFAULT_THREADS;

    private ValidationEngineBootstrap() {
    }

    /**
     * @return the engines, or empty when the configuration keeps the legacy path
     * @throws ValidationEngineConfigurationException when a configured engine is unavailable or invalid
     */
    static Optional<ValidationEngines> create(Map<String, Object> globals, GenesisConfig genesis,
                                              EpochSlotCalc epochSlotCalc, SlotConfigSupplier slotConfig,
                                              EpochProtocolParamsSupplier protocolParams, LongSupplier currentSlot,
                                              long protocolMagic, int networkId, boolean supplementaryRulesEnabled) {
        ValidationEngineSettings settings;
        try {
            settings = ValidationEngineSettings.fromGlobals(globals);
        } catch (RuntimeException e) {
            throw new ValidationEngineConfigurationException("Invalid validation engine settings: " + e.getMessage(),
                    e);
        }
        if (supplementaryRulesEnabled && settings.engineAdmission()) {
            // The supplementary CCL rules layer on the legacy Scalus validator only; they cannot be combined
            // with another admission engine, and silently dropping them would change what is admitted.
            throw new ValidationEngineConfigurationException(YanoPropertyKeys.Validation.SUPPLEMENTARY_RULES_ENABLED
                    + "=true applies to the legacy Scalus validator only and cannot be combined with "
                    + YanoPropertyKeys.Validation.ENGINE + "=" + settings.engine()
                    + "; unset one of them (supplementary-rules-enabled is deprecated, ADR-056 §7)");
        }
        if (!settings.usesEngineApi()) {
            return Optional.empty();
        }
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        LedgerValidationEngines registry;
        try {
            registry = LedgerValidationEngines.discover(
                    loader != null ? loader : ValidationEngineBootstrap.class.getClassLoader());
        } catch (RuntimeException e) {
            // A listed engine provider that cannot be loaded (a native image without its reflection
            // registration, a broken plugin jar) stops the node: running on without any validation would be a
            // silent fallback (ADR-056 §7, ADR-057 §2).
            throw new ValidationEngineConfigurationException(e.getMessage(), e);
        }
        Supplier<NetworkParameters> network = memoized(network(genesis, epochSlotCalc, slotConfig, protocolMagic,
                networkId));
        ForecastHorizon horizon = ForecastHorizon.of(() -> network.get().stabilityWindow(), epochSlotCalc);
        // Both phase-2 evaluators share the forecast horizon: julc for java-julc, Scalus for java-scalus and amaru.
        EngineContext context = new Context(globals, network, protocolParams, slotConfig, currentSlot,
                new ScalusScriptPhaseEvaluator(horizon), new JulcScriptPhaseEvaluator(horizon));
        return Optional.of(ValidationEngines.create(settings, registry, context,
                envFactory(epochSlotCalc, slotConfig, networkId)));
    }

    static ValidationEnvFactory envFactory(EpochSlotCalc epochSlotCalc, SlotConfigSupplier slotConfig, int networkId) {
        NetworkId id = networkId == 1 ? NetworkId.MAINNET : NetworkId.TESTNET;
        return (slot, view) -> {
            ProtocolParams params = view.protocolParams().require("protocol parameters");
            SlotConfig timing = Objects.requireNonNull(slotConfig.getSlotConfig(), "slot config");
            return new ValidationEnv(slot, epochSlotCalc.slotToEpoch(slot), params.getProtocolMajorVer(),
                    params.getProtocolMinorVer(), id, timing, Phase2EnvDigest.of(params));
        };
    }

    /**
     * The genesis-derived network facts, resolved when an engine first needs them: a devnet's system start
     * is known only once its genesis timestamp is resolved.
     */
    static Supplier<NetworkParameters> network(GenesisConfig genesis, EpochSlotCalc epochSlotCalc,
                                               SlotConfigSupplier slotConfig, long protocolMagic, int networkId) {
        return () -> {
            ShelleyGenesisData shelley = genesis != null ? genesis.getShelleyGenesisData() : null;
            if (shelley == null) {
                throw new IllegalStateException("no Shelley genesis is loaded");
            }
            SlotConfig timing = Objects.requireNonNull(slotConfig.getSlotConfig(), "slot config");
            long firstNonByronSlot = epochSlotCalc.firstNonByronSlot();
            long byronSlotMs = genesis.getByronSlotDurationSeconds() * 1000;
            long systemStartMs = timing.getZeroTime() - firstNonByronSlot * byronSlotMs;
            long byronEpochLength = genesis.getByronGenesisData() != null
                    ? genesis.getByronGenesisData().epochLength() : shelley.securityParam() * 10;
            return new NetworkParameters(protocolMagic, networkId == 1 ? NetworkId.MAINNET : NetworkId.TESTNET,
                    shelley.securityParam(), shelley.activeSlotsCoeff(), BigInteger.valueOf(shelley.maxLovelaceSupply()),
                    shelley.slotsPerKESPeriod(), (int) shelley.maxKESEvolutions(), systemStartMs, byronEpochLength,
                    byronSlotMs, firstNonByronSlot, epochSlotCalc.shelleyEpochLength(), timing.getSlotLength());
        };
    }

    private static <T> Supplier<T> memoized(Supplier<T> supplier) {
        AtomicReference<T> value = new AtomicReference<>();
        return () -> {
            T current = value.get();
            if (current == null) {
                current = supplier.get();
                value.compareAndSet(null, current);
            }
            return current;
        };
    }

    /** The {@link EngineContext} handed to engine factories. */
    private record Context(Map<String, Object> globals, Supplier<NetworkParameters> network,
                           EpochProtocolParamsSupplier protocolParams, SlotConfigSupplier slotConfig,
                           LongSupplier currentSlot, ScriptPhaseEvaluator scriptPhaseEvaluator,
                           ScriptPhaseEvaluator julcEvaluator)
            implements EngineContext {

        @Override
        public ScriptPhaseEvaluator julcScriptPhaseEvaluator() {
            return julcEvaluator;
        }

        @Override
        public Optional<String> config(String key) {
            Object value = globals != null ? globals.get(key) : null;
            return value == null ? Optional.empty() : Optional.of(String.valueOf(value));
        }

        @Override
        public int validationThreads() {
            return VALIDATION_THREADS;
        }
    }
}
