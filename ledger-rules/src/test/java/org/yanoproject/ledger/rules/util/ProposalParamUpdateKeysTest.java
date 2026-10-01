package org.yanoproject.ledger.rules.util;

import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionType;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.view.model.GovActionId;
import org.yanoproject.ledger.rules.view.model.ProposalState;

import java.math.BigInteger;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProposalParamUpdateKeysTest {

    @Test
    void securityGroupFollowsHaskellsKeys() {
        GovActionId id = new GovActionId("33".repeat(32), 0);
        ProposalState change = new ProposalState(id, GovActionType.PARAMETER_CHANGE_ACTION, null, null, 1, 7,
                BigInteger.ONE, null);
        assertThat(change.anyInSecurityGroup()).isNull();
        assertThat(change.withParamUpdateKeys(Set.of(30)).anyInSecurityGroup()).isTrue();
        assertThat(change.withParamUpdateKeys(Set.of(33, 23)).anyInSecurityGroup()).isTrue();
        assertThat(change.withParamUpdateKeys(Set.of(5, 23, 29)).anyInSecurityGroup()).isFalse();
        ProposalState info = new ProposalState(id, GovActionType.INFO_ACTION, null, null, 1, 7, BigInteger.ONE, null);
        assertThat(info.anyInSecurityGroup()).isFalse();
    }
}
