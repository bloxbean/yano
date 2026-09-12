package org.yanoproject.scalusbridge;

import com.bloxbean.cardano.client.api.ScriptSupplier;
import org.yanoproject.api.account.LedgerStateProvider;
import org.yanoproject.ledgerrules.EpochProtocolParamsSupplier;
import org.yanoproject.ledgerrules.SlotConfigSupplier;
import org.yanoproject.ledgerrules.TransactionEvaluator;
import org.yanoproject.ledgerrules.TransactionValidator;

import java.util.function.LongFunction;
import java.util.function.LongSupplier;

/**
 * Pure Java factory that hides Scala-compiled types from consumers.
 * Accepts Java suppliers with explicit slot timing and epoch geometry and returns {@link TransactionValidator}.
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
                                                       SlotConfigSupplier slotConfigSupplier, int networkId,
                                                       LedgerStateProvider ledgerStateProvider,
                                                       boolean supplementaryRulesEnabled) {
        return new ScalusBasedTransactionValidator(protocolParamsSupplier, scriptSupplier, slotConfigSupplier,
                networkId, ledgerStateProvider, null, null, false, supplementaryRulesEnabled);
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
                                                       SlotConfigSupplier slotConfigSupplier, int networkId) {
        return new ScalusBasedTransactionEvaluator(protocolParamsSupplier, scriptSupplier, slotConfigSupplier,
                networkId, null);
    }

    public static TransactionEvaluator createEvaluator(EpochProtocolParamsSupplier protocolParamsSupplier,
                                                       ScriptSupplier scriptSupplier,
                                                       SlotConfigSupplier slotConfigSupplier, int networkId,
                                                       LongSupplier currentSlotSupplier) {
        return new ScalusBasedTransactionEvaluator(protocolParamsSupplier, scriptSupplier, slotConfigSupplier,
                networkId, currentSlotSupplier);
    }
}
