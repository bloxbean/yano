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
