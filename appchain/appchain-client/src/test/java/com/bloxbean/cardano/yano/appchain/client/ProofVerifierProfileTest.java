package com.bloxbean.cardano.yano.appchain.client;

import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.jmt.JellyfishMerkleTree;
import com.bloxbean.cardano.vds.jmt.JmtProfile;
import com.bloxbean.cardano.vds.jmt.store.InMemoryJmtStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProofVerifierProfileTest {
    private static final String CHAIN = "proof-chain";
    private static final String GENESIS = "22".repeat(32);

    @Test
    void dispatchesClassicJmtInclusionExclusionAndTombstoneByExactProfile() {
        InMemoryJmtStore store = new InMemoryJmtStore();
        JellyfishMerkleTree tree = new JellyfishMerkleTree(
                store, JmtProfile.classicBlake2b256V1());
        byte[] presentKey = "present".getBytes(StandardCharsets.US_ASCII);
        byte[] tombstoneKey = "deleted".getBytes(StandardCharsets.US_ASCII);
        byte[] missingKey = "missing".getBytes(StandardCharsets.US_ASCII);
        byte[] value = "value".getBytes(StandardCharsets.US_ASCII);
        byte[] tombstone = "tombstone".getBytes(StandardCharsets.US_ASCII);
        byte[] root = tree.put(1, Map.of(presentKey, value, tombstoneKey, tombstone)).rootHash();

        AppChainClient.Proof inclusion = proof(
                presentKey, value, tree.getProofWire(presentKey, 1).orElseThrow(), root,
                AppChainClient.ProofPresence.PRESENT, ProofVerifier.JMT_BLAKE2B256_V1);
        AppChainClient.Proof deleted = proof(
                tombstoneKey, tombstone, tree.getProofWire(tombstoneKey, 1).orElseThrow(), root,
                AppChainClient.ProofPresence.TOMBSTONED, ProofVerifier.JMT_BLAKE2B256_V1);
        AppChainClient.Proof exclusion = proof(
                missingKey, null, tree.getProofWire(missingKey, 1).orElseThrow(), root,
                AppChainClient.ProofPresence.ABSENT, ProofVerifier.JMT_BLAKE2B256_V1);
        ProofVerifier.TrustedStateRoot trusted = trusted(root, ProofVerifier.JMT_BLAKE2B256_V1);

        assertThat(ProofVerifier.verify(inclusion, trusted)).isTrue();
        assertThat(ProofVerifier.verify(deleted, trusted)).isTrue();
        assertThat(ProofVerifier.verify(exclusion, trusted)).isTrue();
        assertThat(ProofVerifier.verify(inclusion,
                trusted(root, ProofVerifier.MPF_BLAKE2B256_V1))).isFalse();
        assertThat(ProofVerifier.verify(proof(
                presentKey, value, inclusion.proofWireHex().isEmpty()
                        ? new byte[]{1} : Hex.decode(inclusion.proofWireHex()), root,
                AppChainClient.ProofPresence.PRESENT, ProofVerifier.MPF_BLAKE2B256_V1),
                trusted(root, ProofVerifier.MPF_BLAKE2B256_V1))).isFalse();
    }

    @Test
    void verifiesCertifiedMpfProofOnlyUnderPinnedMembershipAndIdentity() throws Exception {
        MemoryNodeStore store = new MemoryNodeStore();
        MpfTrie trie = new MpfTrie(store);
        byte[] key = "certified".getBytes(StandardCharsets.US_ASCII);
        byte[] value = "entry".getBytes(StandardCharsets.US_ASCII);
        trie.put(key, value);
        byte[] root = trie.getRootHash();
        byte[] wire = trie.getProofWire(key).orElseThrow();

        AppChainClient.CertifiedBlockHeader unsignedHeader = new AppChainClient.CertifiedBlockHeader(
                1, 1, "00".repeat(32), 0, "", 1234,
                "33".repeat(32), Hex.encode(root), "00".repeat(32));
        byte[] blockHash = blockHash(CHAIN, unsignedHeader);
        AppChainClient.CertifiedBlockHeader header = new AppChainClient.CertifiedBlockHeader(
                1, 1, unsignedHeader.prevHashHex(), 0, "", 1234,
                unsignedHeader.messagesRootHex(), Hex.encode(root), Hex.encode(blockHash));
        byte[] seed = new byte[32];
        java.util.Arrays.fill(seed, (byte) 7);
        byte[] publicKey = KeyGenUtil.getPublicKeyFromPrivateKey(seed);
        byte[] signature = CryptoConfiguration.INSTANCE.getSigningProvider().sign(blockHash, seed);
        AppChainClient.FinalityCertificate certificate = new AppChainClient.FinalityCertificate(
                0, List.of(new AppChainClient.FinalitySignature(
                Hex.encode(publicKey), Hex.encode(signature))));
        ProofVerifier.ProfileMetadata mpf = ProofVerifier.profileMetadata(
                ProofVerifier.MPF_BLAKE2B256_V1).orElseThrow();
        AppChainClient.Proof proof = new AppChainClient.Proof(
                Hex.encode(key), CHAIN, Hex.encode(root), Hex.encode(wire), Hex.encode(value),
                null, 1L, 1, ProofVerifier.MPF_BLAKE2B256_V1, "mpf",
                "mpf-blake2b256-format-v1", mpf.formatFingerprintHex(), GENESIS,
                "mpf-proof-wire-v1", false, true, 1L,
                AppChainClient.ProofPresence.PRESENT, header, certificate);
        ProofVerifier.FinalityTrustContext trust = new ProofVerifier.FinalityTrustContext(
                CHAIN, ProofVerifier.MPF_BLAKE2B256_V1, GENESIS,
                Set.of(Hex.encode(publicKey)), 1);

        assertThat(ProofVerifier.verifyCertified(proof, trust)).isTrue();
        assertThat(ProofVerifier.verifyCertified(proof,
                new ProofVerifier.FinalityTrustContext(CHAIN,
                        ProofVerifier.MPF_BLAKE2B256_V1, "55".repeat(32),
                        Set.of(Hex.encode(publicKey)), 1))).isFalse();
        AppChainClient.CertifiedBlockHeader wrongRoot = new AppChainClient.CertifiedBlockHeader(
                1, 1, header.prevHashHex(), 0, "", 1234, header.messagesRootHex(),
                "66".repeat(32), header.blockHashHex());
        AppChainClient.Proof substituted = new AppChainClient.Proof(
                proof.keyHex(), proof.chainId(), proof.stateRootHex(), proof.proofWireHex(),
                proof.valueHex(), null, 1L, 1, proof.profile(), proof.backend(),
                proof.commitmentFormatId(), proof.formatFingerprintHex(), proof.genesisIdHex(),
                proof.proofEncodingId(), false, true, 1L,
                proof.presence(), wrongRoot, certificate);
        assertThat(ProofVerifier.verifyCertified(substituted, trust)).isFalse();
    }

    private static AppChainClient.Proof proof(
            byte[] key,
            byte[] value,
            byte[] wire,
            byte[] root,
            AppChainClient.ProofPresence presence,
            String profile
    ) {
        ProofVerifier.ProfileMetadata metadata = ProofVerifier.profileMetadata(profile)
                .orElseThrow();
        return new AppChainClient.Proof(
                Hex.encode(key), CHAIN, Hex.encode(root), Hex.encode(wire),
                value != null ? Hex.encode(value) : null, null, 1L, 1,
                profile, metadata.backend(), metadata.commitmentFormatId(),
                metadata.formatFingerprintHex(), GENESIS,
                metadata.proofEncodingId(), metadata.nativeVersioning(),
                metadata.physicalDelete(), 1L,
                presence, null, null);
    }

    private static ProofVerifier.TrustedStateRoot trusted(byte[] root, String profile) {
        return new ProofVerifier.TrustedStateRoot(
                CHAIN, profile, GENESIS, 1, Hex.encode(root),
                ProofVerifier.TrustedRootSource.CALLER_PINNED);
    }

    private static byte[] blockHash(
            String chainId,
            AppChainClient.CertifiedBlockHeader block
    ) throws Exception {
        Array header = new Array();
        header.add(new UnsignedInteger(block.version()));
        header.add(new UnicodeString(chainId));
        header.add(new UnsignedInteger(block.height()));
        header.add(new ByteString(Hex.decode(block.prevHashHex())));
        header.add(new UnsignedInteger(block.l1Slot()));
        header.add(new ByteString(Hex.decode(block.l1BlockHashHex())));
        header.add(new UnsignedInteger(block.timestamp()));
        header.add(new ByteString(Hex.decode(block.messagesRootHex())));
        header.add(new ByteString(Hex.decode(block.stateRootHex())));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new CborEncoder(bytes).encode(header);
        return Blake2bUtil.blake2bHash256(bytes.toByteArray());
    }

    private static final class MemoryNodeStore implements NodeStore {
        private final Map<String, byte[]> values = new HashMap<>();

        @Override public byte[] get(byte[] hash) { return values.get(Hex.encode(hash)); }
        @Override public void put(byte[] hash, byte[] nodeBytes) {
            values.put(Hex.encode(hash), nodeBytes);
        }
        @Override public void delete(byte[] hash) { values.remove(Hex.encode(hash)); }
    }
}
