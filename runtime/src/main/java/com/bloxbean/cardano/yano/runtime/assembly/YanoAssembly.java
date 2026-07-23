package com.bloxbean.cardano.yano.runtime.assembly;

import com.bloxbean.cardano.yano.api.bootstrap.BootstrapDataProvider;
import com.bloxbean.cardano.yano.api.config.RuntimeOptions;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.runtime.internal.RuntimeNode;
import com.bloxbean.cardano.yano.runtime.plugins.PluginLoaderHandle;
import com.bloxbean.cardano.yano.runtime.plugins.PluginRuntimeEnvironment;
import com.bloxbean.cardano.yano.runtime.config.InMemoryDevnetGenesis;
import com.bloxbean.cardano.yano.runtime.kernel.Schedulers;
import com.bloxbean.cardano.yano.runtime.producer.ProducerMode;
import com.bloxbean.cardano.yano.runtime.producer.ProducerStartupPlan;
import com.bloxbean.cardano.yano.runtime.sync.validation.BodyValidationPipeline;
import com.bloxbean.cardano.yano.runtime.sync.validation.BodyValidator;
import com.bloxbean.cardano.yano.runtime.tx.TransactionBootstrapOptions;
import com.bloxbean.cardano.yano.runtime.util.LifecycleFailures;
import com.bloxbean.cardano.yano.runtime.tx.TransactionServices;
import com.bloxbean.cardano.yano.runtime.tx.TransactionServicesFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Explicit runtime composition root.
 */
public final class YanoAssembly {
    private static final Logger log = LoggerFactory.getLogger(YanoAssembly.class);
    private static final String[] BOOTSTRAP_DISABLED_DERIVED_LEDGER_KEYS = {
            YanoPropertyKeys.AccountState.ENABLED,
            YanoPropertyKeys.AccountState.STAKE_BALANCE_INDEX_ENABLED,
            YanoPropertyKeys.AccountHistory.ENABLED,
            YanoPropertyKeys.AccountHistory.TX_EVENTS_ENABLED,
            YanoPropertyKeys.AccountHistory.REWARDS_ENABLED,
            YanoPropertyKeys.EpochSnapshot.AMOUNTS_ENABLED,
            YanoPropertyKeys.Ledger.ADAPOT_ENABLED,
            YanoPropertyKeys.Ledger.REWARDS_ENABLED,
            YanoPropertyKeys.Ledger.EPOCH_PARAMS_TRACKING_ENABLED,
            YanoPropertyKeys.Ledger.GOVERNANCE_ENABLED,
            YanoPropertyKeys.SnapshotExport.ENABLED
    };

    private YanoAssembly() {
    }

    public static Builder relay(YanoConfig config) {
        return new Builder(Role.RELAY, config);
    }

    public static Builder devnet(YanoConfig config) {
        return new Builder(Role.DEVNET, config);
    }

    public static Builder slotLeader(YanoConfig config) {
        return new Builder(Role.SLOT_LEADER, config);
    }

    public static Builder devnetTimeTravel(YanoConfig config) {
        return new Builder(Role.DEVNET_TIME_TRAVEL, config);
    }

    /**
     * Selects an assembly recipe from producer/devnet configuration flags.
     * Explicit recipe methods should be preferred when the host already knows
     * the intended role.
     */
    public static Builder fromConfig(YanoConfig config) {
        Objects.requireNonNull(config, "config");
        if (config.isEnableBlockProducer() && config.isSlotLeaderMode()) {
            return slotLeader(config);
        }
        if (config.isDevMode() && config.isEnableBlockProducer()) {
            return config.isPastTimeTravelMode()
                    ? devnetTimeTravel(config)
                    : devnet(config);
        }
        return relay(config);
    }

    /**
     * Returns true when the runtime is bootstrapping partial chain state from an
     * external provider instead of replaying the full chain locally.
     */
    public static boolean isBootstrapPartialStateMode(YanoConfig config) {
        return config != null && config.isEnableBootstrap();
    }

