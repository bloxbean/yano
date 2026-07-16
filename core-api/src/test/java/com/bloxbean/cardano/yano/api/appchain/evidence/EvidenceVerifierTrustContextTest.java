package com.bloxbean.cardano.yano.api.appchain.evidence;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceVerifierTrustContextTest {
    private static final String CHAIN = "trusted-chain";
    private static final byte[] SEED_A = filled(1);
    private static final byte[] SEED_B = filled(2);
    private static final String MEMBER_A = publicKey(SEED_A);
    private static final String MEMBER_B = publicKey(SEED_B);
    private static final String TX = "ab".repeat(32);

    @Test
    void verifiesAgainstCallerPinnedTrustContext() {
        Fixture fixture = anchoredFixture(CHAIN, SEED_A, List.of(MEMBER_A), 1);

        assertThat(EvidenceVerifier.verifyInternalConsistencyAgainstDeclaredMembers(
                fixture.bundle()).valid()).isTrue();
        assertThat(EvidenceVerifier.verify(fixture.bundle(),
                new EvidenceVerifier.TrustContext(CHAIN, Set.of(MEMBER_A), 1)))
                .satisfies(result -> {
                    assertThat(result.valid()).isTrue();
                    assertThat(result.messageContentVerified()).isTrue();
                });
    }

    @Test
    void rejectsMemberThresholdAndChainSubstitutionThatIsInternallyValid() {
        Fixture attackerMember = anchoredFixture(CHAIN, SEED_B, List.of(MEMBER_B), 1);
        assertThat(EvidenceVerifier.verifyInternalConsistencyAgainstDeclaredMembers(
                attackerMember.bundle()).valid()).isTrue();
        assertThat(EvidenceVerifier.verify(attackerMember.bundle(), CHAIN,
                Set.of(MEMBER_A), 1).valid()).isFalse();

        Fixture attackerChain = anchoredFixture("other-chain", SEED_A, List.of(MEMBER_A), 1);
        assertThat(EvidenceVerifier.verifyInternalConsistencyAgainstDeclaredMembers(
                attackerChain.bundle()).valid()).isTrue();
        assertThat(EvidenceVerifier.verify(attackerChain.bundle(), CHAIN,
                Set.of(MEMBER_A), 1).valid()).isFalse();

        Fixture oneOfTwo = anchoredFixture(CHAIN, SEED_A, List.of(MEMBER_A), 1);
        assertThat(EvidenceVerifier.verifyInternalConsistencyAgainstDeclaredMembers(
                oneOfTwo.bundle()).valid()).isTrue();
        assertThat(EvidenceVerifier.verify(oneOfTwo.bundle(), CHAIN,
                Set.of(MEMBER_A, MEMBER_B), 2).valid()).isFalse();
    }

    @Test
    void rejectsCrossChainUnsupportedAndNonConsecutiveSignedBlocks() {
        AppMessage target = message(CHAIN, 1);
        AppBlock first = signedBlock(AppBlock.BLOCK_VERSION, CHAIN, 5,
                filled(9), List.of(target), SEED_A, FinalityCert.SCHEME_ED25519,
                KeyGenUtil.getPublicKeyFromPrivateKey(SEED_A));
        AppBlock crossChain = signedBlock(AppBlock.BLOCK_VERSION, "other-chain", 6,
                AppBlockCodec.blockHash(first), List.of(), SEED_A,
                FinalityCert.SCHEME_ED25519, KeyGenUtil.getPublicKeyFromPrivateKey(SEED_A));
        assertInvalid(bundle(CHAIN, target, List.of(first, crossChain), 6,
                AppBlockCodec.blockHash(crossChain), List.of(MEMBER_A), 1));

        AppBlock unsupported = signedBlock(2, CHAIN, 5, filled(9),
                List.of(target), SEED_A, FinalityCert.SCHEME_ED25519,
                KeyGenUtil.getPublicKeyFromPrivateKey(SEED_A));
        assertInvalid(bundle(CHAIN, target, List.of(unsupported), 5,
                AppBlockCodec.blockHash(unsupported), List.of(MEMBER_A), 1));

        AppBlock gap = signedBlock(AppBlock.BLOCK_VERSION, CHAIN, 7,
                AppBlockCodec.blockHash(first), List.of(), SEED_A,
                FinalityCert.SCHEME_ED25519, KeyGenUtil.getPublicKeyFromPrivateKey(SEED_A));
        assertInvalid(bundle(CHAIN, target, List.of(first, gap), 7,
                AppBlockCodec.blockHash(gap), List.of(MEMBER_A), 1));

        AppBlock reversed = signedBlock(AppBlock.BLOCK_VERSION, CHAIN, 4,
                AppBlockCodec.blockHash(first), List.of(), SEED_A,
                FinalityCert.SCHEME_ED25519, KeyGenUtil.getPublicKeyFromPrivateKey(SEED_A));
        assertInvalid(bundle(CHAIN, target, List.of(first, reversed), 4,
                AppBlockCodec.blockHash(reversed), List.of(MEMBER_A), 1));

        AppBlock replay = signedBlock(AppBlock.BLOCK_VERSION, CHAIN, 6,
                AppBlockCodec.blockHash(first), List.of(target), SEED_A,
                FinalityCert.SCHEME_ED25519, KeyGenUtil.getPublicKeyFromPrivateKey(SEED_A));
        assertInvalid(bundle(CHAIN, target, List.of(first, replay), 6,
                AppBlockCodec.blockHash(replay), List.of(MEMBER_A), 1));
    }

    @Test
    void rejectsMalformedIdentitiesThresholdAnchorAndHeaderProfile() {
        Fixture fixture = anchoredFixture(CHAIN, SEED_A, List.of(MEMBER_A), 1);
        EvidenceBundle valid = fixture.bundle();

        assertInvalid(new EvidenceBundle(CHAIN, valid.messageIdHex().toUpperCase(),
                valid.blocks(), valid.memberKeysHex(), 1, valid.anchor()));
        assertInvalid(new EvidenceBundle(CHAIN, valid.messageIdHex(), valid.blocks(),
                List.of(MEMBER_A, MEMBER_A), 1, valid.anchor()));
        assertInvalid(new EvidenceBundle(CHAIN, valid.messageIdHex(), valid.blocks(),
                List.of(MEMBER_A), 0, valid.anchor()));
        assertInvalid(new EvidenceBundle(CHAIN, valid.messageIdHex(), valid.blocks(),
                valid.memberKeysHex(), 1, new EvidenceBundle.AnchorRef(
                valid.anchor().anchoredHeight(), valid.anchor().anchoredBlockHashHex(),
                "not-a-cardano-tx", valid.anchor().l1Slot())));
        assertInvalid(new EvidenceBundle(CHAIN, valid.messageIdHex(), valid.blocks(),
                valid.memberKeysHex(), 1, new EvidenceBundle.AnchorRef(
                valid.anchor().anchoredHeight() + 1, valid.anchor().anchoredBlockHashHex(),
                TX, valid.anchor().l1Slot())));
        assertInvalid(new EvidenceBundle(CHAIN, valid.messageIdHex(), valid.blocks(),
                valid.memberKeysHex(), 1, null));

        AppMessage target = message(CHAIN, 1);
        AppBlock badProposer = signedBlock(AppBlock.BLOCK_VERSION, CHAIN, 5,
                filled(9), List.of(target), SEED_A, FinalityCert.SCHEME_ED25519,
                new byte[31]);
        assertInvalid(bundle(CHAIN, target, List.of(badProposer), 5,
                AppBlockCodec.blockHash(badProposer), List.of(MEMBER_A), 1));
    }

    @Test
    void rejectsSignedMessagesWithWrongChainOrForgedMessageIdentity() {
        AppMessage wrongChain = message("other-chain", 1);
        AppBlock wrongChainBlock = signedBlock(AppBlock.BLOCK_VERSION, CHAIN, 5,
                filled(9), List.of(wrongChain), SEED_A, FinalityCert.SCHEME_ED25519,
                KeyGenUtil.getPublicKeyFromPrivateKey(SEED_A));
        assertInvalid(bundle(CHAIN, wrongChain, List.of(wrongChainBlock), 5,
                AppBlockCodec.blockHash(wrongChainBlock), List.of(MEMBER_A), 1));

        AppMessage valid = message(CHAIN, 2);
        AppMessage forged = AppMessage.builder()
                .version(valid.getVersion()).messageId(filled(99))
                .chainId(valid.getChainId()).topic(valid.getTopic())
                .sender(valid.getSender()).senderSeq(valid.getSenderSeq())
                .expiresAt(valid.getExpiresAt()).body(valid.getBody())
                .authScheme(valid.getAuthScheme()).authProof(valid.getAuthProof()).build();
        AppBlock forgedBlock = signedBlock(AppBlock.BLOCK_VERSION, CHAIN, 5,
                filled(9), List.of(forged), SEED_A, FinalityCert.SCHEME_ED25519,
                KeyGenUtil.getPublicKeyFromPrivateKey(SEED_A));
        assertInvalid(bundle(CHAIN, forged, List.of(forgedBlock), 5,
                AppBlockCodec.blockHash(forgedBlock), List.of(MEMBER_A), 1));
    }

    @Test
    void distinguishesCanonicalRetentionTombstoneFromMalformedPseudoTombstone() {
        AppMessage full = message(CHAIN, 3);
        AppMessage tombstone = AppMessage.builder()
                .version(full.getVersion()).messageId(full.getMessageId())
                .chainId(full.getChainId()).topic(full.getTopic())
                .sender(full.getSender()).senderSeq(full.getSenderSeq())
                .expiresAt(full.getExpiresAt()).body(new byte[0])
                .authScheme(full.getAuthScheme()).authProof(new byte[0]).build();
        AppBlock retained = signedBlock(AppBlock.BLOCK_VERSION, CHAIN, 5,
                filled(9), List.of(tombstone), SEED_A, FinalityCert.SCHEME_ED25519,
                KeyGenUtil.getPublicKeyFromPrivateKey(SEED_A));
        EvidenceBundle retainedBundle = bundle(CHAIN, tombstone, List.of(retained), 5,
                AppBlockCodec.blockHash(retained), List.of(MEMBER_A), 1);

        EvidenceVerifier.Result retainedResult = EvidenceVerifier.verify(
                retainedBundle, CHAIN, Set.of(MEMBER_A), 1);
        assertThat(retainedResult.valid()).isTrue();
        assertThat(retainedResult.messageContentVerified()).isFalse();

        AppMessage malformed = AppMessage.builder()
                .version(full.getVersion()).messageId(full.getMessageId())
                .chainId(full.getChainId()).topic(full.getTopic())
                .sender(new byte[31]).senderSeq(full.getSenderSeq())
                .expiresAt(full.getExpiresAt()).body(new byte[0])
                .authScheme(full.getAuthScheme()).authProof(new byte[0]).build();
        AppBlock malformedBlock = signedBlock(AppBlock.BLOCK_VERSION, CHAIN, 5,
                filled(9), List.of(malformed), SEED_A, FinalityCert.SCHEME_ED25519,
                KeyGenUtil.getPublicKeyFromPrivateKey(SEED_A));
        assertInvalid(bundle(CHAIN, malformed, List.of(malformedBlock), 5,
                AppBlockCodec.blockHash(malformedBlock), List.of(MEMBER_A), 1));
    }

    @Test
    void rejectsEveryInvalidDuplicateNonmemberOrMalformedCertificateEntry() {
        AppMessage target = message(CHAIN, 4);
        AppBlock signed = signedBlock(AppBlock.BLOCK_VERSION, CHAIN, 5,
                filled(9), List.of(target), SEED_A, FinalityCert.SCHEME_ED25519,
                KeyGenUtil.getPublicKeyFromPrivateKey(SEED_A));
        FinalityCert.Signature valid = signed.cert().signatures().getFirst();
        FinalityCert.Signature invalid = new FinalityCert.Signature(
                valid.signer(), new byte[64]);
        AppBlock withDuplicate = signed.withCert(new FinalityCert(
                FinalityCert.SCHEME_ED25519, List.of(invalid, valid)));
        EvidenceBundle bundle = bundle(CHAIN, target, List.of(withDuplicate), 5,
                AppBlockCodec.blockHash(withDuplicate), List.of(MEMBER_A), 1);

        assertInvalid(bundle);

        AppBlock duplicatedValid = signed.withCert(new FinalityCert(
                FinalityCert.SCHEME_ED25519, List.of(valid, valid)));
        assertInvalid(bundle(CHAIN, target, List.of(duplicatedValid), 5,
                AppBlockCodec.blockHash(duplicatedValid), List.of(MEMBER_A), 1));

        byte[] blockHash = AppBlockCodec.blockHash(signed);
        FinalityCert.Signature nonmember = new FinalityCert.Signature(
                KeyGenUtil.getPublicKeyFromPrivateKey(SEED_B),
                CryptoConfiguration.INSTANCE.getSigningProvider().sign(blockHash, SEED_B));
        AppBlock withNonmember = signed.withCert(new FinalityCert(
                FinalityCert.SCHEME_ED25519, List.of(valid, nonmember)));
        assertInvalid(bundle(CHAIN, target, List.of(withNonmember), 5,
                AppBlockCodec.blockHash(withNonmember), List.of(MEMBER_A), 1));

        AppBlock malformed = signed.withCert(new FinalityCert(
                FinalityCert.SCHEME_ED25519, List.of(valid,
                new FinalityCert.Signature(valid.signer(), new byte[63]))));
        assertInvalid(bundle(CHAIN, target, List.of(malformed), 5,
                AppBlockCodec.blockHash(malformed), List.of(MEMBER_A), 1));
    }

    @Test
    void rejectsTamperedOrNonmemberMessageAuthentication() {
        Fixture fixture = anchoredFixture(CHAIN, SEED_A, List.of(MEMBER_A), 1);
        AppBlock first = fixture.bundle().blocks().getFirst();
        AppMessage original = first.messages().getFirst();
        AppMessage tampered = AppMessage.builder()
                .version(original.getVersion()).messageId(original.getMessageId())
                .chainId(original.getChainId()).topic(original.getTopic())
                .sender(original.getSender()).senderSeq(original.getSenderSeq())
                .expiresAt(original.getExpiresAt()).body(original.getBody())
                .authScheme(original.getAuthScheme()).authProof(new byte[64]).build();
        AppBlock tamperedFirst = new AppBlock(first.version(), first.chainId(), first.height(),
                first.prevHash(), first.l1Slot(), first.l1BlockHash(), first.timestamp(),
                first.messagesRoot(), first.stateRoot(), List.of(tampered), first.proposer(),
                first.cert());
        EvidenceBundle tamperedBundle = new EvidenceBundle(CHAIN,
                fixture.bundle().messageIdHex(),
                List.of(tamperedFirst, fixture.bundle().blocks().getLast()),
                fixture.bundle().memberKeysHex(), fixture.bundle().threshold(),
                fixture.bundle().anchor());
        assertInvalid(tamperedBundle);

        AppMessage outsider = message(CHAIN, 9, SEED_B);
        AppBlock outsiderBlock = signedBlock(AppBlock.BLOCK_VERSION, CHAIN, 5,
                filled(9), List.of(outsider), SEED_A, FinalityCert.SCHEME_ED25519,
                KeyGenUtil.getPublicKeyFromPrivateKey(SEED_A));
        assertInvalid(bundle(CHAIN, outsider, List.of(outsiderBlock), 5,
                AppBlockCodec.blockHash(outsiderBlock), List.of(MEMBER_A), 1));
    }

    @Test
    void rejectsTrustContextsOutsideTheFrameworkV1Bounds() {
        String oversizedChain = "é".repeat(AppChainConfig.MAX_CHAIN_ID_BYTES);
        Set<String> oversizedMembers = java.util.stream.IntStream.rangeClosed(
                        0, AppChainConfig.MAX_MEMBERS)
                .mapToObj(index -> String.format("%064x", index))
                .collect(java.util.stream.Collectors.toSet());

        assertThatThrownBy(() -> new EvidenceVerifier.TrustContext(
                oversizedChain, Set.of(MEMBER_A), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceVerifier.TrustContext(
                CHAIN, oversizedMembers, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsProgrammaticBundlesOutsidePortableWorkBounds() {
        Fixture fixture = anchoredFixture(CHAIN, SEED_A, List.of(MEMBER_A), 1);
        EvidenceBundle valid = fixture.bundle();
        EvidenceBundle tooManyBlocks = new EvidenceBundle(CHAIN, valid.messageIdHex(),
                Collections.nCopies(EvidenceBundle.MAX_BLOCKS + 1,
                        valid.blocks().getFirst()), valid.memberKeysHex(), 1,
                valid.anchor());
        assertInvalid(tooManyBlocks);

        AppBlock first = valid.blocks().getFirst();
        AppBlock tooManyMessages = new AppBlock(first.version(), first.chainId(),
                first.height(), first.prevHash(), first.l1Slot(), first.l1BlockHash(),
                first.timestamp(), first.messagesRoot(), first.stateRoot(),
                Collections.nCopies(AppChainConfig.MAX_BLOCK_MESSAGES + 1,
                        first.messages().getFirst()), first.proposer(), first.cert());
        assertInvalid(new EvidenceBundle(CHAIN, valid.messageIdHex(),
                List.of(tooManyMessages), valid.memberKeysHex(), 1, null));
    }

    @Test
    void rejectsOtherwiseValidAnchoredSegmentAboveTheCumulativeBlockByteBudget() {
        List<AppBlock> blocks = new ArrayList<>();
        byte[] previousHash = filled(9);
        long totalBlockBytes = 0;
        long sequence = 1;
        while (totalBlockBytes <= EvidenceBundle.MAX_TOTAL_BLOCK_CBOR_BYTES) {
            AppMessage message = message(CHAIN, sequence, SEED_A, 2 * 1024 * 1024);
            AppBlock block = signedBlock(AppBlock.BLOCK_VERSION, CHAIN,
                    4 + sequence, previousHash, List.of(message), SEED_A,
                    FinalityCert.SCHEME_ED25519,
                    KeyGenUtil.getPublicKeyFromPrivateKey(SEED_A));
            blocks.add(block);
            totalBlockBytes += AppBlockCodec.serialize(block).length;
            previousHash = AppBlockCodec.blockHash(block);
            sequence++;
        }

        // Each block is a structurally and cryptographically valid standalone
        // bundle; only their cumulative canonical byte size invalidates the
        // anchored segment assembled below.
        for (AppBlock block : blocks) {
            AppMessage message = block.messages().getFirst();
            EvidenceBundle standalone = new EvidenceBundle(CHAIN,
                    HexUtil.encodeHexString(message.getMessageId()), List.of(block),
                    List.of(MEMBER_A), 1, null);
            assertThat(EvidenceVerifier.verify(standalone, CHAIN,
                    Set.of(MEMBER_A), 1).valid()).isTrue();
        }

        AppBlock last = blocks.getLast();
        AppMessage target = blocks.getFirst().messages().getFirst();
        EvidenceBundle oversized = bundle(CHAIN, target, blocks, last.height(),
                AppBlockCodec.blockHash(last), List.of(MEMBER_A), 1);

        assertThat(blocks).hasSizeLessThan(EvidenceBundle.MAX_BLOCKS);
        assertThat(totalBlockBytes)
                .isGreaterThan(EvidenceBundle.MAX_TOTAL_BLOCK_CBOR_BYTES);
        assertThat(EvidenceVerifier.verify(oversized, CHAIN,
                Set.of(MEMBER_A), 1))
                .satisfies(result -> {
                    assertThat(result.valid()).isFalse();
                    assertThat(result.failure())
                            .isEqualTo("app-block exceeds v1 work bounds");
                });
    }

    private static void assertInvalid(EvidenceBundle bundle) {
        assertThat(EvidenceVerifier.verifyInternalConsistencyAgainstDeclaredMembers(
                bundle).valid()).isFalse();
    }

    private static Fixture anchoredFixture(String chain, byte[] seed,
                                           List<String> members, int threshold) {
        AppMessage target = message(chain, 1, seed);
        byte[] proposer = KeyGenUtil.getPublicKeyFromPrivateKey(seed);
        AppBlock first = signedBlock(AppBlock.BLOCK_VERSION, chain, 5,
                filled(9), List.of(target), seed, FinalityCert.SCHEME_ED25519, proposer);
        AppBlock last = signedBlock(AppBlock.BLOCK_VERSION, chain, 6,
                AppBlockCodec.blockHash(first), List.of(), seed,
                FinalityCert.SCHEME_ED25519, proposer);
        return new Fixture(bundle(chain, target, List.of(first, last), 6,
                AppBlockCodec.blockHash(last), members, threshold));
    }

    private static EvidenceBundle bundle(String chain, AppMessage target,
                                         List<AppBlock> blocks, long anchorHeight,
                                         byte[] anchorHash, List<String> members,
                                         int threshold) {
        return new EvidenceBundle(chain, HexUtil.encodeHexString(target.getMessageId()),
                blocks, members, threshold, new EvidenceBundle.AnchorRef(
                anchorHeight, HexUtil.encodeHexString(anchorHash), TX, 42));
    }

    private static AppBlock signedBlock(int version, String chain, long height,
                                        byte[] previousHash, List<AppMessage> messages,
                                        byte[] seed, int scheme, byte[] proposer) {
        AppBlock unsigned = new AppBlock(version, chain, height, previousHash,
                0, new byte[0], 1_000 + height, AppBlockCodec.messagesRoot(messages),
                filled(7), messages, proposer, FinalityCert.empty());
        byte[] signature = CryptoConfiguration.INSTANCE.getSigningProvider()
                .sign(AppBlockCodec.blockHash(unsigned), seed);
        return unsigned.withCert(new FinalityCert(scheme, List.of(
                new FinalityCert.Signature(
                        KeyGenUtil.getPublicKeyFromPrivateKey(seed), signature))));
    }

    private static AppMessage message(String chain, long sequence) {
        return message(chain, sequence, SEED_A);
    }

    private static AppMessage message(String chain, long sequence, byte[] senderSeed) {
        return message(chain, sequence, senderSeed,
                "evidence".getBytes(StandardCharsets.UTF_8));
    }

    private static AppMessage message(String chain, long sequence, byte[] senderSeed,
                                      int bodyBytes) {
        return message(chain, sequence, senderSeed, new byte[bodyBytes]);
    }

    private static AppMessage message(String chain, long sequence, byte[] senderSeed,
                                      byte[] body) {
        byte[] sender = KeyGenUtil.getPublicKeyFromPrivateKey(senderSeed);
        long expires = 2_000_000_000L;
        byte[] id = AppMessage.computeMessageId(chain, "evidence", sender,
                sequence, expires, body);
        byte[] proof = CryptoConfiguration.INSTANCE.getSigningProvider().sign(
                AppMessage.signedBodyBytes(chain, "evidence", sender,
                        sequence, expires, body), senderSeed);
        return AppMessage.builder().version(AppMessage.ENVELOPE_VERSION)
                .messageId(id).chainId(chain).topic("evidence")
                .sender(sender).senderSeq(sequence).expiresAt(expires).body(body)
                .authScheme(0).authProof(proof).build();
    }

    private static String publicKey(byte[] seed) {
        return HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(seed));
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private record Fixture(EvidenceBundle bundle) {
    }
}
