package com.bloxbean.cardano.yano.scalusbridge;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.ledger.LedgerContext;
import com.bloxbean.cardano.client.ledger.LedgerStateValidator;
import com.bloxbean.cardano.client.ledger.rule.CertificateValidationRule;
import com.bloxbean.cardano.client.ledger.rule.GovernanceValidationRule;
import com.bloxbean.cardano.client.ledger.rule.LedgerRule;
import com.bloxbean.cardano.client.ledger.slice.yaci.YaciAccountsSlice;
import com.bloxbean.cardano.client.ledger.slice.yaci.YaciCommitteeSlice;
import com.bloxbean.cardano.client.ledger.slice.yaci.YaciDRepsSlice;
import com.bloxbean.cardano.client.ledger.slice.yaci.YaciPoolsSlice;
import com.bloxbean.cardano.client.ledger.slice.yaci.YaciProposalsSlice;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.ledgerrules.EpochProtocolParamsSupplier;
import com.bloxbean.cardano.yano.ledgerrules.TransactionValidator;
import com.bloxbean.cardano.yano.ledgerrules.ValidationError;
import com.bloxbean.cardano.yano.ledgerrules.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scalus.bloxbean.ScriptSupplier;
import scalus.cardano.ledger.SlotConfig;

import java.util.*;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;

/**
 * {@link TransactionValidator} implementation using Scalus for full Cardano ledger rule validation.
 * Delegates to {@link LedgerBridge} which calls Scalus's CardanoMutator.transit().
 *
 * <p>Scalus v0.16.0 handles all DELEG rules (registration, deregistration, deposit/refund amounts,
 * reward balance checks, withdrawal validation) with proper intra-tx state tracking. CCL supplementary
 * rules run after Scalus for gaps it doesn't cover (GOVCERT, delegatee existence, governance).
 */
public class ScalusBasedTransactionValidator implements TransactionValidator {

    private static final Logger log = LoggerFactory.getLogger(ScalusBasedTransactionValidator.class);

    // CCL supplementary rules — only for gaps Scalus doesn't cover (GOVCERT + delegatee + governance)
    private static final List<LedgerRule> SUPPLEMENTARY_RULES = List.of(
            new CertificateValidationRule(),
            new GovernanceValidationRule()
    );

    private final EpochProtocolParamsSupplier protocolParamsSupplier;
    private final ScriptSupplier scriptSupplier;
    private final SlotConfig scalusSlotConfig;
    private final int networkId;
    private final LedgerStateProvider ledgerStateProvider;
    private final LongSupplier currentSlotSupplier;
    private final LongFunction<Integer> currentEpochResolver;
    private final boolean requireLedgerStateProvider;

    public ScalusBasedTransactionValidator(ProtocolParams protocolParams,
                                           com.bloxbean.cardano.client.api.ScriptSupplier scriptSupplier,
                                           com.bloxbean.cardano.client.common.model.SlotConfig slotConfig,
                                           int networkId) {
        this(protocolParams, scriptSupplier, slotConfig, networkId, null);
    }

    public ScalusBasedTransactionValidator(ProtocolParams protocolParams,
                                           com.bloxbean.cardano.client.api.ScriptSupplier scriptSupplier,
                                           com.bloxbean.cardano.client.common.model.SlotConfig slotConfig,
                                           int networkId,
                                           LedgerStateProvider ledgerStateProvider) {
        this(slot -> protocolParams, scriptSupplier, slotConfig, networkId, ledgerStateProvider, null, null, false);
    }

    public ScalusBasedTransactionValidator(EpochProtocolParamsSupplier protocolParamsSupplier,
                                           com.bloxbean.cardano.client.api.ScriptSupplier scriptSupplier,
                                           com.bloxbean.cardano.client.common.model.SlotConfig slotConfig,
                                           int networkId,
                                           LedgerStateProvider ledgerStateProvider,
                                           LongSupplier currentSlotSupplier) {
        this(protocolParamsSupplier, scriptSupplier, slotConfig, networkId, ledgerStateProvider,
                currentSlotSupplier, null);
    }

