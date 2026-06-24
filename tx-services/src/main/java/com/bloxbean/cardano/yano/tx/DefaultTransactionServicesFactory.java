package com.bloxbean.cardano.yano.tx;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.api.util.EpochSlotCalc;
import com.bloxbean.cardano.yano.ledgerrules.EpochProtocolParamsSupplier;
import com.bloxbean.cardano.yano.ledgerrules.SlotConfigSupplier;
import com.bloxbean.cardano.yano.ledgerrules.TransactionEvaluator;
import com.bloxbean.cardano.yano.ledgerrules.TransactionValidator;
import com.bloxbean.cardano.yano.ledgerrules.impl.AikenTxEvaluator;
import com.bloxbean.cardano.yano.ledgerrules.impl.JulcTxEvaluator;
import com.bloxbean.cardano.yano.ledgerrules.impl.YaciScriptSupplier;
import com.bloxbean.cardano.yano.runtime.blockproducer.GenesisConfig;
import com.bloxbean.cardano.yano.runtime.config.DefaultEpochParamProvider;
import com.bloxbean.cardano.yano.runtime.config.NetworkGenesisConfig;
import com.bloxbean.cardano.yano.runtime.config.InMemoryDevnetGenesis;
import com.bloxbean.cardano.yano.runtime.tx.ProtocolParamsMapper;
import com.bloxbean.cardano.yano.runtime.tx.TransactionBootstrapContext;
import com.bloxbean.cardano.yano.runtime.tx.TransactionBootstrapOptions;
import com.bloxbean.cardano.yano.runtime.tx.TransactionServices;
import com.bloxbean.cardano.yano.scalusbridge.ScalusTransactionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Optional transaction validation/evaluation bootstrap.
 *
 * Runtime assembly owns service installation; this factory only creates the
 * concrete Scalus/Aiken/JULC-backed services. Keeping it in this optional module
 * avoids pulling Scalus bridge dependencies into the core runtime.
 */
public final class DefaultTransactionServicesFactory {
    private static final Logger log = LoggerFactory.getLogger(DefaultTransactionServicesFactory.class);

    private DefaultTransactionServicesFactory() {
    }

    public static Optional<TransactionServices> create(TransactionBootstrapContext context,
                                                       TransactionBootstrapOptions options) {
        if (options == null || !options.enabled()) {
            return Optional.empty();
        }

        YanoConfig yaciConfig = context.config();
        boolean effectiveEpochParamsTrackingEnabled = options.effectiveEpochParamsTrackingEnabled();
        boolean supplementaryRulesEnabled = options.supplementaryRulesEnabled();
        String scriptEvaluator = options.scriptEvaluator();
        LedgerStateProvider ledgerStateProvider = context.ledgerStateProvider();

        GenesisConfig genesis;
        SlotConfigSupplier slotConfigSupplier;
        int networkId;
        EpochProtocolParamsSupplier protocolParamsSupplier;
        LongSupplier currentSlotSupplier;
        EpochSlotCalc epochSlotCalc;
        String protocolParamsSource;
        boolean requireLedgerStateProviderForValidation;
        try {
            boolean loadStaticProtocolParams =
                    !effectiveEpochParamsTrackingEnabled || ledgerStateProvider == null;
            genesis = resolveGenesisConfig(context, yaciConfig, loadStaticProtocolParams);

            epochSlotCalc = resolveEpochSlotCalc(context, yaciConfig, genesis);
            ProtocolParamsResolution protocolParamsResolution = resolveTransactionProtocolParams(
                    context, effectiveEpochParamsTrackingEnabled, ledgerStateProvider, genesis, epochSlotCalc, yaciConfig);
            if (protocolParamsResolution == null) {
                return Optional.empty();
            }
            protocolParamsSupplier = protocolParamsResolution.supplier();
            protocolParamsSource = protocolParamsResolution.source();
            requireLedgerStateProviderForValidation = protocolParamsResolution.requireLedgerStateProvider();

            currentSlotSupplier = () -> {
                var tip = context.localTip();
                return tip != null ? tip.getSlot() : -1L;
            };
            RuntimeSlotConfigSupplier runtimeSlotConfigSupplier = new RuntimeSlotConfigSupplier(
                    yaciConfig, context::resolvedGenesisTimestamp, genesis);
            slotConfigSupplier = runtimeSlotConfigSupplier;

            long magic = yaciConfig.getProtocolMagic();

            if (runtimeSlotConfigSupplier.canResolveZeroTimeNow()) {
                var initialSlotConfig = runtimeSlotConfigSupplier.getSlotConfig();
                log.info("Yano transaction slot config: slotLengthMillis={}, zeroSlot={}, zeroTimeMillis={}",
                        initialSlotConfig.getSlotLength(),
                        initialSlotConfig.getZeroSlot(),
                        initialSlotConfig.getZeroTime());
            } else {
                log.info("Yano transaction slot config supplier prepared; zeroTime will resolve at runtime");
            }

            networkId = magic == Constants.MAINNET_PROTOCOL_MAGIC ? 1 : 0;
        } catch (Exception e) {
            log.warn("Transaction validation/evaluation not initialized: {}", e.getMessage(), e);
            return Optional.empty();
        }

        TransactionValidator validator = null;
        try {
            validator = ScalusTransactionFactory.createValidator(protocolParamsSupplier,
                    new YaciScriptSupplier(context.utxoState()), slotConfigSupplier, networkId,
                    ledgerStateProvider, currentSlotSupplier, epochSlotCalc::slotToEpoch,
                    requireLedgerStateProviderForValidation, supplementaryRulesEnabled);
            log.info("Transaction validator created (networkId={}, protocolParams={}, supplementaryRules={})",
                    networkId, protocolParamsSource, supplementaryRulesEnabled);
        } catch (Exception e) {
            log.error("Failed to initialize transaction validator (Scalus). "
                    + "Transactions will NOT be validated on submission! Error: {}", e.getMessage(), e);
        }

        TransactionEvaluator transactionEvaluator = null;
        try {
            if ("scalus".equalsIgnoreCase(scriptEvaluator)) {
                transactionEvaluator = ScalusTransactionFactory.createEvaluator(protocolParamsSupplier,
                        new YaciScriptSupplier(context.utxoState()), slotConfigSupplier, networkId, currentSlotSupplier);
            } else if ("julc".equalsIgnoreCase(scriptEvaluator)) {
                transactionEvaluator = new JulcTxEvaluator(
                        () -> protocolParamsSupplier.getProtocolParams(resolveRuntimeCurrentSlot(currentSlotSupplier)),
                        new YaciScriptSupplier(context.utxoState()), slotConfigSupplier);
            } else {
                transactionEvaluator = new AikenTxEvaluator(
                        () -> protocolParamsSupplier.getProtocolParams(resolveRuntimeCurrentSlot(currentSlotSupplier)),
                        new YaciScriptSupplier(context.utxoState()), slotConfigSupplier);
            }
            log.info("Script evaluator created (networkId={}, evaluator={})", networkId, scriptEvaluator);
        } catch (Exception e) {
            log.error("Failed to initialize script evaluator ({}). "
                    + "The /utils/txs/evaluate endpoint will not work. Error: {}", scriptEvaluator, e.getMessage(), e);
        }

        if (validator == null && transactionEvaluator == null) {
            log.error("Neither transaction validator nor script evaluator could be initialized. "
                    + "Plutus script transactions will not be validated!");
            return Optional.empty();
        }

        return Optional.of(new TransactionServices(validator, transactionEvaluator));
    }

