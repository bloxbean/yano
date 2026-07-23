package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.client.ProofVerifier;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeCommitmentV1;
import com.bloxbean.cardano.yano.appchain.evidence.profile.contracts.EvidenceReleaseCommandV1;
import com.bloxbean.cardano.yano.appchain.evidence.profile.contracts.EvidenceReleasePrerequisiteCommandsV1;
import com.bloxbean.cardano.yano.appchain.evidence.profile.contracts.RoleEvidenceKeys;
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
import com.bloxbean.cardano.yano.appchain.roles.contracts.SignedActorCommandV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/** Idempotent no-code role bootstrap and evidence authorization scenario. */
final class RoleDemoWorkflow {
    static final String REGISTRY_TOPIC = "actors.command.v1";
    static final String APPROVAL_TOPIC = "role-approvals.command.v1";
    static final String POLICY_ID = "evidence-release";
    static final String PAYLOAD_DOMAIN = "evidence.release.v1";
    private static final String RECOVERY_ORGANIZATION_ID = "recovery-org";
    private static final String RECOVERY_ACTOR_ID = "recovery-probe";
    private static final String RECOVERY_ROTATION_PROPOSAL_ID = "recovery-rotation-v1";
    private static final String RECOVERY_REVOCATION_PROPOSAL_ID = "recovery-revocation-v1";
    private static final String RECOVERY_PAYLOAD_DOMAIN = "role.recovery.v1";
    private static final long COMMAND_LIFETIME_BLOCKS = 1_000;

    private final DemoEnvironment environment;
    private final FinalityWaiter finality;
    private final Actors actors;

    RoleDemoWorkflow(DemoEnvironment environment, FinalityWaiter finality) {
        this.environment = environment;
        this.finality = finality;
        this.actors = Actors.from(environment.config);
    }

    void bootstrap() {
        organization("manufacturer-org");
        organization("audit-org-a");
        organization("audit-org-b");
        organization("regulator-org");
        actor(actors.manufacturer);
        actor(actors.auditorA1);
        actor(actors.auditorA2);
        actor(actors.auditorB);
        actor(actors.regulator);
        if (!exists("components/role-approvals/policy", POLICY_ID)) {
            ApprovalPolicyV1 policy = new ApprovalPolicyV1(POLICY_ID, 1,
                    List.of("manufacturer"),
                    List.of(new ApprovalPolicyV1.RequiredClause(
                                    "auditors", "auditor", 2,
                                    ApprovalPolicyV1.DistinctBy.ORGANIZATION),
                            new ApprovalPolicyV1.RequiredClause(
                                    "regulator", "regulator", 1,
                                    ApprovalPolicyV1.DistinctBy.ACTOR)),
                    ApprovalPolicyV1.RejectionMode.ANY_ELIGIBLE,
                    COMMAND_LIFETIME_BLOCKS);
            govern(APPROVAL_TOPIC, "bootstrap-policy-evidence-release-v1",
                    new PolicyMutationV1.PutPolicy(policy).encode());
        }
        requireBootstrap();
    }