    public ScalusBasedTransactionValidator(EpochProtocolParamsSupplier protocolParamsSupplier,
                                           com.bloxbean.cardano.client.api.ScriptSupplier scriptSupplier,
                                           com.bloxbean.cardano.client.common.model.SlotConfig slotConfig,
                                           int networkId,
                                           LedgerStateProvider ledgerStateProvider,
                                           LongSupplier currentSlotSupplier,
                                           LongFunction<Integer> currentEpochResolver) {
        this(protocolParamsSupplier, scriptSupplier, slotConfig, networkId, ledgerStateProvider,
                currentSlotSupplier, currentEpochResolver, true);
    }

    private ScalusBasedTransactionValidator(EpochProtocolParamsSupplier protocolParamsSupplier,
                                            com.bloxbean.cardano.client.api.ScriptSupplier scriptSupplier,
                                            com.bloxbean.cardano.client.common.model.SlotConfig slotConfig,
                                            int networkId,
                                            LedgerStateProvider ledgerStateProvider,
                                            LongSupplier currentSlotSupplier,
                                            LongFunction<Integer> currentEpochResolver,
                                            boolean requireLedgerStateProvider) {
        this.protocolParamsSupplier = protocolParamsSupplier;
        if (scriptSupplier != null)
            this.scriptSupplier = new ScalusScriptSupplier(scriptSupplier);
        else
            this.scriptSupplier = null;
        this.networkId = networkId;
        this.ledgerStateProvider = ledgerStateProvider;
        this.currentSlotSupplier = currentSlotSupplier;
        this.currentEpochResolver = currentEpochResolver;
        this.requireLedgerStateProvider = requireLedgerStateProvider;

        this.scalusSlotConfig = new scalus.cardano.ledger.SlotConfig(
                slotConfig.getZeroTime(),
                slotConfig.getZeroSlot(),
                slotConfig.getSlotLength());
    }

    @Override
    public ValidationResult validate(byte[] txCbor, Set<Utxo> inputUtxos) {
        try {
            Transaction tx = null;
            try {
                tx = deserializeTransaction(txCbor);
            } catch (Exception e) {
                // If we can't deserialize for supplementary rules, let Scalus handle the tx error.
            }

            if (requireLedgerStateProvider && ledgerStateProvider == null) {
                return ValidationResult.failure(new ValidationError(
                        "LedgerStateUnavailable",
                        "Ledger state provider is required for production transaction validation",
                        ValidationError.Phase.PHASE_1));
            }

            long currentSlot = resolveCurrentSlot(tx);
            ProtocolParams protocolParams = protocolParamsSupplier.getProtocolParams(currentSlot);
            TransitResult result = runScalusValidation(txCbor, protocolParams, inputUtxos, currentSlot);

            if (!result.isSuccess()) {
                ValidationError error = mapError(result);
                return ValidationResult.failure(error);
            }

            // STEP 2: Scalus passed — run CCL supplementary rules for gaps
            // (GOVCERT certs, delegatee existence, governance validation)
            if (tx != null && ledgerStateProvider != null) {
                String txHash = resolveTransactionHash(txCbor, tx);
                ValidationResult cclResult = runSupplementaryRules(tx, currentSlot, protocolParams, txHash);
                if (!cclResult.valid()) {
                    return cclResult;
                }
            }

            return ValidationResult.success();

        } catch (Exception e) {
            return ValidationResult.failure(new ValidationError(
                    "InternalError",
                    "Validation error: " + e.getMessage(),
                    ValidationError.Phase.PHASE_1));
        }
    }

    protected Transaction deserializeTransaction(byte[] txCbor) throws Exception {
        return Transaction.deserialize(txCbor);
    }

    protected TransitResult runScalusValidation(byte[] txCbor, ProtocolParams protocolParams,
                                                Set<Utxo> inputUtxos, long currentSlot) {
        return LedgerBridge.validate(
                txCbor, protocolParams, inputUtxos, currentSlot,
                scalusSlotConfig, networkId, scriptSupplier, ledgerStateProvider);
    }

    private long resolveCurrentSlot(Transaction tx) {
        if (currentSlotSupplier != null) {
            try {
                long slot = currentSlotSupplier.getAsLong();
                if (slot >= 0) return slot;
                throw new IllegalStateException("current slot supplier returned " + slot);
            } catch (Exception e) {
                log.warn("Failed to resolve current slot from runtime; rejecting tx: {}", e.getMessage());
                throw new IllegalStateException("Failed to resolve current slot from runtime", e);
            }
        }
        if (tx != null && tx.getBody().getValidityStartInterval() > 0) {
            return tx.getBody().getValidityStartInterval();
        }
        return 0;
    }