    private static ProtocolParamsResolution resolveTransactionProtocolParams(TransactionBootstrapContext context,
                                                                            boolean effectiveEpochParamsTrackingEnabled,
                                                                            LedgerStateProvider ledgerStateProvider,
                                                                            GenesisConfig genesis,
                                                                            EpochSlotCalc epochSlotCalc,
                                                                            YanoConfig yaciConfig) {
        ProtocolParamsResolution resolution = selectTransactionProtocolParams(
                effectiveEpochParamsTrackingEnabled,
                ledgerStateProvider,
                epochSlotCalc,
                () -> resolveStaticProtocolParams(genesis, yaciConfig.getProtocolParametersFile()),
                () -> resolveGenesisProtocolParams(context, yaciConfig));
        if (resolution != null) {
            return resolution;
        }

        log.warn("Transaction validation/evaluation not initialized: no protocol params source available "
                        + "(effectiveLedger={}, protocolParamFile={}, shelleyGenesis={})",
                effectiveEpochParamsTrackingEnabled && ledgerStateProvider != null,
                sourceLabel(yaciConfig.getProtocolParametersFile()),
                sourceLabel(yaciConfig.getShelleyGenesisFile()));
        return null;
    }

    static ProtocolParamsResolution selectTransactionProtocolParams(boolean effectiveEpochParamsTrackingEnabled,
                                                                    LedgerStateProvider ledgerStateProvider,
                                                                    EpochSlotCalc epochSlotCalc,
                                                                    Supplier<ProtocolParams> staticParamsSupplier,
                                                                    Supplier<ProtocolParams> genesisParamsSupplier) {
        return TransactionProtocolParamsResolver.select(
                effectiveEpochParamsTrackingEnabled,
                ledgerStateProvider,
                epochSlotCalc,
                staticParamsSupplier,
                genesisParamsSupplier);
    }

    private static ProtocolParams resolveStaticProtocolParams(GenesisConfig genesis, String protocolParamsFile) {
        if (genesis == null || !genesis.hasProtocolParameters()) {
            return null;
        }

        try {
            ProtocolParams params = ProtocolParamsMapper.fromNodeProtocolParam(genesis.getProtocolParameters());
            validateProtocolVersion(params, "protocol-param.json");
            log.info("Transaction protocol params source: protocol-param-json file={} version={}.{}",
                    sourceLabel(protocolParamsFile),
                    params.getProtocolMajorVer(),
                    params.getProtocolMinorVer());
            return params;
        } catch (Exception e) {
            log.warn("Failed to resolve transaction protocol params from protocol-param.json file={}: {}",
                    sourceLabel(protocolParamsFile), e.toString());
            return null;
        }
    }