    /**
     * Exercises governed onboarding, key rotation, revocation, rejected stale credentials,
     * and root-matched historical proofs without changing the normal evidence actors.
     */
    LifecycleResult demonstrateActorLifecycle() {
        requireBootstrap();
        organization(RECOVERY_ORGANIZATION_ID);
        byte[] firstSeed = deriveDemoSeed(actors.manufacturer.seed, "recovery-key-v1");
        byte[] secondSeed = deriveDemoSeed(actors.manufacturer.seed, "recovery-key-v2");
        RecoveryActor initial = RecoveryActor.initial(firstSeed);

        ActorRecordV1 current = currentActor(RECOVERY_ACTOR_ID);
        if (current == null) {
            governActor("recovery-actor-v1", initial);
            current = currentActor(RECOVERY_ACTOR_ID);
        }
        if (current.revision() < 1 || current.revision() > 3) {
            throw new DemoException(DemoError.STATE_PROOF_FAILED);
        }

        byte[] rotationHash = recoveryPayload("rotation");
        ApprovalProposalV1 rotation = ensureRecoveryProposal(
                RECOVERY_ROTATION_PROPOSAL_ID, rotationHash);
        if (current.revision() == 1) {
            requireRecoveryRevision(current, 1, RecordStatus.ACTIVE, "recovery-key-v1");
            governActor("recovery-actor-v2", initial.rotated(
                    Math.addExact(environment.yano.getFirst().status().height(), 1),
                    secondSeed));
            current = currentActor(RECOVERY_ACTOR_ID);
        }
        ActorRecordV1 rotationRevision = current.revision() == 2 ? current
                : ActorRecordV1.decode(query("components/domain-actors/actor",
                RECOVERY_ACTOR_ID + "@2"));
        requireRecoveryRevision(rotationRevision, 2, RecordStatus.ACTIVE, "recovery-key-v2");
        RecoveryActor rotated = new RecoveryActor(
                rotationRevision, "recovery-key-v2", secondSeed);

        rotation = proposal(RECOVERY_ROTATION_PROPOSAL_ID);
        if (rotation.status() == ApprovalProposalV1.ProposalStatus.PENDING
                && rotation.decisions().isEmpty()) {
            submit(environment.yano.get(1), SignedActorCommandV1.sign(
                    recoveryStatement(ActorStatementV1.Action.APPROVE, rotation, initial),
                    initial.seed));
            if (!proposal(RECOVERY_ROTATION_PROPOSAL_ID).decisions().isEmpty()) {
                throw new DemoException(DemoError.STATE_PROOF_FAILED);
            }
            submit(environment.yano.get(2), SignedActorCommandV1.sign(
                    recoveryStatement(ActorStatementV1.Action.APPROVE, rotation, rotated),
                    rotated.seed));
            rotation = proposal(RECOVERY_ROTATION_PROPOSAL_ID);
        }
        if (rotation.status() == ApprovalProposalV1.ProposalStatus.PENDING) {
            submit(environment.yano.getFirst(), SignedActorCommandV1.sign(
                    recoveryStatement(ActorStatementV1.Action.CANCEL,
                            rotation, actors.manufacturer), actors.manufacturer.seed));
            rotation = proposal(RECOVERY_ROTATION_PROPOSAL_ID);
        }
        requireRotationResult(rotation);

        byte[] revocationHash = recoveryPayload("revocation");
        ApprovalProposalV1 revocation = ensureRecoveryProposal(
                RECOVERY_REVOCATION_PROPOSAL_ID, revocationHash);
        RecoveryActor revoked;
        if (current.revision() == 2) {
            revoked = rotated.revoked(environment.yano.getFirst().status().height());
            governActor("recovery-actor-v3", revoked);
            current = currentActor(RECOVERY_ACTOR_ID);
        } else {
            current = ActorRecordV1.decode(query("components/domain-actors/actor",
                    RECOVERY_ACTOR_ID));
            revoked = new RecoveryActor(current, "recovery-key-v2", secondSeed);
        }
        requireRecoveryRevision(current, 3, RecordStatus.REVOKED, "recovery-key-v2");

        revocation = proposal(RECOVERY_REVOCATION_PROPOSAL_ID);
        if (revocation.status() == ApprovalProposalV1.ProposalStatus.PENDING) {
            submit(environment.yano.get(1), SignedActorCommandV1.sign(
                    recoveryStatement(ActorStatementV1.Action.APPROVE, revocation, revoked),
                    revoked.seed));
            revocation = proposal(RECOVERY_REVOCATION_PROPOSAL_ID);
            if (!revocation.decisions().isEmpty()) {
                throw new DemoException(DemoError.STATE_PROOF_FAILED);
            }
            submit(environment.yano.getFirst(), SignedActorCommandV1.sign(
                    recoveryStatement(ActorStatementV1.Action.CANCEL,
                            revocation, actors.manufacturer), actors.manufacturer.seed));
            revocation = proposal(RECOVERY_REVOCATION_PROPOSAL_ID);
        }
        requireRevocationResult(revocation);
        verifyRecoveryHistory(rotation, revocation);
        RoleApprovalStatsV1 stats = RoleApprovalStatsV1.decode(verifiedQuery(
                "components/role-approvals/stats", "", "role-approvals",
                RoleWorkflowKeys.approvalStats()));
        if (stats.cancelled() < 2) {
            throw new DemoException(DemoError.STATE_PROOF_FAILED);
        }
        return new LifecycleResult(RECOVERY_ACTOR_ID, 3, stats.created(),
                stats.pending(), stats.cancelled());
    }

