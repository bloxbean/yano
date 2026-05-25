package com.bloxbean.cardano.yano.scalusbridge;

import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.ledgerrules.EpochProtocolParamsSupplier;
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
    // The legacy overloads below remain for binary compatibility and pass
    // false to the underlying constructor.
    // ---------------------------------------------------------------------

    public static TransactionValidator createValidator(ProtocolParams pp, ScriptSupplier scriptSupplier,
                                                       SlotConfig slotConfig, int networkId) {
        return createValidator(pp, scriptSupplier, slotConfig, networkId, null, false);
    }

    public static TransactionValidator createValidator(ProtocolParams pp, ScriptSupplier scriptSupplier,
                                                       SlotConfig slotConfig, int networkId,
                                                       LedgerStateProvider ledgerStateProvider) {
        return createValidator(pp, scriptSupplier, slotConfig, networkId, ledgerStateProvider, false);
    }

    public static TransactionValidator createValidator(ProtocolParams pp, ScriptSupplier scriptSupplier,
                                                       SlotConfig slotConfig, int networkId,
                                                       LedgerStateProvider ledgerStateProvider,
                                                       boolean supplementaryRulesEnabled) {
        return new ScalusBasedTransactionValidator(pp, scriptSupplier, slotConfig, networkId, ledgerStateProvider,
                supplementaryRulesEnabled);
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

    public static TransactionEvaluator createEvaluator(ProtocolParams pp, ScriptSupplier scriptSupplier,
                                                       SlotConfig slotConfig, int networkId) {
        return new ScalusBasedTransactionEvaluator(pp, scriptSupplier, slotConfig, networkId);
    }

    public static TransactionEvaluator createEvaluator(EpochProtocolParamsSupplier protocolParamsSupplier,
                                                       ScriptSupplier scriptSupplier,
                                                       SlotConfig slotConfig, int networkId,
                                                       LongSupplier currentSlotSupplier) {
        return new ScalusBasedTransactionEvaluator(protocolParamsSupplier, scriptSupplier, slotConfig, networkId,
                currentSlotSupplier);
    }
}