    private static GenesisConfig resolveGenesisConfig(TransactionBootstrapContext context,
                                                      YanoConfig yaciConfig,
                                                      boolean loadStaticProtocolParams) {
        InMemoryDevnetGenesis inMemoryGenesis = context.inMemoryDevnetGenesis();
        if (inMemoryGenesis != null) {
            return GenesisConfig.fromInMemory(
                    inMemoryGenesis.shelley(),
                    inMemoryGenesis.byron(),
                    loadStaticProtocolParams ? inMemoryGenesis.protocolParametersJson() : null);
        }

        return GenesisConfig.load(
                yaciConfig.getShelleyGenesisFile(),
                yaciConfig.getByronGenesisFile(),
                loadStaticProtocolParams ? yaciConfig.getProtocolParametersFile() : null);
    }

    private static ProtocolParams resolveGenesisProtocolParams(TransactionBootstrapContext context,
                                                              YanoConfig yaciConfig) {
        try {
            NetworkGenesisConfig networkGenesisConfig;
            InMemoryDevnetGenesis inMemoryGenesis = context.inMemoryDevnetGenesis();
            if (inMemoryGenesis != null) {
                networkGenesisConfig = NetworkGenesisConfig.fromInMemory(
                        inMemoryGenesis.shelley(),
                        inMemoryGenesis.byron(),
                        inMemoryGenesis.conway());
            } else {
                networkGenesisConfig = NetworkGenesisConfig.load(
                        yaciConfig.getShelleyGenesisFile(),
                        yaciConfig.getByronGenesisFile(),
                        yaciConfig.getAlonzoGenesisFile(),
                        yaciConfig.getConwayGenesisFile());
            }
            long firstNonByronSlot = DefaultEpochParamProvider.resolveFirstNonByronSlot(
                    networkGenesisConfig.getNetworkMagic(),
                    networkGenesisConfig.hasByronGenesis());
            var provider = DefaultEpochParamProvider.fromNetworkGenesisConfig(
                    networkGenesisConfig, firstNonByronSlot);
            ProtocolParams params = ProtocolParamsMapper.fromEpochParamProvider(provider, 0);
            validateProtocolVersion(params, "genesis bootstrap");
            log.info("Transaction protocol params source: genesis-bootstrap shelley={} alonzo={} conway={} version={}.{}",
                    sourceLabel(yaciConfig.getShelleyGenesisFile()),
                    sourceLabel(yaciConfig.getAlonzoGenesisFile()),
                    sourceLabel(yaciConfig.getConwayGenesisFile()),
                    params.getProtocolMajorVer(),
                    params.getProtocolMinorVer());
            return params;
        } catch (Exception e) {
            log.warn("Failed to resolve transaction protocol params from genesis bootstrap "
                            + "shelley={} alonzo={} conway={}: {}",
                    sourceLabel(yaciConfig.getShelleyGenesisFile()),
                    sourceLabel(yaciConfig.getAlonzoGenesisFile()),
                    sourceLabel(yaciConfig.getConwayGenesisFile()),
                    e.toString());
            return null;
        }
    }

    private static void validateProtocolVersion(ProtocolParams params, String source) {
        Integer major = params != null ? params.getProtocolMajorVer() : null;
        Integer minor = params != null ? params.getProtocolMinorVer() : null;
        if (major == null || major <= 0 || minor == null || minor < 0) {
            throw new IllegalStateException("Protocol version not found or invalid in " + source);
        }
    }

    private static String sourceLabel(String source) {
        return source != null && !source.isBlank() ? source : "not-configured";
    }

    static long resolveRuntimeCurrentSlot(LongSupplier currentSlotSupplier) {
        return TransactionProtocolParamsResolver.resolveRuntimeCurrentSlot(currentSlotSupplier);
    }

    private static EpochSlotCalc resolveEpochSlotCalc(TransactionBootstrapContext context,
                                                      YanoConfig yaciConfig,
                                                      GenesisConfig genesis) {
        var provider = context.epochParamProvider();
        if (provider != null) {
            return provider.getEpochSlotCalc();
        }

        if (yaciConfig.isEpochParamsInitialized()) {
            return new EpochSlotCalc(yaciConfig.getEpochLength(),
                    yaciConfig.getByronSlotsPerEpoch(),
                    yaciConfig.getFirstNonByronSlot());
        }

        var shelley = genesis.getShelleyGenesisData();
        if (shelley == null) {
            throw new IllegalStateException("Cannot resolve epoch slot math without Shelley genesis");
        }

        boolean hasByron = genesis.getByronGenesisData() != null;
        long byronSlotsPerEpoch = hasByron
                ? genesis.getByronGenesisData().epochLength()
                : shelley.securityParam() * 10;
        long firstNonByronSlot = DefaultEpochParamProvider
                .resolveFirstNonByronSlot(yaciConfig.getProtocolMagic(), hasByron);
        return new EpochSlotCalc(shelley.epochLength(), byronSlotsPerEpoch, firstNonByronSlot);
    }
}