    Authorization authorize(EvidenceReleaseCommandV1 release, boolean createRegistry) {
        requireBootstrap();
        YanoAuditClient relay = environment.yano.getFirst();
        YanoAuditClient secondRelay = environment.yano.get(1);
        YanoAuditClient thirdRelay = environment.yano.get(2);
        int submitted = 0;
        if (createRegistry) {
            var registry = relay.appChain().submit(
                    EvidenceReleasePrerequisiteCommandsV1.REGISTRY_TOPIC,
                    EvidenceReleasePrerequisiteCommandsV1.registryPut(
                            release.registryKey(), release.documentHash()));
            finality.await(relay, registry.messageId());
            submitted++;
        }
        byte[] retained = query("components/role-approvals/proposal",
                release.approvalItemId());
        ApprovalProposalV1 proposal = retained.length == 0 ? null
                : ApprovalProposalV1.decode(retained);
        if (proposal == null) {
            long deadline = Math.addExact(relay.status().height(), COMMAND_LIFETIME_BLOCKS);
            byte[] payloadHash = release.commandHash();
            submitted += submit(relay, actors.manufacturer,
                    statement(ActorStatementV1.Action.PROPOSE, release.approvalItemId(),
                            payloadHash, deadline, actors.manufacturer, ""));
            // Negative controls are finalized but cannot enter the accepted trail.
            submitted += submit(relay, actors.manufacturer,
                    statement(ActorStatementV1.Action.APPROVE, release.approvalItemId(),
                            payloadHash, deadline, actors.manufacturer, "auditors"));
            byte[] wrongHash = payloadHash.clone();
            wrongHash[0] ^= 1;
            submitted += submit(secondRelay, actors.auditorA1,
                    statement(ActorStatementV1.Action.APPROVE, release.approvalItemId(),
                            wrongHash, deadline, actors.auditorA1, "auditors"));
            submitted += submit(secondRelay, actors.auditorA1,
                    statement(ActorStatementV1.Action.APPROVE, release.approvalItemId(),
                            payloadHash, deadline, actors.auditorA1, "auditors"));
            submitted += submit(thirdRelay, actors.auditorA2,
                    statement(ActorStatementV1.Action.APPROVE, release.approvalItemId(),
                            payloadHash, deadline, actors.auditorA2, "auditors"));
            submitted += submit(relay, actors.auditorB,
                    statement(ActorStatementV1.Action.APPROVE, release.approvalItemId(),
                            payloadHash, deadline, actors.auditorB, "auditors"));
            submitted += submit(relay, actors.regulator,
                    statement(ActorStatementV1.Action.APPROVE, release.approvalItemId(),
                            payloadHash, deadline, actors.regulator, "regulator"));
            proposal = ApprovalProposalV1.decode(query(
                    "components/role-approvals/proposal", release.approvalItemId()));
        }
        requireApproved(proposal, release);
        return new Authorization(proposal, submitted);
    }

    ApprovalProposalV1 audit(String evidenceId, long businessVersion) {
        byte[] link = verifiedQuery("components/role-approvals/evidence-approval",
                evidenceId + "@" + businessVersion,
                "role-approvals", RoleEvidenceKeys.evidenceApproval(
                        evidenceId, businessVersion));
        if (link.length == 0) throw new DemoException(DemoError.STATE_PROOF_FAILED);
        String proposalId = new String(link, StandardCharsets.US_ASCII);
        byte[] encoded = verifiedQuery("components/role-approvals/proposal", proposalId,
                "role-approvals", RoleWorkflowKeys.proposal(proposalId));
        if (encoded.length == 0) throw new DemoException(DemoError.STATE_PROOF_FAILED);
        ApprovalProposalV1 proposal = ApprovalProposalV1.decode(encoded);
        if (proposal.status() != ApprovalProposalV1.ProposalStatus.APPROVED) {
            throw new DemoException(DemoError.STATE_PROOF_FAILED);
        }
        return proposal;
    }

