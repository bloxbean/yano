package com.bloxbean.cardano.yano.appchain.roles;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.appchain.composite.ComponentDescriptor;
import com.bloxbean.cardano.yano.appchain.composite.ComponentGeneration;
import com.bloxbean.cardano.yano.appchain.composite.CompositeWorkflowContext;
import com.bloxbean.cardano.yano.appchain.composite.WorkflowDescriptor;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorKeyEpochV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorKeyProofV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorStatementV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalPolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalProposalV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedMutationCommandV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.OrganizationRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.PolicyMutationV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RecordStatus;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RegistryMutationV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleApprovalStatsV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowLimits;
import com.bloxbean.cardano.yano.appchain.roles.contracts.SignedActorCommandV1;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoleWorkflowTest {
    private static final String CHAIN = "role-demo";
    private static final byte[] ADMIN_A = filled(0xa1);
    private static final byte[] ADMIN_B = filled(0xb2);
    private static final byte[] ADMIN_C = filled(0xc3);

    @Test
    void rolePolicyEnforcesActorSignaturesRolesAndOrganizationDistinctness() {
        Scenario first = scenario();
        Scenario replay = scenario();

        assertThat(first.snapshot).isEqualTo(replay.snapshot);
        assertThat(first.approved.status()).isEqualTo(ApprovalProposalV1.ProposalStatus.APPROVED);
        assertThat(first.approved.decisions()).extracting(
                ApprovalProposalV1.AcceptedDecisionV1::actorId)
                .containsExactly("auditor-a1", "auditor-b", "regulator-a");
        assertThat(first.approved.decisions()).allSatisfy(decision -> {
            assertThat(decision.organizationRevision()).isEqualTo(1);
            assertThat(decision.statementDigest()).hasSize(32);
            assertThat(decision.signature()).hasSize(64);
        });
        assertThat(first.stats).isEqualTo(new RoleApprovalStatsV1(1, 0, 1, 0, 0, 0));
    }

    @Test
    void hostilePublicKeysAndMalformedFinalizedCommandsAreDeterministicNoOps() {
        Fixture fixture = baseFixture();
        byte[] hostilePublicKey = filled(0x7f);
        ActorKeyEpochV1 hostileKey = new ActorKeyEpochV1(
                "hostile-key", hostilePublicKey, 1, 0, RecordStatus.ACTIVE);
        ActorRecordV1 hostileActor = new ActorRecordV1(
                "hostile-actor", "audit-org-a", 1, RecordStatus.ACTIVE,
                List.of("auditor"), List.of(hostileKey), new byte[0]);
        ActorKeyProofV1 invalidProof = new ActorKeyProofV1(
                CHAIN, hostileActor.actorId(), hostileActor.revision(),
                hostileKey, new byte[64]);

        assertThatCode(() -> fixture.governRegistry(
                new RegistryMutationV1.PutActor(hostileActor, List.of(invalidProof))))
                .doesNotThrowAnyException();
        assertThat(fixture.registryState.get(RoleWorkflowKeys.actorRevision(
                hostileActor.actorId(), 1))).isEmpty();

        String before = fixture.snapshot();
        byte[] oversized = new byte[RoleWorkflowLimits.MAX_COMMAND_BYTES + 1];
        assertThatCode(() -> fixture.registry.apply(block(fixture.height++,
                        fixture.message(DomainActorRegistryComponent.TOPIC,
                                oversized, ADMIN_A)), fixture.registryState,
                AppEffectEmitter.rejecting("not used"))).doesNotThrowAnyException();
        assertThat(fixture.snapshot()).isEqualTo(before);
    }

    @Test
    void theSameActorDecisionRelayedByTwoMembersCountsOnce() {
        Fixture fixture = baseFixture();
        fixture.governPolicy(policy());
        long deadline = fixture.height + 100;
        fixture.actorCommand(fixture.command(fixture.manufacturer,
                action(ActorStatementV1.Action.PROPOSE, "relay-dedup", deadline,
                        fixture.manufacturer, "")));
        SignedActorCommandV1 approval = fixture.command(fixture.auditorA1,
                action(ActorStatementV1.Action.APPROVE, "relay-dedup", deadline,
                        fixture.auditorA1, "auditors"));

        fixture.actorCommand(approval, ADMIN_A);
        fixture.actorCommand(approval, ADMIN_B);

        assertThat(fixture.proposal("relay-dedup").decisions())
                .extracting(ApprovalProposalV1.AcceptedDecisionV1::actorId)
                .containsExactly("auditor-a1");
    }

    @Test
    void pendingProposalBudgetIsConsensusBounded() {
        Fixture fixture = baseFixture();
        fixture.governPolicy(policy());
        RoleApprovalStatsV1 saturated = new RoleApprovalStatsV1(
                RoleWorkflowLimits.MAX_PENDING_PROPOSALS,
                RoleWorkflowLimits.MAX_PENDING_PROPOSALS, 0, 0, 0, 0);
        fixture.approvalState.put(RoleWorkflowKeys.approvalStats(), saturated.encode());
        long deadline = fixture.height + 100;

        fixture.actorCommand(fixture.command(fixture.manufacturer,
                action(ActorStatementV1.Action.PROPOSE, "over-pending-budget", deadline,
                        fixture.manufacturer, "")));

        assertThat(fixture.approvalState.get(
                RoleWorkflowKeys.proposal("over-pending-budget"))).isEmpty();
        assertThat(fixture.stats()).isEqualTo(saturated);
    }

    @Test
    void administratorAndPendingGovernanceBudgetsAreConsensusBounded() {
        List<byte[]> tooManyAdministrators = new ArrayList<>();
        for (int index = 0; index <= RoleWorkflowLimits.MAX_ADMINISTRATORS; index++) {
            byte[] administrator = new byte[32];
            java.nio.ByteBuffer.wrap(administrator, 24, 8).putLong(index + 1L);
            tooManyAdministrators.add(administrator);
        }
        assertThatThrownBy(() -> new RoleWorkflowGovernanceConfig(
                tooManyAdministrators, 1, 100))
                .isInstanceOf(IllegalArgumentException.class);

        Fixture fixture = new Fixture();
        for (int index = 0; index < RoleWorkflowLimits.MAX_PENDING_MUTATIONS; index++) {
            fixture.proposePolicyMutation("pending-" + index);
        }
        fixture.proposePolicyMutation("over-pending-budget");

        assertThat(fixture.approvalState.get(
                RoleWorkflowKeys.governedMutation("pending-0"))).isPresent();
        assertThat(fixture.approvalState.get(
                RoleWorkflowKeys.governedMutation("over-pending-budget"))).isEmpty();
    }

    @Test
    void governedActorRotationRejectsOldRevisionAndAcceptsNewKey() {
        Fixture fixture = baseFixture();
        fixture.governPolicy(policy());
        long deadline = fixture.height + 100;
        fixture.actorCommand(fixture.command(fixture.manufacturer,
                action(ActorStatementV1.Action.PROPOSE, "rotation-check", deadline,
                        fixture.manufacturer, "")));

        ActorIdentity old = fixture.auditorA1;
        ActorIdentity rotated = old.rotated(fixture.height + 1, seed(0x55));
        fixture.governRegistry(actorMutation(rotated));
        fixture.actorCommand(fixture.command(old,
                action(ActorStatementV1.Action.APPROVE, "rotation-check", deadline,
                        old, "auditors")));
        assertThat(fixture.proposal("rotation-check").decisions()).isEmpty();
        fixture.actorCommand(fixture.command(rotated,
                action(ActorStatementV1.Action.APPROVE, "rotation-check", deadline,
                        rotated, "auditors")));
        assertThat(fixture.proposal("rotation-check").decisions())
                .extracting(ApprovalProposalV1.AcceptedDecisionV1::actorId)
                .containsExactly("auditor-a1");
        assertThat(fixture.proposal("rotation-check").decisions().getFirst().actorRevision())
                .isEqualTo(2);
    }

    @Test
    void actorAndAdminCancellationAreExplicitAndTerminal() {
        Fixture fixture = baseFixture();
        fixture.governPolicy(policy());
        long deadline = fixture.height + 100;
        fixture.actorCommand(fixture.command(fixture.manufacturer,
                action(ActorStatementV1.Action.PROPOSE, "actor-cancel", deadline,
                        fixture.manufacturer, "")));
        fixture.actorCommand(fixture.command(fixture.auditorA1,
                action(ActorStatementV1.Action.CANCEL, "actor-cancel", deadline,
                        fixture.auditorA1, "")));
        assertThat(fixture.proposal("actor-cancel").status())
                .isEqualTo(ApprovalProposalV1.ProposalStatus.PENDING);
        fixture.actorCommand(fixture.command(fixture.manufacturer,
                action(ActorStatementV1.Action.CANCEL, "actor-cancel", deadline,
                        fixture.manufacturer, "")));
        assertThat(fixture.proposal("actor-cancel").status())
                .isEqualTo(ApprovalProposalV1.ProposalStatus.CANCELLED);

        fixture.actorCommand(fixture.command(fixture.manufacturer,
                action(ActorStatementV1.Action.PROPOSE, "admin-cancel", deadline + 20,
                        fixture.manufacturer, "")));
        fixture.governPolicy(new PolicyMutationV1.CancelProposal("admin-cancel"));
        assertThat(fixture.proposal("admin-cancel").status())
                .isEqualTo(ApprovalProposalV1.ProposalStatus.CANCELLED);
        assertThat(fixture.stats()).isEqualTo(new RoleApprovalStatsV1(2, 0, 0, 0, 2, 0));
    }

    @Test
    void statementsAreDomainBoundIdempotentAndExpireAtDeterministicBlockHeight() {
        Fixture fixture = baseFixture();
        fixture.governPolicy(policy());
        long deadline = fixture.height + 20;
        fixture.actorCommand(fixture.command(fixture.manufacturer,
                action(ActorStatementV1.Action.PROPOSE, "bounded-proposal", deadline,
                        fixture.manufacturer, "")));

        ActorStatementV1 valid = action(ActorStatementV1.Action.APPROVE,
                "bounded-proposal", deadline, fixture.auditorA1, "auditors");
        ActorStatementV1 wrongChain = new ActorStatementV1(valid.action(), "other-chain",
                valid.proposalId(), valid.policyId(), valid.policyRevision(),
                valid.payloadDomain(), valid.payloadHash(), valid.deadlineHeight(),
                valid.actorId(), valid.actorRevision(), valid.keyId(), valid.clauseId());
        fixture.actorCommand(fixture.command(fixture.auditorA1, wrongChain));
        byte[] wrongPayload = valid.payloadHash();
        wrongPayload[0] ^= 1;
        ActorStatementV1 mismatched = new ActorStatementV1(valid.action(), valid.chainId(),
                valid.proposalId(), valid.policyId(), valid.policyRevision(),
                valid.payloadDomain(), wrongPayload, valid.deadlineHeight(),
                valid.actorId(), valid.actorRevision(), valid.keyId(), valid.clauseId());
        fixture.actorCommand(fixture.command(fixture.auditorA1, mismatched));
        fixture.actorCommand(fixture.command(fixture.manufacturer, valid));
        assertThat(fixture.proposal("bounded-proposal").decisions()).isEmpty();

        SignedActorCommandV1 accepted = fixture.command(fixture.auditorA1, valid);
        fixture.actorCommand(accepted);
        fixture.actorCommand(accepted);
        assertThat(fixture.proposal("bounded-proposal").decisions())
                .extracting(ApprovalProposalV1.AcceptedDecisionV1::actorId)
                .containsExactly("auditor-a1");

        fixture.height = deadline + 1;
        fixture.actorCommand(fixture.command(fixture.auditorB,
                action(ActorStatementV1.Action.APPROVE, "bounded-proposal", deadline,
                        fixture.auditorB, "auditors")));
        assertThat(fixture.proposal("bounded-proposal").status())
                .isEqualTo(ApprovalProposalV1.ProposalStatus.EXPIRED);
        assertThat(fixture.proposal("bounded-proposal").decisions())
                .extracting(ApprovalProposalV1.AcceptedDecisionV1::actorId)
                .containsExactly("auditor-a1");
        assertThat(fixture.stats()).isEqualTo(new RoleApprovalStatsV1(1, 0, 0, 0, 0, 1));
    }

    @Test
    void eligibleRejectionIsTerminalAndCountedExactlyOnce() {
        Fixture fixture = baseFixture();
        fixture.governPolicy(policy());
        long deadline = fixture.height + 100;
        fixture.actorCommand(fixture.command(fixture.manufacturer,
                action(ActorStatementV1.Action.PROPOSE, "rejected", deadline,
                        fixture.manufacturer, "")));
        SignedActorCommandV1 rejection = fixture.command(fixture.auditorA1,
                action(ActorStatementV1.Action.REJECT, "rejected", deadline,
                        fixture.auditorA1, "auditors"));
        fixture.actorCommand(rejection);
        fixture.actorCommand(rejection);

        assertThat(fixture.proposal("rejected").status())
                .isEqualTo(ApprovalProposalV1.ProposalStatus.REJECTED);
        assertThat(fixture.stats()).isEqualTo(new RoleApprovalStatsV1(1, 0, 0, 1, 0, 0));
    }

    @Test
    void suspendedOrganizationsAndRevokedActorsCannotAuthorize() {
        Fixture suspended = baseFixture();
        suspended.governPolicy(policy());
        long suspendedDeadline = suspended.height + 100;
        suspended.actorCommand(suspended.command(suspended.manufacturer,
                action(ActorStatementV1.Action.PROPOSE, "suspended-org", suspendedDeadline,
                        suspended.manufacturer, "")));
        suspended.governRegistry(new RegistryMutationV1.PutOrganization(
                new OrganizationRecordV1("audit-org-a", 2, RecordStatus.SUSPENDED,
                        new byte[0])));
        suspended.actorCommand(suspended.command(suspended.auditorA1,
                action(ActorStatementV1.Action.APPROVE, "suspended-org", suspendedDeadline,
                        suspended.auditorA1, "auditors")));
        assertThat(suspended.proposal("suspended-org").decisions()).isEmpty();

        Fixture revoked = baseFixture();
        revoked.governPolicy(policy());
        long revokedDeadline = revoked.height + 100;
        revoked.actorCommand(revoked.command(revoked.manufacturer,
                action(ActorStatementV1.Action.PROPOSE, "revoked-actor", revokedDeadline,
                        revoked.manufacturer, "")));
        ActorIdentity original = revoked.auditorA1;
        ActorRecordV1 revokedRecord = new ActorRecordV1(original.actorId,
                original.organizationId, 2, RecordStatus.REVOKED,
                List.of(original.role), original.keys, new byte[0]);
        revoked.governRegistry(new RegistryMutationV1.PutActor(revokedRecord, List.of()));
        revoked.actorCommand(revoked.command(original,
                action(ActorStatementV1.Action.APPROVE, "revoked-actor", revokedDeadline,
                        original, "auditors")));
        ActorStatementV1 currentRevision = new ActorStatementV1(
                ActorStatementV1.Action.APPROVE, CHAIN, "revoked-actor",
                "evidence-release", 1, "evidence.release.v1", filled(0x19),
                revokedDeadline, original.actorId, 2, original.key.keyId(), "auditors");
        revoked.actorCommand(SignedActorCommandV1.sign(currentRevision, original.seed));
        assertThat(revoked.proposal("revoked-actor").decisions()).isEmpty();
    }

    @Test
    void expiredUnknownAndSignatureDomainSubstitutionCannotAuthorize() {
        Fixture fixture = baseFixture();
        fixture.governPolicy(policy());
        ActorIdentity original = fixture.auditorA1;
        ActorKeyEpochV1 expiredKey = new ActorKeyEpochV1(original.key.keyId(),
                original.key.publicKey(), original.key.validFromHeight(), fixture.height,
                RecordStatus.ACTIVE);
        fixture.governRegistry(new RegistryMutationV1.PutActor(new ActorRecordV1(
                original.actorId, original.organizationId, 2, RecordStatus.ACTIVE,
                List.of(original.role), List.of(expiredKey), new byte[0]), List.of()));

        long deadline = fixture.height + 100;
        fixture.actorCommand(fixture.command(fixture.manufacturer,
                action(ActorStatementV1.Action.PROPOSE, "credential-bound", deadline,
                        fixture.manufacturer, "")));
        ActorStatementV1 expired = new ActorStatementV1(ActorStatementV1.Action.APPROVE,
                CHAIN, "credential-bound", "evidence-release", 1,
                "evidence.release.v1", filled(0x19), deadline, original.actorId, 2,
                original.key.keyId(), "auditors");
        fixture.actorCommand(SignedActorCommandV1.sign(expired, original.seed));

        ActorStatementV1 unknown = new ActorStatementV1(ActorStatementV1.Action.APPROVE,
                CHAIN, "credential-bound", "evidence-release", 1,
                "evidence.release.v1", filled(0x19), deadline, "unknown-actor", 1,
                "unknown-key", "auditors");
        fixture.actorCommand(SignedActorCommandV1.sign(unknown, original.seed));

        ActorStatementV1 validForOriginalProposal = new ActorStatementV1(
                ActorStatementV1.Action.APPROVE, CHAIN, "credential-bound",
                "evidence-release", 1, "evidence.release.v1", filled(0x19), deadline,
                original.actorId, 2, original.key.keyId(), "auditors");
        SignedActorCommandV1 validSignature = SignedActorCommandV1.sign(
                validForOriginalProposal, original.seed);
        ActorStatementV1 substitutedProposal = new ActorStatementV1(
                ActorStatementV1.Action.APPROVE, CHAIN, "different-proposal",
                "evidence-release", 1, "evidence.release.v1", filled(0x19), deadline,
                original.actorId, 2, original.key.keyId(), "auditors");
        fixture.actorCommand(new SignedActorCommandV1(
                substitutedProposal, validSignature.signature()));
        ActorStatementV1 substitutedPolicy = new ActorStatementV1(
                ActorStatementV1.Action.APPROVE, CHAIN, "credential-bound",
                "different-policy", 1, "evidence.release.v1", filled(0x19), deadline,
                original.actorId, 2, original.key.keyId(), "auditors");
        fixture.actorCommand(new SignedActorCommandV1(
                substitutedPolicy, validSignature.signature()));
        ActorStatementV1 substitutedDecision = new ActorStatementV1(
                ActorStatementV1.Action.REJECT, CHAIN, "credential-bound",
                "evidence-release", 1, "evidence.release.v1", filled(0x19), deadline,
                original.actorId, 2, original.key.keyId(), "auditors");
        fixture.actorCommand(new SignedActorCommandV1(
                substitutedDecision, validSignature.signature()));

        assertThat(fixture.proposal("credential-bound").decisions()).isEmpty();
        assertThat(fixture.proposal("credential-bound").status())
                .isEqualTo(ApprovalProposalV1.ProposalStatus.PENDING);
    }

    @Test
    void keyHistoryCannotBeDroppedRevokedKeysCannotReactivateAndProofsMustBeExact() {
        Fixture fixture = baseFixture();
        ActorIdentity original = fixture.auditorA1;
        ActorIdentity rotated = original.rotated(fixture.height + 1, seed(0x66));
        fixture.governRegistry(actorMutation(rotated));

        ActorRecordV1 dropsOldKey = new ActorRecordV1(original.actorId,
                original.organizationId, 3, RecordStatus.ACTIVE, List.of(original.role),
                List.of(rotated.key), new byte[0]);
        fixture.governRegistry(new RegistryMutationV1.PutActor(dropsOldKey, List.of()));
        assertThat(fixture.registryState.get(RoleWorkflowKeys.actorRevision(
                original.actorId, 3))).isEmpty();

        ActorKeyEpochV1 retired = rotated.keys.stream()
                .filter(key -> key.keyId().equals(original.key.keyId())).findFirst().orElseThrow();
        ActorKeyEpochV1 reactivated = new ActorKeyEpochV1(retired.keyId(),
                retired.publicKey(), retired.validFromHeight(), 0, RecordStatus.ACTIVE);
        ActorRecordV1 reactivatesRevokedKey = new ActorRecordV1(original.actorId,
                original.organizationId, 3, RecordStatus.ACTIVE, List.of(original.role),
                List.of(reactivated, rotated.key), new byte[0]);
        fixture.governRegistry(new RegistryMutationV1.PutActor(
                reactivatesRevokedKey, List.of()));
        assertThat(fixture.registryState.get(RoleWorkflowKeys.actorRevision(
                original.actorId, 3))).isEmpty();

        byte[] firstSeed = seed(0x71);
        byte[] secondSeed = seed(0x72);
        ActorKeyEpochV1 onlyKey = new ActorKeyEpochV1("extra-key-1",
                KeyGenUtil.getPublicKeyFromPrivateKey(firstSeed), 1, 0, RecordStatus.ACTIVE);
        ActorKeyEpochV1 unrelatedKey = new ActorKeyEpochV1("extra-key-2",
                KeyGenUtil.getPublicKeyFromPrivateKey(secondSeed), 1, 0, RecordStatus.ACTIVE);
        ActorRecordV1 extraActor = new ActorRecordV1("extra-actor", "audit-org-a", 1,
                RecordStatus.ACTIVE, List.of("auditor"), List.of(onlyKey), new byte[0]);
        fixture.governRegistry(new RegistryMutationV1.PutActor(extraActor, List.of(
                ActorKeyProofV1.sign(CHAIN, "extra-actor", 1, onlyKey, firstSeed),
                ActorKeyProofV1.sign(CHAIN, "extra-actor", 1, unrelatedKey, secondSeed))));
        assertThat(fixture.registryState.get(RoleWorkflowKeys.actorRevision(
                "extra-actor", 1))).isEmpty();
    }

    @Test
    void governanceRequiresDistinctConfiguredAdministratorsBeforeActivation() {
        Fixture fixture = new Fixture();
        RegistryMutationV1 mutation = new RegistryMutationV1.PutOrganization(
                org("threshold-org"));
        GovernedMutationCommandV1.Propose proposed = new GovernedMutationCommandV1.Propose(
                "threshold-org-v1", mutation.encode(), fixture.height + 50);
        fixture.registry.apply(block(fixture.height++,
                        fixture.message(DomainActorRegistryComponent.TOPIC,
                                proposed.encode(), ADMIN_A),
                        fixture.message(DomainActorRegistryComponent.TOPIC,
                                new GovernedMutationCommandV1.Approve(
                                        proposed.mutationId(), proposed.mutationHash()).encode(),
                                ADMIN_A),
                        fixture.message(DomainActorRegistryComponent.TOPIC,
                                new GovernedMutationCommandV1.Activate(
                                        proposed.mutationId(), proposed.mutationHash()).encode(),
                                ADMIN_A)), fixture.registryState,
                AppEffectEmitter.rejecting("not used"));
        assertThat(fixture.registryState.get(RoleWorkflowKeys.organizationRevision(
                "threshold-org", 1))).isEmpty();

        fixture.registry.apply(block(fixture.height++,
                        fixture.message(DomainActorRegistryComponent.TOPIC,
                                new GovernedMutationCommandV1.Approve(
                                        proposed.mutationId(), proposed.mutationHash()).encode(),
                                ADMIN_B),
                        fixture.message(DomainActorRegistryComponent.TOPIC,
                                new GovernedMutationCommandV1.Activate(
                                        proposed.mutationId(), proposed.mutationHash()).encode(),
                                ADMIN_A)), fixture.registryState,
                AppEffectEmitter.rejecting("not used"));
        assertThat(fixture.registryState.get(RoleWorkflowKeys.organizationRevision(
                "threshold-org", 1))).isPresent();
    }

    private static Scenario scenario() {
        Fixture fixture = baseFixture();
        fixture.governPolicy(policy());
        long deadline = fixture.height + 100;
        fixture.actorCommand(fixture.command(fixture.manufacturer,
                action(ActorStatementV1.Action.PROPOSE, "evidence-001", deadline,
                        fixture.manufacturer, "")));
        // Wrong role, then same-organization duplicate: neither enters the trail.
        fixture.actorCommand(fixture.command(fixture.manufacturer,
                action(ActorStatementV1.Action.APPROVE, "evidence-001", deadline,
                        fixture.manufacturer, "auditors")));
        fixture.actorCommand(fixture.command(fixture.auditorA1,
                action(ActorStatementV1.Action.APPROVE, "evidence-001", deadline,
                        fixture.auditorA1, "auditors")));
        fixture.actorCommand(fixture.command(fixture.auditorA2,
                action(ActorStatementV1.Action.APPROVE, "evidence-001", deadline,
                        fixture.auditorA2, "auditors")));
        fixture.actorCommand(fixture.command(fixture.auditorB,
                action(ActorStatementV1.Action.APPROVE, "evidence-001", deadline,
                        fixture.auditorB, "auditors")));
        fixture.actorCommand(fixture.command(fixture.regulator,
                action(ActorStatementV1.Action.APPROVE, "evidence-001", deadline,
                        fixture.regulator, "regulator")));
        ApprovalProposalV1 proposal = fixture.proposal("evidence-001");
        return new Scenario(proposal, fixture.stats(), fixture.snapshot());
    }

    private static Fixture baseFixture() {
        Fixture fixture = new Fixture();
        fixture.governRegistry(new RegistryMutationV1.PutOrganization(org("manufacturer-org")));
        fixture.governRegistry(new RegistryMutationV1.PutOrganization(org("audit-org-a")));
        fixture.governRegistry(new RegistryMutationV1.PutOrganization(org("audit-org-b")));
        fixture.governRegistry(new RegistryMutationV1.PutOrganization(org("regulator-org")));
        fixture.governRegistry(actorMutation(fixture.manufacturer));
        fixture.governRegistry(actorMutation(fixture.auditorA1));
        fixture.governRegistry(actorMutation(fixture.auditorA2));
        fixture.governRegistry(actorMutation(fixture.auditorB));
        fixture.governRegistry(actorMutation(fixture.regulator));
        return fixture;
    }

    private static ApprovalPolicyV1 policy() {
        return new ApprovalPolicyV1("evidence-release", 1, List.of("manufacturer"),
                List.of(new ApprovalPolicyV1.RequiredClause(
                                "auditors", "auditor", 2,
                                ApprovalPolicyV1.DistinctBy.ORGANIZATION),
                        new ApprovalPolicyV1.RequiredClause(
                                "regulator", "regulator", 1,
                                ApprovalPolicyV1.DistinctBy.ACTOR)),
                ApprovalPolicyV1.RejectionMode.ANY_ELIGIBLE, 1_000);
    }

    private static OrganizationRecordV1 org(String id) {
        return new OrganizationRecordV1(id, 1, RecordStatus.ACTIVE, new byte[0]);
    }

    private static RegistryMutationV1 actorMutation(ActorIdentity identity) {
        ActorRecordV1 record = identity.record();
        ActorKeyProofV1 proof = ActorKeyProofV1.sign(CHAIN, identity.actorId,
                identity.revision, identity.key, identity.seed);
        return new RegistryMutationV1.PutActor(record, List.of(proof));
    }

    private static ActorStatementV1 action(ActorStatementV1.Action action,
                                           String proposalId, long deadline,
                                           ActorIdentity actor, String clause) {
        return new ActorStatementV1(action, CHAIN, proposalId, "evidence-release", 1,
                "evidence.release.v1", filled(0x19), deadline, actor.actorId,
                actor.revision, actor.key.keyId(), clause);
    }

    private static final class Fixture {
        private final MemoryState registryState = new MemoryState();
        private final MemoryState approvalState = new MemoryState();
        private final ComponentDescriptor registryDescriptor;
        private final ComponentDescriptor approvalDescriptor;
        private final DomainActorRegistryComponent registry;
        private final RoleApprovalWorkflow workflow;
        private final Context context;
        private long height = 1;
        private long sequence = 1;
        private final ActorIdentity manufacturer = identity(
                "manufacturer-a", "manufacturer-org", "manufacturer", 0x11);
        private final ActorIdentity auditorA1 = identity(
                "auditor-a1", "audit-org-a", "auditor", 0x21);
        private final ActorIdentity auditorA2 = identity(
                "auditor-a2", "audit-org-a", "auditor", 0x22);
        private final ActorIdentity auditorB = identity(
                "auditor-b", "audit-org-b", "auditor", 0x31);
        private final ActorIdentity regulator = identity(
                "regulator-a", "regulator-org", "regulator", 0x41);

        private Fixture() {
            RoleWorkflowGovernanceConfig governance = new RoleWorkflowGovernanceConfig(
                    List.of(ADMIN_A, ADMIN_B, ADMIN_C), 2, 1_000);
            registryDescriptor = new ComponentDescriptor(DomainActorRegistryComponent.COMPONENT_ID,
                    "1.0.0", governance.configurationId(), "domain-actors-state-v1",
                    1, 0, List.of(DomainActorRegistryComponent.TOPIC),
                    List.of(DomainActorRegistryComponent.QUERY_ACTOR,
                            DomainActorRegistryComponent.QUERY_ORGANIZATION), 0);
            approvalDescriptor = new ComponentDescriptor(RoleAwareApprovalsComponent.COMPONENT_ID,
                    "1.0.0", governance.configurationId(), "role-approvals-state-v1",
                    1, 0, List.of(), List.of(RoleAwareApprovalsComponent.QUERY_POLICY,
                    RoleAwareApprovalsComponent.QUERY_PROPOSAL,
                    RoleAwareApprovalsComponent.QUERY_STATS), 0);
            registry = new DomainActorRegistryComponent(registryDescriptor, CHAIN, governance);
            WorkflowDescriptor workflowDescriptor = new WorkflowDescriptor(
                    RoleApprovalWorkflow.WORKFLOW_ID, RoleApprovalWorkflow.PRODUCT_VERSION,
                    RoleApprovalWorkflow.TOPIC, 1, 0,
                    List.of(registryDescriptor.generation(), approvalDescriptor.generation()), 0);
            workflow = new RoleApprovalWorkflow(workflowDescriptor,
                    registryDescriptor.generation(), approvalDescriptor.generation(),
                    CHAIN, governance);
            context = new Context(registryDescriptor.generation(), approvalDescriptor.generation(),
                    registryState, approvalState);
        }

        private void governRegistry(RegistryMutationV1 mutation) {
            String id = "registry-" + sequence;
            GovernedMutationCommandV1.Propose proposed = new GovernedMutationCommandV1.Propose(
                    id, mutation.encode(), height + 50);
            registry.apply(block(height++, message(DomainActorRegistryComponent.TOPIC,
                            proposed.encode(), ADMIN_A),
                    message(DomainActorRegistryComponent.TOPIC,
                            new GovernedMutationCommandV1.Approve(
                                    id, proposed.mutationHash()).encode(), ADMIN_B),
                    message(DomainActorRegistryComponent.TOPIC,
                            new GovernedMutationCommandV1.Activate(
                                    id, proposed.mutationHash()).encode(), ADMIN_A)),
                    registryState, AppEffectEmitter.rejecting("not used"));
        }

        private void governPolicy(ApprovalPolicyV1 policy) {
            governPolicy(new PolicyMutationV1.PutPolicy(policy));
        }

        private void governPolicy(PolicyMutationV1 mutation) {
            String id = "policy-" + sequence;
            GovernedMutationCommandV1.Propose proposed = new GovernedMutationCommandV1.Propose(
                    id, mutation.encode(), height + 50);
            workflow.apply(block(height++, message(RoleApprovalWorkflow.TOPIC,
                            proposed.encode(), ADMIN_A),
                    message(RoleApprovalWorkflow.TOPIC,
                            new GovernedMutationCommandV1.Approve(
                                    id, proposed.mutationHash()).encode(), ADMIN_B),
                    message(RoleApprovalWorkflow.TOPIC,
                            new GovernedMutationCommandV1.Activate(
                                    id, proposed.mutationHash()).encode(), ADMIN_A)), context);
        }

        private void proposePolicyMutation(String mutationId) {
            PolicyMutationV1 mutation = new PolicyMutationV1.CancelProposal(
                    "proposal-" + mutationId);
            GovernedMutationCommandV1.Propose proposed = new GovernedMutationCommandV1.Propose(
                    mutationId, mutation.encode(), height + 50);
            workflow.apply(block(height++, message(RoleApprovalWorkflow.TOPIC,
                    proposed.encode(), ADMIN_A)), context);
        }

        private SignedActorCommandV1 command(ActorIdentity actor, ActorStatementV1 statement) {
            return SignedActorCommandV1.sign(statement, actor.seed);
        }

        private void actorCommand(SignedActorCommandV1 command) {
            actorCommand(command, ADMIN_C);
        }

        private void actorCommand(SignedActorCommandV1 command, byte[] relay) {
            workflow.apply(block(height++, message(
                    RoleApprovalWorkflow.TOPIC, command.encode(), relay)), context);
        }

        private ApprovalProposalV1 proposal(String id) {
            return ApprovalProposalV1.decode(approvalState.get(
                    RoleWorkflowKeys.proposal(id)).orElseThrow());
        }

        private RoleApprovalStatsV1 stats() {
            return RoleApprovalStatsV1.decode(approvalState.get(
                    RoleWorkflowKeys.approvalStats()).orElseThrow());
        }

        private String snapshot() {
            return registryState.snapshot() + ":" + approvalState.snapshot();
        }

        private AppMessage message(String topic, byte[] body, byte[] sender) {
            byte[] id = new byte[32];
            java.nio.ByteBuffer.wrap(id, 24, 8).putLong(sequence++);
            return AppMessage.builder().messageId(id).chainId(CHAIN).topic(topic).sender(sender)
                    .senderSeq(sequence).expiresAt(Long.MAX_VALUE).body(body)
                    .authScheme(0).authProof(new byte[64]).build();
        }
    }

    private record Context(ComponentGeneration registryGeneration,
                           ComponentGeneration approvalGeneration,
                           MemoryState registryState, MemoryState approvalState)
            implements CompositeWorkflowContext {
        @Override public AppStateWriter state(ComponentGeneration participant) {
            if (participant.equals(registryGeneration)) return registryState;
            if (participant.equals(approvalGeneration)) return approvalState;
            throw new IllegalArgumentException("undeclared participant");
        }
        @Override public AppEffectEmitter effects(ComponentGeneration owner) {
            throw new IllegalArgumentException("effects not declared");
        }
        @Override public ClaimResult claim(String operationId, byte[] commandHash) {
            throw new IllegalArgumentException("claims not used");
        }
    }

    private record ActorIdentity(String actorId, String organizationId, String role,
                                 long revision, ActorKeyEpochV1 key, byte[] seed,
                                 List<ActorKeyEpochV1> keys) {
        private ActorIdentity {
            seed = seed.clone();
            keys = List.copyOf(keys);
        }
        @Override public byte[] seed() { return seed.clone(); }
        private ActorRecordV1 record() {
            return new ActorRecordV1(actorId, organizationId, revision, RecordStatus.ACTIVE,
                    List.of(role), keys, new byte[0]);
        }
        private ActorIdentity rotated(long validFrom, byte[] nextSeed) {
            ActorKeyEpochV1 old = new ActorKeyEpochV1(key.keyId(), key.publicKey(),
                    key.validFromHeight(), validFrom - 1, RecordStatus.REVOKED);
            ActorKeyEpochV1 next = new ActorKeyEpochV1("key-2",
                    KeyGenUtil.getPublicKeyFromPrivateKey(nextSeed), validFrom, 0,
                    RecordStatus.ACTIVE);
            return new ActorIdentity(actorId, organizationId, role, revision + 1,
                    next, nextSeed, List.of(old, next));
        }
    }

    private static ActorIdentity identity(String actor, String organization,
                                          String role, int seedValue) {
        byte[] seed = seed(seedValue);
        ActorKeyEpochV1 key = new ActorKeyEpochV1("key-1",
                KeyGenUtil.getPublicKeyFromPrivateKey(seed), 1, 0, RecordStatus.ACTIVE);
        return new ActorIdentity(actor, organization, role, 1, key, seed, List.of(key));
    }

    private static AppBlock block(long height, AppMessage... messages) {
        return new AppBlock(1, CHAIN, height, new byte[32], 0, new byte[0], height,
                new byte[32], new byte[32], List.of(messages), new byte[32], FinalityCert.empty());
    }

    private static byte[] seed(int value) {
        byte[] seed = new byte[32];
        for (int index = 0; index < seed.length; index++) seed[index] = (byte) (value + index);
        return seed;
    }

    private static byte[] filled(int value) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) value);
        return result;
    }

    private static final class MemoryState implements AppStateWriter {
        private final Map<String, byte[]> values = new HashMap<>();
        @Override public Optional<byte[]> get(byte[] key) {
            byte[] value = values.get(HexFormat.of().formatHex(key));
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }
        @Override public byte[] stateRoot() { return new byte[32]; }
        @Override public void put(byte[] key, byte[] value) {
            values.put(HexFormat.of().formatHex(key), value.clone());
        }
        @Override public void delete(byte[] key) { values.remove(HexFormat.of().formatHex(key)); }
        private String snapshot() {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                    digest.update(HexFormat.of().parseHex(entry.getKey()));
                    digest.update(entry.getValue());
                });
                return HexFormat.of().formatHex(digest.digest());
            } catch (Exception impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    private record Scenario(ApprovalProposalV1 approved, RoleApprovalStatsV1 stats,
                            String snapshot) {
    }
}
