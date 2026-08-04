package com.bloxbean.cardano.yano.appchain.client;

import com.bloxbean.cardano.vds.jmt.JellyfishMerkleTree;
import com.bloxbean.cardano.vds.jmt.JmtProfile;
import com.bloxbean.cardano.vds.jmt.store.InMemoryJmtStore;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeCommitmentV1;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedMapProofBundleTest {
    private static final String CHAIN = "proof-bundle-chain";
    private static final String GENESIS = "33".repeat(32);

    @Test
    void verifiesSameRootExactBasicFactsAndRejectsSubstitution() {
        byte[] applicationKey = "sku-1".getBytes(StandardCharsets.US_ASCII);
        byte[] entryKey = CompositeCommitmentV1.componentKey(
                AuthenticatedMapContract.STATE_MACHINE_ID,
                AuthenticatedMapContract.canonicalKey("records", applicationKey));
        byte[] receiptKey = CompositeCommitmentV1.componentKey(
                AuthenticatedMapContract.STATE_MACHINE_ID,
                AuthenticatedMapContract.receiptKey(Hex.decode("44".repeat(32))));
        byte[] unrelatedKey = CompositeCommitmentV1.componentKey(
                AuthenticatedMapContract.STATE_MACHINE_ID,
                AuthenticatedMapContract.canonicalKey("records",
                        "sku-2".getBytes(StandardCharsets.US_ASCII)));
        byte[] value = "value".getBytes(StandardCharsets.US_ASCII);
        byte[] entry = AuthenticatedMapContract.encodeEntry(
                AuthenticatedMapContract.Entry.active(1, new byte[0], value, 1, 1));
        byte[] unrelatedEntry = AuthenticatedMapContract.encodeEntry(
                AuthenticatedMapContract.Entry.active(1, new byte[0],
                        "unrelated".getBytes(StandardCharsets.US_ASCII), 1, 1));
        var command = AuthenticatedMapContract.Command.single(
                AuthenticatedMapContract.Mutation.put("records", applicationKey, value));
        var action = AuthenticatedMapAuthorizationContract.MapActionV1.open(command);
        byte[] commitment = AuthenticatedMapAuthorizationContract.actionCommitment(action);
        byte[] receipt = AuthenticatedMapContract.encodeReceipt(
                AuthenticatedMapContract.Receipt.applied(Hex.decode("44".repeat(32)), 1,
                        commitment, List.of(new AuthenticatedMapContract.MutationResult(
                                "records", applicationKey,
                                AuthenticatedMapContract.STATUS_ACTIVE, 1,
                                AuthenticatedMapContract.logicalValueHash(value)))));

        InMemoryJmtStore store = new InMemoryJmtStore();
        JellyfishMerkleTree tree = new JellyfishMerkleTree(
                store, JmtProfile.classicBlake2b256V1());
        byte[] root = tree.put(1, Map.of(
                entryKey, entry, receiptKey, receipt, unrelatedKey, unrelatedEntry)).rootHash();
        AppChainClient.Proof entryProof = proof(tree, entryKey, entry, root);
        AppChainClient.Proof receiptProof = proof(tree, receiptKey, receipt, root);
        AppChainClient.Proof unrelatedProof = proof(
                tree, unrelatedKey, unrelatedEntry, root);
        var bundle = new AuthenticatedMapProofBundle(
                AuthenticatedMapProofBundle.Kind.BASIC, CHAIN,
                ProofVerifier.JMT_BLAKE2B256_V1, GENESIS, 1, Hex.encode(root),
                commitment, null, List.of(
                new AuthenticatedMapProofBundle.Fact(
                        AuthenticatedMapProofBundle.ENTRY, entryKey, entry, entryProof),
                new AuthenticatedMapProofBundle.Fact(
                        AuthenticatedMapProofBundle.RECEIPT,
                        receiptKey, receipt, receiptProof)));
        ProofVerifier.TrustedStateRoot trusted = new ProofVerifier.TrustedStateRoot(
                CHAIN, ProofVerifier.JMT_BLAKE2B256_V1, GENESIS, 1, Hex.encode(root),
                ProofVerifier.TrustedRootSource.CALLER_PINNED);

        assertThat(bundle.verify(trusted)).isTrue();
        assertThat(bundle.verify(new ProofVerifier.TrustedStateRoot(
                CHAIN, ProofVerifier.JMT_BLAKE2B256_V1, GENESIS, 1,
                "55".repeat(32), ProofVerifier.TrustedRootSource.CALLER_PINNED))).isFalse();
        var substituted = new AuthenticatedMapProofBundle(
                AuthenticatedMapProofBundle.Kind.BASIC, CHAIN,
                ProofVerifier.JMT_BLAKE2B256_V1, GENESIS, 1, Hex.encode(root),
                commitment, null, List.of(
                new AuthenticatedMapProofBundle.Fact(
                        AuthenticatedMapProofBundle.ENTRY, entryKey,
                        "other".getBytes(StandardCharsets.US_ASCII), entryProof),
                new AuthenticatedMapProofBundle.Fact(
                        AuthenticatedMapProofBundle.RECEIPT,
                        receiptKey, receipt, receiptProof)));
        assertThat(substituted.verify(trusted)).isFalse();
        var unrelatedEntryBundle = new AuthenticatedMapProofBundle(
                AuthenticatedMapProofBundle.Kind.BASIC, CHAIN,
                ProofVerifier.JMT_BLAKE2B256_V1, GENESIS, 1, Hex.encode(root),
                commitment, null, List.of(
                new AuthenticatedMapProofBundle.Fact(
                        AuthenticatedMapProofBundle.ENTRY, unrelatedKey,
                        unrelatedEntry, unrelatedProof),
                new AuthenticatedMapProofBundle.Fact(
                        AuthenticatedMapProofBundle.RECEIPT,
                        receiptKey, receipt, receiptProof)));
        assertThat(unrelatedEntryBundle.verify(trusted)).isFalse();
    }

    @Test
    void directAndApprovalBundlesRequireCurrentPointerFacts() {
        AppChainClient.Proof placeholder = new AppChainClient.Proof(
                "01", CHAIN, "11".repeat(32), "80", "01", null, 1L);
        assertThatThrownBy(() -> new AuthenticatedMapProofBundle(
                AuthenticatedMapProofBundle.Kind.DIRECT_ROLE, CHAIN,
                ProofVerifier.MPF_BLAKE2B256_V1, GENESIS, 1, "11".repeat(32),
                Hex.decode("22".repeat(32)), new byte[]{1}, List.of(
                new AuthenticatedMapProofBundle.Fact(
                        AuthenticatedMapProofBundle.ENTRY, new byte[]{1},
                        new byte[]{1}, placeholder),
                new AuthenticatedMapProofBundle.Fact(
                        AuthenticatedMapProofBundle.RECEIPT, new byte[]{1},
                        new byte[]{1}, placeholder))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing required DIRECT_ROLE facts");
    }

    @Test
    void verifiesDirectRoleBundleAndRejectsWrongGenesisAndStalePointer() throws Exception {
        byte[] seed = new byte[32];
        java.util.Arrays.fill(seed, (byte) 0x5a);
        byte[] publicKey = com.bloxbean.cardano.client.crypto.KeyGenUtil
                .getPublicKeyFromPrivateKey(seed);
        byte[] applicationKey = "sku-9".getBytes(StandardCharsets.US_ASCII);
        byte[] value = "governed".getBytes(StandardCharsets.US_ASCII);
        byte[] messageId = Hex.decode("46".repeat(32));
        byte[] authorizationId = Hex.decode("47".repeat(32));

        var command = AuthenticatedMapContract.Command.single(
                AuthenticatedMapContract.Mutation.put(
                        "governed-products", applicationKey, value));
        var action = new AuthenticatedMapAuthorizationContract.MapActionV1(
                false, command.mutations(), List.of(
                new AuthenticatedMapAuthorizationContract.AuthorizationAssignmentV1(
                        0, AuthenticatedMapContract.AUTH_GOVERNED_ROLE,
                        "issuer-write", 1)));
        byte[] commitment = AuthenticatedMapAuthorizationContract.actionCommitment(action);
        var authorization = AuthenticatedMapAuthorizationContract.MapActorAuthorizationV1
                .sign(authorizationId, CHAIN, Hex.decode(GENESIS), commitment,
                        List.of(0), "issuer-write", 1, "issuer-a", 1,
                        "issuer-a-k1", publicKey, 1, 20, seed);
        byte[] signatureDigest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(authorization.signature());
        var consumption = new AuthenticatedMapAuthorizationContract.DirectConsumptionV1(
                "issuer-a", authorizationId, commitment, 1, messageId, List.of(0),
                "issuer-write", 1, 1, "acme", 1, "issuer", "issuer-a-k1",
                authorization.statementDigest(), signatureDigest);
        var policy = new com.bloxbean.cardano.yano.appchain.roles.contracts.DirectRolePolicyV1(
                "issuer-write", 1,
                com.bloxbean.cardano.yano.appchain.roles.contracts.RecordStatus.ACTIVE,
                "issuer", 100);
        var actorKey = new com.bloxbean.cardano.yano.appchain.roles.contracts.ActorKeyEpochV1(
                "issuer-a-k1", publicKey, 1, 0,
                com.bloxbean.cardano.yano.appchain.roles.contracts.RecordStatus.ACTIVE);
        var actor = new com.bloxbean.cardano.yano.appchain.roles.contracts.ActorRecordV1(
                "issuer-a", "acme", 1,
                com.bloxbean.cardano.yano.appchain.roles.contracts.RecordStatus.ACTIVE,
                List.of("issuer"), List.of(actorKey), new byte[0]);
        var organization = new com.bloxbean.cardano.yano.appchain.roles.contracts
                .OrganizationRecordV1("acme", 1,
                com.bloxbean.cardano.yano.appchain.roles.contracts.RecordStatus.ACTIVE,
                new byte[0]);
        byte[] entry = AuthenticatedMapContract.encodeEntry(
                AuthenticatedMapContract.Entry.active(1, new byte[0], value, 1, 1));
        byte[] receipt = AuthenticatedMapContract.encodeReceipt(
                AuthenticatedMapContract.Receipt.applied(messageId, 1, commitment,
                        List.of(new AuthenticatedMapContract.MutationResult(
                                "governed-products", applicationKey,
                                AuthenticatedMapContract.STATUS_ACTIVE, 1,
                                AuthenticatedMapContract.logicalValueHash(value)))));
        byte[] revisionOne = java.nio.ByteBuffer.allocate(Long.BYTES).putLong(1).array();
        byte[] revisionTwo = java.nio.ByteBuffer.allocate(Long.BYTES).putLong(2).array();

        String map = AuthenticatedMapContract.STATE_MACHINE_ID;
        String approvals = com.bloxbean.cardano.yano.appchain.roles.contracts
                .RoleWorkflowIdentifiers.ROLE_APPROVALS_COMPONENT_ID;
        String actors = com.bloxbean.cardano.yano.appchain.roles.contracts
                .RoleWorkflowIdentifiers.DOMAIN_ACTORS_COMPONENT_ID;
        var keys = com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys.class;
        byte[] entryKey = CompositeCommitmentV1.componentKey(map,
                AuthenticatedMapContract.canonicalKey("governed-products", applicationKey));
        byte[] receiptKey = CompositeCommitmentV1.componentKey(map,
                AuthenticatedMapContract.receiptKey(messageId));
        byte[] consumptionKey = CompositeCommitmentV1.componentKey(map,
                AuthenticatedMapContract.directConsumptionKey("issuer-a", authorizationId));
        byte[] policyKey = CompositeCommitmentV1.componentKey(approvals,
                com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys
                        .directPolicyRevision("issuer-write", 1));
        byte[] policyPointerKey = CompositeCommitmentV1.componentKey(approvals,
                com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys
                        .directPolicyCurrent("issuer-write"));
        byte[] actorKeyBytes = CompositeCommitmentV1.componentKey(actors,
                com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys
                        .actorRevision("issuer-a", 1));
        byte[] actorPointerKey = CompositeCommitmentV1.componentKey(actors,
                com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys
                        .actorCurrent("issuer-a"));
        byte[] organizationKey = CompositeCommitmentV1.componentKey(actors,
                com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys
                        .organizationRevision("acme", 1));
        assertThat(keys).isNotNull();

        InMemoryJmtStore store = new InMemoryJmtStore();
        JellyfishMerkleTree tree = new JellyfishMerkleTree(
                store, JmtProfile.classicBlake2b256V1());
        java.util.Map<byte[], byte[]> leaves = new java.util.LinkedHashMap<>();
        leaves.put(entryKey, entry);
        leaves.put(receiptKey, receipt);
        leaves.put(consumptionKey, consumption.encode());
        leaves.put(policyKey, policy.encode());
        leaves.put(policyPointerKey, revisionOne);
        leaves.put(actorKeyBytes, actor.encode());
        leaves.put(actorPointerKey, revisionOne);
        leaves.put(organizationKey, organization.encode());
        byte[] root = tree.put(1, leaves).rootHash();

        java.util.function.BiFunction<String, byte[][], AuthenticatedMapProofBundle.Fact>
                fact = (name, pair) -> new AuthenticatedMapProofBundle.Fact(
                name, pair[0], pair[1], proof(tree, pair[0], pair[1], root));
        var bundle = new AuthenticatedMapProofBundle(
                AuthenticatedMapProofBundle.Kind.DIRECT_ROLE, CHAIN,
                ProofVerifier.JMT_BLAKE2B256_V1, GENESIS, 1, Hex.encode(root),
                commitment, authorization.encode(), List.of(
                fact.apply(AuthenticatedMapProofBundle.ENTRY,
                        new byte[][]{entryKey, entry}),
                fact.apply(AuthenticatedMapProofBundle.RECEIPT,
                        new byte[][]{receiptKey, receipt}),
                fact.apply(AuthenticatedMapProofBundle.DIRECT_CONSUMPTION,
                        new byte[][]{consumptionKey, consumption.encode()}),
                fact.apply(AuthenticatedMapProofBundle.DIRECT_POLICY,
                        new byte[][]{policyKey, policy.encode()}),
                fact.apply(AuthenticatedMapProofBundle.DIRECT_POLICY_CURRENT,
                        new byte[][]{policyPointerKey, revisionOne}),
                fact.apply(AuthenticatedMapProofBundle.ACTOR,
                        new byte[][]{actorKeyBytes, actor.encode()}),
                fact.apply(AuthenticatedMapProofBundle.ACTOR_CURRENT,
                        new byte[][]{actorPointerKey, revisionOne}),
                fact.apply(AuthenticatedMapProofBundle.ORGANIZATION,
                        new byte[][]{organizationKey, organization.encode()})));
        ProofVerifier.TrustedStateRoot trusted = new ProofVerifier.TrustedStateRoot(
                CHAIN, ProofVerifier.JMT_BLAKE2B256_V1, GENESIS, 1, Hex.encode(root),
                ProofVerifier.TrustedRootSource.CALLER_PINNED);
        assertThat(bundle.verify(trusted)).isTrue();

        // Wrong-genesis assemblies must be rejected even with valid proofs.
        assertThat(bundle.verify(new ProofVerifier.TrustedStateRoot(
                CHAIN, ProofVerifier.JMT_BLAKE2B256_V1, "77".repeat(32), 1,
                Hex.encode(root), ProofVerifier.TrustedRootSource.CALLER_PINNED)))
                .isFalse();

        // A proven-but-stale current pointer breaks the historical-currency claim.
        InMemoryJmtStore staleStore = new InMemoryJmtStore();
        JellyfishMerkleTree staleTree = new JellyfishMerkleTree(
                staleStore, JmtProfile.classicBlake2b256V1());
        leaves.put(policyPointerKey, revisionTwo);
        byte[] staleRoot = staleTree.put(1, leaves).rootHash();
        java.util.function.BiFunction<String, byte[][], AuthenticatedMapProofBundle.Fact>
                staleFact = (name, pair) -> new AuthenticatedMapProofBundle.Fact(
                name, pair[0], pair[1], proof(staleTree, pair[0], pair[1], staleRoot));
        var stale = new AuthenticatedMapProofBundle(
                AuthenticatedMapProofBundle.Kind.DIRECT_ROLE, CHAIN,
                ProofVerifier.JMT_BLAKE2B256_V1, GENESIS, 1, Hex.encode(staleRoot),
                commitment, authorization.encode(), List.of(
                staleFact.apply(AuthenticatedMapProofBundle.ENTRY,
                        new byte[][]{entryKey, entry}),
                staleFact.apply(AuthenticatedMapProofBundle.RECEIPT,
                        new byte[][]{receiptKey, receipt}),
                staleFact.apply(AuthenticatedMapProofBundle.DIRECT_CONSUMPTION,
                        new byte[][]{consumptionKey, consumption.encode()}),
                staleFact.apply(AuthenticatedMapProofBundle.DIRECT_POLICY,
                        new byte[][]{policyKey, policy.encode()}),
                staleFact.apply(AuthenticatedMapProofBundle.DIRECT_POLICY_CURRENT,
                        new byte[][]{policyPointerKey, revisionTwo}),
                staleFact.apply(AuthenticatedMapProofBundle.ACTOR,
                        new byte[][]{actorKeyBytes, actor.encode()}),
                staleFact.apply(AuthenticatedMapProofBundle.ACTOR_CURRENT,
                        new byte[][]{actorPointerKey, revisionOne}),
                staleFact.apply(AuthenticatedMapProofBundle.ORGANIZATION,
                        new byte[][]{organizationKey, organization.encode()})));
        assertThat(stale.verify(new ProofVerifier.TrustedStateRoot(
                CHAIN, ProofVerifier.JMT_BLAKE2B256_V1, GENESIS, 1,
                Hex.encode(staleRoot), ProofVerifier.TrustedRootSource.CALLER_PINNED)))
                .isFalse();
    }

    private static AppChainClient.Proof proof(
            JellyfishMerkleTree tree, byte[] key, byte[] value, byte[] root) {
        ProofVerifier.ProfileMetadata metadata = ProofVerifier.profileMetadata(
                ProofVerifier.JMT_BLAKE2B256_V1).orElseThrow();
        return new AppChainClient.Proof(
                Hex.encode(key), CHAIN, Hex.encode(root),
                Hex.encode(tree.getProofWire(key, 1).orElseThrow()), Hex.encode(value),
                null, 1L, 1, metadata.id(), metadata.backend(),
                metadata.dependencyDescriptor(), metadata.formatFingerprintHex(),
                GENESIS, false, metadata.nativeProofEncoding(),
                metadata.nativeVersioning(), metadata.physicalDelete(), 1L,
                AppChainClient.ProofPresence.PRESENT, null, null);
    }
}