    ScenarioReport.AuthorizationSummary auditSummary(String evidenceId,
                                                      long businessVersion) {
        ApprovalProposalV1 proposal = audit(evidenceId, businessVersion);
        byte[] encodedPolicy = verifiedQuery("components/role-approvals/policy",
                proposal.policyId() + "@" + proposal.policyRevision(),
                "role-approvals", RoleWorkflowKeys.policyRevision(
                        proposal.policyId(), proposal.policyRevision()));
        if (encodedPolicy.length == 0) {
            throw new DemoException(DemoError.STATE_PROOF_FAILED);
        }
        ApprovalPolicyV1 policy = ApprovalPolicyV1.decode(encodedPolicy);
        if (policy.revision() != proposal.policyRevision()
                || !Arrays.equals(policy.digest(), proposal.policyDigest())) {
            throw new DemoException(DemoError.STATE_PROOF_FAILED);
        }
        proposal.decisions().forEach(decision -> verifyDecision(proposal, decision));
        List<ScenarioReport.DecisionSummary> decisions = proposal.decisions().stream()
                .map(decision -> new ScenarioReport.DecisionSummary(
                        decision.actorId(), decision.organizationId(), decision.role(),
                        decision.clauseId(), decision.keyId(), decision.acceptedHeight()))
                .toList();
        List<ScenarioReport.ClauseSummary> clauses = policy.clauses().stream()
                .map(clause -> new ScenarioReport.ClauseSummary(
                        clause.clauseId(), clause.role(), clause.minimumCount(),
                        clause.distinctBy().name(),
                        (int) proposal.decisions().stream()
                                .filter(decision -> clause.clauseId()
                                        .equals(decision.clauseId()))
                                .count()))
                .toList();
        return new ScenarioReport.AuthorizationSummary(
                proposal.proposalId(), proposal.policyId(), proposal.policyRevision(),
                proposal.status().name(), proposal.payloadDomain(),
                HexFormat.of().formatHex(proposal.payloadHash()), proposal.deadlineHeight(),
                environment.yano.getFirst().status().memberKey(),
                proposal.proposerActorId(), proposal.proposerOrganizationId(),
                proposal.proposerRole(), clauses, decisions);
    }

    private void organization(String id) {
        if (exists("components/domain-actors/organization", id)) return;
        govern(REGISTRY_TOPIC, "bootstrap-org-" + id + "-v1",
                new RegistryMutationV1.PutOrganization(new OrganizationRecordV1(
                        id, 1, RecordStatus.ACTIVE, new byte[0])).encode());
    }

    private void actor(ActorIdentity identity) {
        if (exists("components/domain-actors/actor", identity.actorId)) return;
        ActorKeyEpochV1 key = identity.key();
        ActorRecordV1 actor = new ActorRecordV1(identity.actorId,
                identity.organizationId, 1, RecordStatus.ACTIVE,
                List.of(identity.role), List.of(key), new byte[0]);
        ActorKeyProofV1 proof = ActorKeyProofV1.sign(environment.config.chainId(),
                identity.actorId, 1, key, identity.seed);
        govern(REGISTRY_TOPIC, "bootstrap-actor-" + identity.actorId + "-v1",
                new RegistryMutationV1.PutActor(actor, List.of(proof)).encode());
    }

    private void governActor(String mutationId, RecoveryActor identity) {
        ActorRecordV1 current = currentActor(identity.actor.actorId());
        if (current != null && current.revision() >= identity.actor.revision()) return;
        ActorKeyEpochV1 newest = identity.actor.key(identity.keyId);
        List<ActorKeyProofV1> proofs = current == null || current.key(identity.keyId) == null
                ? List.of(ActorKeyProofV1.sign(environment.config.chainId(),
                identity.actor.actorId(), identity.actor.revision(), newest, identity.seed))
                : List.of();
        govern(REGISTRY_TOPIC, mutationId,
                new RegistryMutationV1.PutActor(identity.actor, proofs).encode());
    }

    private void govern(String topic, String mutationId, byte[] mutation) {
        YanoAuditClient first = environment.yano.get(0);
        YanoAuditClient second = environment.yano.get(1);
        long expiry = Math.addExact(first.status().height(), COMMAND_LIFETIME_BLOCKS);
        GovernedMutationCommandV1.Propose proposed =
                new GovernedMutationCommandV1.Propose(mutationId, mutation, expiry);
        var proposal = first.appChain().submit(topic, proposed.encode());
        finality.await(first, proposal.messageId());
        var approval = second.appChain().submit(topic,
                new GovernedMutationCommandV1.Approve(
                        mutationId, proposed.mutationHash()).encode());
        finality.await(second, approval.messageId());
        var activation = first.appChain().submit(topic,
                new GovernedMutationCommandV1.Activate(
                        mutationId, proposed.mutationHash()).encode());
        finality.await(first, activation.messageId());
    }

    private int submit(YanoAuditClient relay, ActorIdentity identity,
                       ActorStatementV1 statement) {
        return submit(relay, SignedActorCommandV1.sign(statement, identity.seed));
    }

