package com.bloxbean.cardano.yano.appchain.roles;

import com.bloxbean.cardano.yano.api.appchain.AppQueryResult;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiException;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRequest;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainHttpMethod;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainQueryService;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeCommitmentV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalPolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalProposalV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoleApprovalsDomainApiTest {
    private static final String CHAIN = "generic-role-chain";
    private static final byte[] ROOT = filled(0x61);
    private static final ApprovalPolicyV1 POLICY = new ApprovalPolicyV1(
            "order-approval", 1, List.of("buyer"),
            List.of(new ApprovalPolicyV1.RequiredClause(
                    "reviewers", "reviewer", 1,
                    ApprovalPolicyV1.DistinctBy.ORGANIZATION)),
            ApprovalPolicyV1.RejectionMode.ANY_ELIGIBLE, 100);
    private static final ApprovalProposalV1 PROPOSAL = new ApprovalProposalV1(
            "order-a-approval", POLICY.policyId(), 1, POLICY.digest(),
            "com.example.order.v1", filled(0x31), 200,
            ApprovalProposalV1.ProposalStatus.PENDING,
            "buyer-a", "buyer-org", 1, "buyer", 1, "buyer-key", 10, List.of());

    @Test
    void projectsGenericProposalAndReturnsItsExactProofMaterial() {
        RoleApprovalsDomainApi api = api(RoleApprovalsStateMachineProvider.ID,
                (path, params) -> PROPOSAL.encode());

        assertThat(api.routes()).extracting(route -> route.routeId()).containsExactly(
                "get-organization", "get-actor", "get-policy",
                "get-proposal", "get-approval-stats");
        var response = api.handle(request("get-proposal",
                Map.of("id", PROPOSAL.proposalId()), Map.of()));
        byte[] proofKey = CompositeCommitmentV1.componentKey(
                RoleAwareApprovalsComponent.COMPONENT_ID,
                RoleWorkflowKeys.proposal(PROPOSAL.proposalId()));
        assertThat(response.status()).isEqualTo(200);
        assertThat(new String(response.body(), StandardCharsets.UTF_8))
                .contains("\"stateMachineId\":\"role-approvals\"",
                        "\"payloadDomain\":\"com.example.order.v1\"",
                        "\"payloadHash\":\""
                                + java.util.HexFormat.of().formatHex(PROPOSAL.payloadHash()) + "\"",
                        "\"proofKey\":\""
                                + java.util.HexFormat.of().formatHex(proofKey) + "\"");
    }

    @Test
    void failsClosedForEvidenceRoutesIdentityMismatchAndInvalidSelectors() {
        RoleApprovalsDomainApi api = api(RoleApprovalsStateMachineProvider.ID,
                (path, params) -> PROPOSAL.encode());
        assertThat(RoleApprovalsDomainApi.supports("get-evidence-approval")).isFalse();
        assertCode(api, request("get-evidence-approval", Map.of(), Map.of()),
                DomainApiException.Code.INVALID_REQUEST);
        assertCode(api, request("get-proposal", Map.of("id", PROPOSAL.proposalId()),
                        Map.of("revision", List.of("1"))),
                DomainApiException.Code.INVALID_REQUEST);

        RoleApprovalsDomainApi wrong = api("role-evidence",
                (path, params) -> PROPOSAL.encode());
        assertCode(wrong, request("get-proposal",
                        Map.of("id", PROPOSAL.proposalId()), Map.of()),
                DomainApiException.Code.FAILED);
    }

    private static RoleApprovalsDomainApi api(String machine, Query query) {
        DomainQueryService service = new DomainQueryService() {
            @Override public List<String> chainIds() { return List.of(CHAIN); }
            @Override public AppQueryResult query(String chainId, String path, byte[] params) {
                return new AppQueryResult(chainId, machine, 42, ROOT,
                        query.apply(path, params));
            }
        };
        return new RoleApprovalsDomainApi(new DomainApiContext(Map.of(), service));
    }

    private static DomainApiRequest request(String route, Map<String, String> path,
                                            Map<String, List<String>> query) {
        return new DomainApiRequest(route, DomainHttpMethod.GET, route,
                path, query, new byte[0]);
    }

    private static void assertCode(RoleApprovalsDomainApi api, DomainApiRequest request,
                                   DomainApiException.Code code) {
        assertThatThrownBy(() -> api.handle(request))
                .isInstanceOfSatisfying(DomainApiException.class,
                        failure -> assertThat(failure.code()).isEqualTo(code));
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    @FunctionalInterface
    private interface Query {
        byte[] apply(String path, byte[] params);
    }
}
