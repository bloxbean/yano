package com.bloxbean.cardano.yano.appchain.stdlib.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorKeyEpochV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorKeyProofV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.AdministratorAuthorityV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalPolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.DirectRolePolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GenesisActorV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedAuthorizationLimitsV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedGenesisV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.OrganizationRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RecordStatus;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapAuthorizationContract.*;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.internal.StdlibContractCbor;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedMapAuthorizationContractTest {
    private static final byte[] ACTOR_SEED = repeated(1);

    @Test
    void finalGenesisRoundTripsAllFiveAuthorizationKinds() {
        AuthenticatedMapContract.Genesis genesis = genesis();
        byte[] encoded = AuthenticatedMapContract.encodeGenesis(genesis);
        AuthenticatedMapContract.Genesis decoded =
                AuthenticatedMapContract.decodeGenesis(encoded);

        assertThat(AuthenticatedMapContract.encodeGenesis(decoded)).isEqualTo(encoded);
        assertThat(decoded.collections())
                .extracting(AuthenticatedMapContract.CollectionDescriptor::authorization)
                .containsExactly(
                        AuthenticatedMapContract.AUTH_OPEN,
                        AuthenticatedMapContract.AUTH_GOVERNED_ROLE,
                        AuthenticatedMapContract.AUTH_MEMBER,
                        AuthenticatedMapContract.AUTH_OWNER,
                        AuthenticatedMapContract.AUTH_APPROVAL);
        assertThat(decoded.governedGenesis()).isNotNull();
        assertThat(AuthenticatedMapContract.genesisId(decoded)).hasSize(32);
    }

    @Test
    void collectionPolicyBindingFailsClosed() {
        assertThatThrownBy(() -> collection(
                "bad-basic", AuthenticatedMapContract.AUTH_OPEN, "issuer-write"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("policy id");
        assertThatThrownBy(() -> collection(
                "bad-governed", AuthenticatedMapContract.AUTH_GOVERNED_ROLE, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("policy id");
        assertThatThrownBy(() -> new AuthenticatedMapContract.CollectionDescriptor(
                "unknown", 5, "", false, 64, 1_024,
                AuthenticatedMapContract.VALUE_ENCODING_OPAQUE, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authorization");
    }

    @Test
    void completeActionCommitmentBindsMixedCoverageAndClaimedKeySignature() {
        AuthenticatedMapContract.Genesis genesis = genesis();
        byte[] genesisId = AuthenticatedMapContract.genesisId(genesis);
        MapActionV1 action = mixedAction();
        byte[] commitment = AuthenticatedMapAuthorizationContract.actionCommitment(action);
        byte[] publicKey = KeyGenUtil.getPublicKeyFromPrivateKey(ACTOR_SEED);
        MapActorAuthorizationV1 direct = MapActorAuthorizationV1.sign(
                repeated(7), genesis.chainId(), genesisId, commitment,
                List.of(1), "issuer-write", 1,
                "admin-a", 1, "admin-a-key", publicKey,
                10, 50, ACTOR_SEED);
        MapApprovalReferenceV1 approval = new MapApprovalReferenceV1(
                "release-001", commitment, List.of(2), "release-policy", 1);
        AuthenticatedMapCommandV1 command = new AuthenticatedMapCommandV1(
                action, List.of(direct, approval));
        byte[] encoded = AuthenticatedMapAuthorizationContract.encodeCommand(command);
        AuthenticatedMapCommandV1 decoded =
                AuthenticatedMapAuthorizationContract.decodeCommand(encoded);

        assertThat(AuthenticatedMapAuthorizationContract.encodeCommand(decoded))
                .isEqualTo(encoded);
        assertThat(((MapActorAuthorizationV1) decoded.evidence().getFirst())
                .verifyClaimedKey()).isTrue();
        assertThat(decoded.cryptoWorkUnits()).isEqualTo(1);

        List<AuthenticatedMapContract.Mutation> changed = action.mutations();
        changed = List.of(changed.get(0),
                AuthenticatedMapContract.Mutation.put(
                        "issuer-keys", bytes("issuer-a"), bytes("changed")),
                changed.get(2));
        MapActionV1 substituted = new MapActionV1(true, changed, action.authorizations());
        assertThatThrownBy(() -> new AuthenticatedMapCommandV1(
                substituted, List.of(direct, approval)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("coverage");
    }

    @Test
    void coverageRejectsGapsOverlapUnusedEvidenceAndUnknownKinds() {
        MapActionV1 action = mixedAction();
        byte[] commitment = AuthenticatedMapAuthorizationContract.actionCommitment(action);
        MapApprovalReferenceV1 wrongCoverage = new MapApprovalReferenceV1(
                "release-001", commitment, List.of(1), "release-policy", 1);
        assertThatThrownBy(() -> new AuthenticatedMapCommandV1(
                action, List.of(wrongCoverage, wrongCoverage)))
                .isInstanceOf(IllegalArgumentException.class);

        Array unknown = new Array();
        unknown.add(new UnsignedInteger(1));
        unknown.add(new ByteString(AuthenticatedMapAuthorizationContract.encodeAction(action)));
        Array evidence = new Array();
        Array wrapped = new Array();
        wrapped.add(new UnsignedInteger(9));
        wrapped.add(new ByteString(new byte[]{(byte) 0x80}));
        evidence.add(wrapped);
        unknown.add(evidence);
        assertThatThrownBy(() -> AuthenticatedMapAuthorizationContract.decodeCommand(
                StdlibContractCbor.encode(unknown)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void directConsumptionIsActorNamespacedAndApprovalHasOneGlobalKey() {
        byte[] authorizationId = repeated(4);
        byte[] actorA = AuthenticatedMapContract.directConsumptionKey(
                "actor-a", authorizationId);
        byte[] actorB = AuthenticatedMapContract.directConsumptionKey(
                "actor-b", authorizationId);

        assertThat(actorA).isNotEqualTo(actorB);
        assertThat(AuthenticatedMapContract.directConsumptionKey(
                "actor-a", authorizationId)).isEqualTo(actorA);
        assertThat(AuthenticatedMapContract.approvalConsumptionKey("proposal-001"))
                .isEqualTo(AuthenticatedMapContract.approvalConsumptionKey("proposal-001"));

        DirectConsumptionV1 direct = new DirectConsumptionV1(
                "actor-a", authorizationId, repeated(5), 20, repeated(6),
                List.of(0, 2), "issuer-write", 1, 3,
                "operator-a", 2, "issuer", "actor-a-key",
                repeated(8), repeated(9));
        ApprovalConsumptionV1 approval = new ApprovalConsumptionV1(
                "proposal-001", repeated(5), 20, repeated(6),
                List.of(1), "release-policy", 4);
        assertThat(DirectConsumptionV1.decode(direct.encode()).encode())
                .isEqualTo(direct.encode());
        assertThat(ApprovalConsumptionV1.decode(approval.encode()).encode())
                .isEqualTo(approval.encode());
    }

    @Test
    void submittedMutationOrderIsPartOfTheCompleteActionCommitment() {
        MapActionV1 original = mixedAction();
        MapActionV1 reordered = new MapActionV1(true,
                List.of(original.mutations().get(2), original.mutations().get(1),
                        original.mutations().get(0)),
                List.of(
                        new AuthorizationAssignmentV1(
                                0, AuthenticatedMapContract.AUTH_APPROVAL,
                                "release-policy", 1),
                        new AuthorizationAssignmentV1(
                                1, AuthenticatedMapContract.AUTH_GOVERNED_ROLE,
                                "issuer-write", 2),
                        new AuthorizationAssignmentV1(
                                2, AuthenticatedMapContract.AUTH_OPEN, "", 0)));

        assertThat(AuthenticatedMapAuthorizationContract.actionCommitment(reordered))
                .isNotEqualTo(AuthenticatedMapAuthorizationContract.actionCommitment(original));
    }

    private static MapActionV1 mixedAction() {
        List<AuthenticatedMapContract.Mutation> mutations = List.of(
                AuthenticatedMapContract.Mutation.put(
                        "attachments", bytes("doc-1"), bytes("opaque")),
                AuthenticatedMapContract.Mutation.put(
                        "issuer-keys", bytes("issuer-a"), bytes("key")),
                AuthenticatedMapContract.Mutation.put(
                        "releases", bytes("release-1"), bytes("approved")));
        return new MapActionV1(true, mutations, List.of(
                new AuthorizationAssignmentV1(
                        0, AuthenticatedMapContract.AUTH_OPEN, "", 0),
                new AuthorizationAssignmentV1(
                        1, AuthenticatedMapContract.AUTH_GOVERNED_ROLE,
                        "issuer-write", 1),
                new AuthorizationAssignmentV1(
                        2, AuthenticatedMapContract.AUTH_APPROVAL,
                        "release-policy", 2)));
    }

    private static AuthenticatedMapContract.Genesis genesis() {
        String chainId = "authenticated-map-chain";
        OrganizationRecordV1 organization = new OrganizationRecordV1(
                "operator-a", 1, RecordStatus.ACTIVE, new byte[0]);
        ActorKeyEpochV1 key = new ActorKeyEpochV1(
                "admin-a-key", KeyGenUtil.getPublicKeyFromPrivateKey(ACTOR_SEED),
                1, 0, RecordStatus.ACTIVE);
        ActorRecordV1 actor = new ActorRecordV1(
                "admin-a", "operator-a", 1, RecordStatus.ACTIVE,
                List.of("issuer", "registry-admin"), List.of(key), new byte[0]);
        GenesisActorV1 genesisActor = new GenesisActorV1(actor,
                List.of(ActorKeyProofV1.sign(chainId, "admin-a", 1, key, ACTOR_SEED)));
        GovernedGenesisV1 governed = new GovernedGenesisV1(
                chainId,
                new AdministratorAuthorityV1(
                        "registry-admins", 1, List.of("admin-a"), 1, 1_000),
                List.of(organization), List.of(genesisActor),
                List.of(new DirectRolePolicyV1(
                        "issuer-write", 1, RecordStatus.ACTIVE, "issuer", 100)),
                List.of(new ApprovalPolicyV1(
                        "release-policy", 1, RecordStatus.ACTIVE, List.of("issuer"),
                        List.of(new ApprovalPolicyV1.RequiredClause(
                                "issuer", "issuer", 1,
                                ApprovalPolicyV1.DistinctBy.ACTOR)),
                        ApprovalPolicyV1.RejectionMode.ANY_ELIGIBLE, 1_000)),
                GovernedAuthorizationLimitsV1.defaults());
        return new AuthenticatedMapContract.Genesis(
                chainId, AuthenticatedMapContract.PROFILE_JMT_BLAKE2B256_V1,
                repeated(1), repeated(2), repeated(3), repeated(4),
                32, 65_536,
                List.of(
                        collection("products", AuthenticatedMapContract.AUTH_OWNER, ""),
                        collection("members", AuthenticatedMapContract.AUTH_MEMBER, ""),
                        collection("attachments", AuthenticatedMapContract.AUTH_OPEN, ""),
                        collection("issuer-keys",
                                AuthenticatedMapContract.AUTH_GOVERNED_ROLE,
                                "issuer-write"),
                        collection("releases", AuthenticatedMapContract.AUTH_APPROVAL,
                                "release-policy")),
                List.of(), List.of(), governed);
    }

    private static AuthenticatedMapContract.CollectionDescriptor collection(
            String id,
            int authorization,
            String policyId
    ) {
        return new AuthenticatedMapContract.CollectionDescriptor(
                id, authorization, policyId, false, 64, 16_384,
                AuthenticatedMapContract.VALUE_ENCODING_OPAQUE, "");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] repeated(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
