package com.bloxbean.cardano.yano.appchain.client;

import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeCommitmentV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.AdministratorAuthorityV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorStatementV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalPolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalProposalV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.DirectRolePolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedMutationRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.OrganizationRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RecordStatus;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleCommandResultV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowIdentifiers;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowResultCode;
import com.bloxbean.cardano.yano.appchain.roles.contracts.SignedActorCommandV1;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bounded same-root proof assembly for basic, direct-role, approval, and governance facts. */
public final class AuthenticatedMapProofBundle {
    public static final String ENTRY = "entry";
    public static final String RECEIPT = "receipt";
    public static final String DIRECT_CONSUMPTION = "direct-consumption";
    public static final String APPROVAL_CONSUMPTION = "approval-consumption";
    public static final String DIRECT_POLICY = "direct-policy";
    public static final String DIRECT_POLICY_CURRENT = "direct-policy-current";
    public static final String APPROVAL_POLICY = "approval-policy";
    public static final String APPROVAL_POLICY_CURRENT = "approval-policy-current";
    public static final String ACTOR = "actor";
    public static final String ACTOR_CURRENT = "actor-current";
    public static final String ORGANIZATION = "organization";
    public static final String PROPOSAL = "proposal";
    public static final String GOVERNANCE_RESULT = "governance-result";
    public static final String GOVERNANCE_MUTATION = "governance-mutation";
    public static final String AUTHORITY = "authority";
    public static final String AUTHORITY_CURRENT = "authority-current";

    private static final int MAX_FACTS = 32;
    private static final Map<Kind, Set<String>> REQUIRED = Map.of(
            Kind.BASIC, Set.of(ENTRY, RECEIPT),
            Kind.DIRECT_ROLE, Set.of(ENTRY, RECEIPT, DIRECT_CONSUMPTION,
                    DIRECT_POLICY, DIRECT_POLICY_CURRENT, ACTOR, ACTOR_CURRENT,
                    ORGANIZATION),
            Kind.APPROVAL, Set.of(ENTRY, RECEIPT, APPROVAL_CONSUMPTION,
                    PROPOSAL, APPROVAL_POLICY),
            Kind.ADMINISTRATOR_GOVERNANCE, Set.of(GOVERNANCE_RESULT,
                    GOVERNANCE_MUTATION, AUTHORITY, AUTHORITY_CURRENT, ACTOR,
                    ACTOR_CURRENT, ORGANIZATION));

    private final Kind kind;
    private final String chainId;
    private final String profile;
    private final String genesisIdHex;
    private final long height;
    private final String stateRootHex;
    private final byte[] actionCommitment;
    private final byte[] authorizationEvidence;
    private final Map<String, Fact> facts;

