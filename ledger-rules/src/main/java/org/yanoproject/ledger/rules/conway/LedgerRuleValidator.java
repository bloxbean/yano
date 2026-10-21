package org.yanoproject.ledger.rules.conway;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.client.transaction.spec.Transaction;

import org.yanoproject.ledger.rules.TransactionEvaluator;

import java.util.Set;

/**
 * Interface for full transaction validation against Cardano ledger rules.
 * <p>
 * Unlike {@link TransactionEvaluator} which only evaluates Plutus script costs (Phase 2),
 * this interface validates transactions against the complete set of ledger rules including
 * Phase 1 structural validation (fees, UTxO balance, validity intervals, signatures, etc.)
 * and optionally Phase 2 script execution.
 * <p>
 * Implementations return a {@link RuleValidationResult} with structured error information
 * rather than throwing exceptions, allowing callers to inspect individual validation failures.
 */
public interface LedgerRuleValidator {
    /**
     * Validate a transaction against ledger rules.
     *
     * @param transaction the transaction to validate
     * @param inputUtxos  the set of UTxOs consumed by the transaction (inputs, reference inputs, collateral)
     * @return validation result containing success/failure status and any errors
     */
    RuleValidationResult validateTx(Transaction transaction, Set<Utxo> inputUtxos);

    /**
     * Validate a CBOR-serialized transaction against ledger rules.
     *
     * @param cbor       CBOR-serialized transaction bytes
     * @param inputUtxos the set of UTxOs consumed by the transaction
     * @return validation result containing success/failure status and any errors
     */
    default RuleValidationResult validateTx(byte[] cbor, Set<Utxo> inputUtxos) {
        try {
            Transaction transaction = Transaction.deserialize(cbor);
            return validateTx(transaction, inputUtxos);
        } catch (Exception e) {
            throw new CborRuntimeException("Unable to deserialize transaction", e);
        }
    }
}
