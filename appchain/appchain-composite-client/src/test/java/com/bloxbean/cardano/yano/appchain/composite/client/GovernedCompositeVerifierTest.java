package com.bloxbean.cardano.yano.appchain.composite.client;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceBundle;
import com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceVerifier;
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeCommitmentV1;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeProfileEpochV1;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GovernedCompositeVerifierTest {
    private static final String CHAIN = "governed-client";
    private static final long HEIGHT = 7;
    private static final byte[] PRIVATE_KEY = filled(11);
    private static final byte[] PUBLIC_KEY =
            KeyGenUtil.getPublicKeyFromPrivateKey(PRIVATE_KEY);

    @Test
    void verifiesFinalityOneRootProofsStructureThenAuthorizationPolicy() {
        Fixture fixture = fixture();
        AtomicBoolean policyCalled = new AtomicBoolean();

        GovernedCompositeVerifier.VerifiedProfile verified =
                GovernedCompositeVerifier.verify(
                        fixture.evidence(), fixture.trust(), fixture.pointerProof(),
                        List.of(fixture.epochProof()), fixture.markerProof(),
                        fixture.genesisDigest(), 64, context -> {
                            policyCalled.set(true);
                            assertThat(context.chainId()).isEqualTo(CHAIN);
                            assertThat(context.finalizedHeight()).isEqualTo(HEIGHT);
                            assertThat(context.stateRoot()).containsExactly(fixture.root());
                            assertThat(context.epochs()).singleElement()
                                    .extracting(CompositeProfileEpochV1::epochNumber)
                                    .isEqualTo(0L);
                            return true;
                        });

        assertThat(policyCalled).isTrue();
        assertThat(verified.finalizedHeight()).isEqualTo(HEIGHT);
        assertThat(verified.stateRoot()).containsExactly(fixture.root());
        assertThat(verified.structure().epochNumber()).isZero();
        assertThat(verified.structure().profileDigest())
                .containsExactly(fixture.genesisDigest());
        assertThat(verified.validFinalitySignatures()).isEqualTo(1);
    }

    @Test
    void rejectsFalseAuthorizationInvalidFinalityAndCrossRootProofs() {
        Fixture fixture = fixture();
        assertThatThrownBy(() -> GovernedCompositeVerifier.verify(
                fixture.evidence(), fixture.trust(), fixture.pointerProof(),
                List.of(fixture.epochProof()), fixture.markerProof(),
                fixture.genesisDigest(), 64, context -> false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authorization policy rejected");

        EvidenceVerifier.TrustContext wrongTrust = new EvidenceVerifier.TrustContext(
                CHAIN, Set.of("22".repeat(32)), 1);
        assertThatThrownBy(() -> GovernedCompositeVerifier.verify(
                fixture.evidence(), wrongTrust, fixture.pointerProof(),
                List.of(fixture.epochProof()), fixture.markerProof(),
                fixture.genesisDigest(), 64, context -> true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finalized block evidence did not verify");

        AppChainClient.Proof wrongRoot = new AppChainClient.Proof(
                fixture.markerProof().keyHex(), CHAIN, "00".repeat(32),
                fixture.markerProof().proofWireHex(), fixture.markerProof().valueHex(),
                HEIGHT, HEIGHT);
        assertThatThrownBy(() -> GovernedCompositeVerifier.verify(
                fixture.evidence(), fixture.trust(), fixture.pointerProof(),
                List.of(fixture.epochProof()), wrongRoot,
                fixture.genesisDigest(), 64, context -> true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MPF inclusion proof did not verify");
    }

    @Test
    void pinnedProposalPolicyRequiresEveryNonGenesisEpochExactly() {
        Fixture fixture = fixture();
        byte[] genesisProfile = "genesis-profile".getBytes(StandardCharsets.US_ASCII);
        byte[] nextProfile = "next-profile".getBytes(StandardCharsets.US_ASCII);
        byte[] proposalHash = filled(19);
        CompositeProfileEpochV1 genesis = new CompositeProfileEpochV1(
                1, 0, 1, new byte[32], genesisProfile, new byte[32]);
        CompositeProfileEpochV1 next = new CompositeProfileEpochV1(
                1, 1, 5, genesis.profileDigest(), nextProfile, proposalHash);
        GovernedCompositeVerifier.AuthorizationContext context =
                new GovernedCompositeVerifier.AuthorizationContext(
                        CHAIN, HEIGHT, fixture.root(), List.of(genesis, next),
                        fixture.evidence());

        assertThat(GovernedCompositeVerifier.requirePinnedProposalHashes(
                Map.of(1L, proposalHash)).verify(context)).isTrue();
        assertThat(GovernedCompositeVerifier.requirePinnedProposalHashes(
                Map.of()).verify(context)).isFalse();
        assertThat(GovernedCompositeVerifier.requirePinnedProposalHashes(
                Map.of(1L, filled(20))).verify(context)).isFalse();
    }

    private static Fixture fixture() {
        byte[] profile = "canonical-profile-v1".getBytes(StandardCharsets.US_ASCII);
        byte[] digest = CompositeCommitmentV1.profileDigest(profile);
        byte[] epoch = new CompositeProfileEpochV1(
                1, 0, 1, new byte[32], profile, new byte[32]).encode();
        byte[] pointer = ByteBuffer.allocate(Long.BYTES).putLong(0).array();

        MpfTrie trie = new MpfTrie(new MapNodeStore());
        trie.put(CompositeCommitmentV1.currentProfileEpochKey(), pointer);
        trie.put(CompositeCommitmentV1.profileEpochKey(0), epoch);
        trie.put(CompositeCommitmentV1.profileMarkerKey(), profile);
        byte[] root = trie.getRootHash();

        AppChainClient.Proof pointerProof = proof(
                trie, CompositeCommitmentV1.currentProfileEpochKey(), pointer, root);
        AppChainClient.Proof epochProof = proof(
                trie, CompositeCommitmentV1.profileEpochKey(0), epoch, root);
        AppChainClient.Proof markerProof = proof(
                trie, CompositeCommitmentV1.profileMarkerKey(), profile, root);

        AppMessage message = message();
        AppBlock unsigned = new AppBlock(AppBlock.BLOCK_VERSION, CHAIN, HEIGHT,
                filled(3), 0, new byte[0], 1_000,
                AppBlockCodec.messagesRoot(List.of(message)), root,
                List.of(message), PUBLIC_KEY, FinalityCert.empty());
        byte[] signature = CryptoConfiguration.INSTANCE.getSigningProvider()
                .sign(AppBlockCodec.blockHash(unsigned), PRIVATE_KEY);
        AppBlock finalized = unsigned.withCert(new FinalityCert(
                FinalityCert.SCHEME_ED25519,
                List.of(new FinalityCert.Signature(PUBLIC_KEY, signature))));
        String member = HexFormat.of().formatHex(PUBLIC_KEY);
        EvidenceBundle evidence = new EvidenceBundle(
                CHAIN, HexFormat.of().formatHex(message.getMessageId()),
                List.of(finalized), List.of(member), 1, null);
        EvidenceVerifier.TrustContext trust = new EvidenceVerifier.TrustContext(
                CHAIN, Set.of(member), 1);
        return new Fixture(root, digest, pointerProof, epochProof, markerProof,
                evidence, trust);
    }

    private static AppChainClient.Proof proof(
            MpfTrie trie, byte[] key, byte[] value, byte[] root
    ) {
        return new AppChainClient.Proof(
                HexFormat.of().formatHex(key), CHAIN, HexFormat.of().formatHex(root),
                HexFormat.of().formatHex(trie.getProofWire(key).orElseThrow()),
                HexFormat.of().formatHex(value), HEIGHT, HEIGHT);
    }

    private static AppMessage message() {
        byte[] body = new byte[]{1};
        long expires = 2_000_000_000L;
        byte[] id = AppMessage.computeMessageId(
                CHAIN, "profile.verify", PUBLIC_KEY, 1, expires, body);
        byte[] signature = CryptoConfiguration.INSTANCE.getSigningProvider().sign(
                AppMessage.signedBodyBytes(
                        CHAIN, "profile.verify", PUBLIC_KEY, 1, expires, body),
                PRIVATE_KEY);
        return AppMessage.builder().version(AppMessage.ENVELOPE_VERSION)
                .messageId(id).chainId(CHAIN).topic("profile.verify")
                .sender(PUBLIC_KEY).senderSeq(1).expiresAt(expires).body(body)
                .authScheme(FinalityCert.SCHEME_ED25519).authProof(signature).build();
    }

    private static byte[] filled(int value) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) value);
        return result;
    }

    private record Fixture(
            byte[] root,
            byte[] genesisDigest,
            AppChainClient.Proof pointerProof,
            AppChainClient.Proof epochProof,
            AppChainClient.Proof markerProof,
            EvidenceBundle evidence,
            EvidenceVerifier.TrustContext trust
    ) {
        private Fixture {
            root = root.clone();
            genesisDigest = genesisDigest.clone();
        }

        @Override public byte[] root() { return root.clone(); }
        @Override public byte[] genesisDigest() { return genesisDigest.clone(); }
    }

    private static final class MapNodeStore implements NodeStore {
        private final Map<String, byte[]> values = new HashMap<>();

        @Override public byte[] get(byte[] hash) {
            return values.get(HexFormat.of().formatHex(hash));
        }

        @Override public void put(byte[] hash, byte[] nodeBytes) {
            values.put(HexFormat.of().formatHex(hash), nodeBytes);
        }

        @Override public void delete(byte[] hash) {
            values.remove(HexFormat.of().formatHex(hash));
        }
    }
}