    private int submit(YanoAuditClient relay, SignedActorCommandV1 command) {
        var result = relay.appChain().submit(APPROVAL_TOPIC, command.encode());
        finality.await(relay, result.messageId());
        return 1;
    }

    private ActorStatementV1 statement(ActorStatementV1.Action action,
                                       String proposalId, byte[] payloadHash,
                                       long deadline, ActorIdentity actor, String clause) {
        return new ActorStatementV1(action, environment.config.chainId(), proposalId,
                POLICY_ID, 1, PAYLOAD_DOMAIN, payloadHash, deadline,
                actor.actorId, 1, actor.keyId, clause);
    }

    private ActorStatementV1 recoveryStatement(ActorStatementV1.Action action,
                                               ApprovalProposalV1 proposal,
                                               RecoveryActor actor) {
        return new ActorStatementV1(action, environment.config.chainId(),
                proposal.proposalId(), proposal.policyId(), proposal.policyRevision(),
                proposal.payloadDomain(), proposal.payloadHash(), proposal.deadlineHeight(),
                actor.actor.actorId(), actor.actor.revision(), actor.keyId,
                action.requiresClause() ? "auditors" : "");
    }

    private ActorStatementV1 recoveryStatement(ActorStatementV1.Action action,
                                               ApprovalProposalV1 proposal,
                                               ActorIdentity actor) {
        return new ActorStatementV1(action, environment.config.chainId(),
                proposal.proposalId(), proposal.policyId(), proposal.policyRevision(),
                proposal.payloadDomain(), proposal.payloadHash(), proposal.deadlineHeight(),
                actor.actorId, 1, actor.keyId, "");
    }

    private ApprovalProposalV1 ensureRecoveryProposal(String proposalId, byte[] payloadHash) {
        ApprovalProposalV1 retained = proposal(proposalId);
        if (retained != null) {
            if (!RECOVERY_PAYLOAD_DOMAIN.equals(retained.payloadDomain())
                    || !Arrays.equals(payloadHash, retained.payloadHash())) {
                throw new DemoException(DemoError.STATE_PROOF_FAILED);
            }
            return retained;
        }
        long deadline = Math.addExact(environment.yano.getFirst().status().height(),
                COMMAND_LIFETIME_BLOCKS);
        ActorStatementV1 statement = new ActorStatementV1(
                ActorStatementV1.Action.PROPOSE, environment.config.chainId(), proposalId,
                POLICY_ID, 1, RECOVERY_PAYLOAD_DOMAIN, payloadHash, deadline,
                actors.manufacturer.actorId, 1, actors.manufacturer.keyId, "");
        submit(environment.yano.getFirst(), SignedActorCommandV1.sign(
                statement, actors.manufacturer.seed));
        retained = proposal(proposalId);
        if (retained == null) throw new DemoException(DemoError.STATE_PROOF_FAILED);
        return retained;
    }

    private ApprovalProposalV1 proposal(String proposalId) {
        byte[] encoded = query("components/role-approvals/proposal", proposalId);
        return encoded.length == 0 ? null : ApprovalProposalV1.decode(encoded);
    }

    private ActorRecordV1 currentActor(String actorId) {
        byte[] encoded = query("components/domain-actors/actor", actorId);
        return encoded.length == 0 ? null : ActorRecordV1.decode(encoded);
    }

    private void verifyRecoveryHistory(ApprovalProposalV1 rotation,
                                       ApprovalProposalV1 revocation) {
        ActorRecordV1 first = verifiedActorRevision(1);
        ActorRecordV1 second = verifiedActorRevision(2);
        ActorRecordV1 third = verifiedActorRevision(3);
        requireRecoveryRevision(first, 1, RecordStatus.ACTIVE, "recovery-key-v1");
        requireRecoveryRevision(second, 2, RecordStatus.ACTIVE, "recovery-key-v2");
        requireRecoveryRevision(third, 3, RecordStatus.REVOKED, "recovery-key-v2");
        OrganizationRecordV1 organization = OrganizationRecordV1.decode(verifiedQuery(
                "components/domain-actors/organization", RECOVERY_ORGANIZATION_ID + "@1",
                "domain-actors", RoleWorkflowKeys.organizationRevision(
                        RECOVERY_ORGANIZATION_ID, 1)));
        if (organization.status() != RecordStatus.ACTIVE) {
            throw new DemoException(DemoError.STATE_PROOF_FAILED);
        }
        ApprovalProposalV1 provenRotation = verifiedProposal(rotation.proposalId());
        ApprovalProposalV1 provenRevocation = verifiedProposal(revocation.proposalId());
        requireRotationResult(provenRotation);
        requireRevocationResult(provenRevocation);
        verifyDecision(provenRotation, provenRotation.decisions().getFirst());
    }

