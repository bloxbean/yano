package com.bloxbean.cardano.yano.scalusbridge;

import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.ledgerrules.EpochProtocolParamsSupplier;
import com.bloxbean.cardano.yano.ledgerrules.SlotConfigSupplier;
import com.bloxbean.cardano.yano.ledgerrules.TransactionEvaluator;
import com.bloxbean.cardano.yano.ledgerrules.TransactionValidator;

import java.util.function.LongFunction;
import java.util.function.LongSupplier;

/**
 * Pure Java factory that hides Scala-compiled types from consumers.
 * Accepts only Java types (CCL {@link SlotConfig}) and returns {@link TransactionValidator}.
 */
public class ScalusTransactionFactory {

    // ---------------------------------------------------------------------
    // CCL "supplementary rules" (GOVCERT/governance/delegatee gap rules) are
    // OFF by default for all factory entry points — production callers should
    // pass an explicit `supplementaryRulesEnabled` flag (driven by the
    // `yano.validation.supplementary-rules-enabled` config) to turn them on.
    // Convenience overloads without runtime slot suppliers pass false to the
    // underlying constructor for standalone/static-param consumers.
    // ---------------------------------------------------------------------

    public static TransactionValidator createValidator(EpochProtocolParamsSupplier protocolParamsSupplier,
                                                       ScriptSupplier scriptSupplier,
                                                       SlotConfig slotConfig, int networkId) {
        return createValidator(protocolParamsSupplier, scriptSupplier, slotConfig, networkId, null, false);
    }

    public static TransactionValidator createValidator(EpochProtocolParamsSupplier protocolParamsSupplier,
                                                       ScriptSupplier scriptSupplier,
                                                       SlotConfig slotConfig, int networkId,
                                                       LedgerStateProvider ledgerStateProvider) {
        return createValidator(protocolParamsSupplier, scriptSupplier, slotConfig, networkId, ledgerStateProvider, false);
    }

    public static TransactionValidator createValidator(EpochProtocolParamsSupplier protocolParamsSupplier,
                                                       ScriptSupplier scriptSupplier,
                                                       SlotConfig slotConfig, int networkId,
                                                       LedgerStateProvider ledgerStateProvider,
                                                       boolean supplementaryRulesEnabled) {
        return new ScalusBasedTransactionValidator(protocolParamsSupplier, scriptSupplier, () -> slotConfig,
                networkId, ledgerStateProvider, null, null, false, supplementaryRulesEnabled);
    }

    public static TransactionValidator createValidator(EpochProtocolParamsSupplier protocolParamsSupplier,
                                                       ScriptSupplier scriptSupplier,
                                                       SlotConfigSupplier slotConfigSupplier, int networkId,
                                                       LedgerStateProvider ledgerStateProvider,
                                                       boolean supplementaryRulesEnabled) {
        return new ScalusBasedTransactionValidator(protocolParamsSupplier, scriptSupplier, slotConfigSupplier,
                networkId, ledgerStateProvider, null, null, false, supplementaryRulesEnabled);
    }

    public static TransactionValidator createValidator(EpochProtocolParamsSupplier protocolParamsSupplier,
                                                       ScriptSupplier scriptSupplier,
                                                       SlotConfig slotConfig, int networkId,
                                                       LedgerStateProvider ledgerStateProvider,
                                                       LongSupplier currentSlotSupplier) {
        return createValidator(protocolParamsSupplier, scriptSupplier, slotConfig, networkId,
                ledgerStateProvider, currentSlotSupplier, null, false);
    }

    public static TransactionValidator createValidator(EpochProtocolParamsSupplier protocolParamsSupplier,
                                                       ScriptSupplier scriptSupplier,
                                                       SlotConfig slotConfig, int networkId,
                                                       LedgerStateProvider ledgerStateProvider,
                                                       LongSupplier currentSlotSupplier,
                                                       LongFunction<Integer> currentEpochResolver) {
        return createValidator(protocolParamsSupplier, scriptSupplier, slotConfig, networkId,
                ledgerStateProvider, currentSlotSupplier, currentEpochResolver, false);
    }

