package org.yanoproject.ledger.rules.conway.rule;

import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.Transaction;

import org.yanoproject.ledger.rules.conway.RuleValidationError;
import org.yanoproject.ledger.rules.conway.LedgerContext;
import org.yanoproject.ledger.rules.view.slice.UtxoSlice;

import java.util.*;
import java.util.stream.Collectors;

public class InputValidationRule implements LedgerRule {

    private static final String RULE_NAME = "InputValidation";

    @Override
    public List<RuleValidationError> validate(LedgerContext context, Transaction transaction) {
        List<RuleValidationError> errors = new ArrayList<>();
        TransactionBody body = transaction.getBody();
        UtxoSlice utxoSlice = context.getUtxoSlice();

        List<TransactionInput> inputs = body.getInputs();

        if (inputs == null || inputs.isEmpty()) {
            errors.add(error("Transaction has no inputs"));
            return errors;
        }

        Set<TransactionInput> inputSet = new HashSet<>(inputs);
        if (inputSet.size() < inputs.size()) {
            errors.add(error("Transaction has duplicate spending inputs"));
        }

        if (utxoSlice != null) {
            for (TransactionInput input : inputs) {
                if (utxoSlice.lookup(input).isEmpty()) {
                    errors.add(error("Spending input not found in UTxO set: " + input.getTransactionId() + "#" + input.getIndex()));
                }
            }
        }

        List<TransactionInput> refInputs = body.getReferenceInputs();
        if (refInputs != null && !refInputs.isEmpty()) {
            Set<TransactionInput> refInputSet = new HashSet<>(refInputs);
            if (refInputSet.size() < refInputs.size()) {
                errors.add(error("Transaction has duplicate reference inputs"));
            }

            Set<TransactionInput> overlap = refInputSet.stream()
                    .filter(inputSet::contains)
                    .collect(Collectors.toSet());
            if (!overlap.isEmpty()) {
                errors.add(error("Reference inputs overlap with spending inputs: "
                        + overlap.stream()
                            .map(i -> i.getTransactionId() + "#" + i.getIndex())
                            .collect(Collectors.joining(", "))));
            }

            if (utxoSlice != null) {
                for (TransactionInput refInput : refInputs) {
                    if (utxoSlice.lookup(refInput).isEmpty()) {
                        errors.add(error("Reference input not found in UTxO set: " + refInput.getTransactionId() + "#" + refInput.getIndex()));
                    }
                }
            }
        }

        return errors;
    }

    private RuleValidationError error(String message) {
        return RuleValidationError.builder()
                .rule(RULE_NAME)
                .message(message)
                .phase(RuleValidationError.Phase.PHASE_1)
                .build();
    }
}
