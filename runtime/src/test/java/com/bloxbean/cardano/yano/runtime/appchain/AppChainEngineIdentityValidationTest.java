package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AuthScheme;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerContext;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@Timeout(30)
class AppChainEngineIdentityValidationTest {

    private static final String CHAIN = "identity-chain";
    private static final String FOREIGN_CHAIN = "foreign-chain";

    @Test
    void rejectsWrongChainVersionAndInnerMessageIdentityOnLiveAndCatchUpPaths(
            @TempDir Path directory) throws Exception {
        byte[] seed = filled(31);
        AppMessageSigner signer = new AppMessageSigner(HexUtil.encodeHexString(seed));
        AppMessageSigner otherMember = new AppMessageSigner(
                HexUtil.encodeHexString(filled(32)));
        Set<String> members = Set.of(signer.publicKeyHex(), otherMember.publicKeyHex());
        AppChainConfig config = AppChainConfig.builder(CHAIN)
                .signingKeyHex(HexUtil.encodeHexString(seed))
                .memberKeysHex(members)
                .proposerKeyHex(signer.publicKeyHex())
                .threshold(1)
                .maxBlockMessages(2)
                .build();
        Logger logger = mock(Logger.class);
        AppLedgerStore ledger = new AppLedgerStore(
                directory.resolve("ledger").toString(), logger);
        AppChainEngine engine = new AppChainEngine(
                config, ledger, new AppMsgPool(10), new NoOpMachine(), signer,
                new MemberGroup(members, 1),
                new AlwaysSequencer(), 60_000, 10, config.blockMaxBytes(),
                (topic, body) -> null, logger);
        engine.setObservationValidator(message -> AppChainEngine.L1RefVerdict.UNKNOWN);
        try {
            AppBlock wrongChain = block(AppBlock.BLOCK_VERSION, FOREIGN_CHAIN,
                    List.of(), signer.publicKey());
            AppBlock wrongVersion = block(AppBlock.BLOCK_VERSION + 1, CHAIN,
                    List.of(), signer.publicKey());
            AppMessage foreignMessage = signedMessage(signer, FOREIGN_CHAIN, "payload", 7);
            AppMessage validMessage = signedMessage(signer, CHAIN, "payload", 8);
            AppBlock foreignInner = block(AppBlock.BLOCK_VERSION, CHAIN,
                    List.of(foreignMessage), signer.publicKey());

            engine.onConsensusMessage(proposal(signer, wrongChain, 1));
            verify(logger, timeout(5_000)).warn(
                    "{} chain identity does not match local app chain '{}' — rejecting",
                    "Proposal", CHAIN);
            assertThat(ledger.tipHeight()).isZero();

            engine.onConsensusMessage(proposal(signer, wrongVersion, 2));
            verify(logger, timeout(5_000)).warn(
                    "{} has unsupported app-block version — rejecting", "Proposal");
            assertThat(ledger.tipHeight()).isZero();

            engine.onConsensusMessage(proposal(signer, foreignInner, 3));
            verify(logger, timeout(5_000)).warn(
                    "Proposal contains an invalid message at height {} — rejecting block", 1L);
            assertThat(ledger.tipHeight()).isZero();

            AppBlock invalidL1Hash = new AppBlock(AppBlock.BLOCK_VERSION, CHAIN, 1,
                    AppBlock.GENESIS_PREV_HASH, 0, new byte[31], 1_000,
                    new byte[32], new byte[32], List.of(), signer.publicKey(),
                    FinalityCert.empty());
            AppBlock hashWithoutL1Slot = new AppBlock(AppBlock.BLOCK_VERSION, CHAIN, 1,
                    AppBlock.GENESIS_PREV_HASH, 0, new byte[32], 1_000,
                    new byte[32], new byte[32], List.of(), signer.publicKey(),
                    FinalityCert.empty());
            AppBlock l1SlotWithoutHash = new AppBlock(AppBlock.BLOCK_VERSION, CHAIN, 1,
                    AppBlock.GENESIS_PREV_HASH, 1, new byte[0], 1_000,
                    new byte[32], new byte[32], List.of(), signer.publicKey(),
                    FinalityCert.empty());
            AppBlock tooManyMessages = block(AppBlock.BLOCK_VERSION, CHAIN,
                    List.of(validMessage, validMessage, validMessage), signer.publicKey());
            AppBlock duplicateMessages = block(AppBlock.BLOCK_VERSION, CHAIN,
                    List.of(validMessage, validMessage), signer.publicKey());
            engine.onConsensusMessage(proposal(signer, invalidL1Hash, 4));
            engine.onConsensusMessage(proposal(signer, hashWithoutL1Slot, 5));
            engine.onConsensusMessage(proposal(signer, l1SlotWithoutHash, 6));
            engine.onConsensusMessage(proposal(signer, tooManyMessages, 7));
            verify(logger, timeout(5_000).times(4)).warn(
                    "{} is outside the app-block v1 structural profile — rejecting", "Proposal");
            engine.onConsensusMessage(proposal(signer, duplicateMessages, 8));
            verify(logger, timeout(5_000)).warn(
                    "{} has duplicate or malformed message identities — rejecting", "Proposal");

            AppMessage wrongEnvelopeVersion = copyMessage(validMessage,
                    AppMessage.ENVELOPE_VERSION + 1,
                    validMessage.getAuthScheme(), validMessage.getAuthProof());
            AppMessage wrongAuthScheme = copyMessage(validMessage,
                    validMessage.getVersion(), FinalityCert.SCHEME_ED25519 + 1,
                    validMessage.getAuthProof());
            AppMessage oversizedTopic = signedMessage(signer, CHAIN,
                    "é".repeat(AppChainConfig.MAX_TOPIC_BYTES / 2 + 1),
                    new byte[]{1}, 9);
            AppMessage oversizedBody = signedMessage(signer, CHAIN,
                    "identity-test", new byte[config.maxMessageBytes() + 1], 10);
            byte[] oversizedObservationBody = new L1Observation(
                    "test-observer", filled(91), 0, filled(92),
                    new byte[config.maxMessageBytes()]).encode();
            assertThat(oversizedObservationBody.length).isGreaterThan(config.maxMessageBytes());
            AppMessage oversizedReservedBody = signedMessage(signer, CHAIN,
                    "~l1/test-observer", oversizedObservationBody, 11);
            AppMessage embeddedConsensus = signedMessage(signer, CHAIN,
                    ConsensusCodec.TOPIC_VOTE, new byte[]{1}, 12);
            AppMessage embeddedAnchor = signedMessage(signer, CHAIN,
                    ScriptAnchorService.TOPIC_SIGN, new byte[]{1}, 13);
            List<AppBlock> invalidMessageProfiles = List.of(
                    block(AppBlock.BLOCK_VERSION, CHAIN,
                            List.of(wrongEnvelopeVersion), signer.publicKey()),
                    block(AppBlock.BLOCK_VERSION, CHAIN,
                            List.of(wrongAuthScheme), signer.publicKey()),
                    block(AppBlock.BLOCK_VERSION, CHAIN,
                            List.of(oversizedTopic), signer.publicKey()),
                    block(AppBlock.BLOCK_VERSION, CHAIN,
                            List.of(oversizedBody), signer.publicKey()),
                    block(AppBlock.BLOCK_VERSION, CHAIN,
                            List.of(oversizedReservedBody), signer.publicKey()),
                    block(AppBlock.BLOCK_VERSION, CHAIN,
                            List.of(embeddedConsensus), signer.publicKey()),
                    block(AppBlock.BLOCK_VERSION, CHAIN,
                            List.of(embeddedAnchor), signer.publicKey()));
            long proposalSequence = 14;
            for (AppBlock invalid : invalidMessageProfiles) {
                engine.onConsensusMessage(proposal(signer, invalid, proposalSequence++));
            }
            verify(logger, timeout(5_000).times(8)).warn(
                    "Proposal contains an invalid message at height {} — rejecting block", 1L);

            byte[] oversizedProposal = new byte[Math.toIntExact(
                    config.proposalMaxBytes() + 1)];
            engine.onConsensusMessage(signedMessage(signer, CHAIN,
                    ConsensusCodec.TOPIC_PROPOSE, oversizedProposal, proposalSequence));
            verify(logger, timeout(5_000)).warn(
                    "Proposal exceeds the v1 proposal byte budget ({} > {}) — rejecting",
                    oversizedProposal.length, config.proposalMaxBytes());

            byte[] deeplyNestedCbor = nestedIndefiniteArrays(3_000);
            engine.onConsensusMessage(signedMessage(signer, CHAIN,
                    ConsensusCodec.TOPIC_PROPOSE, deeplyNestedCbor, 40));
            verify(logger, timeout(5_000)).error(
                    "Error handling consensus message on {} (errorType={})",
                    ConsensusCodec.TOPIC_PROPOSE, "java.lang.IllegalArgumentException");
            engine.onConsensusMessage(signedMessage(signer, CHAIN,
                    ConsensusCodec.TOPIC_CERT, deeplyNestedCbor, 41));
            verify(logger, timeout(5_000)).error(
                    "Error handling consensus message on {} (errorType={})",
                    ConsensusCodec.TOPIC_CERT, "java.lang.IllegalArgumentException");

            engine.onCertifiedBlocks(List.of(AppBlockCodec.serialize(wrongChain)));
            verify(logger, timeout(5_000)).warn(
                    "{} chain identity does not match local app chain '{}' — rejecting",
                    "Catch-up block", CHAIN);
            assertThat(ledger.tipHeight()).isZero();

            engine.onCertifiedBlocks(List.of(AppBlockCodec.serialize(wrongVersion)));
            verify(logger, timeout(5_000)).warn(
                    "{} has unsupported app-block version — rejecting", "Catch-up block");
            assertThat(ledger.tipHeight()).isZero();

            engine.onCertifiedBlocks(List.of(AppBlockCodec.serialize(foreignInner)));
            verify(logger, timeout(5_000)).warn(
                    "Catch-up block contains an invalid message at height {} — rejecting", 1L);
            assertThat(ledger.tipHeight()).isZero();

            engine.onCertifiedBlocks(List.of(AppBlockCodec.serialize(invalidL1Hash)));
            engine.onCertifiedBlocks(List.of(AppBlockCodec.serialize(hashWithoutL1Slot)));
            engine.onCertifiedBlocks(List.of(AppBlockCodec.serialize(l1SlotWithoutHash)));
            engine.onCertifiedBlocks(List.of(AppBlockCodec.serialize(tooManyMessages)));
            verify(logger, timeout(5_000).times(4)).warn(
                    "{} is outside the app-block v1 structural profile — rejecting",
                    "Catch-up block");
            engine.onCertifiedBlocks(List.of(AppBlockCodec.serialize(duplicateMessages)));
            verify(logger, timeout(5_000)).warn(
                    "{} has duplicate or malformed message identities — rejecting",
                    "Catch-up block");
            for (AppBlock invalid : invalidMessageProfiles) {
                engine.onCertifiedBlocks(List.of(AppBlockCodec.serialize(invalid)));
            }
            verify(logger, timeout(5_000).times(8)).warn(
                    "Catch-up block contains an invalid message at height {} — rejecting", 1L);

            engine.onCertifiedBlocks(List.of(
                    new byte[(int) config.blockMaxBytes() + 1]));
            verify(logger, timeout(5_000)).warn(
                    "Catch-up block exceeds the configured v1 byte profile — stopping batch");

            engine.onCertifiedBlocks(List.of(deeplyNestedCbor));
            verify(logger, timeout(5_000)).warn(
                    "Catch-up block rejected (errorType={})",
                    "java.lang.IllegalArgumentException");

            AppBlock paddedCert = withRepeatedCertificate(block(
                    AppBlock.BLOCK_VERSION, CHAIN, List.of(), signer.publicKey()),
                    signer, AppChainConfig.MAX_MEMBERS + 1);
            engine.onCertifiedBlocks(List.of(AppBlockCodec.serialize(paddedCert)));
            verify(logger, timeout(5_000)).warn(
                    "Catch-up block cert verification FAILED at height {} — rejecting", 1L);
            assertThat(ledger.tipHeight()).isZero();

            AppBlock certificateBase = block(AppBlock.BLOCK_VERSION, CHAIN,
                    List.of(), signer.publicKey());
            for (FinalityCert invalid : invalidCertificates(
                    certificateBase, signer, otherMember)) {
                engine.onCertifiedBlocks(List.of(AppBlockCodec.serialize(
                        certificateBase.withCert(invalid))));
            }
            verify(logger, timeout(5_000).times(5)).warn(
                    "Catch-up block cert verification FAILED at height {} — rejecting", 1L);
            assertThat(ledger.tipHeight()).isZero();

            AppBlock first = certify(block(AppBlock.BLOCK_VERSION, CHAIN,
                    List.of(validMessage), signer.publicKey()), signer);
            engine.onCertifiedBlocks(List.of(AppBlockCodec.serialize(first)));
            awaitTip(ledger, 1);
            AppBlock replayProposal = new AppBlock(AppBlock.BLOCK_VERSION, CHAIN, 2,
                    AppBlockCodec.blockHash(first), 0, new byte[0], 2_000,
                    AppBlockCodec.messagesRoot(List.of(validMessage)), new byte[32],
                    List.of(validMessage), signer.publicKey(), FinalityCert.empty());
            engine.onConsensusMessage(proposal(signer, replayProposal, 30));
            verify(logger, timeout(5_000)).warn(
                    "{} replays an already-finalized message identity — rejecting", "Proposal");
            assertThat(ledger.tipHeight()).isEqualTo(1);

            AppBlock replayCertified = certify(replayProposal, signer);
            engine.onCertifiedBlocks(List.of(AppBlockCodec.serialize(replayCertified)));
            verify(logger, timeout(5_000)).warn(
                    "{} replays an already-finalized message identity — rejecting",
                    "Catch-up block");
            assertThat(ledger.tipHeight()).isEqualTo(1);
        } finally {
            engine.close();
            engine.closeCompletion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            ledger.close();
        }
    }