    private ActorRecordV1 verifiedActorRevision(long revision) {
        return ActorRecordV1.decode(verifiedQuery("components/domain-actors/actor",
                RECOVERY_ACTOR_ID + "@" + revision, "domain-actors",
                RoleWorkflowKeys.actorRevision(RECOVERY_ACTOR_ID, revision)));
    }

    private ApprovalProposalV1 verifiedProposal(String proposalId) {
        return ApprovalProposalV1.decode(verifiedQuery(
                "components/role-approvals/proposal", proposalId, "role-approvals",
                RoleWorkflowKeys.proposal(proposalId)));
    }

    private static void requireRecoveryRevision(ActorRecordV1 actor, long revision,
                                                RecordStatus status, String currentKeyId) {
        ActorKeyEpochV1 key = actor == null ? null : actor.key(currentKeyId);
        if (actor == null || !RECOVERY_ACTOR_ID.equals(actor.actorId())
                || !RECOVERY_ORGANIZATION_ID.equals(actor.organizationId())
                || actor.revision() != revision || actor.status() != status
                || !actor.roles().equals(List.of("auditor")) || key == null
                || (status == RecordStatus.ACTIVE && key.status() != RecordStatus.ACTIVE)
                || (status == RecordStatus.REVOKED && key.status() != RecordStatus.REVOKED)) {
            throw new DemoException(DemoError.STATE_PROOF_FAILED);
        }
    }

    private static void requireRotationResult(ApprovalProposalV1 proposal) {
        if (proposal == null
                || proposal.status() != ApprovalProposalV1.ProposalStatus.CANCELLED
                || proposal.decisions().size() != 1
                || !RECOVERY_ACTOR_ID.equals(proposal.decisions().getFirst().actorId())
                || proposal.decisions().getFirst().actorRevision() != 2
                || !"recovery-key-v2".equals(proposal.decisions().getFirst().keyId())) {
            throw new DemoException(DemoError.STATE_PROOF_FAILED);
        }
    }

    private static void requireRevocationResult(ApprovalProposalV1 proposal) {
        if (proposal == null
                || proposal.status() != ApprovalProposalV1.ProposalStatus.CANCELLED
                || !proposal.decisions().isEmpty()) {
            throw new DemoException(DemoError.STATE_PROOF_FAILED);
        }
    }

    private static byte[] recoveryPayload(String operation) {
        return Digests.sha256(("yano:demo:role-recovery:" + operation + ":v1")
                .getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] deriveDemoSeed(byte[] master, String purpose) {
        byte[] domain = ("yano:demo:role-recovery-seed:v1:" + purpose + "\0")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] input = Arrays.copyOf(domain, domain.length + master.length);
        System.arraycopy(master, 0, input, domain.length, master.length);
        return Digests.sha256(input);
    }

    private byte[] query(String path, String params) {
        AppChainClient.QueryResult result = environment.yano.getFirst().appChain().query(
                path, params.getBytes(StandardCharsets.US_ASCII));
        if (!environment.config.chainId().equals(result.chainId())
                || !environment.config.stateMachine().equals(result.stateMachineId())) {
            throw new DemoException(DemoError.STATE_PROOF_FAILED);
        }
        return result.payload();
    }

    private boolean exists(String path, String params) {
        // Before the first app block there is no committed query snapshot yet.
        // The first governed mutation also initializes the composite profile.
        if (environment.yano.getFirst().status().height() == 0) return false;
        return query(path, params).length != 0;
    }

