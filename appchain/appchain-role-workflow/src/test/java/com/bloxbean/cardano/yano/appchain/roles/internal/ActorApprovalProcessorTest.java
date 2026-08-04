package com.bloxbean.cardano.yano.appchain.roles.internal;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorKeyEpochV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorStatementV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalPolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalProposalV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedAuthorizationLimitsV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.OrganizationRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RecordStatus;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleApprovalStatsV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RolePendingQueriesV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowLimits;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowResultCode;
import com.bloxbean.cardano.yano.appchain.roles.contracts.SignedActorCommandV1;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActorApprovalProcessorTest {
    private static final String CHAIN_ID = "governed-map";
    private static final String PAYLOAD_DOMAIN = "yano.authenticated-map.action.v1";
    private static final byte[] SEED_A = repeated(1);
    private static final byte[] SEED_B = repeated(2);
    private static final byte[] SEED_C = repeated(3);
    private static final byte[] SEED_D = repeated(4);
    private static final byte[] SEED_E = repeated(5);
    private static final byte[] SEED_F = repeated(6);
    private static final byte[] PAYLOAD = repeated(9);

    private final MemoryState state = new MemoryState();
    private ActorApprovalProcessor processor;

    @BeforeEach
    void setUp() {
        actor("issuer-a", "org-a", "issuer-a-key", SEED_A, "issuer");
        actor("auditor-b", "org-b", "auditor-b-key", SEED_B, "auditor");
        actor("issuer-c", "org-c", "issuer-c-key", SEED_C, "issuer");
        ApprovalPolicyV1 policy = policy(1, RecordStatus.ACTIVE, "auditor");
        state.put(RoleWorkflowKeys.policyRevision(policy.policyId(), 1), policy.encode());
        RoleState.pointer(state, RoleWorkflowKeys.policyCurrent(policy.policyId()), 1);
        state.put(RoleWorkflowKeys.approvalStats(), RoleApprovalStatsV1.empty().encode());
        processor = new ActorApprovalProcessor(
                CHAIN_ID, PAYLOAD_DOMAIN, constrainedLimits());
    }

    @Test
    void freezesPolicyAndRetainsTerminalDecisionTrail() {
        SignedActorCommandV1 propose = command(ActorStatementV1.Action.PROPOSE,
                "release-1", 10, "issuer-a", SEED_A, "");
        assertThat(processor.apply(propose, 2, state, state))
                .isEqualTo(RoleWorkflowResultCode.ACCEPTED);
        assertThat(ActorApprovalProcessor.pendingPage(state,
                new RolePendingQueriesV1.PageQuery("", 10), 10).entries())
                .extracting(RolePendingQueriesV1.ApprovalEntry::proposalId)
                .containsExactly("release-1");

        ApprovalPolicyV1 suspended = policy(2, RecordStatus.SUSPENDED, "regulator");
        state.put(RoleWorkflowKeys.policyRevision(suspended.policyId(), 2),
                suspended.encode());
        RoleState.pointer(state, RoleWorkflowKeys.policyCurrent(suspended.policyId()), 2);

        SignedActorCommandV1 approve = command(ActorStatementV1.Action.APPROVE,
                "release-1", 10, "auditor-b", SEED_B, "auditors");
        assertThat(processor.apply(approve, 3, state, state))
                .isEqualTo(RoleWorkflowResultCode.ACCEPTED);
        ApprovalProposalV1 proposal = proposal("release-1");
        assertThat(proposal.status()).isEqualTo(
                ApprovalProposalV1.ProposalStatus.APPROVED);
        assertThat(proposal.policyRevision()).isEqualTo(1);
        assertThat(proposal.decisions()).singleElement().satisfies(decision -> {
            assertThat(decision.actorId()).isEqualTo("auditor-b");
            assertThat(decision.organizationId()).isEqualTo("org-b");
            assertThat(decision.role()).isEqualTo("auditor");
        });
        assertThat(RoleApprovalStatsV1.decode(state.get(
                RoleWorkflowKeys.approvalStats()).orElseThrow()))
                .isEqualTo(new RoleApprovalStatsV1(1, 0, 1, 0, 0, 0));
    }

    @Test
    void expiryReclaimsPerPolicyCapacityWithoutPokeMessages() {
        assertThat(processor.apply(command(ActorStatementV1.Action.PROPOSE,
                        "expiring", 4, "issuer-a", SEED_A, ""),
                2, state, state)).isEqualTo(RoleWorkflowResultCode.ACCEPTED);
        assertThat(processor.apply(command(ActorStatementV1.Action.PROPOSE,
                        "blocked", 10, "issuer-c", SEED_C, ""),
                3, state, state)).isEqualTo(RoleWorkflowResultCode.CAPACITY_EXCEEDED);

        processor.prepareHeight(5, state);
        assertThat(proposal("expiring").status())
                .isEqualTo(ApprovalProposalV1.ProposalStatus.EXPIRED);
        assertThat(processor.apply(command(ActorStatementV1.Action.PROPOSE,
                        "reopened", 10, "issuer-c", SEED_C, ""),
                5, state, state)).isEqualTo(RoleWorkflowResultCode.ACCEPTED);
        assertThat(ActorApprovalProcessor.pendingPage(state,
                new RolePendingQueriesV1.PageQuery("", 10), 10).entries())
                .extracting(RolePendingQueriesV1.ApprovalEntry::proposalId)
                .containsExactly("reopened");
        assertThat(RoleApprovalStatsV1.decode(state.get(
                RoleWorkflowKeys.approvalStats()).orElseThrow()))
                .isEqualTo(new RoleApprovalStatsV1(2, 1, 0, 0, 0, 1));
    }

    @Test
    void expiryRetainsPartialAcceptedDecisionTrail() {
        ApprovalPolicyV1 thresholdTwo = new ApprovalPolicyV1(
                "release-policy", 1, RecordStatus.ACTIVE, List.of("issuer"),
                List.of(new ApprovalPolicyV1.RequiredClause(
                        "auditors", "auditor", 2,
                        ApprovalPolicyV1.DistinctBy.ACTOR)),
                ApprovalPolicyV1.RejectionMode.ANY_ELIGIBLE, 100);
        state.put(RoleWorkflowKeys.policyRevision("release-policy", 1),
                thresholdTwo.encode());
        assertThat(processor.apply(command(ActorStatementV1.Action.PROPOSE,
                        "partially-approved", 5, "issuer-a", SEED_A, ""),
                2, state, state)).isEqualTo(RoleWorkflowResultCode.ACCEPTED);
        assertThat(processor.apply(command(ActorStatementV1.Action.APPROVE,
                        "partially-approved", 5, "auditor-b", SEED_B, "auditors"),
                3, state, state)).isEqualTo(RoleWorkflowResultCode.ACCEPTED);
        assertThat(proposal("partially-approved").status())
                .isEqualTo(ApprovalProposalV1.ProposalStatus.PENDING);

        processor.prepareHeight(6, state);
        assertThat(proposal("partially-approved")).satisfies(expired -> {
            assertThat(expired.status())
                    .isEqualTo(ApprovalProposalV1.ProposalStatus.EXPIRED);
            assertThat(expired.decisions()).singleElement().satisfies(decision ->
                    assertThat(decision.actorId()).isEqualTo("auditor-b"));
        });
    }

    @Test
    void rejectionAndProposerCancellationRetainStableTerminalOutcomes() {
        assertThat(processor.apply(command(ActorStatementV1.Action.PROPOSE,
                        "rejected", 20, "issuer-a", SEED_A, ""),
                2, state, state)).isEqualTo(RoleWorkflowResultCode.ACCEPTED);
        assertThat(processor.apply(command(ActorStatementV1.Action.REJECT,
                        "rejected", 20, "auditor-b", SEED_B, "auditors"),
                3, state, state)).isEqualTo(RoleWorkflowResultCode.ACCEPTED);
        assertThat(proposal("rejected").status())
                .isEqualTo(ApprovalProposalV1.ProposalStatus.REJECTED);

        assertThat(processor.apply(command(ActorStatementV1.Action.PROPOSE,
                        "cancelled", 30, "issuer-a", SEED_A, ""),
                4, state, state)).isEqualTo(RoleWorkflowResultCode.ACCEPTED);
        assertThat(processor.apply(command(ActorStatementV1.Action.CANCEL,
                        "cancelled", 30, "issuer-a", SEED_A, ""),
                5, state, state)).isEqualTo(RoleWorkflowResultCode.ACCEPTED);
        assertThat(proposal("cancelled").status())
                .isEqualTo(ApprovalProposalV1.ProposalStatus.CANCELLED);
        assertThat(RoleApprovalStatsV1.decode(state.get(
                RoleWorkflowKeys.approvalStats()).orElseThrow()))
                .isEqualTo(new RoleApprovalStatsV1(2, 0, 0, 1, 1, 0));
    }

    @Test
    void governanceCancellationUsesTheSameAtomicTerminalTransition() {
        assertThat(processor.apply(command(ActorStatementV1.Action.PROPOSE,
                        "governance-cancelled", 20, "issuer-a", SEED_A, ""),
                2, state, state)).isEqualTo(RoleWorkflowResultCode.ACCEPTED);
        assertThat(processor.canCancelByGovernance(
                "governance-cancelled", state)).isTrue();

        assertThat(processor.cancelByGovernance(
                "governance-cancelled", state)).isTrue();
        assertThat(proposal("governance-cancelled").status())
                .isEqualTo(ApprovalProposalV1.ProposalStatus.CANCELLED);
        assertThat(state.get(RoleWorkflowKeys.approvalDeadline(
                20, "governance-cancelled"))).isEmpty();
        assertThat(RoleApprovalStatsV1.decode(state.get(
                RoleWorkflowKeys.approvalStats()).orElseThrow()))
                .isEqualTo(new RoleApprovalStatsV1(1, 0, 0, 0, 1, 0));
        assertThat(processor.cancelByGovernance(
                "governance-cancelled", state)).isFalse();
    }

    @Test
    void allClausesAndOrganizationDistinctnessMustBeSatisfied() {
        actor("auditor-c", "org-b", "auditor-c-key", SEED_D, "auditor");
        actor("auditor-d", "org-d", "auditor-d-key", SEED_E, "auditor");
        actor("regulator-e", "org-e", "regulator-e-key", SEED_F, "regulator");
        ApprovalPolicyV1 policy = new ApprovalPolicyV1(
                "release-policy", 1, RecordStatus.ACTIVE, List.of("issuer"),
                List.of(new ApprovalPolicyV1.RequiredClause(
                                "auditors", "auditor", 2,
                                ApprovalPolicyV1.DistinctBy.ORGANIZATION),
                        new ApprovalPolicyV1.RequiredClause(
                                "regulator", "regulator", 1,
                                ApprovalPolicyV1.DistinctBy.ACTOR)),
                ApprovalPolicyV1.RejectionMode.ANY_ELIGIBLE, 100);
        state.put(RoleWorkflowKeys.policyRevision("release-policy", 1),
                policy.encode());

        assertThat(processor.apply(command(ActorStatementV1.Action.PROPOSE,
                        "multi-clause", 20, "issuer-a", SEED_A, ""),
                2, state, state)).isEqualTo(RoleWorkflowResultCode.ACCEPTED);
        assertThat(processor.apply(command(ActorStatementV1.Action.APPROVE,
                        "multi-clause", 20, "auditor-b", SEED_B, "auditors"),
                3, state, state)).isEqualTo(RoleWorkflowResultCode.ACCEPTED);
        assertThat(processor.apply(command(ActorStatementV1.Action.APPROVE,
                        "multi-clause", 20, "auditor-c", SEED_D, "auditors"),
                4, state, state))
                .isEqualTo(RoleWorkflowResultCode.DISTINCTNESS_DUPLICATE);
        assertThat(processor.apply(command(ActorStatementV1.Action.APPROVE,
                        "multi-clause", 20, "auditor-d", SEED_E, "auditors"),
                5, state, state)).isEqualTo(RoleWorkflowResultCode.ACCEPTED);
        assertThat(proposal("multi-clause").status())
                .isEqualTo(ApprovalProposalV1.ProposalStatus.PENDING);

        assertThat(processor.apply(command(ActorStatementV1.Action.APPROVE,
                        "multi-clause", 20, "regulator-e", SEED_F, "regulator"),
                6, state, state)).isEqualTo(RoleWorkflowResultCode.ACCEPTED);
        assertThat(proposal("multi-clause")).satisfies(approved -> {
            assertThat(approved.status())
                    .isEqualTo(ApprovalProposalV1.ProposalStatus.APPROVED);
            assertThat(approved.decisions()).extracting(
                            ApprovalProposalV1.AcceptedDecisionV1::actorId)
                    .containsExactly("auditor-b", "auditor-d", "regulator-e");
        });
    }

    @Test
    void invalidSignatureAndClosedDeadlineAreStableNoOps() {
        SignedActorCommandV1 wrongSignature = command(
                ActorStatementV1.Action.PROPOSE, "forged", 20,
                "issuer-a", SEED_B, "");
        assertThat(processor.apply(wrongSignature, 2, state, state))
                .isEqualTo(RoleWorkflowResultCode.INVALID_SIGNATURE);
        assertThat(state.get(RoleWorkflowKeys.proposal("forged"))).isEmpty();

        assertThat(processor.apply(command(ActorStatementV1.Action.PROPOSE,
                        "closed", 3, "issuer-a", SEED_A, ""),
                3, state, state)).isEqualTo(RoleWorkflowResultCode.EXPIRED);
        assertThat(state.get(RoleWorkflowKeys.proposal("closed"))).isEmpty();
    }

    @Test
    void pendingPageUsesExclusiveCanonicalCursor() {
        ApprovalPendingIndexV1 index = new ApprovalPendingIndexV1(List.of(
                new ApprovalPendingIndexV1.Entry(
                        "proposal-c", 30, "release-policy", "issuer-c"),
                new ApprovalPendingIndexV1.Entry(
                        "proposal-a", 10, "release-policy", "issuer-a"),
                new ApprovalPendingIndexV1.Entry(
                        "proposal-b", 20, "release-policy", "issuer-a")));
        state.put(RoleWorkflowKeys.approvalPendingIndex(), index.encode());

        RolePendingQueriesV1.ApprovalPage first = ActorApprovalProcessor.pendingPage(
                state, new RolePendingQueriesV1.PageQuery("", 2), 2);
        assertThat(first.entries()).extracting(
                        RolePendingQueriesV1.ApprovalEntry::proposalId)
                .containsExactly("proposal-a", "proposal-b");
        assertThat(first.nextAfterId()).isEqualTo("proposal-b");
        RolePendingQueriesV1.ApprovalPage second = ActorApprovalProcessor.pendingPage(
                state, new RolePendingQueriesV1.PageQuery(first.nextAfterId(), 2), 2);
        assertThat(second.entries()).extracting(
                        RolePendingQueriesV1.ApprovalEntry::proposalId)
                .containsExactly("proposal-c");
        assertThat(second.nextAfterId()).isEmpty();
    }

    @Test
    void pendingIndexRoundTripsBeyondCommandCborLimits() {
        ApprovalPendingIndexV1 index = new ApprovalPendingIndexV1(IntStream
                .range(0, 512)
                .mapToObj(value -> new ApprovalPendingIndexV1.Entry(
                        "proposal-" + value, value + 1L,
                        "release-policy", "issuer-a"))
                .toList());

        byte[] encoded = index.encode();
        assertThat(encoded.length).isGreaterThan(RoleWorkflowLimits.MAX_COMMAND_BYTES);
        assertThat(ApprovalPendingIndexV1.decode(encoded)).isEqualTo(index);
    }

    @Test
    void restartAuditRejectsMissingPendingMarker() {
        assertThat(processor.apply(command(ActorStatementV1.Action.PROPOSE,
                        "audited", 20, "issuer-a", SEED_A, ""),
                2, state, state)).isEqualTo(RoleWorkflowResultCode.ACCEPTED);
        ActorApprovalProcessor.verifyPendingState(state, constrainedLimits());

        state.delete(RoleWorkflowKeys.approvalByPolicy(
                "release-policy", "audited"));
        assertThatThrownBy(() -> ActorApprovalProcessor.verifyPendingState(
                state, constrainedLimits()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("marker is absent");
    }

    private SignedActorCommandV1 command(
            ActorStatementV1.Action action,
            String proposalId,
            long deadline,
            String actorId,
            byte[] seed,
            String clauseId
    ) {
        ActorStatementV1 statement = new ActorStatementV1(
                action, CHAIN_ID, proposalId, "release-policy", 1,
                PAYLOAD_DOMAIN, PAYLOAD, deadline, actorId, 1,
                actorId + "-key", clauseId);
        return SignedActorCommandV1.sign(statement, seed);
    }

    private void actor(
            String actorId,
            String organizationId,
            String keyId,
            byte[] seed,
            String role
    ) {
        OrganizationRecordV1 organization = new OrganizationRecordV1(
                organizationId, 1, RecordStatus.ACTIVE, new byte[0]);
        state.put(RoleWorkflowKeys.organizationRevision(organizationId, 1),
                organization.encode());
        RoleState.pointer(state, RoleWorkflowKeys.organizationCurrent(organizationId), 1);
        ActorRecordV1 actor = new ActorRecordV1(
                actorId, organizationId, 1, RecordStatus.ACTIVE, List.of(role),
                List.of(new ActorKeyEpochV1(keyId,
                        KeyGenUtil.getPublicKeyFromPrivateKey(seed),
                        1, 0, RecordStatus.ACTIVE)), new byte[0]);
        state.put(RoleWorkflowKeys.actorRevision(actorId, 1), actor.encode());
        RoleState.pointer(state, RoleWorkflowKeys.actorCurrent(actorId), 1);
    }

    private static ApprovalPolicyV1 policy(
            long revision,
            RecordStatus status,
            String requiredRole
    ) {
        return new ApprovalPolicyV1("release-policy", revision, status,
                List.of("issuer"), List.of(new ApprovalPolicyV1.RequiredClause(
                "auditors", requiredRole, 1,
                ApprovalPolicyV1.DistinctBy.ORGANIZATION)),
                ApprovalPolicyV1.RejectionMode.ANY_ELIGIBLE, 100);
    }

    private ApprovalProposalV1 proposal(String id) {
        return state.get(RoleWorkflowKeys.proposal(id))
                .map(ApprovalProposalV1::decode).orElseThrow();
    }

    private static GovernedAuthorizationLimitsV1 constrainedLimits() {
        return new GovernedAuthorizationLimitsV1(
                RoleWorkflowLimits.MAX_AUTHORIZATION_EVIDENCE_ITEMS,
                RoleWorkflowLimits.MAX_COVERED_MUTATION_INDEXES,
                RoleWorkflowLimits.MAX_GENESIS_ORGANIZATIONS,
                RoleWorkflowLimits.MAX_GENESIS_ACTORS,
                RoleWorkflowLimits.MAX_GENESIS_KEYS,
                RoleWorkflowLimits.MAX_GENESIS_POLICIES,
                RoleWorkflowLimits.MAX_GENESIS_RECORD_BYTES,
                2, 2, 1, 1, 2, 2, 2, 2, 10,
                RoleWorkflowLimits.MAX_CRYPTO_WORK_UNITS_PER_BLOCK);
    }

    private static byte[] repeated(int value) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) value);
        return result;
    }

    private static final class MemoryState implements AppStateWriter {
        private final Map<Key, byte[]> values = new LinkedHashMap<>();

        @Override
        public Optional<byte[]> get(byte[] key) {
            byte[] value = values.get(new Key(key));
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }

        @Override public byte[] stateRoot() { return new byte[32]; }
        @Override public void put(byte[] key, byte[] value) {
            values.put(new Key(key), value.clone());
        }
        @Override public void delete(byte[] key) { values.remove(new Key(key)); }
    }

    private record Key(byte[] value) {
        private Key { value = value.clone(); }
        @Override public byte[] value() { return value.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Key key && Arrays.equals(value, key.value);
        }
        @Override public int hashCode() { return Arrays.hashCode(value); }
    }
}
