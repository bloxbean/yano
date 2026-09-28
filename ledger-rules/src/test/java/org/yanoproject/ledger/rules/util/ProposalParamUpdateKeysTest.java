package org.yanoproject.ledger.rules.util;

import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionType;
import com.bloxbean.cardano.client.util.HexUtil;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.view.model.GovActionId;
import org.yanoproject.ledger.rules.view.model.ProposalState;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProposalParamUpdateKeysTest {

    /**
     * {@code [{0: [], 1: [], 2: 0, 20: 258([p1, p2, p3])}, {}, true, null]} with
     * p1 = parameter change {30: 1, 23: 2} (govActionDeposit is a Conway key CCL cannot decode),
     * p2 = info action [6], p3 = parameter change with a parent and an empty update.
     */
    private static final String TX = "84a400800180020014d9010283841864581de0111111111111111111111111111111111111111111111111111111118400f6"
            + "a2181e011702f682617558200000000000000000000000000000000000000000000000000000000000000000841864581de0"
            + "1111111111111111111111111111111111111111111111111111111181068261755820000000000000000000000000000000"
            + "0000000000000000000000000000000000841864581de0111111111111111111111111111111111111111111111111111111"
            + "118400825820222222222222222222222222222222222222222222222222222222222222222201a0f6826175582000000000"
            + "00000000000000000000000000000000000000000000000000000000a0f5f6";

    @Test
    void readsTheKeysOfEveryParameterChangeInBodyOrder() {
        var keys = ProposalParamUpdateKeys.fromTransaction(HexUtil.decodeHexString(TX));
        assertThat(keys).hasSize(3);
        assertThat(keys.get(0)).containsExactly(23, 30);
        assertThat(keys.get(1)).isNull();
        assertThat(keys.get(2)).isEmpty();
    }

    @Test
    void aTransactionWithoutProposalsHasNoKeys() {
        // [{0: [], 1: [], 2: 0}, {}, true, null]
        assertThat(ProposalParamUpdateKeys.fromTransaction(HexUtil.decodeHexString("84a3008001800200a0f5f6")))
                .isEmpty();
    }

    @Test
    void truncatedBytesAreRejected() {
        byte[] tx = HexUtil.decodeHexString(TX);
        assertThatThrownBy(() -> ProposalParamUpdateKeys.fromTransaction(Arrays.copyOf(tx, 60)))
                .isInstanceOf(IllegalArgumentException.class);
    }

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