    public AuthenticatedMapProofBundle(
            Kind kind,
            String chainId,
            String profile,
            String genesisIdHex,
            long height,
            String stateRootHex,
            byte[] actionCommitment,
            byte[] authorizationEvidence,
            List<Fact> facts
    ) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.chainId = nonBlank(chainId, "chainId");
        this.profile = nonBlank(profile, "profile");
        this.genesisIdHex = canonicalHex(genesisIdHex, 32, "genesisIdHex");
        if (height < 1) throw new IllegalArgumentException("height must be positive");
        this.height = height;
        this.stateRootHex = canonicalHex(stateRootHex, 32, "stateRootHex");
        this.actionCommitment = actionCommitment == null ? new byte[0]
                : exact(actionCommitment, 32, "actionCommitment");
        this.authorizationEvidence = authorizationEvidence == null ? new byte[0]
                : authorizationEvidence.clone();
        if (facts == null || facts.size() > MAX_FACTS) {
            throw new IllegalArgumentException("proof bundle fact count is invalid");
        }
        Map<String, Fact> indexed = new LinkedHashMap<>();
        for (Fact fact : facts) {
            Objects.requireNonNull(fact, "fact");
            if (indexed.putIfAbsent(fact.name(), fact) != null) {
                throw new IllegalArgumentException("proof bundle fact names must be unique");
            }
        }
        if (!indexed.keySet().containsAll(REQUIRED.get(kind))) {
            throw new IllegalArgumentException("proof bundle is missing required " + kind
                    + " facts");
        }
        if (kind != Kind.ADMINISTRATOR_GOVERNANCE && this.actionCommitment.length != 32) {
            throw new IllegalArgumentException("map proof bundle requires an action commitment");
        }
        if ((kind == Kind.DIRECT_ROLE) != (this.authorizationEvidence.length > 0)) {
            throw new IllegalArgumentException(
                    "direct-role proof bundle requires exactly one actor authorization");
        }
        this.facts = Map.copyOf(indexed);
    }

    public boolean verify(ProofVerifier.TrustedStateRoot trustedRoot) {
        if (!identityMatches(trustedRoot)) return false;
        try {
            for (Fact fact : facts.values()) {
                if (!fact.verify(trustedRoot)) return false;
            }
            return verifySemanticBinding();
        } catch (RuntimeException | StackOverflowError malformed) {
            return false;
        }
    }

    /** Verify every native proof and its shared finality certificate under pinned membership. */
    public boolean verifyCertified(ProofVerifier.FinalityTrustContext trustContext) {
        if (trustContext == null || !chainId.equals(trustContext.chainId())
                || !profile.equals(trustContext.profile())
                || !genesisIdHex.equals(trustContext.genesisIdHex())) return false;
        try {
            for (Fact fact : facts.values()) {
                AppChainClient.Proof proof = fact.proof();
                if (proof.committedHeight() == null || proof.committedHeight() != height
                        || !stateRootHex.equals(proof.stateRootHex())
                        || !fact.matchesExpectedRecord()
                        || !ProofVerifier.verifyCertified(proof, trustContext)) return false;
            }
            return verifySemanticBinding();
        } catch (RuntimeException | StackOverflowError malformed) {
            return false;
        }
    }

    private boolean verifySemanticBinding() {
        AuthenticatedMapContract.Receipt receipt = null;
        if (kind != Kind.ADMINISTRATOR_GOVERNANCE) {
            receipt = AuthenticatedMapContract.decodeReceipt(
                    facts.get(RECEIPT).expectedValue());
            if (receipt.status() != AuthenticatedMapContract.RECEIPT_APPLIED
                    || receipt.height() > height
                    || !MessageDigest.isEqual(receipt.batchCommitment(), actionCommitment)
                    || !factKey(RECEIPT, AuthenticatedMapContract.STATE_MACHINE_ID,
                    AuthenticatedMapContract.receiptKey(receipt.messageId()))
                    || !receiptBindsEntry(receipt)) return false;
        }
        if (kind == Kind.DIRECT_ROLE) {
            var authorization = AuthenticatedMapAuthorizationContract.MapActorAuthorizationV1
                    .decode(authorizationEvidence);
            var consumption = AuthenticatedMapAuthorizationContract.DirectConsumptionV1
                    .decode(facts.get(DIRECT_CONSUMPTION).expectedValue());
            DirectRolePolicyV1 policy = DirectRolePolicyV1.decode(
                    facts.get(DIRECT_POLICY).expectedValue());
            ActorRecordV1 actor = ActorRecordV1.decode(facts.get(ACTOR).expectedValue());
            OrganizationRecordV1 organization = OrganizationRecordV1.decode(
                    facts.get(ORGANIZATION).expectedValue());
            var key = actor.key(authorization.keyId());
            return authorization.verifyClaimedKey()
                    && authorization.chainId().equals(chainId)
                    && MessageDigest.isEqual(
                    authorization.genesisId(), Hex.decode(genesisIdHex))
                    && MessageDigest.isEqual(
                    authorization.actionCommitment(), actionCommitment)
                    && MessageDigest.isEqual(consumption.actionCommitment(), actionCommitment)
                    && MessageDigest.isEqual(receipt.messageId(), consumption.messageId())
                    && receipt.height() == consumption.appliedHeight()
                    && authorization.actorId().equals(consumption.actorId())
                    && MessageDigest.isEqual(
                    authorization.authorizationId(), consumption.authorizationId())
                    && authorization.coveredMutationIndexes().equals(
                    consumption.mutationIndexes())
                    && authorization.policyId().equals(consumption.policyId())
                    && authorization.policyRevision() == consumption.policyRevision()
                    && authorization.actorRevision() == consumption.actorRevision()
                    && authorization.keyId().equals(consumption.keyId())
                    && authorization.issuedHeight() <= consumption.appliedHeight()
                    && authorization.deadlineHeight() > consumption.appliedHeight()
                    && authorization.deadlineHeight() - authorization.issuedHeight()
                    <= policy.maximumAuthorizationLifetimeBlocks()
                    && MessageDigest.isEqual(
                    authorization.statementDigest(), consumption.statementDigest())
                    && MessageDigest.isEqual(
                    sha256(authorization.signature()), consumption.signatureDigest())
                    && policy.policyId().equals(consumption.policyId())
                    && policy.revision() == consumption.policyRevision()
                    && policy.status() == RecordStatus.ACTIVE
                    && policy.requiredRole().equals(consumption.role())
                    && pointerRevision(facts.get(DIRECT_POLICY_CURRENT)) == policy.revision()
                    && factKey(DIRECT_CONSUMPTION,
                    AuthenticatedMapContract.STATE_MACHINE_ID,
                    AuthenticatedMapContract.directConsumptionKey(
                            consumption.actorId(), consumption.authorizationId()))
                    && factKey(DIRECT_POLICY,
                    RoleWorkflowIdentifiers.ROLE_APPROVALS_COMPONENT_ID,
                    RoleWorkflowKeys.directPolicyRevision(
                            policy.policyId(), policy.revision()))
                    && factKey(DIRECT_POLICY_CURRENT,
                    RoleWorkflowIdentifiers.ROLE_APPROVALS_COMPONENT_ID,
                    RoleWorkflowKeys.directPolicyCurrent(policy.policyId()))
                    && actor.actorId().equals(consumption.actorId())
                    && actor.revision() == consumption.actorRevision()
                    && actor.status() == RecordStatus.ACTIVE
                    && actor.roles().contains(consumption.role())
                    && pointerRevision(facts.get(ACTOR_CURRENT)) == actor.revision()
                    && factKey(ACTOR, RoleWorkflowIdentifiers.DOMAIN_ACTORS_COMPONENT_ID,
                    RoleWorkflowKeys.actorRevision(actor.actorId(), actor.revision()))
                    && factKey(ACTOR_CURRENT,
                    RoleWorkflowIdentifiers.DOMAIN_ACTORS_COMPONENT_ID,
                    RoleWorkflowKeys.actorCurrent(actor.actorId()))
                    && organization.organizationId().equals(consumption.organizationId())
                    && organization.revision() == consumption.organizationRevision()
                    && organization.status() == RecordStatus.ACTIVE
                    && actor.organizationId().equals(organization.organizationId())
                    && factKey(ORGANIZATION,
                    RoleWorkflowIdentifiers.DOMAIN_ACTORS_COMPONENT_ID,
                    RoleWorkflowKeys.organizationRevision(
                            organization.organizationId(), organization.revision()))
                    && key != null && key.activeAt(consumption.appliedHeight())
                    && MessageDigest.isEqual(key.publicKey(), authorization.publicKey());
        }
        if (kind == Kind.APPROVAL) {
            ApprovalProposalV1 proposal = ApprovalProposalV1.decode(
                    facts.get(PROPOSAL).expectedValue());
            ApprovalPolicyV1 policy = ApprovalPolicyV1.decode(
                    facts.get(APPROVAL_POLICY).expectedValue());
            var consumption = AuthenticatedMapAuthorizationContract.ApprovalConsumptionV1
                    .decode(facts.get(APPROVAL_CONSUMPTION).expectedValue());
            byte[] expectedPayload = AuthenticatedMapAuthorizationContract.approvalPayloadHash(
                    Hex.decode(genesisIdHex), actionCommitment);
            Fact proposerFact = revisionFact(
                    ACTOR, proposal.proposerActorId(), proposal.proposerActorRevision());
            ActorRecordV1 proposer = proposerFact == null ? null
                    : ActorRecordV1.decode(proposerFact.expectedValue());
            var proposerKey = proposer == null ? null : proposer.key(proposal.proposerKeyId());
            return proposal.status() == ApprovalProposalV1.ProposalStatus.APPROVED
                    && AuthenticatedMapAuthorizationContract.APPROVAL_PAYLOAD_DOMAIN.equals(
                    proposal.payloadDomain())
                    && MessageDigest.isEqual(proposal.payloadHash(), expectedPayload)
                    && proposal.proposalId().equals(consumption.proposalId())
                    && MessageDigest.isEqual(consumption.actionCommitment(), actionCommitment)
                    && MessageDigest.isEqual(receipt.messageId(), consumption.messageId())
                    && receipt.height() == consumption.appliedHeight()
                    && consumption.appliedHeight() <= proposal.deadlineHeight()
                    && proposal.policyId().equals(consumption.policyId())
                    && proposal.policyRevision() == consumption.policyRevision()
                    && policy.policyId().equals(proposal.policyId())
                    && policy.revision() == proposal.policyRevision()
                    && policy.status() == RecordStatus.ACTIVE
                    && MessageDigest.isEqual(policy.digest(), proposal.policyDigest())
                    && factKey(APPROVAL_CONSUMPTION,
                    AuthenticatedMapContract.STATE_MACHINE_ID,
                    AuthenticatedMapContract.approvalConsumptionKey(
                            consumption.proposalId()))
                    && factKey(PROPOSAL,
                    RoleWorkflowIdentifiers.ROLE_APPROVALS_COMPONENT_ID,
                    RoleWorkflowKeys.proposal(proposal.proposalId()))
                    && factKey(APPROVAL_POLICY,
                    RoleWorkflowIdentifiers.ROLE_APPROVALS_COMPONENT_ID,
                    RoleWorkflowKeys.policyRevision(
                            policy.policyId(), policy.revision()))
                    && retainedActor(proposal.proposerActorId(),
                    proposal.proposerActorRevision(), proposal.proposerOrganizationId(),
                    proposal.proposerOrganizationRevision(), proposal.proposerRole(),
                    proposal.proposerKeyId())
                    && proposerKey != null && proposerKey.activeAt(proposal.createdHeight())
                    && (policy.proposerRoles().isEmpty()
                    || policy.proposerRoles().contains(proposal.proposerRole()))
                    && proposal.decisions().stream()
                    .allMatch(decision -> retainedDecision(proposal, policy, decision))
                    && approvalSatisfied(policy, proposal.decisions());
        }
        if (kind == Kind.ADMINISTRATOR_GOVERNANCE) return verifyGovernance();
        return true;
    }

    private boolean receiptBindsEntry(AuthenticatedMapContract.Receipt receipt) {
        AuthenticatedMapContract.Entry entry = AuthenticatedMapContract.decodeEntry(
                facts.get(ENTRY).expectedValue());
        for (AuthenticatedMapContract.MutationResult result : receipt.results()) {
            if (factKey(ENTRY, AuthenticatedMapContract.STATE_MACHINE_ID,
                    AuthenticatedMapContract.canonicalKey(
                            result.collectionId(), result.applicationKey()))
                    && result.status() == entry.status()
                    && result.revision() == entry.revision()
                    && MessageDigest.isEqual(
                    result.logicalValueHash(), entry.logicalValueHash())) return true;
        }
        return false;
    }

    private boolean retainedDecision(
            ApprovalProposalV1 proposal,
            ApprovalPolicyV1 policy,
            ApprovalProposalV1.AcceptedDecisionV1 decision
    ) {
        ApprovalPolicyV1.RequiredClause clause = policy.clause(decision.clauseId());
        if (clause == null || decision.action() != ActorStatementV1.Action.APPROVE
                || !clause.role().equals(decision.role())) return false;
        ActorStatementV1 statement = new ActorStatementV1(
                decision.action(), chainId, proposal.proposalId(), proposal.policyId(),
                proposal.policyRevision(), proposal.payloadDomain(), proposal.payloadHash(),
                proposal.deadlineHeight(),
                decision.actorId(), decision.actorRevision(), decision.keyId(),
                decision.clauseId());
        Fact actorFact = revisionFact(ACTOR, decision.actorId(), decision.actorRevision());
        if (actorFact == null) return false;
        ActorRecordV1 actor = ActorRecordV1.decode(actorFact.expectedValue());
        var key = actor.key(decision.keyId());
        return retainedActor(decision.actorId(), decision.actorRevision(),
                decision.organizationId(), decision.organizationRevision(),
                decision.role(), decision.keyId())
                && key != null && key.activeAt(decision.acceptedHeight())
                && MessageDigest.isEqual(statement.digest(), decision.statementDigest())
                && new SignedActorCommandV1(statement, decision.signature())
                .verify(key.publicKey());
    }

    private static boolean approvalSatisfied(
            ApprovalPolicyV1 policy,
            List<ApprovalProposalV1.AcceptedDecisionV1> decisions
    ) {
        for (ApprovalPolicyV1.RequiredClause clause : policy.clauses()) {
            long count = decisions.stream()
                    .filter(decision -> decision.action() == ActorStatementV1.Action.APPROVE)
                    .filter(decision -> decision.clauseId().equals(clause.clauseId()))
                    .map(decision -> clause.distinctBy() == ApprovalPolicyV1.DistinctBy.ACTOR
                            ? decision.actorId() : decision.organizationId())
                    .distinct().count();
            if (count < clause.minimumCount()) return false;
        }
        return true;
    }

    private boolean verifyGovernance() {
        RoleCommandResultV1 result = RoleCommandResultV1.decode(
                facts.get(GOVERNANCE_RESULT).expectedValue());
        GovernedMutationRecordV1 mutation = GovernedMutationRecordV1.decode(
                facts.get(GOVERNANCE_MUTATION).expectedValue());
        AdministratorAuthorityV1 authority = AdministratorAuthorityV1.decode(
                facts.get(AUTHORITY).expectedValue());
        String component = switch (result.commandKind()) {
            case RoleCommandResultV1.KIND_REGISTRY_GOVERNANCE ->
                    RoleWorkflowIdentifiers.DOMAIN_ACTORS_COMPONENT_ID;
            case RoleCommandResultV1.KIND_POLICY_GOVERNANCE ->
                    RoleWorkflowIdentifiers.ROLE_APPROVALS_COMPONENT_ID;
            default -> null;
        };
        if (component == null || result.resultCode() != RoleWorkflowResultCode.ACCEPTED
                || !result.subjectId().equals(mutation.mutationId())
                || mutation.status() != GovernedMutationRecordV1.Status.ACTIVATED
                || result.appliedHeight() != mutation.terminalHeight()
                || result.appliedHeight() > height
                || !authority.authorityId().equals(mutation.authorityId())
                || authority.revision() != mutation.authorityRevision()
                || !MessageDigest.isEqual(authority.digest(), mutation.authorityDigest())
                || mutation.approvals().size() < authority.distinctActorThreshold()
                || !factKey(GOVERNANCE_RESULT, component,
                RoleWorkflowKeys.commandResult(result.messageId()))
                || !factKey(GOVERNANCE_MUTATION, component,
                RoleWorkflowKeys.governedMutation(mutation.mutationId()))
                || !factKey(AUTHORITY,
                RoleWorkflowIdentifiers.DOMAIN_ACTORS_COMPONENT_ID,
                RoleWorkflowKeys.authorityRevision(
                        authority.authorityId(), authority.revision()))
                || !factKey(AUTHORITY_CURRENT,
                RoleWorkflowIdentifiers.DOMAIN_ACTORS_COMPONENT_ID,
                RoleWorkflowKeys.authorityCurrent(authority.authorityId()))
                || pointerRevision(facts.get(AUTHORITY_CURRENT)) < authority.revision()) {
            return false;
        }
        for (var vote : mutation.approvals()) {
            var signed = vote.authorization();
            var statement = signed.statement();
            Fact actorFact = revisionFact(
                    ACTOR, statement.actorId(), statement.actorRevision());
            if (actorFact == null) return false;
            ActorRecordV1 actor = ActorRecordV1.decode(actorFact.expectedValue());
            var key = actor.key(statement.keyId());
            if (!authority.administratorActorIds().contains(statement.actorId())
                    || !statement.chainId().equals(chainId)
                    || !MessageDigest.isEqual(statement.genesisId(), Hex.decode(genesisIdHex))
                    || !statement.authorityId().equals(authority.authorityId())
                    || statement.authorityRevision() != authority.revision()
                    || !statement.mutationId().equals(mutation.mutationId())
                    || !MessageDigest.isEqual(statement.mutationHash(), mutation.mutationHash())
                    || statement.notBeforeHeight() != mutation.notBeforeHeight()
                    || statement.expiryHeight() != mutation.expiryHeight()
                    || statement.issuedHeight() > vote.acceptedHeight()
                    || statement.deadlineHeight() <= vote.acceptedHeight()
                    || vote.acceptedHeight() > mutation.terminalHeight()
                    || !signed.verifyClaimedKey()
                    || key == null || !key.activeAt(vote.acceptedHeight())
                    || !MessageDigest.isEqual(key.publicKey(), statement.publicKey())
                    || !retainedIdentity(statement.actorId(), statement.actorRevision(),
                    vote.organizationId(), vote.organizationRevision(), statement.keyId())) {
                return false;
            }
        }
        ActorRecordV1 currentActor = ActorRecordV1.decode(facts.get(ACTOR).expectedValue());
        return factKey(ACTOR_CURRENT,
                RoleWorkflowIdentifiers.DOMAIN_ACTORS_COMPONENT_ID,
                RoleWorkflowKeys.actorCurrent(currentActor.actorId()))
                && pointerRevision(facts.get(ACTOR_CURRENT)) >= currentActor.revision();
    }

    private boolean retainedActor(
            String actorId, long actorRevision, String organizationId,
            long organizationRevision, String role, String keyId
    ) {
        Fact actorFact = revisionFact(ACTOR, actorId, actorRevision);
        Fact organizationFact = revisionFact(
                ORGANIZATION, organizationId, organizationRevision);
        if (actorFact == null || organizationFact == null) return false;
        ActorRecordV1 actor = ActorRecordV1.decode(actorFact.expectedValue());
        OrganizationRecordV1 organization = OrganizationRecordV1.decode(
                organizationFact.expectedValue());
        return actor.actorId().equals(actorId) && actor.revision() == actorRevision
                && actor.organizationId().equals(organizationId)
                && actor.status() == RecordStatus.ACTIVE && actor.roles().contains(role)
                && (keyId == null || actor.key(keyId) != null)
                && organization.organizationId().equals(organizationId)
                && organization.revision() == organizationRevision
                && organization.status() == RecordStatus.ACTIVE
                && factKey(actorFact, RoleWorkflowIdentifiers.DOMAIN_ACTORS_COMPONENT_ID,
                RoleWorkflowKeys.actorRevision(actorId, actorRevision))
                && factKey(organizationFact,
                RoleWorkflowIdentifiers.DOMAIN_ACTORS_COMPONENT_ID,
                RoleWorkflowKeys.organizationRevision(
                        organizationId, organizationRevision));
    }

    private boolean retainedIdentity(
            String actorId, long actorRevision, String organizationId,
            long organizationRevision, String keyId
    ) {
        Fact actorFact = revisionFact(ACTOR, actorId, actorRevision);
        Fact organizationFact = revisionFact(
                ORGANIZATION, organizationId, organizationRevision);
        if (actorFact == null || organizationFact == null) return false;
        ActorRecordV1 actor = ActorRecordV1.decode(actorFact.expectedValue());
        OrganizationRecordV1 organization = OrganizationRecordV1.decode(
                organizationFact.expectedValue());
        return actor.actorId().equals(actorId) && actor.revision() == actorRevision
                && actor.organizationId().equals(organizationId)
                && actor.status() == RecordStatus.ACTIVE && actor.key(keyId) != null
                && organization.organizationId().equals(organizationId)
                && organization.revision() == organizationRevision
                && organization.status() == RecordStatus.ACTIVE
                && factKey(actorFact, RoleWorkflowIdentifiers.DOMAIN_ACTORS_COMPONENT_ID,
                RoleWorkflowKeys.actorRevision(actorId, actorRevision))
                && factKey(organizationFact,
                RoleWorkflowIdentifiers.DOMAIN_ACTORS_COMPONENT_ID,
                RoleWorkflowKeys.organizationRevision(
                        organizationId, organizationRevision));
    }

    private Fact revisionFact(String kind, String id, long revision) {
        Fact exact = facts.get(revisionFactName(kind, id, revision));
        if (exact != null) return exact;
        Fact legacySingle = facts.get(kind);
        if (legacySingle == null) return null;
        try {
            if (ACTOR.equals(kind)) {
                ActorRecordV1 actor = ActorRecordV1.decode(legacySingle.expectedValue());
                return actor.actorId().equals(id) && actor.revision() == revision
                        ? legacySingle : null;
            }
            OrganizationRecordV1 organization = OrganizationRecordV1.decode(
                    legacySingle.expectedValue());
            return organization.organizationId().equals(id)
                    && organization.revision() == revision ? legacySingle : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    public static String revisionFactName(String kind, String id, long revision) {
        if (!ACTOR.equals(kind) && !ORGANIZATION.equals(kind) || id == null
                || id.isBlank() || revision < 1) {
            throw new IllegalArgumentException("invalid retained revision fact name");
        }
        return kind + ":" + id + "@" + revision;
    }

    private static long pointerRevision(Fact fact) {
        byte[] value = fact.expectedValue();
        if (value == null || value.length != Long.BYTES) return 0;
        return java.nio.ByteBuffer.wrap(value).getLong();
    }

    private boolean factKey(String name, String component, byte[] localKey) {
        return factKey(facts.get(name), component, localKey);
    }

    private static boolean factKey(Fact fact, String component, byte[] localKey) {
        return fact != null && Arrays.equals(fact.expectedKey(),
                CompositeCommitmentV1.componentKey(component, localKey));
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private boolean identityMatches(ProofVerifier.TrustedStateRoot trustedRoot) {
        return trustedRoot != null
                && chainId.equals(trustedRoot.chainId())
                && profile.equals(trustedRoot.profile())
                && genesisIdHex.equals(trustedRoot.genesisIdHex())
                && height == trustedRoot.height()
                && stateRootHex.equals(trustedRoot.stateRootHex());
    }

    public Kind kind() { return kind; }
    public String chainId() { return chainId; }
    public String profile() { return profile; }
    public String genesisIdHex() { return genesisIdHex; }
    public long height() { return height; }
    public String stateRootHex() { return stateRootHex; }
    public byte[] actionCommitment() { return actionCommitment.clone(); }
    public byte[] authorizationEvidence() { return authorizationEvidence.clone(); }
    public Map<String, Fact> facts() { return facts; }

    public enum Kind { BASIC, DIRECT_ROLE, APPROVAL, ADMINISTRATOR_GOVERNANCE }

    public record Fact(
            String name,
            byte[] expectedKey,
            byte[] expectedValue,
            AppChainClient.Proof proof
    ) {
        public Fact {
            name = nonBlank(name, "name");
            expectedKey = Objects.requireNonNull(expectedKey, "expectedKey").clone();
            expectedValue = expectedValue == null ? null : expectedValue.clone();
            proof = Objects.requireNonNull(proof, "proof");
            if (expectedKey.length == 0 || expectedKey.length > 256) {
                throw new IllegalArgumentException("expected proof key is outside bounds");
            }
        }

        @Override public byte[] expectedKey() { return expectedKey.clone(); }
        @Override public byte[] expectedValue() {
            return expectedValue == null ? null : expectedValue.clone();
        }

        private boolean verify(ProofVerifier.TrustedStateRoot root) {
            return matchesExpectedRecord() && ProofVerifier.verify(proof, root);
        }

        private boolean matchesExpectedRecord() {
            if (!Hex.encode(expectedKey).equals(proof.keyHex())) return false;
            if (expectedValue == null) {
                return proof.presence() == AppChainClient.ProofPresence.ABSENT
                        && proof.valueHex() == null;
            }
            return proof.presence() != AppChainClient.ProofPresence.ABSENT
                    && proof.valueHex() != null
                    && Arrays.equals(expectedValue, Hex.decode(proof.valueHex()));
        }
    }

    private static byte[] exact(byte[] value, int length, String name) {
        byte[] copy = Objects.requireNonNull(value, name).clone();
        if (copy.length != length) throw new IllegalArgumentException(name + " has wrong length");
        return copy;
    }

    private static String canonicalHex(String value, int bytes, String name) {
        if (value == null || !value.matches("[0-9a-f]{" + (bytes * 2) + "}")) {
            throw new IllegalArgumentException(name + " must be canonical lowercase hex");
        }
        return value;
    }

    private static String nonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
