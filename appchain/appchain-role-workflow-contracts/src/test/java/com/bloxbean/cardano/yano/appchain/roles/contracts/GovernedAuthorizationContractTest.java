package com.bloxbean.cardano.yano.appchain.roles.contracts;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GovernedAuthorizationContractTest {
    private static final byte[] SEED_A = repeated(1);
    private static final byte[] SEED_B = repeated(2);

    @Test
    void directPolicyAuthorityAndLimitsRoundTripCanonically() {
        DirectRolePolicyV1 direct = new DirectRolePolicyV1(
                "issuer-write", 1, RecordStatus.ACTIVE, "issuer", 100);
        AdministratorAuthorityV1 authority = new AdministratorAuthorityV1(
                "registry-admins", 1, List.of("admin-b", "admin-a"), 2, 1_000);
        GovernedAuthorizationLimitsV1 limits = GovernedAuthorizationLimitsV1.defaults();

        assertThat(DirectRolePolicyV1.decode(direct.encode())).isEqualTo(direct);
        assertThat(AdministratorAuthorityV1.decode(authority.encode()))
                .isEqualTo(authority);
        assertThat(GovernedAuthorizationLimitsV1.decode(limits.encode()))
                .isEqualTo(limits);
        assertThat(authority.administratorActorIds()).containsExactly("admin-a", "admin-b");
        assertThat(direct.digest()).hasSize(32);
        assertThat(authority.digest()).hasSize(32);
    }

    @Test
    void genesisClosureRequiresEveryAdministratorOrganizationKeyAndProof() {
        GovernedGenesisV1 genesis = genesis();
        byte[] encoded = genesis.encode();

        assertThat(GovernedGenesisV1.decode(encoded).encode()).isEqualTo(encoded);
        assertThat(genesis.administratorAuthority().distinctActorThreshold()).isEqualTo(2);
        assertThat(genesis.directPolicy("issuer-write")).isNotNull();
        assertThat(genesis.approvalPolicy("release-policy")).isNotNull();

        GenesisActorV1 first = genesis.actors().getFirst();
        assertThatThrownBy(() -> new GovernedGenesisV1(
                genesis.chainId(), genesis.administratorAuthority(),
                genesis.organizations(),
                List.of(new GenesisActorV1(first.actor(), first.keyProofs())),
                genesis.directPolicies(), genesis.approvalPolicies(), genesis.limits()))
                .isInstanceOf(RoleWorkflowException.class)
                .satisfies(error -> assertThat(((RoleWorkflowException) error).code())
                        .isEqualTo(RoleWorkflowResultCode.UNAUTHORIZED_ACTOR));

        ActorKeyEpochV1 wrongKey = new ActorKeyEpochV1(
                "admin-a-key", KeyGenUtil.getPublicKeyFromPrivateKey(SEED_B),
                1, 0, RecordStatus.ACTIVE);
        ActorRecordV1 wrongActor = new ActorRecordV1(
                "admin-a", "operator-a", 1, RecordStatus.ACTIVE,
                List.of("registry-admin"), List.of(wrongKey), new byte[0]);
        assertThatThrownBy(() -> new GenesisActorV1(
                wrongActor, first.keyProofs()))
                .isInstanceOf(RoleWorkflowException.class)
                .satisfies(error -> assertThat(((RoleWorkflowException) error).code())
                        .isEqualTo(RoleWorkflowResultCode.GOVERNANCE_PROOF_INVALID));
    }

    @Test
    void administratorStatementsBindClaimedKeyAndExactMutationSubject() {
        byte[] mutation = "put actor revision two".getBytes(StandardCharsets.UTF_8);
        byte[] mutationHash = ActorGovernanceCommandV1.mutationHash(mutation);
        byte[] publicKey = KeyGenUtil.getPublicKeyFromPrivateKey(SEED_A);
        AdministratorStatementV1 statement = new AdministratorStatementV1(
                AdministratorStatementV1.Decision.PROPOSE,
                "authenticated-map-chain", repeated(9), "registry-admins", 1,
                "mutation-001", mutationHash, 20, 100,
                "admin-a", 1, "admin-a-key", publicKey,
                10, 80, AdministratorStatementV1.ED25519);
        SignedAdministratorStatementV1 signed =
                SignedAdministratorStatementV1.sign(statement, SEED_A);
        ActorGovernanceCommandV1 command = new ActorGovernanceCommandV1(
                ActorGovernanceCommandV1.Operation.PROPOSE,
                "mutation-001", mutation, List.of(signed));

        assertThat(signed.verifyClaimedKey()).isTrue();
        assertThat(AdministratorStatementV1.decode(statement.encode()).encode())
                .isEqualTo(statement.encode());
        assertThat(SignedAdministratorStatementV1.decode(signed.encode())
                .verifyClaimedKey()).isTrue();
        assertThat(ActorGovernanceCommandV1.decode(command.encode()).encode())
                .isEqualTo(command.encode());

        byte[] changed = mutation.clone();
        changed[0] ^= 1;
        assertThatThrownBy(() -> new ActorGovernanceCommandV1(
                ActorGovernanceCommandV1.Operation.PROPOSE,
                "mutation-001", changed, List.of(signed)))
                .isInstanceOf(RoleWorkflowException.class);
    }

    @Test
    void governedMutationFamiliesAndRetainedVoteRecordRoundTrip() {
        AdministratorAuthorityV1 successor = new AdministratorAuthorityV1(
                "registry-admins", 2, List.of("admin-a"), 1, 500);
        RegistryMutationV1 registry = new RegistryMutationV1.PutAuthority(successor);
        DirectRolePolicyV1 direct = new DirectRolePolicyV1(
                "issuer-write", 2, RecordStatus.ACTIVE, "auditor", 50);
        PolicyMutationV1 policy = new PolicyMutationV1.PutDirectPolicy(direct);

        assertThat(RegistryMutationV1.decode(registry.encode())).isEqualTo(registry);
        assertThat(PolicyMutationV1.decode(policy.encode())).isEqualTo(policy);

        byte[] mutationHash = ActorGovernanceCommandV1.mutationHash(policy.encode());
        AdministratorStatementV1 statement = new AdministratorStatementV1(
                AdministratorStatementV1.Decision.PROPOSE,
                "authenticated-map-chain", repeated(9), "registry-admins", 1,
                "policy-change", mutationHash, 20, 100,
                "admin-a", 1, "admin-a-key",
                KeyGenUtil.getPublicKeyFromPrivateKey(SEED_A),
                10, 80, AdministratorStatementV1.ED25519);
        AcceptedAdministratorVoteV1 vote = new AcceptedAdministratorVoteV1(
                SignedAdministratorStatementV1.sign(statement, SEED_A),
                "operator-a", 1, 10);
        GovernedMutationRecordV1 record = new GovernedMutationRecordV1(
                "policy-change", policy.encode(), mutationHash,
                "registry-admins", 1, repeated(7), 20, 100,
                "admin-a", 10, List.of(vote), List.of(),
                GovernedMutationRecordV1.Status.PENDING, 0);

        assertThat(GovernedMutationRecordV1.decode(record.encode()).encode())
                .isEqualTo(record.encode());
        assertThat(record.terminal(GovernedMutationRecordV1.Status.ACTIVATED,
                20, List.of()).status())
                .isEqualTo(GovernedMutationRecordV1.Status.ACTIVATED);
    }

    @Test
    void commandResultsAndBoundedPendingPagesRoundTripCanonically() {
        byte[] messageId = repeated(0x31);
        RoleCommandResultV1 result = new RoleCommandResultV1(
                RoleCommandResultV1.KIND_APPROVAL, "release-001",
                RoleWorkflowResultCode.CAPACITY_EXCEEDED, 42,
                messageId, RoleCommandResultV1.commandDigest(
                "proposal".getBytes(StandardCharsets.UTF_8)));
        RolePendingQueriesV1.PageQuery query =
                new RolePendingQueriesV1.PageQuery("release-001", 25);
        RolePendingQueriesV1.ApprovalPage approvals =
                new RolePendingQueriesV1.ApprovalPage(List.of(
                        new RolePendingQueriesV1.ApprovalEntry(
                                "release-003", 80, "release-policy", "issuer-b"),
                        new RolePendingQueriesV1.ApprovalEntry(
                                "release-002", 70, "release-policy", "issuer-a")),
                        "release-003");
        RolePendingQueriesV1.GovernancePage governance =
                new RolePendingQueriesV1.GovernancePage(List.of(
                        new RolePendingQueriesV1.GovernanceEntry(
                                "mutation-002", 90, "registry-admins", 2, "admin-b"),
                        new RolePendingQueriesV1.GovernanceEntry(
                                "mutation-001", 85, "registry-admins", 2, "admin-a")),
                        "mutation-002");

        assertThat(RoleCommandResultV1.decode(result.encode()).encode())
                .isEqualTo(result.encode());
        assertThat(RolePendingQueriesV1.PageQuery.decode(query.encode())).isEqualTo(query);
        assertThat(RolePendingQueriesV1.ApprovalPage.decode(approvals.encode()))
                .isEqualTo(approvals);
        assertThat(RolePendingQueriesV1.GovernancePage.decode(governance.encode()))
                .isEqualTo(governance);
        assertThat(approvals.entries()).extracting(
                        RolePendingQueriesV1.ApprovalEntry::proposalId)
                .containsExactly("release-002", "release-003");
        assertThat(governance.entries()).extracting(
                        RolePendingQueriesV1.GovernanceEntry::mutationId)
                .containsExactly("mutation-001", "mutation-002");
        assertThat(new String(RoleWorkflowKeys.commandResult(messageId),
                StandardCharsets.US_ASCII)).isEqualTo("r/c/"
                + HexFormat.of().formatHex(messageId));

        assertThatThrownBy(() -> new RolePendingQueriesV1.ApprovalPage(List.of(
                approvals.entries().getFirst(), approvals.entries().getFirst()), ""))
                .isInstanceOf(RoleWorkflowException.class);
        assertThatThrownBy(() -> new RolePendingQueriesV1.PageQuery("", 101))
                .isInstanceOf(RoleWorkflowException.class);
    }

    static GovernedGenesisV1 genesis() {
        OrganizationRecordV1 organizationA = new OrganizationRecordV1(
                "operator-a", 1, RecordStatus.ACTIVE, new byte[0]);
        OrganizationRecordV1 organizationB = new OrganizationRecordV1(
                "operator-b", 1, RecordStatus.ACTIVE, new byte[0]);
        GenesisActorV1 actorA = actor(
                "authenticated-map-chain", "admin-a", "operator-a", SEED_A);
        GenesisActorV1 actorB = actor(
                "authenticated-map-chain", "admin-b", "operator-b", SEED_B);
        AdministratorAuthorityV1 authority = new AdministratorAuthorityV1(
                "registry-admins", 1, List.of("admin-a", "admin-b"), 2, 1_000);
        DirectRolePolicyV1 direct = new DirectRolePolicyV1(
                "issuer-write", 1, RecordStatus.ACTIVE, "issuer", 100);
        ApprovalPolicyV1 approval = new ApprovalPolicyV1(
                "release-policy", 1, RecordStatus.ACTIVE, List.of("issuer"),
                List.of(new ApprovalPolicyV1.RequiredClause(
                        "auditors", "auditor", 2,
                        ApprovalPolicyV1.DistinctBy.ORGANIZATION)),
                ApprovalPolicyV1.RejectionMode.ANY_ELIGIBLE, 1_000);
        return new GovernedGenesisV1(
                "authenticated-map-chain", authority,
                List.of(organizationB, organizationA), List.of(actorB, actorA),
                List.of(direct), List.of(approval),
                GovernedAuthorizationLimitsV1.defaults());
    }

    private static GenesisActorV1 actor(
            String chainId,
            String actorId,
            String organizationId,
            byte[] seed
    ) {
        ActorKeyEpochV1 key = new ActorKeyEpochV1(
                actorId + "-key", KeyGenUtil.getPublicKeyFromPrivateKey(seed),
                1, 0, RecordStatus.ACTIVE);
        ActorRecordV1 actor = new ActorRecordV1(
                actorId, organizationId, 1, RecordStatus.ACTIVE,
                List.of("registry-admin"), List.of(key), new byte[0]);
        return new GenesisActorV1(actor,
                List.of(ActorKeyProofV1.sign(chainId, actorId, 1, key, seed)));
    }

    private static byte[] repeated(int value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