    @Test
    void rejectsMalformedAndOverMemberLimitCertificatesBeforeCommittingPendingRound(
            @TempDir Path directory) throws Exception {
        AppMessageSigner proposer = new AppMessageSigner(HexUtil.encodeHexString(filled(41)));
        AppMessageSigner follower = new AppMessageSigner(HexUtil.encodeHexString(filled(42)));
        AppMessageSigner thirdMember = new AppMessageSigner(HexUtil.encodeHexString(filled(43)));
        Set<String> members = Set.of(proposer.publicKeyHex(), follower.publicKeyHex(),
                thirdMember.publicKeyHex());
        AppChainConfig config = AppChainConfig.builder(CHAIN)
                .signingKeyHex(HexUtil.encodeHexString(filled(42)))
                .memberKeysHex(members)
                .proposerKeyHex(proposer.publicKeyHex())
                .threshold(2)
                .build();
        Logger logger = mock(Logger.class);
        AppLedgerStore ledger = new AppLedgerStore(
                directory.resolve("ledger").toString(), logger);
        CountDownLatch voted = new CountDownLatch(1);
        AppChainEngine engine = new AppChainEngine(
                config, ledger, new AppMsgPool(10), new NoOpMachine(), follower,
                new MemberGroup(members, 2), new AlwaysSequencer(), 60_000, 10,
                config.blockMaxBytes(), (topic, body) -> {
                    if (ConsensusCodec.TOPIC_VOTE.equals(topic)) {
                        voted.countDown();
                    }
                    return null;
                }, logger);
        try {
            AppBlock proposed = block(AppBlock.BLOCK_VERSION, CHAIN,
                    List.of(), proposer.publicKey());
            engine.onConsensusMessage(proposal(proposer, proposed, 1));
            assertThat(voted.await(5, TimeUnit.SECONDS)).isTrue();

            AppBlock padded = withRepeatedCertificate(proposed, proposer,
                    AppChainConfig.MAX_MEMBERS + 1);
            List<FinalityCert> invalidCertificates = new ArrayList<>(
                    invalidCertificates(proposed, proposer, thirdMember));
            invalidCertificates.add(padded.cert());
            long sequence = 2;
            for (FinalityCert invalid : invalidCertificates) {
                byte[] certNotice = ConsensusCodec.encodeCertNotice(
                        proposed.height(), AppBlockCodec.blockHash(proposed),
                        AppBlockCodec.serializeCert(invalid));
                engine.onConsensusMessage(signedMessage(proposer, CHAIN,
                        ConsensusCodec.TOPIC_CERT, certNotice, sequence++));
            }

            verify(logger, timeout(5_000).times(5)).warn(
                    "Cert verification FAILED for height {} — rejecting", 1L);
            assertThat(ledger.tipHeight()).isZero();
        } finally {
            engine.close();
            engine.closeCompletion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            ledger.close();
        }
    }

