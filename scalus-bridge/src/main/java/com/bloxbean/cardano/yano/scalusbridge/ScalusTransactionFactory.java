package com.bloxbean.cardano.yano.scalusbridge;

import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.ledgerrules.EpochProtocolParamsSupplier;
import com.bloxbean.cardano.yano.ledgerrules.TransactionEvaluator;
import com.bloxbean.cardano.yano.ledgerrules.TransactionValidator;

import java.util.function.LongSupplier;

/**
 * Pure Java factory that hides Scala-compiled types from consumers.
 * Accepts only Java types (CCL {@link SlotConfig}) and returns {@link TransactionValidator}.
 */
public class ScalusTransactionFactory {

    public static TransactionValidator createValidator(ProtocolParams pp, ScriptSupplier scriptSupplier,
                                                       SlotConfig slotConfig, int networkId) {
        return new ScalusBasedTransactionValidator(pp, scriptSupplier, slotConfig, networkId);
    }

    public static TransactionValidator createValidator(ProtocolParams pp, ScriptSupplier scriptSupplier,
                                                       SlotConfig slotConfig, int networkId,
                                                       LedgerStateProvider ledgerStateProvider) {
        return new ScalusBasedTransactionValidator(pp, scriptSupplier, slotConfig, networkId, ledgerStateProvider);
    }

    public static TransactionValidator createValidator(EpochProtocolParamsSupplier protocolParamsSupplier,
                                                       ScriptSupplier scriptSupplier,
                                                       SlotConfig slotConfig, int networkId,
                                                       LedgerStateProvider ledgerStateProvider,
                                                       LongSupplier currentSlotSupplier) {
        return new ScalusBasedTransactionValidator(protocolParamsSupplier, scriptSupplier, slotConfig, networkId,
                ledgerStateProvider, currentSlotSupplier);
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