    /**
     * Applies runtime-owned bootstrap-mode policy to derived ledger-state
     * globals. In bootstrap partial-state mode, UTXO bootstrap stays enabled but
     * derived indexes that require full history are forced off.
     */
    public static RuntimeOptions applyBootstrapPartialStatePolicy(YanoConfig config,
                                                                  RuntimeOptions runtimeOptions) {
        RuntimeOptions base = runtimeOptions != null ? runtimeOptions : RuntimeOptions.defaults();
        if (!isBootstrapPartialStateMode(config)) {
            return base;
        }

        Map<String, Object> globals = new HashMap<>(base.globals());
        boolean changed = false;
        for (String key : BOOTSTRAP_DISABLED_DERIVED_LEDGER_KEYS) {
            Object previous = globals.put(key, false);
            changed |= !Boolean.FALSE.equals(previous);
        }
        if (changed) {
            log.info("Bootstrap mode enabled: disabling derived ledger-state subsystems "
                    + "(account-state, stake-balance-index, account-history, epoch-params, "
                    + "rewards, adapot, governance, snapshots). UTXO bootstrap remains enabled; "
                    + "transaction evaluation may use protocol-param.json if configured.");
        }
        return new RuntimeOptions(base.events(), base.plugins(), globals);
    }

    /**
     * Resolves an individual derived ledger-state flag after runtime bootstrap
     * policy has been applied.
     */
    public static boolean effectiveDerivedLedgerStateEnabled(YanoConfig config, boolean configured) {
        return configured && !isBootstrapPartialStateMode(config);
    }

    /**
     * Runtime recipe selected by the composition root.
     */
    public enum Role {
        RELAY,
        SLOT_LEADER,
        DEVNET,
        DEVNET_TIME_TRAVEL
    }

    /**
     * Mutable assembly recipe used to configure optional runtime collaborators
     * before creating a {@link Yano}.
     */
    public static final class Builder {
        private final Role role;
        private final YanoConfig config;
        private RuntimeOptions runtimeOptions = RuntimeOptions.defaults();
        private InMemoryDevnetGenesis inMemoryGenesis;
        private TransactionBootstrapOptions transactionBootstrapOptions = TransactionBootstrapOptions.disabled();
        private TransactionServicesFactory transactionServicesFactory;
        private BodyValidator bodyValidator = BodyValidator.none();
        private BootstrapDataProvider bootstrapDataProvider;
        private PluginLoaderHandle pluginLoader;
        private boolean adhocRollbackConfigured;
        private long adhocRollbackToSlot = -1;
        private int adhocRollbackToEpoch = -1;

        private Builder(Role role, YanoConfig config) {
            this.role = Objects.requireNonNull(role, "role");
            this.config = Objects.requireNonNull(config, "config");
        }

        public Builder runtimeOptions(RuntimeOptions runtimeOptions) {
            this.runtimeOptions = runtimeOptions != null ? runtimeOptions : RuntimeOptions.defaults();
            return this;
        }

        public Builder inMemoryGenesis(InMemoryDevnetGenesis inMemoryGenesis) {
            this.inMemoryGenesis = inMemoryGenesis;
            return this;
        }

        public Builder bootstrapDataProvider(BootstrapDataProvider bootstrapDataProvider) {
            this.bootstrapDataProvider = bootstrapDataProvider;
            return this;
        }

        /** Transfer an explicit plugin loader lifetime into the runtime. */
        public Builder pluginLoader(PluginLoaderHandle pluginLoader) {
            this.pluginLoader = Objects.requireNonNull(pluginLoader, "pluginLoader");
            return this;
        }

        public Builder adhocRollback(long rollbackToSlot, int rollbackToEpoch) {
            this.adhocRollbackConfigured = rollbackToSlot >= 0 || rollbackToEpoch >= 0;
            this.adhocRollbackToSlot = rollbackToSlot;
            this.adhocRollbackToEpoch = rollbackToEpoch;
            return this;
        }

