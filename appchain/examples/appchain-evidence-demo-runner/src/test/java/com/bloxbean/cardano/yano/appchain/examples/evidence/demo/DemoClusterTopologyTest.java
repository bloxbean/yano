package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DemoClusterTopologyTest {
    private static final String ROOT = "10".repeat(32);
    private static final String TX = "20".repeat(32);
    private static final String ADDRESS = "addr_test1wzscriptanchor";
    private static final String POLICY = "30".repeat(28);
    private static final Set<String> MEMBERS = Set.of(key(1), key(2), key(3));

    @Test
    void acceptsThreeDistinctMembersWithExpectedThresholdAndMachine() {
        DemoClusterTopology topology = DemoClusterTopology.verifyAnchored(List.of(
                status(1, 3, 2, "evidence-registry"),
                status(2, 3, 2, "evidence-registry"),
                status(3, 3, 2, "evidence-registry")), MEMBERS, 2);

        assertThat(topology.members()).isEqualTo(3);
        assertThat(topology.threshold()).isEqualTo(2);
        assertThat(topology.memberKeys()).containsExactlyInAnyOrder(
                key(1), key(2), key(3));
        assertThat(topology.observedAnchorScriptAddress()).isEqualTo(ADDRESS);
        assertThat(topology.observedAnchorThreadPolicyId()).isEqualTo(POLICY);
    }

    @Test
    void genericTopologyAllowsFollowersBeforeAnchorIdentityAdoption() {
        DemoClusterTopology topology = DemoClusterTopology.verify(List.of(
                status(1, 3, 2, "evidence-registry"),
                statusWithoutAnchor(2), statusWithoutAnchor(3)), MEMBERS, 2);

        assertThat(topology.members()).isEqualTo(3);
        assertThat(topology.observedAnchorScriptAddress()).isNull();
        assertThatThrownBy(() -> DemoClusterTopology.verifyAnchored(List.of(
                status(1, 3, 2, "evidence-registry"),
                statusWithoutAnchor(2), statusWithoutAnchor(3)), MEMBERS, 2))
                .isInstanceOf(DemoException.class);
        assertThatThrownBy(() -> DemoClusterTopology.verifyAnchored(List.of(
                statusWithoutAnchor(1),
                status(2, 3, 2, "evidence-registry"),
                status(3, 3, 2, "evidence-registry")), MEMBERS, 2))
                .isInstanceOf(DemoException.class);
    }

    @Test
    void rejectsRepeatedEndpointIdentityAndOneOfOneMasqueradingAsThreeNodes() {
        assertDiverged(List.of(
                status(1, 3, 2, "evidence-registry"),
                status(1, 3, 2, "evidence-registry"),
                status(1, 3, 2, "evidence-registry")));
        assertDiverged(List.of(
                status(1, 1, 1, "evidence-registry"),
                status(2, 1, 1, "evidence-registry"),
                status(3, 1, 1, "evidence-registry")));
    }

    @Test
    void rejectsWrongStateMachineOrThreshold() {
        assertDiverged(List.of(
                status(1, 3, 2, "evidence-registry"),
                status(2, 3, 2, "ordered-log"),
                status(3, 3, 2, "evidence-registry")));
        assertDiverged(List.of(
                status(1, 3, 1, "evidence-registry"),
                status(2, 3, 1, "evidence-registry"),
                status(3, 3, 1, "evidence-registry")));
    }

    private static void assertDiverged(List<YanoAuditClient.Status> statuses) {
        assertThatThrownBy(() -> DemoClusterTopology.verify(statuses, MEMBERS, 2))
                .isInstanceOf(DemoException.class)
                .extracting(failure -> ((DemoException) failure).error())
                .isEqualTo(DemoError.CLUSTER_DIVERGED);
    }

    private static YanoAuditClient.Status status(int member, int members,
                                                  int threshold, String stateMachine) {
        return new YanoAuditClient.Status(12, ROOT, key(member), members,
                threshold, stateMachine, 12, TX, 100, ADDRESS, POLICY);
    }

    private static YanoAuditClient.Status statusWithoutAnchor(int member) {
        return new YanoAuditClient.Status(0, ROOT, key(member), 3,
                2, "evidence-registry", 0, null, 0, null, null);
    }

    private static String key(int value) {
        return "%02x".formatted(value).repeat(32);
    }
}
