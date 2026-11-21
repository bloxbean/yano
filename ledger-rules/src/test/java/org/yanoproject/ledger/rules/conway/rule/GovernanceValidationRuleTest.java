package org.yanoproject.ledger.rules.conway.rule;

import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.spec.UnitInterval;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.governance.ProposalProcedure;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.UpdateCommittee;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.conway.LedgerContext;
import org.yanoproject.ledger.rules.conway.RuleValidationError;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GovernanceValidationRuleTest {

    private static final Credential MEMBER = Credential.fromKey(new byte[28]);

    /** A term is a Word64 epoch: 2^64 - 1 is after any current epoch, not the signed -1. */
    @Test
    void aCommitteeTermIsComparedUnsigned() {
        assertThat(expirationErrors(-1L)).isEmpty();
        assertThat(expirationErrors(11L)).isEmpty();
        assertThat(expirationErrors(10L)).singleElement().asString().contains("expiration epoch 10 ");
    }

    private static List<String> expirationErrors(long term) {
        UpdateCommittee update = new UpdateCommittee(null, Set.of(), Map.of(MEMBER, term),
                new UnitInterval(BigInteger.ONE, BigInteger.TWO));
        Transaction tx = Transaction.builder()
                .body(TransactionBody.builder()
                        .proposalProcedures(List.of(ProposalProcedure.builder().govAction(update).build()))
                        .build())
                .build();
        LedgerContext context = LedgerContext.builder().currentEpoch(10).build();
        return new GovernanceValidationRule().validate(context, tx).stream()
                .map(RuleValidationError::toString)
                .filter(message -> message.contains("expiration epoch"))
                .toList();
    }
}