    private byte[] verifiedQuery(String path, String params,
                                 String componentId, byte[] localKey) {
        byte[] physicalKey = CompositeCommitmentV1.componentKey(componentId, localKey);
        for (int attempt = 0; attempt < 3; attempt++) {
            AppChainClient.QueryResult query = environment.yano.getFirst().appChain().query(
                    path, params.getBytes(StandardCharsets.US_ASCII));
            AppChainClient.Proof proof = environment.yano.getFirst().appChain()
                    .proof(physicalKey).orElse(null);
            if (proof == null || query.payload().length == 0) break;
            String root = HexFormat.of().formatHex(query.stateRoot());
            if (environment.config.chainId().equals(query.chainId())
                    && environment.config.stateMachine().equals(query.stateMachineId())
                    && environment.config.chainId().equals(proof.chainId())
                    && root.equals(proof.stateRootHex())
                    && proof.committedHeight() != null
                    && proof.committedHeight() == query.committedHeight()
                    && HexFormat.of().formatHex(physicalKey).equals(proof.keyHex())
                    && HexFormat.of().formatHex(query.payload()).equals(proof.valueHex())
                    && ProofVerifier.verify(proof, root)) {
                return query.payload();
            }
        }
        throw new DemoException(DemoError.STATE_PROOF_FAILED);
    }

    private void verifyDecision(ApprovalProposalV1 proposal,
                                ApprovalProposalV1.AcceptedDecisionV1 decision) {
        ActorRecordV1 actor = ActorRecordV1.decode(verifiedQuery(
                "components/domain-actors/actor",
                decision.actorId() + "@" + decision.actorRevision(),
                "domain-actors", RoleWorkflowKeys.actorRevision(
                        decision.actorId(), decision.actorRevision())));
        OrganizationRecordV1 organization = OrganizationRecordV1.decode(verifiedQuery(
                "components/domain-actors/organization",
                decision.organizationId() + "@" + decision.organizationRevision(),
                "domain-actors", RoleWorkflowKeys.organizationRevision(
                        decision.organizationId(), decision.organizationRevision())));
        ActorKeyEpochV1 key = actor.key(decision.keyId());
        ActorStatementV1 statement = new ActorStatementV1(decision.action(),
                environment.config.chainId(), proposal.proposalId(), proposal.policyId(),
                proposal.policyRevision(), proposal.payloadDomain(), proposal.payloadHash(),
                proposal.deadlineHeight(), decision.actorId(), decision.actorRevision(),
                decision.keyId(), decision.clauseId());
        if (!actor.organizationId().equals(decision.organizationId())
                || actor.status() != RecordStatus.ACTIVE
                || organization.status() != RecordStatus.ACTIVE
                || !actor.roles().contains(decision.role())
                || key == null || !key.activeAt(decision.acceptedHeight())
                || !Arrays.equals(statement.digest(), decision.statementDigest())
                || !new SignedActorCommandV1(statement, decision.signature())
                .verify(key.publicKey())) {
            throw new DemoException(DemoError.STATE_PROOF_FAILED);
        }
    }

    private void requireBootstrap() {
        if (query("components/domain-actors/organization", "manufacturer-org").length == 0
                || query("components/domain-actors/actor", "manufacturer-a").length == 0
                || query("components/domain-actors/actor", "auditor-a1").length == 0
                || query("components/domain-actors/actor", "auditor-a2").length == 0
                || query("components/domain-actors/actor", "auditor-b").length == 0
                || query("components/domain-actors/actor", "regulator-a").length == 0
                || query("components/role-approvals/policy", POLICY_ID).length == 0) {
            throw new DemoException(DemoError.INITIALIZATION_FAILED);
        }
    }

    private static void requireApproved(ApprovalProposalV1 proposal,
                                        EvidenceReleaseCommandV1 release) {
        if (proposal.status() != ApprovalProposalV1.ProposalStatus.APPROVED
                || !POLICY_ID.equals(proposal.policyId())
                || !PAYLOAD_DOMAIN.equals(proposal.payloadDomain())
                || !Arrays.equals(proposal.payloadHash(), release.commandHash())
                || proposal.decisions().size() != 3
                || !proposal.decisions().stream().map(
                        ApprovalProposalV1.AcceptedDecisionV1::actorId).toList()
                .equals(List.of("auditor-a1", "auditor-b", "regulator-a"))) {
            throw new DemoException(DemoError.STATE_PROOF_FAILED);
        }
    }

    record Authorization(ApprovalProposalV1 proposal, int submittedEnvelopes) {
    }

    record LifecycleResult(String actorId, long revision, long proposalsCreated,
                           long proposalsPending, long proposalsCancelled) {
    }

    @FunctionalInterface
    interface FinalityWaiter {
        void await(YanoAuditClient node, String messageId);
    }