        public Builder transactionBootstrap(TransactionBootstrapOptions options,
                                            TransactionServicesFactory servicesFactory) {
            this.transactionBootstrapOptions = options != null ? options : TransactionBootstrapOptions.disabled();
            this.transactionServicesFactory = servicesFactory;
            return this;
        }

        public Builder bodyValidation(Consumer<BodyValidationBuilder> customizer) {
            BodyValidationBuilder builder = new BodyValidationBuilder();
            if (customizer != null) {
                customizer.accept(builder);
            }
            this.bodyValidator = builder.build();
            return this;
        }

        public Yano build() {
            validateRole();
            Schedulers schedulers = new Schedulers();
            PluginRuntimeEnvironment pluginEnvironment = null;
            RuntimeNode runtimeNode = null;
            try {
                pluginEnvironment = PluginRuntimeEnvironment.open(
                        runtimeOptions.plugins(), pluginLoader != null
                                ? pluginLoader
                                : PluginLoaderHandle.classpath(
                                        Thread.currentThread().getContextClassLoader()));
                runtimeNode = new RuntimeNode(config, runtimeOptions, inMemoryGenesis,
                        producerStartupPlan(), schedulers, bodyValidator, pluginEnvironment);
                applyPreStartConfiguration(runtimeNode);
                installTransactionServices(runtimeNode);
                return new RuntimeYano(
                        runtimeNode,
                        runtimeNode,
                        runtimeNode,
                        runtimeNode,
                        runtimeNode,
                        runtimeNode,
                        runtimeNode.getMaintenanceGate(),
                        runtimeNode,
                        runtimeNode,
                        role,
                        schedulers);
            } catch (Throwable failure) {
                RuntimeNode failedRuntimeNode = runtimeNode;
                PluginRuntimeEnvironment failedPluginEnvironment = pluginEnvironment;
                throw cleanupBuildFailure(failure,
                        () -> {
                            if (failedRuntimeNode != null) {
                                failedRuntimeNode.close();
                            }
                        },
                        () -> {
                            if (failedPluginEnvironment != null) {
                                failedPluginEnvironment.close();
                            }
                        },
                        schedulers::close);
            }
        }

        private void applyPreStartConfiguration(RuntimeNode runtimeNode) {
            if (adhocRollbackConfigured) {
                runtimeNode.setAdhocRollback(adhocRollbackToSlot, adhocRollbackToEpoch);
            }
            if (bootstrapDataProvider != null) {
                runtimeNode.setBootstrapDataProvider(bootstrapDataProvider);
            }
        }

        private void installTransactionServices(RuntimeNode runtimeNode) {
            if (!transactionBootstrapOptions.enabled()) {
                return;
            }

            if (transactionServicesFactory == null) {
                log.warn("Transaction validation/evaluation not initialized: no transaction services factory configured");
                return;
            }

            TransactionServices services;
            try {
                services = transactionServicesFactory
                        .create(new RuntimeTransactionBootstrapContext(runtimeNode, config, inMemoryGenesis),
                                transactionBootstrapOptions)
                        .orElse(null);
            } catch (Exception e) {
                log.warn("Transaction validation/evaluation not initialized: {}", e.getMessage(), e);
                return;
            }

            if (services == null || !services.hasServices()) {
                log.warn("Transaction validation/evaluation not initialized: no transaction services created");
                return;
            }

            if (services.validator() != null) {
                runtimeNode.setTransactionEvaluator(services.validator());
            }
            if (services.scriptEvaluator() != null) {
                runtimeNode.setScriptEvaluator(services.scriptEvaluator());
            }
        }