    public static TransactionValidator createValidator(EpochProtocolParamsSupplier protocolParamsSupplier,
                                                       ScriptSupplier scriptSupplier,
                                                       SlotConfig slotConfig, int networkId,
                                                       LedgerStateProvider ledgerStateProvider,
                                                       LongSupplier currentSlotSupplier,
                                                       LongFunction<Integer> currentEpochResolver,
                                                       boolean supplementaryRulesEnabled) {
        return new ScalusBasedTransactionValidator(protocolParamsSupplier, scriptSupplier, slotConfig, networkId,
                ledgerStateProvider, currentSlotSupplier, currentEpochResolver, supplementaryRulesEnabled);
    }

    public static TransactionValidator createValidator(EpochProtocolParamsSupplier protocolParamsSupplier,
                                                       ScriptSupplier scriptSupplier,
                                                       SlotConfigSupplier slotConfigSupplier, int networkId,
                                                       LedgerStateProvider ledgerStateProvider,
                                                       LongSupplier currentSlotSupplier,
                                                       LongFunction<Integer> currentEpochResolver,
                                                       boolean supplementaryRulesEnabled) {
        return new ScalusBasedTransactionValidator(protocolParamsSupplier, scriptSupplier, slotConfigSupplier,
                networkId, ledgerStateProvider, currentSlotSupplier, currentEpochResolver, supplementaryRulesEnabled);
    }

    public static TransactionValidator createValidator(EpochProtocolParamsSupplier protocolParamsSupplier,
                                                       ScriptSupplier scriptSupplier,
                                                       SlotConfigSupplier slotConfigSupplier, int networkId,
                                                       LedgerStateProvider ledgerStateProvider,
                                                       LongSupplier currentSlotSupplier,
                                                       LongFunction<Integer> currentEpochResolver,
                                                       boolean requireLedgerStateProvider,
                                                       boolean supplementaryRulesEnabled) {
        return new ScalusBasedTransactionValidator(protocolParamsSupplier, scriptSupplier, slotConfigSupplier,
                networkId, ledgerStateProvider, currentSlotSupplier, currentEpochResolver,
                requireLedgerStateProvider, supplementaryRulesEnabled);
    }

    public static TransactionEvaluator createEvaluator(EpochProtocolParamsSupplier protocolParamsSupplier,
                                                       ScriptSupplier scriptSupplier,
                                                       SlotConfig slotConfig, int networkId) {
        return new ScalusBasedTransactionEvaluator(protocolParamsSupplier, scriptSupplier, slotConfig, networkId,
                null);
    }

    public static TransactionEvaluator createEvaluator(EpochProtocolParamsSupplier protocolParamsSupplier,
                                                       ScriptSupplier scriptSupplier,
                                                       SlotConfigSupplier slotConfigSupplier, int networkId) {
        return new ScalusBasedTransactionEvaluator(protocolParamsSupplier, scriptSupplier, slotConfigSupplier,
                networkId, null);
    }

    public static TransactionEvaluator createEvaluator(EpochProtocolParamsSupplier protocolParamsSupplier,
                                                       ScriptSupplier scriptSupplier,
                                                       SlotConfig slotConfig, int networkId,
                                                       LongSupplier currentSlotSupplier) {
        return new ScalusBasedTransactionEvaluator(protocolParamsSupplier, scriptSupplier, slotConfig, networkId,
                currentSlotSupplier);
    }

    public static TransactionEvaluator createEvaluator(EpochProtocolParamsSupplier protocolParamsSupplier,
                                                       ScriptSupplier scriptSupplier,
                                                       SlotConfigSupplier slotConfigSupplier, int networkId,
                                                       LongSupplier currentSlotSupplier) {
        return new ScalusBasedTransactionEvaluator(protocolParamsSupplier, scriptSupplier, slotConfigSupplier,
                networkId, currentSlotSupplier);
    }
}
