package org.yanoproject.ledger.rules.conway.rule;

import com.bloxbean.cardano.client.transaction.spec.Transaction;

import org.yanoproject.ledger.rules.conway.RuleValidationError;
import org.yanoproject.ledger.rules.conway.LedgerContext;

import java.util.List;

/**
 * A single ledger validation rule.
 * <p>
 * Each rule checks one aspect of a transaction against the Cardano Conway-era
 * ledger specification and returns a list of validation errors (empty if valid).
 */
public interface LedgerRule {

    /**
     * Validate the transaction against this rule.
     *
     * @param context     ledger context (protocol params, slot, network, state slices)
     * @param transaction the transaction to validate
     * @return list of validation errors; empty list means the rule passed
     */
    List<RuleValidationError> validate(LedgerContext context, Transaction transaction);
}
