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

    @Test
    void verifiesApprovalBundleAndRederivesClauseSatisfaction() throws Exception {
        byte[] proposerSeed = seed(0x71);
        byte[] auditorASeed = seed(0x72);
        byte[] auditorBSeed = seed(0x73);
        byte[] applicationKey = "release-1".getBytes(StandardCharsets.US_ASCII);
        byte[] value = "released".getBytes(StandardCharsets.US_ASCII);
        byte[] messageId = Hex.decode("48".repeat(32));

        var command = AuthenticatedMapContract.Command.single(
                AuthenticatedMapContract.Mutation.put(
                        "released-products", applicationKey, value));
        var action = new AuthenticatedMapAuthorizationContract.MapActionV1(
                false, command.mutations(), List.of(
                new AuthenticatedMapAuthorizationContract.AuthorizationAssignmentV1(
                        0, AuthenticatedMapContract.AUTH_APPROVAL,
                        "product-release", 1)));
        byte[] commitment = AuthenticatedMapAuthorizationContract.actionCommitment(action);
        byte[] payloadHash = AuthenticatedMapAuthorizationContract.approvalPayloadHash(
                Hex.decode(GENESIS), commitment);

        var policy = new com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalPolicyV1(
                "product-release", 1,
                com.bloxbean.cardano.yano.appchain.roles.contracts.RecordStatus.ACTIVE,
                List.of("issuer"), List.of(
                new com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalPolicyV1
                        .RequiredClause("independent-auditors", "auditor", 2,
                        com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalPolicyV1
                                .DistinctBy.ORGANIZATION)),
                com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalPolicyV1
                        .RejectionMode.ANY_ELIGIBLE, 600);
        var decisionA = decision("auditor-a", "auditor-guild-a", auditorASeed, payloadHash);
        var decisionB = decision("auditor-b", "auditor-guild-b", auditorBSeed, payloadHash);
        var proposal = new com.bloxbean.cardano.yano.appchain.roles.contracts
                .ApprovalProposalV1("release-1", "product-release", 1, policy.digest(),
                AuthenticatedMapAuthorizationContract.APPROVAL_PAYLOAD_DOMAIN, payloadHash,
                20, com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalProposalV1
                .ProposalStatus.APPROVED, "issuer-a", "acme", 1, "issuer", 1,
                "issuer-a-k1", 1, List.of(decisionA, decisionB));
        var consumption = new AuthenticatedMapAuthorizationContract.ApprovalConsumptionV1(
                "release-1", commitment, 1, messageId, List.of(0), "product-release", 1);
        byte[] entry = AuthenticatedMapContract.encodeEntry(
                AuthenticatedMapContract.Entry.active(1, new byte[0], value, 1, 1));
        byte[] receipt = AuthenticatedMapContract.encodeReceipt(
                AuthenticatedMapContract.Receipt.applied(messageId, 1, commitment,
                        List.of(new AuthenticatedMapContract.MutationResult(
                                "released-products", applicationKey,
                                AuthenticatedMapContract.STATUS_ACTIVE, 1,
                                AuthenticatedMapContract.logicalValueHash(value)))));

        var bundle = approvalBundle(proposal, consumption, policy, entry, receipt,
                applicationKey, messageId, commitment,
                Map.of("issuer-a", proposerSeed, "auditor-a", auditorASeed,
                        "auditor-b", auditorBSeed),
                Map.of("issuer-a", "acme", "auditor-a", "auditor-guild-a",
                        "auditor-b", "auditor-guild-b"));
        assertThat(bundle.bundle().verify(bundle.trusted())).isTrue();

        // A fabricated APPROVED proposal without enough distinct-organization
        // approvals must fail: the client re-derives clause satisfaction.
        var underApproved = new com.bloxbean.cardano.yano.appchain.roles.contracts
                .ApprovalProposalV1("release-1", "product-release", 1, policy.digest(),
                AuthenticatedMapAuthorizationContract.APPROVAL_PAYLOAD_DOMAIN, payloadHash,
                20, com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalProposalV1
                .ProposalStatus.APPROVED, "issuer-a", "acme", 1, "issuer", 1,
                "issuer-a-k1", 1, List.of(decisionA));
        var rejected = approvalBundle(underApproved, consumption, policy, entry, receipt,
                applicationKey, messageId, commitment,
                Map.of("issuer-a", proposerSeed, "auditor-a", auditorASeed,
                        "auditor-b", auditorBSeed),
                Map.of("issuer-a", "acme", "auditor-a", "auditor-guild-a",
                        "auditor-b", "auditor-guild-b"));
        assertThat(rejected.bundle().verify(rejected.trusted())).isFalse();
    }

    @Test
    void verifiesAdministratorGovernanceBundleAndRederivesThreshold() {
        byte[] adminSeed = seed(0x74);
        byte[] publicKey = com.bloxbean.cardano.client.crypto.KeyGenUtil
                .getPublicKeyFromPrivateKey(adminSeed);
        byte[] messageId = Hex.decode("49".repeat(32));
        byte[] mutationBytes = "registry-mutation".getBytes(StandardCharsets.US_ASCII);
        byte[] mutationHash = com.bloxbean.cardano.yano.appchain.roles.contracts
                .ActorGovernanceCommandV1.mutationHash(mutationBytes);

        var actorKey = new com.bloxbean.cardano.yano.appchain.roles.contracts.ActorKeyEpochV1(
                "admin-a-k1", publicKey, 1, 0,
                com.bloxbean.cardano.yano.appchain.roles.contracts.RecordStatus.ACTIVE);
        var actor = new com.bloxbean.cardano.yano.appchain.roles.contracts.ActorRecordV1(
                "admin-a", "acme", 1,
                com.bloxbean.cardano.yano.appchain.roles.contracts.RecordStatus.ACTIVE,
                List.of("registry-admin"), List.of(actorKey), new byte[0]);
        var organization = new com.bloxbean.cardano.yano.appchain.roles.contracts
                .OrganizationRecordV1("acme", 1,
                com.bloxbean.cardano.yano.appchain.roles.contracts.RecordStatus.ACTIVE,
                new byte[0]);
        var authority = new com.bloxbean.cardano.yano.appchain.roles.contracts
                .AdministratorAuthorityV1("registry-admins", 1, List.of("admin-a"), 1, 1_000);
        var statement = new com.bloxbean.cardano.yano.appchain.roles.contracts
                .AdministratorStatementV1(
                com.bloxbean.cardano.yano.appchain.roles.contracts.AdministratorStatementV1
                        .Decision.PROPOSE,
                CHAIN, Hex.decode(GENESIS), "registry-admins", 1, "mutation-1",
                mutationHash, 1, 30, "admin-a", 1, "admin-a-k1", publicKey, 1, 20,
                com.bloxbean.cardano.yano.appchain.roles.contracts.AdministratorStatementV1
                        .ED25519);
        var vote = new com.bloxbean.cardano.yano.appchain.roles.contracts
                .AcceptedAdministratorVoteV1(
                com.bloxbean.cardano.yano.appchain.roles.contracts
                        .SignedAdministratorStatementV1.sign(statement, adminSeed),
                "acme", 1, 1);
        var mutation = new com.bloxbean.cardano.yano.appchain.roles.contracts
                .GovernedMutationRecordV1("mutation-1", mutationBytes, mutationHash,
                "registry-admins", 1, authority.digest(), 1, 30, "admin-a", 1,
                List.of(vote), List.of(),
                com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedMutationRecordV1
                        .Status.ACTIVATED, 1);
        var result = new com.bloxbean.cardano.yano.appchain.roles.contracts
                .RoleCommandResultV1(
                com.bloxbean.cardano.yano.appchain.roles.contracts.RoleCommandResultV1
                        .KIND_REGISTRY_GOVERNANCE,
                "mutation-1",
                com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowResultCode
                        .ACCEPTED, 1, messageId, Hex.decode("50".repeat(32)));

        var assembled = governanceBundle(result, mutation, authority, actor, organization,
                messageId);
        assertThat(assembled.bundle().verify(assembled.trusted())).isTrue();

        // A fabricated ACTIVATED mutation below the authority threshold must
        // fail: the client re-derives the distinct-administrator threshold.
        var widerAuthority = new com.bloxbean.cardano.yano.appchain.roles.contracts
                .AdministratorAuthorityV1("registry-admins", 1,
                List.of("admin-a", "admin-b"), 2, 1_000);
        var underThreshold = new com.bloxbean.cardano.yano.appchain.roles.contracts
                .GovernedMutationRecordV1("mutation-1", mutationBytes, mutationHash,
                "registry-admins", 1, widerAuthority.digest(), 1, 30, "admin-a", 1,
                List.of(vote), List.of(),
                com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedMutationRecordV1
                        .Status.ACTIVATED, 1);
        var rejected = governanceBundle(result, underThreshold, widerAuthority, actor,
                organization, messageId);
        assertThat(rejected.bundle().verify(rejected.trusted())).isFalse();
    }

    private static AssembledBundle governanceBundle(
            com.bloxbean.cardano.yano.appchain.roles.contracts.RoleCommandResultV1 result,
            com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedMutationRecordV1
                    mutation,
            com.bloxbean.cardano.yano.appchain.roles.contracts.AdministratorAuthorityV1
                    authority,
            com.bloxbean.cardano.yano.appchain.roles.contracts.ActorRecordV1 actor,
            com.bloxbean.cardano.yano.appchain.roles.contracts.OrganizationRecordV1
                    organization,
            byte[] messageId
    ) {
        String actors = com.bloxbean.cardano.yano.appchain.roles.contracts
                .RoleWorkflowIdentifiers.DOMAIN_ACTORS_COMPONENT_ID;
        byte[] revisionOne = java.nio.ByteBuffer.allocate(Long.BYTES).putLong(1).array();
        java.util.Map<String, byte[][]> leaves = new java.util.LinkedHashMap<>();
        leaves.put(AuthenticatedMapProofBundle.GOVERNANCE_RESULT, new byte[][]{
                CompositeCommitmentV1.componentKey(actors,
                        com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys
                                .commandResult(messageId)), result.encode()});
        leaves.put(AuthenticatedMapProofBundle.GOVERNANCE_MUTATION, new byte[][]{
                CompositeCommitmentV1.componentKey(actors,
                        com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys
                                .governedMutation(mutation.mutationId())),
                mutation.encode()});
        leaves.put(AuthenticatedMapProofBundle.AUTHORITY, new byte[][]{
                CompositeCommitmentV1.componentKey(actors,
                        com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys
                                .authorityRevision(authority.authorityId(), 1)),
                authority.encode()});
        leaves.put(AuthenticatedMapProofBundle.AUTHORITY_CURRENT, new byte[][]{
                CompositeCommitmentV1.componentKey(actors,
                        com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys
                                .authorityCurrent(authority.authorityId())), revisionOne});
        leaves.put(AuthenticatedMapProofBundle.ACTOR, new byte[][]{
                CompositeCommitmentV1.componentKey(actors,
                        com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys
                                .actorRevision(actor.actorId(), 1)), actor.encode()});
        leaves.put(AuthenticatedMapProofBundle.ACTOR_CURRENT, new byte[][]{
                CompositeCommitmentV1.componentKey(actors,
                        com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys
                                .actorCurrent(actor.actorId())), revisionOne});
        leaves.put(AuthenticatedMapProofBundle.ORGANIZATION, new byte[][]{
                CompositeCommitmentV1.componentKey(actors,
                        com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys
                                .organizationRevision(organization.organizationId(), 1)),
                organization.encode()});
        InMemoryJmtStore store = new InMemoryJmtStore();
        JellyfishMerkleTree tree = new JellyfishMerkleTree(
                store, JmtProfile.classicBlake2b256V1());
        java.util.Map<byte[], byte[]> byKey = new java.util.LinkedHashMap<>();
        leaves.values().forEach(pair -> byKey.put(pair[0], pair[1]));
        byte[] root = tree.put(1, byKey).rootHash();
        List<AuthenticatedMapProofBundle.Fact> facts = leaves.entrySet().stream()
                .map(fact -> new AuthenticatedMapProofBundle.Fact(
                        fact.getKey(), fact.getValue()[0], fact.getValue()[1],
                        proof(tree, fact.getValue()[0], fact.getValue()[1], root)))
                .toList();
        var bundle = new AuthenticatedMapProofBundle(
                AuthenticatedMapProofBundle.Kind.ADMINISTRATOR_GOVERNANCE, CHAIN,
                ProofVerifier.JMT_BLAKE2B256_V1, GENESIS, 1, Hex.encode(root),
                null, null, facts);
        return new AssembledBundle(bundle, new ProofVerifier.TrustedStateRoot(
                CHAIN, ProofVerifier.JMT_BLAKE2B256_V1, GENESIS, 1, Hex.encode(root),
                ProofVerifier.TrustedRootSource.CALLER_PINNED));
    }

    private record AssembledBundle(
            AuthenticatedMapProofBundle bundle, ProofVerifier.TrustedStateRoot trusted) {
    }

    private static AssembledBundle approvalBundle(
            com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalProposalV1 proposal,
            AuthenticatedMapAuthorizationContract.ApprovalConsumptionV1 consumption,
            com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalPolicyV1 policy,
            byte[] entry, byte[] receipt, byte[] applicationKey, byte[] messageId,
            byte[] commitment, Map<String, byte[]> seeds, Map<String, String> organizations
    ) {
        String map = AuthenticatedMapContract.STATE_MACHINE_ID;
        String approvals = com.bloxbean.cardano.yano.appchain.roles.contracts
                .RoleWorkflowIdentifiers.ROLE_APPROVALS_COMPONENT_ID;
        String actors = com.bloxbean.cardano.yano.appchain.roles.contracts
                .RoleWorkflowIdentifiers.DOMAIN_ACTORS_COMPONENT_ID;
        java.util.Map<String, byte[][]> leaves = new java.util.LinkedHashMap<>();
        leaves.put(AuthenticatedMapProofBundle.ENTRY, new byte[][]{
                CompositeCommitmentV1.componentKey(map,
                        AuthenticatedMapContract.canonicalKey(
                                "released-products", applicationKey)), entry});
        leaves.put(AuthenticatedMapProofBundle.RECEIPT, new byte[][]{
                CompositeCommitmentV1.componentKey(map,
                        AuthenticatedMapContract.receiptKey(messageId)), receipt});
        leaves.put(AuthenticatedMapProofBundle.APPROVAL_CONSUMPTION, new byte[][]{
                CompositeCommitmentV1.componentKey(map,
                        AuthenticatedMapContract.approvalConsumptionKey("release-1")),
                consumption.encode()});
        leaves.put(AuthenticatedMapProofBundle.PROPOSAL, new byte[][]{
                CompositeCommitmentV1.componentKey(approvals,
                        com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys
                                .proposal("release-1")), proposal.encode()});
        leaves.put(AuthenticatedMapProofBundle.APPROVAL_POLICY, new byte[][]{
                CompositeCommitmentV1.componentKey(approvals,
                        com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys
                                .policyRevision("product-release", 1)), policy.encode()});
        for (Map.Entry<String, byte[]> identity : seeds.entrySet()) {
            String actorId = identity.getKey();
            String organizationId = organizations.get(actorId);
            byte[] publicKey = com.bloxbean.cardano.client.crypto.KeyGenUtil
                    .getPublicKeyFromPrivateKey(identity.getValue());
            var actorKey = new com.bloxbean.cardano.yano.appchain.roles.contracts
                    .ActorKeyEpochV1(actorId + "-k1", publicKey, 1, 0,
                    com.bloxbean.cardano.yano.appchain.roles.contracts.RecordStatus.ACTIVE);
            var actor = new com.bloxbean.cardano.yano.appchain.roles.contracts.ActorRecordV1(
                    actorId, organizationId, 1,
                    com.bloxbean.cardano.yano.appchain.roles.contracts.RecordStatus.ACTIVE,
                    List.of(actorId.startsWith("auditor") ? "auditor" : "issuer"),
                    List.of(actorKey), new byte[0]);
            var organization = new com.bloxbean.cardano.yano.appchain.roles.contracts
                    .OrganizationRecordV1(organizationId, 1,
                    com.bloxbean.cardano.yano.appchain.roles.contracts.RecordStatus.ACTIVE,
                    new byte[0]);
            leaves.put(AuthenticatedMapProofBundle.revisionFactName(
                    AuthenticatedMapProofBundle.ACTOR, actorId, 1), new byte[][]{
                    CompositeCommitmentV1.componentKey(actors,
                            com.bloxbean.cardano.yano.appchain.roles.contracts
                                    .RoleWorkflowKeys.actorRevision(actorId, 1)),
                    actor.encode()});
            leaves.put(AuthenticatedMapProofBundle.revisionFactName(
                    AuthenticatedMapProofBundle.ORGANIZATION, organizationId, 1),
                    new byte[][]{CompositeCommitmentV1.componentKey(actors,
                            com.bloxbean.cardano.yano.appchain.roles.contracts
                                    .RoleWorkflowKeys.organizationRevision(
                                            organizationId, 1)),
                            organization.encode()});
        }
        InMemoryJmtStore store = new InMemoryJmtStore();
        JellyfishMerkleTree tree = new JellyfishMerkleTree(
                store, JmtProfile.classicBlake2b256V1());
        java.util.Map<byte[], byte[]> byKey = new java.util.LinkedHashMap<>();
        leaves.values().forEach(pair -> byKey.put(pair[0], pair[1]));
        byte[] root = tree.put(1, byKey).rootHash();
        List<AuthenticatedMapProofBundle.Fact> facts = leaves.entrySet().stream()
                .map(fact -> new AuthenticatedMapProofBundle.Fact(
                        fact.getKey(), fact.getValue()[0], fact.getValue()[1],
                        proof(tree, fact.getValue()[0], fact.getValue()[1], root)))
                .toList();
        var bundle = new AuthenticatedMapProofBundle(
                AuthenticatedMapProofBundle.Kind.APPROVAL, CHAIN,
                ProofVerifier.JMT_BLAKE2B256_V1, GENESIS, 1, Hex.encode(root),
                commitment, null, facts);
        return new AssembledBundle(bundle, new ProofVerifier.TrustedStateRoot(
                CHAIN, ProofVerifier.JMT_BLAKE2B256_V1, GENESIS, 1, Hex.encode(root),
                ProofVerifier.TrustedRootSource.CALLER_PINNED));
    }

    private static com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalProposalV1
            .AcceptedDecisionV1 decision(
            String actorId, String organizationId, byte[] seed, byte[] payloadHash) {
        var statement = new com.bloxbean.cardano.yano.appchain.roles.contracts
                .ActorStatementV1(
                com.bloxbean.cardano.yano.appchain.roles.contracts.ActorStatementV1
                        .Action.APPROVE,
                CHAIN, "release-1", "product-release", 1,
                AuthenticatedMapAuthorizationContract.APPROVAL_PAYLOAD_DOMAIN, payloadHash,
                20, actorId, 1, actorId + "-k1", "independent-auditors");
        byte[] signature = com.bloxbean.cardano.yano.appchain.roles.contracts
                .SignedActorCommandV1.sign(statement, seed).signature();
        return new com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalProposalV1
                .AcceptedDecisionV1(
                com.bloxbean.cardano.yano.appchain.roles.contracts.ActorStatementV1
                        .Action.APPROVE,
                actorId, organizationId, 1, "auditor", 1, actorId + "-k1",
                "independent-auditors", statement.digest(), signature, 1);
    }

    private static byte[] seed(int fill) {
        byte[] seed = new byte[32];
        java.util.Arrays.fill(seed, (byte) fill);
        return seed;
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
