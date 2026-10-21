package org.yanoproject.ledger.rules.conway.rule;

import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;

import org.yanoproject.ledger.rules.conway.RuleValidationError;
import org.yanoproject.ledger.rules.conway.LedgerContext;

import java.util.ArrayList;
import java.util.List;

public class ValidityIntervalRule implements LedgerRule {

    private static final String RULE_NAME = "ValidityInterval";

    @Override
    public List<RuleValidationError> validate(LedgerContext context, Transaction transaction) {
        List<RuleValidationError> errors = new ArrayList<>();
        TransactionBody body = transaction.getBody();
        long currentSlot = context.getCurrentSlot();

        long validityStart = body.getValidityStartInterval();
        if (validityStart > 0 && currentSlot < validityStart) {
            errors.add(error("Current slot " + currentSlot
                    + " is before validity start interval " + validityStart));
        }

        long ttl = body.getTtl();
        if (ttl > 0 && currentSlot >= ttl) {
            errors.add(error("Current slot " + currentSlot
                    + " is at or past TTL " + ttl));
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
