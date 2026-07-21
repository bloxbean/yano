package com.bloxbean.cardano.yano.appchain.roles.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.api.SigningProvider;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.yano.appchain.roles.contracts.internal.RoleWorkflowCbor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoleWorkflowAdversarialContractTest {
    @Test
    void verificationIsTotalStrictAndIndependentOfTheMutableGlobalProvider() {
        byte[] seed = filled(0x31, 32);
        byte[] publicKey = KeyGenUtil.getPublicKeyFromPrivateKey(seed);
        ActorKeyEpochV1 key = new ActorKeyEpochV1(
                "key-1", publicKey, 1, 0, RecordStatus.ACTIVE);
        ActorStatementV1 statement = statement("proposal-a", "actor-a");
        SignedActorCommandV1 command = SignedActorCommandV1.sign(statement, seed);
        ActorKeyProofV1 proof = ActorKeyProofV1.sign(
                "role-chain", "actor-a", 1, key, seed);
        byte[] hostileKey = filled(0x7f, 32);
        SignedActorCommandV1 hostileCommand = new SignedActorCommandV1(
                statement, new byte[64]);
        ActorKeyProofV1 hostileProof = new ActorKeyProofV1(
                "role-chain", "actor-a", 1,
                new ActorKeyEpochV1("hostile", hostileKey, 1, 0, RecordStatus.ACTIVE),
                new byte[64]);

        assertThatCode(() -> hostileCommand.verify(hostileKey)).doesNotThrowAnyException();
        assertThatCode(hostileProof::verify).doesNotThrowAnyException();
        assertThat(hostileCommand.verify(hostileKey)).isFalse();
        assertThat(hostileProof.verify()).isFalse();

        SigningProvider previous = CryptoConfiguration.INSTANCE.getSigningProvider();
        try {
            CryptoConfiguration.INSTANCE.setSigningProvider(new RejectingSigningProvider());
            assertThat(command.verify(publicKey)).isTrue();
            assertThat(proof.verify()).isTrue();
        } finally {
            CryptoConfiguration.INSTANCE.setSigningProvider(previous);
        }
    }

    @Test
    void setLikeArraysMustAlreadyUseTheirCanonicalOrder() {
        byte[] seedA = filled(0x41, 32);
        byte[] seedB = filled(0x42, 32);
        ActorKeyEpochV1 keyA = new ActorKeyEpochV1(
                "key-a", KeyGenUtil.getPublicKeyFromPrivateKey(seedA),
                1, 0, RecordStatus.ACTIVE);
        ActorKeyEpochV1 keyB = new ActorKeyEpochV1(
                "key-b", KeyGenUtil.getPublicKeyFromPrivateKey(seedB),
                1, 0, RecordStatus.ACTIVE);
        ActorRecordV1 actor = new ActorRecordV1(
                "actor-a", "organization-a", 1, RecordStatus.ACTIVE,
                List.of("auditor", "regulator"), List.of(keyA, keyB), new byte[0]);
        assertSwappedArrayRejected(actor.encode(), 8, 5, ActorRecordV1::decode);
        assertSwappedArrayRejected(actor.encode(), 8, 6, ActorRecordV1::decode);

        ApprovalPolicyV1 policy = new ApprovalPolicyV1(
                "policy-a", 1, List.of("auditor", "manufacturer"),
                List.of(new ApprovalPolicyV1.RequiredClause(
                                "auditors", "auditor", 1,
                                ApprovalPolicyV1.DistinctBy.ORGANIZATION),
                        new ApprovalPolicyV1.RequiredClause(
                                "regulator", "regulator", 1,
                                ApprovalPolicyV1.DistinctBy.ACTOR)),
                ApprovalPolicyV1.RejectionMode.ANY_ELIGIBLE, 100);
        assertSwappedArrayRejected(policy.encode(), 7, 3, ApprovalPolicyV1::decode);
        assertSwappedArrayRejected(policy.encode(), 7, 4, ApprovalPolicyV1::decode);

        ApprovalProposalV1 proposal = proposal(policy);
        assertSwappedArrayRejected(proposal.encode(), 17, 16, ApprovalProposalV1::decode);

        RegistryMutationV1 mutation = new RegistryMutationV1.PutActor(actor, List.of(
                ActorKeyProofV1.sign("role-chain", actor.actorId(), 1, keyA, seedA),
                ActorKeyProofV1.sign("role-chain", actor.actorId(), 1, keyB, seedB)));
        assertSwappedArrayRejected(mutation.encode(), 4, 3, RegistryMutationV1::decode);
    }

    @Test
    void everyConsensusCollectionAndPayloadLimitRejectsItsFirstExcessValue() {
        assertThatThrownBy(() -> SignedActorCommandV1.decode(
                new byte[RoleWorkflowLimits.MAX_COMMAND_BYTES + 1]))
                .isInstanceOfSatisfying(RoleWorkflowException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(RoleWorkflowResultCode.INVALID_PAYLOAD));
        assertThatThrownBy(() -> RoleWorkflowIdentifiers.id("a".repeat(64), "secret-field"))
                .isInstanceOfSatisfying(RoleWorkflowException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(RoleWorkflowResultCode.INVALID_PAYLOAD);
                    assertThat(failure.getMessage()).isEqualTo("INVALID_PAYLOAD");
                });
        assertThatThrownBy(() -> RoleWorkflowIdentifiers.role("r".repeat(64)))
                .isInstanceOf(RoleWorkflowException.class);
        assertThatThrownBy(() -> RoleWorkflowIdentifiers.payloadDomain("d".repeat(65)))
                .isInstanceOf(RoleWorkflowException.class);
        assertThatThrownBy(() -> new GovernedMutationCommandV1.Propose(
                "mutation-a", new byte[RoleWorkflowLimits.MAX_MUTATION_BYTES + 1], 10))
                .isInstanceOf(RoleWorkflowException.class);

        ActorKeyEpochV1 oneKey = new ActorKeyEpochV1(
                "key-1", filled(0x51, 32), 1, 0, RecordStatus.ACTIVE);
        List<String> tooManyRoles = range("role-", RoleWorkflowLimits.MAX_ROLES_PER_ACTOR + 1);
        assertThatThrownBy(() -> new ActorRecordV1(
                "actor-a", "organization-a", 1, RecordStatus.ACTIVE,
                tooManyRoles, List.of(oneKey), new byte[0]))
                .isInstanceOf(RoleWorkflowException.class);

        List<ActorKeyEpochV1> tooManyKeys = new ArrayList<>();
        for (int index = 0; index <= RoleWorkflowLimits.MAX_KEYS_PER_ACTOR; index++) {
            tooManyKeys.add(new ActorKeyEpochV1(
                    "key-" + index, filled(index, 32), 1, 0, RecordStatus.ACTIVE));
        }
        assertThatThrownBy(() -> new ActorRecordV1(
                "actor-a", "organization-a", 1, RecordStatus.ACTIVE,
                List.of("auditor"), tooManyKeys, new byte[0]))
                .isInstanceOf(RoleWorkflowException.class);

        List<ApprovalPolicyV1.RequiredClause> tooManyClauses = new ArrayList<>();
        for (int index = 0; index <= RoleWorkflowLimits.MAX_CLAUSES_PER_POLICY; index++) {
            tooManyClauses.add(new ApprovalPolicyV1.RequiredClause(
                    "clause-" + index, "auditor", 1,
                    ApprovalPolicyV1.DistinctBy.ACTOR));
        }
        assertThatThrownBy(() -> new ApprovalPolicyV1(
                "policy-a", 1, List.of(), tooManyClauses,
                ApprovalPolicyV1.RejectionMode.DISABLED, 100))
                .isInstanceOf(RoleWorkflowException.class);
        assertThatThrownBy(() -> new ApprovalPolicyV1(
                "policy-a", 1,
                range("role-", RoleWorkflowLimits.MAX_PROPOSER_ROLES + 1),
                List.of(tooManyClauses.getFirst()),
                ApprovalPolicyV1.RejectionMode.DISABLED, 100))
                .isInstanceOf(RoleWorkflowException.class);

        ApprovalPolicyV1 policy = new ApprovalPolicyV1(
                "policy-a", 1, List.of("manufacturer"),
                List.of(tooManyClauses.getFirst()),
                ApprovalPolicyV1.RejectionMode.DISABLED, 100);
        List<ApprovalProposalV1.AcceptedDecisionV1> tooManyDecisions = new ArrayList<>();
        for (int index = 0; index <= RoleWorkflowLimits.MAX_DECISIONS_PER_PROPOSAL; index++) {
            tooManyDecisions.add(decision("actor-" + index, index + 1L));
        }
        assertThatThrownBy(() -> new ApprovalProposalV1(
                "proposal-a", policy.policyId(), policy.revision(), policy.digest(),
                "evidence.release.v1", filled(0x61, 32), 100,
                ApprovalProposalV1.ProposalStatus.PENDING,
                "manufacturer-a", "manufacturer-org", 1, "manufacturer",
                1, "key-1", 1, tooManyDecisions))
                .isInstanceOf(RoleWorkflowException.class);
    }

    @Test
    void rawCborPreflightBoundsNestingAndTotalItemCount() {
        Array nested = new Array();
        Array cursor = nested;
        for (int depth = 0; depth <= RoleWorkflowLimits.MAX_NESTING_DEPTH; depth++) {
            Array child = new Array();
            cursor.add(child);
            cursor = child;
        }
        assertThatThrownBy(() -> RoleWorkflowCbor.decodeArray(
                RoleWorkflowCbor.encode(nested), 1))
                .isInstanceOf(RoleWorkflowException.class);

        Array tooManyItems = new Array();
        for (int index = 0; index < RoleWorkflowLimits.MAX_CBOR_ITEMS; index++) {
            tooManyItems.add(new UnsignedInteger(index));
        }
        assertThatThrownBy(() -> RoleWorkflowCbor.decodeArray(
                RoleWorkflowCbor.encode(tooManyItems), RoleWorkflowLimits.MAX_CBOR_ITEMS))
                .isInstanceOf(RoleWorkflowException.class);
    }

    private static ApprovalProposalV1 proposal(ApprovalPolicyV1 policy) {
        return new ApprovalProposalV1(
                "proposal-a", policy.policyId(), policy.revision(), policy.digest(),
                "evidence.release.v1", filled(0x21, 32), 100,
                ApprovalProposalV1.ProposalStatus.APPROVED,
                "manufacturer-a", "manufacturer-org", 1, "manufacturer",
                1, "key-1", 1,
                List.of(decision("actor-a", 10), decision("actor-b", 20)));
    }

    private static ApprovalProposalV1.AcceptedDecisionV1 decision(
            String actorId, long height) {
        return new ApprovalProposalV1.AcceptedDecisionV1(
                ActorStatementV1.Action.APPROVE, actorId, "organization-a", 1,
                "auditor", 1, "key-1", "auditors",
                filled(0x71, 32), filled(0x72, 64), height);
    }

    private static ActorStatementV1 statement(String proposal, String actor) {
        return new ActorStatementV1(
                ActorStatementV1.Action.PROPOSE, "role-chain", proposal,
                "policy-a", 1, "evidence.release.v1", filled(0x11, 32),
                100, actor, 1, "key-1", "");
    }

    private static void assertSwappedArrayRejected(
            byte[] canonical, int arity, int arrayIndex, Consumer<byte[]> decoder) {
        Array root = RoleWorkflowCbor.decodeArray(canonical, arity);
        Array values = (Array) root.getDataItems().get(arrayIndex);
        Collections.swap(values.getDataItems(), 0, 1);
        byte[] unsorted = RoleWorkflowCbor.encode(root);
        assertThatThrownBy(() -> decoder.accept(unsorted))
                .isInstanceOf(RoleWorkflowException.class);
    }

    private static List<String> range(String prefix, int size) {
        List<String> result = new ArrayList<>();
        for (int index = 0; index < size; index++) result.add(prefix + index);
        return result;
    }

    private static byte[] filled(int value, int length) {
        byte[] result = new byte[length];
        Arrays.fill(result, (byte) value);
        return result;
    }

    private static final class RejectingSigningProvider implements SigningProvider {
        @Override public byte[] sign(byte[] message, byte[] privateKey) {
            throw new UnsupportedOperationException();
        }
        @Override public byte[] signExtended(byte[] message, byte[] privateKey, byte[] publicKey) {
            throw new UnsupportedOperationException();
        }
        @Override public byte[] signExtended(byte[] message, byte[] privateKey) {
            throw new UnsupportedOperationException();
        }
        @Override public boolean verify(byte[] signature, byte[] message, byte[] publicKey) {
            return false;
        }
    }
}
