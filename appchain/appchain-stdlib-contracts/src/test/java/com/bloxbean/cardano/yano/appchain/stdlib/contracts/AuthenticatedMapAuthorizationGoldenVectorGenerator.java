package com.bloxbean.cardano.yano.appchain.stdlib.contracts;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorGovernanceCommandV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorKeyEpochV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorKeyProofV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.AdministratorAuthorityV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.AdministratorStatementV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalPolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.DirectRolePolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GenesisActorV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedAuthorizationLimitsV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedGenesisV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.OrganizationRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RecordStatus;
import com.bloxbean.cardano.yano.appchain.roles.contracts.SignedAdministratorStatementV1;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapAuthorizationContract.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Reproducible cross-language vectors for the final ADR-025.2 v1 contract. */
public final class AuthenticatedMapAuthorizationGoldenVectorGenerator {
    private static final HexFormat HEX = HexFormat.of();
    private static final byte[] SEED = HEX.parseHex(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    private static final String CHAIN_ID = "authenticated-map-chain";

    private AuthenticatedMapAuthorizationGoldenVectorGenerator() {
    }

    public static void main(String[] arguments) {
        vectors().forEach((key, value) -> System.out.println(key + "=" + value));
    }

    static Map<String, String> vectors() {
        Map<String, String> vectors = new TreeMap<>();
        byte[] publicKey = KeyGenUtil.getPublicKeyFromPrivateKey(SEED);
        ActorKeyEpochV1 key = new ActorKeyEpochV1(
                "admin-a-key", publicKey, 1, 0, RecordStatus.ACTIVE);
        ActorKeyProofV1 proof = ActorKeyProofV1.sign(
                CHAIN_ID, "admin-a", 1, key, SEED);
        OrganizationRecordV1 organization = new OrganizationRecordV1(
                "operator-a", 1, RecordStatus.ACTIVE, new byte[0]);
        ActorRecordV1 actor = new ActorRecordV1(
                "admin-a", "operator-a", 1, RecordStatus.ACTIVE,
                List.of("issuer", "registry-admin"), List.of(key), new byte[0]);
        AdministratorAuthorityV1 authority = new AdministratorAuthorityV1(
                "registry-admins", 1, List.of("admin-a"), 1, 1_000);
        DirectRolePolicyV1 directPolicy = new DirectRolePolicyV1(
                "issuer-write", 1, RecordStatus.ACTIVE, "issuer", 100);
        ApprovalPolicyV1 approvalPolicy = new ApprovalPolicyV1(
                "release-policy", 1, RecordStatus.ACTIVE, List.of("issuer"),
                List.of(new ApprovalPolicyV1.RequiredClause(
                        "issuer", "issuer", 1, ApprovalPolicyV1.DistinctBy.ACTOR)),
                ApprovalPolicyV1.RejectionMode.ANY_ELIGIBLE, 1_000);
        GovernedAuthorizationLimitsV1 limits = GovernedAuthorizationLimitsV1.defaults();
        GovernedGenesisV1 closure = new GovernedGenesisV1(
                CHAIN_ID, authority, List.of(organization),
                List.of(new GenesisActorV1(actor, List.of(proof))),
                List.of(directPolicy), List.of(approvalPolicy), limits);
        AuthenticatedMapContract.Genesis genesis = new AuthenticatedMapContract.Genesis(
                CHAIN_ID, StateCommitmentProfiles.CLASSIC_JMT.id(),
                StateCommitmentProfiles.CLASSIC_JMT.formatFingerprint(),
                repeated(0x21), repeated(0x22), repeated(0x23),
                32, 65_536,
                List.of(
                        collection("attachments", AuthenticatedMapContract.AUTH_OPEN, ""),
                        collection("issuer-keys",
                                AuthenticatedMapContract.AUTH_GOVERNED_ROLE,
                                "issuer-write"),
                        collection("releases", AuthenticatedMapContract.AUTH_APPROVAL,
                                "release-policy")),
                List.of(), List.of(), closure);
        byte[] genesisId = AuthenticatedMapContract.genesisId(genesis);

        MapActionV1 action = new MapActionV1(true, List.of(
                AuthenticatedMapContract.Mutation.put(
                        "attachments", ascii("doc-1"), ascii("opaque")),
                AuthenticatedMapContract.Mutation.put(
                        "issuer-keys", ascii("issuer-a"), ascii("key-v1")),
                AuthenticatedMapContract.Mutation.put(
                        "releases", ascii("release-1"), ascii("approved"))),
                List.of(
                        new AuthorizationAssignmentV1(
                                0, AuthenticatedMapContract.AUTH_OPEN, "", 0),
                        new AuthorizationAssignmentV1(
                                1, AuthenticatedMapContract.AUTH_GOVERNED_ROLE,
                                "issuer-write", 1),
                        new AuthorizationAssignmentV1(
                                2, AuthenticatedMapContract.AUTH_APPROVAL,
                                "release-policy", 2)));
        byte[] actionCommitment =
                AuthenticatedMapAuthorizationContract.actionCommitment(action);
        MapActorAuthorizationV1 actorAuthorization = MapActorAuthorizationV1.sign(
                repeated(0xa1), CHAIN_ID, genesisId, actionCommitment,
                List.of(1), "issuer-write", 1, "admin-a", 1,
                "admin-a-key", publicKey, 10, 50, SEED);
        MapApprovalReferenceV1 approval = new MapApprovalReferenceV1(
                "release-001", actionCommitment, List.of(2), "release-policy", 1);
        AuthenticatedMapCommandV1 command = new AuthenticatedMapCommandV1(
                action, List.of(actorAuthorization, approval));

        DirectConsumptionV1 directConsumption = new DirectConsumptionV1(
                "admin-a", actorAuthorization.authorizationId(), actionCommitment,
                20, repeated(0x51), List.of(1), "issuer-write", 1, 1,
                "operator-a", 1, "issuer", "admin-a-key",
                actorAuthorization.statementDigest(), sha256(actorAuthorization.signature()));
        ApprovalConsumptionV1 approvalConsumption = new ApprovalConsumptionV1(
                "release-001", actionCommitment, 20, repeated(0x51),
                List.of(2), "release-policy", 1);

        byte[] mutation = ascii("actor-revision-2");
        byte[] mutationHash = ActorGovernanceCommandV1.mutationHash(mutation);
        AdministratorStatementV1 administratorStatement = new AdministratorStatementV1(
                AdministratorStatementV1.Decision.PROPOSE,
                CHAIN_ID, genesisId, "registry-admins", 1,
                "mutation-001", mutationHash, 20, 100,
                "admin-a", 1, "admin-a-key", publicKey,
                10, 80, AdministratorStatementV1.ED25519);
        SignedAdministratorStatementV1 signedAdministrator =
                SignedAdministratorStatementV1.sign(administratorStatement, SEED);
        ActorGovernanceCommandV1 governanceCommand = new ActorGovernanceCommandV1(
                ActorGovernanceCommandV1.Operation.PROPOSE,
                "mutation-001", mutation, List.of(signedAdministrator));

        vectors.put("schema.version", "1");
        vectors.put("seed", hex(SEED));
        vectors.put("public-key", hex(publicKey));
        vectors.put("genesis.cbor", hex(AuthenticatedMapContract.encodeGenesis(genesis)));
        vectors.put("genesis.id", hex(genesisId));
        vectors.put("genesis.governed-closure", hex(closure.encode()));
        vectors.put("genesis.key-proof", hex(proof.encode()));
        vectors.put("policy.direct", hex(directPolicy.encode()));
        vectors.put("policy.approval", hex(approvalPolicy.encode()));
        vectors.put("authority", hex(authority.encode()));
        vectors.put("limits", hex(limits.encode()));
        vectors.put("action.cbor", hex(AuthenticatedMapAuthorizationContract.encodeAction(action)));
        vectors.put("action.commitment", hex(actionCommitment));
        vectors.put("approval.payload-hash", hex(
                AuthenticatedMapAuthorizationContract.approvalPayloadHash(
                        genesisId, actionCommitment)));
        vectors.put("actor.statement", hex(actorAuthorization.unsignedStatement()));
        vectors.put("actor.preimage", hex(actorAuthorization.signingPreimage()));
        vectors.put("actor.signature", hex(actorAuthorization.signature()));
        vectors.put("actor.evidence", hex(actorAuthorization.encode()));
        vectors.put("approval.reference", hex(approval.encode()));
        vectors.put("command.cbor", hex(AuthenticatedMapAuthorizationContract.encodeCommand(command)));
        vectors.put("consumption.direct.key.actor-a", hex(
                AuthenticatedMapContract.directConsumptionKey(
                        "admin-a", actorAuthorization.authorizationId())));
        vectors.put("consumption.direct.key.actor-b", hex(
                AuthenticatedMapContract.directConsumptionKey(
                        "admin-b", actorAuthorization.authorizationId())));
        vectors.put("consumption.approval.key", hex(
                AuthenticatedMapContract.approvalConsumptionKey("release-001")));
        vectors.put("consumption.direct.value", hex(directConsumption.encode()));
        vectors.put("consumption.approval.value", hex(approvalConsumption.encode()));
        vectors.put("administrator.statement", hex(administratorStatement.encode()));
        vectors.put("administrator.preimage", hex(administratorStatement.signingPreimage()));
        vectors.put("administrator.signature", hex(signedAdministrator.signature()));
        vectors.put("administrator.command", hex(governanceCommand.encode()));
        return vectors;
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

    private static byte[] repeated(int value) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) value);
        return result;
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String hex(byte[] value) { return HEX.formatHex(value); }
}
