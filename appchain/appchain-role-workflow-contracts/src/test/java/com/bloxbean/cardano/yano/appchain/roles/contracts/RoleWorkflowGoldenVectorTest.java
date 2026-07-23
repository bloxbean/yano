package com.bloxbean.cardano.yano.appchain.roles.contracts;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoleWorkflowGoldenVectorTest {
    private static final HexFormat HEX = HexFormat.of();

    @Test
    void frozenActorAndProofVectorsAreCanonicalAndVerifiable() {
        Properties vectors = vectors();
        byte[] seed = HEX.parseHex(vectors.getProperty("seed"));
        byte[] publicKey = KeyGenUtil.getPublicKeyFromPrivateKey(seed);
        ActorKeyEpochV1 key = new ActorKeyEpochV1(
                "signing-1", publicKey, 1, 0, RecordStatus.ACTIVE);
        ActorStatementV1 statement = new ActorStatementV1(
                ActorStatementV1.Action.PROPOSE, "demo-chain", "evidence-001",
                "evidence-release", 1, "evidence.release.v1", repeated(0x11),
                100, "manufacturer-a", 1, "signing-1", "");
        SignedActorCommandV1 command = SignedActorCommandV1.sign(statement, seed);
        ActorKeyProofV1 proof = ActorKeyProofV1.sign(
                "demo-chain", "manufacturer-a", 1, key, seed);

        assertVector(vectors, "public-key", publicKey);
        assertVector(vectors, "statement.propose", statement.encode());
        assertVector(vectors, "statement.preimage", statement.signingPreimage());
        assertVector(vectors, "statement.signature", command.signature());
        assertVector(vectors, "command.propose", command.encode());
        assertVector(vectors, "key-proof", proof.encode());
        assertThat(command.verify(publicKey)).isTrue();
        assertThat(SignedActorCommandV1.decode(command.encode()).encode())
                .containsExactly(command.encode());
        assertThat(proof.verify()).isTrue();
        assertThat(ActorKeyProofV1.decode(proof.encode()).verify()).isTrue();
    }

    @Test
    void statementBindsEverySecurityDomain() {
        byte[] hash = repeated(0x22);
        ActorStatementV1 base = new ActorStatementV1(ActorStatementV1.Action.APPROVE,
                "chain-a", "proposal-a", "policy-a", 2, "document.release.v1",
                hash, 90, "actor-a", 3, "key-a", "auditors");
        byte[] digest = base.digest();
        assertThat(new ActorStatementV1(ActorStatementV1.Action.REJECT,
                "chain-a", "proposal-a", "policy-a", 2, "document.release.v1",
                hash, 90, "actor-a", 3, "key-a", "auditors").digest())
                .isNotEqualTo(digest);
        assertThat(new ActorStatementV1(ActorStatementV1.Action.APPROVE,
                "chain-b", "proposal-a", "policy-a", 2, "document.release.v1",
                hash, 90, "actor-a", 3, "key-a", "auditors").digest())
                .isNotEqualTo(digest);
        assertThat(new ActorStatementV1(ActorStatementV1.Action.APPROVE,
                "chain-a", "proposal-b", "policy-a", 2, "document.release.v1",
                hash, 90, "actor-a", 3, "key-a", "auditors").digest())
                .isNotEqualTo(digest);
        assertThat(new ActorStatementV1(ActorStatementV1.Action.APPROVE,
                "chain-a", "proposal-a", "policy-b", 2, "document.release.v1",
                hash, 90, "actor-a", 3, "key-a", "auditors").digest())
                .isNotEqualTo(digest);
        assertThat(new ActorStatementV1(ActorStatementV1.Action.APPROVE,
                "chain-a", "proposal-a", "policy-a", 2, "document.release.v1",
                repeated(0x23), 90, "actor-a", 3, "key-a", "auditors").digest())
                .isNotEqualTo(digest);
    }

    @Test
    void recordsPoliciesAndMutationsRoundTripAndRejectNonCanonicalForms() {
        OrganizationRecordV1 organization = new OrganizationRecordV1(
                "audit-org", 1, RecordStatus.ACTIVE, new byte[0]);
        byte[] publicKey = repeated(0x42);
        ActorRecordV1 actor = new ActorRecordV1("auditor-a", "audit-org", 1,
                RecordStatus.ACTIVE, List.of("auditor"),
                List.of(new ActorKeyEpochV1("key-1", publicKey, 1, 0,
                        RecordStatus.ACTIVE)), new byte[0]);
        ApprovalPolicyV1 policy = new ApprovalPolicyV1("evidence-release", 1,
                List.of("manufacturer"), List.of(
                new ApprovalPolicyV1.RequiredClause("auditors", "auditor", 2,
                        ApprovalPolicyV1.DistinctBy.ORGANIZATION)),
                ApprovalPolicyV1.RejectionMode.ANY_ELIGIBLE, 1000);

        assertThat(OrganizationRecordV1.decode(organization.encode()).encode())
                .containsExactly(organization.encode());
        assertThat(ActorRecordV1.decode(actor.encode()).encode()).containsExactly(actor.encode());
        assertThat(ApprovalPolicyV1.decode(policy.encode()).encode()).containsExactly(policy.encode());
        RegistryMutationV1 registry = new RegistryMutationV1.PutOrganization(organization);
        assertThat(RegistryMutationV1.decode(registry.encode()).encode())
                .containsExactly(registry.encode());
        PolicyMutationV1 policies = new PolicyMutationV1.PutPolicy(policy);
        assertThat(PolicyMutationV1.decode(policies.encode()).encode())
                .containsExactly(policies.encode());
        GovernedMutationCommandV1.Propose proposed = new GovernedMutationCommandV1.Propose(
                "put-audit-org", registry.encode(), 100);
        assertThat(GovernedMutationCommandV1.decode(proposed.encode()).encode())
                .containsExactly(proposed.encode());
        byte[] trailing = java.util.Arrays.copyOf(proposed.encode(), proposed.encode().length + 1);
        assertThatThrownBy(() -> GovernedMutationCommandV1.decode(trailing))
                .isInstanceOf(RoleWorkflowException.class);
        assertThatThrownBy(() -> GovernedMutationCommandV1.decode(
                HEX.parseHex("9f01006c7075742d61756469742d6f7267410001ff")))
                .isInstanceOf(RoleWorkflowException.class);
    }

    private static byte[] repeated(int value) {
        byte[] result = new byte[32];
        java.util.Arrays.fill(result, (byte) value);
        return result;
    }

    private static void assertVector(Properties vectors, String name, byte[] actual) {
        String hex = HEX.formatHex(actual);
        String expected = vectors.getProperty(name);
        assertThat(hex).as(name).isEqualTo(expected);
    }

    private static Properties vectors() {
        Properties result = new Properties();
        try (var input = RoleWorkflowGoldenVectorTest.class.getResourceAsStream(
                "/META-INF/yano/contracts/role-workflow/v1/golden-vectors.properties")) {
            if (input == null) throw new IllegalStateException("golden vectors are absent");
            result.load(input);
            return result;
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read golden vectors", failure);
        }
    }
}