    private long resolveCurrentEpoch(long currentSlot) {
        if (currentEpochResolver == null) {
            return -1;
        }
        try {
            Integer epoch = currentEpochResolver.apply(currentSlot);
            if (epoch != null && epoch >= 0) {
                return epoch;
            }
            throw new IllegalStateException("current epoch resolver returned " + epoch);
        } catch (Exception e) {
            log.warn("Failed to resolve current epoch for slot {}; rejecting tx: {}",
                    currentSlot, e.getMessage());
            throw new IllegalStateException("Failed to resolve current epoch for slot " + currentSlot, e);
        }
    }

    /**
     * Run CCL supplementary rules for validation gaps not covered by Scalus:
     * GOVCERT certs, delegatee existence checks, governance proposals/voting.
     */
    protected ValidationResult runSupplementaryRules(Transaction tx, long currentSlot, ProtocolParams protocolParams) {
        return runSupplementaryRules(tx, currentSlot, protocolParams, null);
    }

    protected ValidationResult runSupplementaryRules(Transaction tx, long currentSlot, ProtocolParams protocolParams,
                                                    String currentTransactionHash) {
        try {
            LedgerContext ctx = LedgerContext.builder()
                    .protocolParams(protocolParams)
                    .currentSlot(currentSlot)
                    .currentEpoch(resolveCurrentEpoch(currentSlot))
                    .currentTransactionHash(currentTransactionHash)
                    .networkId(networkId == 1 ? NetworkId.MAINNET : NetworkId.TESTNET)
                    .accountsSlice(new YaciAccountsSlice(ledgerStateProvider))
                    .poolsSlice(new YaciPoolsSlice(ledgerStateProvider))
                    .drepsSlice(new YaciDRepsSlice(ledgerStateProvider))
                    .committeeSlice(new YaciCommitteeSlice(ledgerStateProvider))
                    .proposalsSlice(new YaciProposalsSlice(ledgerStateProvider))
                    .build();

            LedgerStateValidator validator = LedgerStateValidator.builder()
                    .customRules(SUPPLEMENTARY_RULES)
                    .build();

            var cclResult = validator.validate(ctx, tx);
            if (!cclResult.isValid()) {
                // Map first CCL error to yaci ValidationResult
                var cclError = cclResult.getErrors().get(0);
                return ValidationResult.failure(new ValidationError(
                        cclError.getRule(),
                        cclError.getMessage(),
                        cclError.getPhase() == com.bloxbean.cardano.client.api.model.ValidationError.Phase.PHASE_2
                                ? ValidationError.Phase.PHASE_2
                                : ValidationError.Phase.PHASE_1));
            }
        } catch (Exception e) {
            log.warn("CCL supplementary rule validation failed; rejecting tx: {}", e.getMessage(), e);
            return ValidationResult.failure(new ValidationError(
                    "SupplementaryRuleException",
                    "CCL supplementary rule validation failed: " + e.getMessage(),
                    ValidationError.Phase.PHASE_1));
        }
        return ValidationResult.success();
    }

    private String resolveTransactionHash(byte[] txCbor, Transaction tx) {
        try {
            return TransactionUtil.getTxHash(txCbor);
        } catch (Exception ignored) {
            try {
                return TransactionUtil.getTxHash(tx);
            } catch (Exception e) {
                log.debug("Unable to resolve transaction hash for supplementary governance checks: {}", e.getMessage());
                return null;
            }
        }
    }

    private ValidationError mapError(TransitResult result) {
        String className = result.errorClassName() != null ? result.errorClassName() : "Unknown";
        String message = result.errorMessage();

        ValidationError.Phase phase = className.contains("PlutusScript") || className.contains("Script")
                ? ValidationError.Phase.PHASE_2
                : ValidationError.Phase.PHASE_1;

        String rule = className.replace("Exception", "").replace("$", "");

        return new ValidationError(rule, message, phase);
    }
}
