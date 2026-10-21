package org.yanoproject.ledgerstate.governance.epoch;

import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yaci.core.model.governance.actions.TreasuryWithdrawalsAction;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledgerstate.governance.epoch.GovernanceEpochProcessor.RemovalCause;
import org.yanoproject.ledgerstate.governance.epoch.GovernanceEpochProcessor.RemovalStep;
import org.yanoproject.ledgerstate.governance.model.GovActionRecord;
import org.yanoproject.ledgerstate.governance.ratification.ProposalDropService;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Phase 1 plan functions shared by the real enactment phase and the ADR-056 boundary preview:
 * removal order (enacted, expired, siblings each followed by their descendants, descendants of
 * expired), de-duplication and refund aggregation, and withdrawal aggregation.
 */
class Phase1PlanTest {

    private static GovActionId id(char c) {
        return new GovActionId(String.valueOf(c).repeat(64), 0);
    }

    private static GovActionRecord record(GovActionType type, GovActionId prev, String returnAddress, long deposit) {
        return new GovActionRecord(BigInteger.valueOf(deposit), returnAddress, 10, 16, type,
                prev != null ? prev.getTransactionId() : null, prev != null ? prev.getGov_action_index() : null,
                null, 1L);
    }

    @Test
    void removalOrderMatchesThePhase1Sequence() {
        GovActionId enacted = id('a');
        GovActionId sibling = id('b');
        GovActionId siblingChild = id('c');
        GovActionId expired = id('d');
        GovActionId expiredChild = id('e');
        GovActionId missing = id('f');
        Map<GovActionId, GovActionRecord> all = new LinkedHashMap<>();
        all.put(enacted, record(GovActionType.PARAMETER_CHANGE_ACTION, null, "e0r1", 5));
        all.put(sibling, record(GovActionType.PARAMETER_CHANGE_ACTION, null, "e0r2", 7));
        all.put(siblingChild, record(GovActionType.PARAMETER_CHANGE_ACTION, sibling, "e0r1", 11));
        all.put(expired, record(GovActionType.NEW_CONSTITUTION, null, "e0r2", 13));
        all.put(expiredChild, record(GovActionType.NEW_CONSTITUTION, expired, "e0r2", 17));

        List<RemovalStep> plan = GovernanceEpochProcessor.planProposalRemovals(
                List.of(enacted, missing), List.of(expired, enacted), all, new ProposalDropService());

        assertThat(plan).containsExactly(
                new RemovalStep(enacted, RemovalCause.ENACTED),
                new RemovalStep(missing, RemovalCause.ENACTED),
                new RemovalStep(expired, RemovalCause.EXPIRED),
                new RemovalStep(enacted, RemovalCause.EXPIRED),
                new RemovalStep(sibling, RemovalCause.SIBLING_OF_ENACTED),
                new RemovalStep(siblingChild, RemovalCause.DESCENDANT_OF_SIBLING),
                new RemovalStep(expiredChild, RemovalCause.DESCENDANT_OF_EXPIRED));

        Set<GovActionId> removed = new LinkedHashSet<>();
        Map<String, BigInteger> refunds = new HashMap<>();
        for (RemovalStep step : plan) {
            GovernanceEpochProcessor.claimForRemoval(step.id(), all, removed, refunds);
        }
        // The missing id is skipped and the duplicate is refunded once.
        assertThat(removed).containsExactly(enacted, expired, sibling, siblingChild, expiredChild);
        assertThat(refunds).isEqualTo(Map.of("e0r1", BigInteger.valueOf(16), "e0r2", BigInteger.valueOf(37)));
    }

    @Test
    void treasuryWithdrawalsAreSummedPerAccountInEnactmentOrder() {
        GovActionId first = id('1');
        GovActionId second = id('2');
        Map<GovActionId, GovActionRecord> all = new LinkedHashMap<>();
        Map<String, BigInteger> w1 = new LinkedHashMap<>();
        w1.put("e0aa", BigInteger.valueOf(3));
        w1.put("e0bb", BigInteger.valueOf(4));
        all.put(first, new GovActionRecord(BigInteger.ONE, "e0r", 1, 2, GovActionType.TREASURY_WITHDRAWALS_ACTION,
                null, null, new TreasuryWithdrawalsAction(w1, null), 1L));
        all.put(second, new GovActionRecord(BigInteger.ONE, "e0r", 1, 2, GovActionType.TREASURY_WITHDRAWALS_ACTION,
                null, null, new TreasuryWithdrawalsAction(Map.of("e0aa", BigInteger.TEN), null), 1L));

        assertThat(GovernanceEpochProcessor.aggregateTreasuryWithdrawals(List.of(first, second, id('9')), all))
                .isEqualTo(Map.of("e0aa", BigInteger.valueOf(13), "e0bb", BigInteger.valueOf(4)));
    }
}