    private record ActorIdentity(String actorId, String organizationId,
                                 String role, String keyId, byte[] seed) {
        private ActorIdentity {
            seed = seed.clone();
        }
        @Override public byte[] seed() { return seed.clone(); }
        ActorKeyEpochV1 key() {
            return new ActorKeyEpochV1(keyId,
                    KeyGenUtil.getPublicKeyFromPrivateKey(seed),
                    1, 0, RecordStatus.ACTIVE);
        }
    }

    private record RecoveryActor(ActorRecordV1 actor, String keyId, byte[] seed) {
        private RecoveryActor {
            seed = seed.clone();
        }
        @Override public byte[] seed() { return seed.clone(); }

        static RecoveryActor initial(byte[] seed) {
            ActorKeyEpochV1 key = new ActorKeyEpochV1("recovery-key-v1",
                    KeyGenUtil.getPublicKeyFromPrivateKey(seed), 1, 0, RecordStatus.ACTIVE);
            return new RecoveryActor(new ActorRecordV1(RECOVERY_ACTOR_ID,
                    RECOVERY_ORGANIZATION_ID, 1, RecordStatus.ACTIVE,
                    List.of("auditor"), List.of(key), new byte[0]), key.keyId(), seed);
        }

        RecoveryActor rotated(long validFromHeight, byte[] nextSeed) {
            ActorKeyEpochV1 old = actor.key(keyId);
            ActorKeyEpochV1 retired = new ActorKeyEpochV1(old.keyId(), old.publicKey(),
                    old.validFromHeight(), validFromHeight - 1, RecordStatus.REVOKED);
            ActorKeyEpochV1 next = new ActorKeyEpochV1("recovery-key-v2",
                    KeyGenUtil.getPublicKeyFromPrivateKey(nextSeed),
                    validFromHeight, 0, RecordStatus.ACTIVE);
            return new RecoveryActor(new ActorRecordV1(actor.actorId(), actor.organizationId(),
                    2, RecordStatus.ACTIVE, actor.roles(), List.of(retired, next),
                    new byte[0]), next.keyId(), nextSeed);
        }

        RecoveryActor revoked(long validUntilHeight) {
            ActorKeyEpochV1 current = actor.key(keyId);
            ActorKeyEpochV1 retired = new ActorKeyEpochV1(current.keyId(), current.publicKey(),
                    current.validFromHeight(), Math.max(current.validFromHeight(),
                    validUntilHeight), RecordStatus.REVOKED);
            List<ActorKeyEpochV1> keys = actor.keys().stream()
                    .map(key -> key.keyId().equals(keyId) ? retired : key).toList();
            return new RecoveryActor(new ActorRecordV1(actor.actorId(), actor.organizationId(),
                    3, RecordStatus.REVOKED, actor.roles(), keys, new byte[0]),
                    keyId, seed);
        }
    }

    private record Actors(ActorIdentity manufacturer, ActorIdentity auditorA1,
                          ActorIdentity auditorA2, ActorIdentity auditorB,
                          ActorIdentity regulator) {
        static Actors from(DemoConfig config) {
            DemoConfig.RoleSettings values = config.roles();
            if (!config.roleAware() || values == null) {
                throw new DemoException(DemoError.INVALID_CONFIG);
            }
            return new Actors(
                    identity("manufacturer-a", "manufacturer-org", "manufacturer",
                            "manufacturer-key-v1", values.manufacturer()),
                    identity("auditor-a1", "audit-org-a", "auditor",
                            "auditor-a1-key-v1", values.auditorA1()),
                    identity("auditor-a2", "audit-org-a", "auditor",
                            "auditor-a2-key-v1", values.auditorA2()),
                    identity("auditor-b", "audit-org-b", "auditor",
                            "auditor-b-key-v1", values.auditorB()),
                    identity("regulator-a", "regulator-org", "regulator",
                            "regulator-key-v1", values.regulator()));
        }

        private static ActorIdentity identity(String actorId, String organizationId,
                                              String role, String keyId,
                                              SecretValue secret) {
            byte[] seed;
            try { seed = java.util.HexFormat.of().parseHex(secret.reveal()); }
            catch (RuntimeException invalid) { throw new DemoException(DemoError.INVALID_CONFIG); }
            if (seed.length != 32) throw new DemoException(DemoError.INVALID_CONFIG);
            return new ActorIdentity(actorId, organizationId, role, keyId, seed);
        }
    }
}
