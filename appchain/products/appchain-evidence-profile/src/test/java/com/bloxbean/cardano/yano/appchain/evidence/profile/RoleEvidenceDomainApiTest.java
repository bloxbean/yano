package com.bloxbean.cardano.yano.appchain.evidence.profile;

import com.bloxbean.cardano.yano.api.appchain.AppQueryResult;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiAccess;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiException;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRequest;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiResponse;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainHttpMethod;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeCommitmentV1;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainQueryService;
import com.bloxbean.cardano.yano.appchain.roles.DomainActorRegistryComponent;
import com.bloxbean.cardano.yano.appchain.roles.RoleAwareApprovalsComponent;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorKeyEpochV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorStatementV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalPolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalProposalV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.OrganizationRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RecordStatus;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleApprovalStatsV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoleEvidenceDomainApiTest {
    private static final String CHAIN = "role-chain";
    private static final byte[] ROOT = fill(0x61, 32);
    private static final ApprovalPolicyV1 POLICY = new ApprovalPolicyV1(
            "evidence-release", 1, List.of("manufacturer"),
            List.of(new ApprovalPolicyV1.RequiredClause(
                    "auditors", "auditor", 1,
                    ApprovalPolicyV1.DistinctBy.ORGANIZATION)),
            ApprovalPolicyV1.RejectionMode.ANY_ELIGIBLE, 100);
    private static final ApprovalProposalV1 PROPOSAL = new ApprovalProposalV1(
            "approval-evidence-a", POLICY.policyId(), POLICY.revision(), POLICY.digest(),
            "evidence.release.v1", fill(0x31, 32), 200,
            ApprovalProposalV1.ProposalStatus.APPROVED,
            "manufacturer-a", "manufacturer-org", 1, "manufacturer", 1,
            "manufacturer-key", 10,
            List.of(new ApprovalProposalV1.AcceptedDecisionV1(
                    ActorStatementV1.Action.APPROVE, "auditor-a", "audit-org", 1,
                    "auditor", 1, "auditor-key", "auditors",
                    fill(0x41, 32), fill(0x51, 64), 20)));

    @Test
    void publishesReadOnlyRoutesAndProjectsEveryAuthenticatedRecord() {
        OrganizationRecordV1 organization = new OrganizationRecordV1(
                "audit-org", 1, RecordStatus.ACTIVE, new byte[0]);
        ActorRecordV1 actor = new ActorRecordV1(
                "auditor-a", "audit-org", 1, RecordStatus.ACTIVE,
                List.of("auditor"), List.of(new ActorKeyEpochV1(
                "auditor-key", fill(0x21, 32), 1, 0, RecordStatus.ACTIVE)), new byte[0]);
        RoleEvidenceDomainApi api = api((chain, path, params) -> switch (path) {
            case "components/domain-actors/organization-current",
                 "components/domain-actors/actor-current",
                 "components/role-approvals/policy-current" ->
                    ByteBuffer.allocate(Long.BYTES).putLong(1).array();
            case "components/domain-actors/organization" -> organization.encode();
            case "components/domain-actors/actor" -> actor.encode();
            case "components/role-approvals/policy" -> POLICY.encode();
            case "components/role-approvals/proposal" -> PROPOSAL.encode();
            case "components/role-approvals/evidence-approval" ->
                    PROPOSAL.proposalId().getBytes(StandardCharsets.US_ASCII);
            case "components/role-approvals/stats" ->
                    new RoleApprovalStatsV1(3, 1, 1, 0, 1, 0).encode();
            default -> throw new AssertionError(path);
        });

        assertThat(api.routes()).hasSize(6).allSatisfy(route -> {
            assertThat(route.method()).isEqualTo(DomainHttpMethod.GET);
            assertThat(route.access()).isEqualTo(DomainApiAccess.READ);
        });
        assertJson(api.handle(request("get-organization", Map.of("id", "audit-org"),
                Map.of())), "\"type\":\"organization\"", "\"status\":\"ACTIVE\"");
        assertJson(api.handle(request("get-actor", Map.of("id", "auditor-a"), Map.of())),
                "\"type\":\"actor\"", "\"roles\":[\"auditor\"]");
        assertJson(api.handle(request("get-policy", Map.of("id", POLICY.policyId()), Map.of())),
                "\"minimumCount\":1", "\"distinctBy\":\"ORGANIZATION\"");
        assertJson(api.handle(request("get-proposal", Map.of("id", PROPOSAL.proposalId()),
                        Map.of())),
                "\"status\":\"APPROVED\"", "\"actorId\":\"auditor-a\"");
        assertJson(api.handle(request("get-evidence-approval",
                        Map.of("evidence_id", "evidence-a", "version", "1"), Map.of())),
                "\"businessVersion\":1", "\"proposalId\":\"approval-evidence-a\"");
        assertJson(api.handle(request("get-approval-stats", Map.of(), Map.of())),
                "\"type\":\"approval-stats\"", "\"pending\":1", "\"cancelled\":1");

        String proposalJson = new String(api.handle(request("get-proposal",
                Map.of("id", PROPOSAL.proposalId()), Map.of())).body(), StandardCharsets.UTF_8);
        byte[] proposalKey = CompositeCommitmentV1.componentKey(
                RoleAwareApprovalsComponent.COMPONENT_ID,
                RoleWorkflowKeys.proposal(PROPOSAL.proposalId()));
        assertThat(proposalJson).contains("\"proofKey\":\""
                        + java.util.HexFormat.of().formatHex(proposalKey) + "\"")
                .contains("\"recordValue\":\""
                        + java.util.HexFormat.of().formatHex(PROPOSAL.encode()) + "\"");

        String currentActorJson = new String(api.handle(request("get-actor",
                Map.of("id", "auditor-a"), Map.of())).body(), StandardCharsets.UTF_8);
        byte[] pointerKey = CompositeCommitmentV1.componentKey(
                DomainActorRegistryComponent.COMPONENT_ID,
                RoleWorkflowKeys.actorCurrent("auditor-a"));
        assertThat(currentActorJson)
                .contains("\"currentPointerProofKey\":\""
                        + java.util.HexFormat.of().formatHex(pointerKey) + "\"")
                .contains("\"currentPointerValue\":\"0000000000000001\"");
        String historicalActorJson = new String(api.handle(request("get-actor",
                Map.of("id", "auditor-a"), Map.of("revision", List.of("1")))).body(),
                StandardCharsets.UTF_8);
        assertThat(historicalActorJson).doesNotContain("currentPointerProofKey");
    }

    @Test
    void rejectsAmbiguousSelectorsAndFailsClosedOnIdentityOrLinkCorruption() {
        RoleEvidenceDomainApi api = api((chain, path, params) -> PROPOSAL.encode());
        assertCode(api, request("get-proposal", Map.of("id", PROPOSAL.proposalId()),
                Map.of("revision", List.of("1"))), DomainApiException.Code.INVALID_REQUEST);
        assertCode(api, request("get-evidence-approval",
                        Map.of("evidence_id", "evidence-a", "version", "1"),
                        Map.of("revision", List.of("1"))),
                DomainApiException.Code.INVALID_REQUEST);
        assertCode(api, request("get-approval-stats", Map.of(),
                        Map.of("revision", List.of("1"))),
                DomainApiException.Code.INVALID_REQUEST);
        assertCode(api, request("get-actor", Map.of("id", "INVALID"), Map.of()),
                DomainApiException.Code.INVALID_REQUEST);

        RoleEvidenceDomainApi wrongMachine = api("another-machine",
                (chain, path, params) -> PROPOSAL.encode());
        assertCode(wrongMachine, request("get-proposal",
                        Map.of("id", PROPOSAL.proposalId()), Map.of()),
                DomainApiException.Code.FAILED);

        RoleEvidenceDomainApi corruptLink = api((chain, path, params) ->
                "INVALID-LINK".getBytes(StandardCharsets.US_ASCII));
        assertCode(corruptLink, request("get-evidence-approval",
                        Map.of("evidence_id", "evidence-a", "version", "1"), Map.of()),
                DomainApiException.Code.FAILED);

        ActorRecordV1 stale = new ActorRecordV1(
                "auditor-a", "audit-org", 1, RecordStatus.ACTIVE,
                List.of("auditor"), List.of(new ActorKeyEpochV1(
                "auditor-key", fill(0x21, 32), 1, 0, RecordStatus.ACTIVE)), new byte[0]);
        RoleEvidenceDomainApi staleCurrent = api((chain, path, params) ->
                path.endsWith("actor-current")
                        ? ByteBuffer.allocate(Long.BYTES).putLong(2).array()
                        : stale.encode());
        assertCode(staleCurrent, request("get-actor", Map.of("id", "auditor-a"), Map.of()),
                DomainApiException.Code.FAILED);
    }

    private static RoleEvidenceDomainApi api(Query query) {
        return api(RoleEvidenceStateMachineProvider.ID, query);
    }

    private static RoleEvidenceDomainApi api(String machine, Query query) {
        DomainQueryService service = new DomainQueryService() {
            @Override public List<String> chainIds() { return List.of(CHAIN); }
            @Override public AppQueryResult query(String chainId, String path, byte[] params) {
                assertThat(chainId).isEqualTo(CHAIN);
                return new AppQueryResult(CHAIN, machine, 42, ROOT,
                        query.apply(chainId, path, params));
            }
        };
        return new RoleEvidenceDomainApi(new DomainApiContext(Map.of(), service));
    }

    private static DomainApiRequest request(String route, Map<String, String> path,
                                            Map<String, List<String>> query) {
        return new DomainApiRequest(route, DomainHttpMethod.GET, route,
                path, query, new byte[0]);
    }

    private static void assertJson(DomainApiResponse response, String... fragments) {
        assertThat(response.status()).isEqualTo(200);
        assertThat(new String(response.body(), StandardCharsets.UTF_8))
                .contains(fragments)
                .contains("\"chainId\":\"role-chain\"",
                        "\"stateMachineId\":\"role-evidence\"",
                        "\"proofKey\":\"", "\"recordValue\":\"");
    }

    private static void assertCode(RoleEvidenceDomainApi api, DomainApiRequest request,
                                   DomainApiException.Code code) {
        assertThatThrownBy(() -> api.handle(request))
                .isInstanceOfSatisfying(DomainApiException.class,
                        failure -> assertThat(failure.code()).isEqualTo(code));
    }

    private static byte[] fill(int value, int length) {
        byte[] result = new byte[length];
        Arrays.fill(result, (byte) value);
        return result;
    }

    @FunctionalInterface
    private interface Query {
        byte[] apply(String chain, String path, byte[] params);
    }
}
