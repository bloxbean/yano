package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Fail-closed validation of the fixed ADR-013 Milestone 1 demo topology.
 * Anchor address/policy are cluster-observed identity, not an independent
 * trust anchor; authenticity still comes from pinned app-chain membership.
 */
record DemoClusterTopology(Set<String> memberKeys,
                           int threshold,
                           String stateMachine,
                           String observedAnchorScriptAddress,
                           String observedAnchorThreadPolicyId) {
    static final int EXPECTED_MEMBERS = 3;
    static final int EXPECTED_THRESHOLD = 2;
    static final String EXPECTED_STATE_MACHINE = "evidence-registry";

    DemoClusterTopology {
        memberKeys = Set.copyOf(memberKeys);
    }

    static DemoClusterTopology verify(List<YanoAuditClient.Status> statuses,
                                      Set<String> expectedMemberKeys,
                                      int expectedThreshold) {
        return verify(statuses, expectedMemberKeys, expectedThreshold, EXPECTED_STATE_MACHINE);
    }

    static DemoClusterTopology verify(List<YanoAuditClient.Status> statuses,
                                      Set<String> expectedMemberKeys,
                                      int expectedThreshold,
                                      String expectedStateMachine) {
        if (statuses == null || statuses.size() != EXPECTED_MEMBERS
                || expectedMemberKeys == null
                || expectedMemberKeys.size() != EXPECTED_MEMBERS
                || expectedMemberKeys.stream().anyMatch(
                key -> key == null || !key.matches("[0-9a-f]{64}"))
                || expectedThreshold != EXPECTED_THRESHOLD) {
            throw new DemoException(DemoError.CLUSTER_DIVERGED);
        }
        Set<String> identities = new LinkedHashSet<>();
        for (YanoAuditClient.Status status : statuses) {
            if (status == null
                    || status.members() != EXPECTED_MEMBERS
                    || status.threshold() != expectedThreshold
                    || !expectedStateMachine.equals(status.stateMachine())
                    || !identities.add(status.memberKey())) {
                throw new DemoException(DemoError.CLUSTER_DIVERGED);
            }
        }
        if (identities.size() != EXPECTED_MEMBERS
                || !identities.equals(expectedMemberKeys)) {
            throw new DemoException(DemoError.CLUSTER_DIVERGED);
        }
        YanoAuditClient.Status observed = statuses.getFirst();
        return new DemoClusterTopology(identities, observed.threshold(),
                observed.stateMachine(), null, null);
    }

    static DemoClusterTopology verifyAnchored(List<YanoAuditClient.Status> statuses,
                                              Set<String> expectedMemberKeys,
                                              int expectedThreshold) {
        return verifyAnchored(statuses, expectedMemberKeys, expectedThreshold,
                EXPECTED_STATE_MACHINE);
    }

    static DemoClusterTopology verifyAnchored(List<YanoAuditClient.Status> statuses,
                                              Set<String> expectedMemberKeys,
                                              int expectedThreshold,
                                              String expectedStateMachine) {
        DemoClusterTopology base = verify(
                statuses, expectedMemberKeys, expectedThreshold, expectedStateMachine);
        String scriptAddress = statuses.getFirst().anchorScriptAddress();
        String threadPolicyId = statuses.getFirst().anchorThreadPolicyId();
        if (scriptAddress == null || scriptAddress.isBlank()
                || threadPolicyId == null
                || !threadPolicyId.matches("[0-9a-f]{56}")) {
            throw new DemoException(DemoError.CLUSTER_DIVERGED);
        }
        for (YanoAuditClient.Status status : statuses) {
            if (!scriptAddress.equals(status.anchorScriptAddress())
                    || !threadPolicyId.equals(status.anchorThreadPolicyId())) {
                throw new DemoException(DemoError.CLUSTER_DIVERGED);
            }
        }
        return new DemoClusterTopology(base.memberKeys(), base.threshold(),
                base.stateMachine(), scriptAddress, threadPolicyId);
    }

    int members() {
        return memberKeys.size();
    }
}