        private void validateRole() {
            if ((role == Role.RELAY || role == Role.SLOT_LEADER) && inMemoryGenesis != null) {
                throw new IllegalStateException("In-memory genesis is only valid for devnet recipes");
            }
            if (role == Role.SLOT_LEADER
                    && (!config.isEnableBlockProducer() || !config.isSlotLeaderMode())) {
                throw new IllegalStateException("SLOT_LEADER recipe requires enableBlockProducer=true and slotLeaderMode=true");
            }
            if ((role == Role.DEVNET || role == Role.DEVNET_TIME_TRAVEL)
                    && (!config.isDevMode() || !config.isEnableBlockProducer())) {
                throw new IllegalStateException(role + " recipe requires devMode=true and enableBlockProducer=true");
            }
            if (role == Role.DEVNET_TIME_TRAVEL && !config.isPastTimeTravelMode()) {
                throw new IllegalStateException("DEVNET_TIME_TRAVEL recipe requires pastTimeTravelMode=true");
            }
            if (role == Role.DEVNET && (config.isPastTimeTravelMode()
                    || config.isSlotLeaderMode())) {
                throw new IllegalStateException("DEVNET recipe requires a non-time-travel devnet producer configuration");
            }
            if (role == Role.DEVNET_TIME_TRAVEL && config.isSlotLeaderMode()
                    && !config.isPastTimeTravelSlotLeaderMode()) {
                throw new IllegalStateException(
                        "DEVNET_TIME_TRAVEL recipe requires pastTimeTravelSlotLeaderMode=true for slot-leader time travel");
            }
        }

        private ProducerStartupPlan producerStartupPlan() {
            if (!config.isEnableBlockProducer()) {
                return null;
            }

            return switch (role) {
                case RELAY -> ProducerStartupPlan.from(config);
                case SLOT_LEADER -> new ProducerStartupPlan(ProducerMode.SLOT_LEADER, false);
                case DEVNET -> new ProducerStartupPlan(ProducerMode.DEVNET, false);
                case DEVNET_TIME_TRAVEL -> new ProducerStartupPlan(
                        config.isPastTimeTravelSlotLeaderMode()
                                ? ProducerMode.SLOT_LEADER_TIME_TRAVEL
                                : ProducerMode.DEVNET_TIME_TRAVEL,
                        true);
            };
        }
    }

    /**
     * Runs every construction rollback action and preserves fatal-error
     * precedence. A cleanup action may encounter the same throwable as the
     * construction path, so self-suppression must not abort later cleanup.
     */
    static RuntimeException cleanupBuildFailure(Throwable primary, Runnable... cleanupActions) {
        Throwable outcome = Objects.requireNonNull(primary, "primary");
        for (Runnable cleanupAction : cleanupActions) {
            try {
                Objects.requireNonNull(cleanupAction, "cleanupAction").run();
            } catch (Throwable cleanupFailure) {
                outcome = mergeCleanupFailure(outcome, cleanupFailure);
            }
        }
        if (outcome instanceof Error error) {
            throw error;
        }
        if (outcome instanceof RuntimeException runtime) {
            return runtime;
        }
        return new IllegalStateException("Yano assembly construction failed", outcome);
    }

    private static Throwable mergeCleanupFailure(Throwable current, Throwable next) {
        return LifecycleFailures.merge(current, next);
    }

    /**
     * Builder for future block-body validation presets and custom validators.
     */
    public static final class BodyValidationBuilder {
        private final List<BodyValidator> validators = new ArrayList<>();
        private boolean disabled;

        public BodyValidationBuilder useDefault(String preset) {
            String normalized = preset == null || preset.isBlank()
                    ? "none"
                    : preset.trim().toLowerCase(Locale.ROOT);
            if (!"none".equals(normalized)) {
                throw new IllegalArgumentException("Unsupported body validation default preset: " + preset
                        + " (supported now: none)");
            }
            disabled = false;
            return this;
        }

        public BodyValidationBuilder add(BodyValidator validator) {
            Objects.requireNonNull(validator, "validator");
            disabled = false;
            validators.add(validator);
            return this;
        }

        public BodyValidationBuilder replaceDefault(BodyValidator validator) {
            Objects.requireNonNull(validator, "validator");
            disabled = false;
            validators.clear();
            validators.add(validator);
            return this;
        }

        public BodyValidationBuilder disabled() {
            disabled = true;
            validators.clear();
            return this;
        }

        private BodyValidator build() {
            if (disabled || validators.isEmpty()) {
                return BodyValidator.none();
            }
            return BodyValidationPipeline.of(validators);
        }
    }
}