    private static AppBlock block(int version, String chainId,
                                  List<AppMessage> messages, byte[] proposer) {
        return new AppBlock(version, chainId, 1, AppBlock.GENESIS_PREV_HASH,
                0, new byte[0], 1_000, AppBlockCodec.messagesRoot(messages),
                new byte[32], messages, proposer, FinalityCert.empty());
    }

    private static AppBlock withRepeatedCertificate(AppBlock block,
                                                    AppMessageSigner signer,
                                                    int count) {
        byte[] hash = AppBlockCodec.blockHash(block);
        FinalityCert.Signature signature = new FinalityCert.Signature(
                signer.publicKey(), signer.sign(hash));
        List<FinalityCert.Signature> signatures = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            signatures.add(signature);
        }
        return block.withCert(new FinalityCert(
                FinalityCert.SCHEME_ED25519, signatures));
    }

    private static AppBlock certify(AppBlock block, AppMessageSigner signer) {
        byte[] hash = AppBlockCodec.blockHash(block);
        return block.withCert(new FinalityCert(FinalityCert.SCHEME_ED25519,
                List.of(new FinalityCert.Signature(signer.publicKey(), signer.sign(hash)))));
    }

    private static List<FinalityCert> invalidCertificates(
            AppBlock block, AppMessageSigner signer, AppMessageSigner otherMember) {
        byte[] hash = AppBlockCodec.blockHash(block);
        FinalityCert.Signature valid = new FinalityCert.Signature(
                signer.publicKey(), signer.sign(hash));
        FinalityCert.Signature validOther = new FinalityCert.Signature(
                otherMember.publicKey(), otherMember.sign(hash));
        AppMessageSigner outsider = new AppMessageSigner(HexUtil.encodeHexString(filled(99)));
        FinalityCert.Signature nonmember = new FinalityCert.Signature(
                outsider.publicKey(), outsider.sign(hash));
        FinalityCert.Signature malformed = new FinalityCert.Signature(
                otherMember.publicKey(), new byte[63]);
        FinalityCert.Signature invalidCrypto = new FinalityCert.Signature(
                otherMember.publicKey(), new byte[64]);
        return List.of(
                new FinalityCert(FinalityCert.SCHEME_ED25519,
                        List.of(valid, validOther, valid)),
                new FinalityCert(FinalityCert.SCHEME_ED25519,
                        List.of(valid, validOther, nonmember)),
                new FinalityCert(FinalityCert.SCHEME_ED25519,
                        List.of(valid, validOther, malformed)),
                new FinalityCert(FinalityCert.SCHEME_ED25519,
                        List.of(valid, validOther, invalidCrypto)));
    }

    private static void awaitTip(AppLedgerStore ledger, long expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ledger.tipHeight() != expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(ledger.tipHeight()).isEqualTo(expected);
    }

    private static AppMessage proposal(AppMessageSigner signer, AppBlock block,
                                       long sequence) {
        return signedMessage(signer, CHAIN, ConsensusCodec.TOPIC_PROPOSE,
                AppBlockCodec.serialize(block), sequence);
    }

    private static AppMessage signedMessage(AppMessageSigner signer, String chainId,
                                            String body, long sequence) {
        return signedMessage(signer, chainId, "identity-test",
                body.getBytes(StandardCharsets.UTF_8), sequence);
    }

    private static AppMessage signedMessage(AppMessageSigner signer, String chainId,
                                            String topic, byte[] body, long sequence) {
        long expiresAt = System.currentTimeMillis() / 1_000 + 600;
        byte[] signedBody = AppMessage.signedBodyBytes(chainId, topic,
                signer.publicKey(), sequence, expiresAt, body);
        return AppMessage.builder()
                .version(AppMessage.ENVELOPE_VERSION)
                .messageId(AppMessage.computeMessageId(chainId, topic,
                        signer.publicKey(), sequence, expiresAt, body))
                .chainId(chainId)
                .topic(topic)
                .sender(signer.publicKey())
                .senderSeq(sequence)
                .expiresAt(expiresAt)
                .body(body)
                .authScheme(AuthScheme.ED25519.getValue())
                .authProof(signer.sign(signedBody))
                .build();
    }

    private static AppMessage copyMessage(AppMessage source, int version,
                                          int authScheme, byte[] authProof) {
        return AppMessage.builder()
                .version(version)
                .messageId(source.getMessageId())
                .chainId(source.getChainId())
                .topic(source.getTopic())
                .sender(source.getSender())
                .senderSeq(source.getSenderSeq())
                .expiresAt(source.getExpiresAt())
                .body(source.getBody())
                .authScheme(authScheme)
                .authProof(authProof)
                .build();
    }

    private static byte[] nestedIndefiniteArrays(int depth) {
        byte[] bytes = new byte[depth * 2 + 1];
        Arrays.fill(bytes, 0, depth, (byte) 0x9f);
        bytes[depth] = 0;
        Arrays.fill(bytes, depth + 1, bytes.length, (byte) 0xff);
        return bytes;
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static final class NoOpMachine implements AppStateMachine {
        @Override
        public String id() {
            return "identity-test";
        }

        @Override
        public void apply(AppBlock block, AppStateWriter writer) {
        }
    }

    private static final class AlwaysSequencer implements SequencerMode {
        @Override
        public String id() {
            return "identity-test";
        }

        @Override
        public void init(SequencerContext context) {
        }

        @Override
        public boolean shouldProposeNow(long height) {
            return true;
        }

        @Override
        public ProposalEligibility checkProposal(byte[] proposerKey, long height) {
            return ProposalEligibility.ACCEPT;
        }
    }
}
