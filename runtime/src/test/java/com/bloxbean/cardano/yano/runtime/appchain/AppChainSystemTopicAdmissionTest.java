package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AuthScheme;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AppChainSystemTopicAdmissionTest {
    private static final byte[] MEMBER_SEED = filled(73);

    @TempDir
    Path directory;

    private AppChainSubsystem subsystem;

    @AfterEach
    void closeSubsystem() {
        if (subsystem != null) {
            subsystem.stop();
        }
    }

    @Test
    void classifiesOnlyTheTwoDiffusionNamespacesAsNonSequenced() {
        assertThat(AppChainSystemTopics.isDiffusionOnly(ConsensusCodec.TOPIC_PROPOSE)).isTrue();
        assertThat(AppChainSystemTopics.isDiffusionOnly("~consensus/future")).isTrue();
        assertThat(AppChainSystemTopics.isDiffusionOnly(ScriptAnchorService.TOPIC_SIGN)).isTrue();
        assertThat(AppChainSystemTopics.isDiffusionOnly("~anchor/future")).isTrue();

        for (String sequenced : List.of(
                "ordinary", "~consensus", "~anchor", "~governance/member",
                "~fx/result", "~l1/deposit")) {
            assertThat(AppChainSystemTopics.isDiffusionOnly(sequenced))
                    .as("%s remains eligible for sequencing", sequenced)
                    .isFalse();
        }
    }

    @Test
    void onlyExactConsensusWireTopicsReceiveAnElevatedBodyLimit() throws Exception {
        AppMessageSigner signer = new AppMessageSigner(
                HexUtil.encodeHexString(MEMBER_SEED));
        AppChainConfig config = AppChainConfig.builder("system-topic-bounds")
                .signingKeyHex(HexUtil.encodeHexString(MEMBER_SEED))
                .memberKeysHex(Set.of(signer.publicKeyHex()))
                .proposerKeyHex(signer.publicKeyHex())
                .threshold(1)
                .blockIntervalMs(200)
                .maxMessageBytes(1_024)
                .blockMaxBytes(8_192)
                .stateCommitmentIdentity(TestStateCommitments.MPF)
                .build();
        subsystem = new AppChainSubsystem(config, 42, null, null,
                directory.resolve("ledger").toString(), null,
                LoggerFactory.getLogger(getClass()));

        byte[] ordinaryLimit = new byte[config.maxMessageBytes()];
        byte[] oversizedForFinalizedMessage = new byte[config.maxMessageBytes() + 1];

        assertBodyBoundary(ConsensusCodec.TOPIC_PROPOSE,
                Math.toIntExact(config.proposalMaxBytes()));
        assertBodyBoundary(ConsensusCodec.TOPIC_PREPARE,
                CertifiedConsensusCodec.MAX_VOTE_BYTES);
        assertBodyBoundary(ConsensusCodec.TOPIC_PREPARED,
                CertifiedConsensusCodec.MAX_QC_BYTES);
        assertBodyBoundary(ConsensusCodec.TOPIC_COMMIT,
                CertifiedConsensusCodec.MAX_VOTE_BYTES);
        assertBodyBoundary(ConsensusCodec.TOPIC_TIMEOUT,
                CertifiedConsensusCodec.MAX_TIMEOUT_BYTES);
        assertBodyBoundary(ConsensusCodec.TOPIC_NEW_VIEW,
                CertifiedConsensusCodec.MAX_NEW_VIEW_BYTES);
        assertBodyBoundary(ConsensusCodec.TOPIC_CERT,
                ConsensusCodec.MAX_CERT_NOTICE_BYTES);
        assertBodyBoundary("~governance/member", config.maxMessageBytes());

        assertThat(subsystem.verifyEnvelope(message(
                signer, "ordinary", ordinaryLimit, 1)).isAccepted()).isTrue();
        assertThat(subsystem.verifyEnvelope(message(
                signer, ConsensusCodec.TOPIC_PROPOSE,
                oversizedForFinalizedMessage, 2)).isAccepted()).isTrue();

        for (String sequencedOrLookalike : Set.of(
                "~governance/member",
                "~fx/result",
                "~l1/deposit",
                "~anchor/signature",
                "~consensus/propose/extra")) {
            assertThat(subsystem.verifyEnvelope(message(
                    signer, sequencedOrLookalike,
                    oversizedForFinalizedMessage, 3)).isAccepted())
                    .as("%s must retain the finalized-message size limit",
                            sequencedOrLookalike)
                    .isFalse();
        }

        AppMessage maximumSequencedSystemMessage = message(
                signer, "~test/sequenced", ordinaryLimit, 100);
        assertThat(subsystem.verifyEnvelope(maximumSequencedSystemMessage).isAccepted())
                .isTrue();
        subsystem.start();
        subsystem.onInboundMessages(List.of(maximumSequencedSystemMessage));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (subsystem.messageHeight(maximumSequencedSystemMessage.getMessageId()).isEmpty()
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(subsystem.messageHeight(maximumSequencedSystemMessage.getMessageId()))
                .as("an admitted system message must also satisfy proposal/finalization bounds")
                .isPresent();

        assertThat(subsystem.status()).containsEntry("poolSize", 0);
    }

    @Test
    void consensusReceivedBeforeStartIsDrainedAfterLifecycleActivation() {
        AppMessageSigner signer = new AppMessageSigner(
                HexUtil.encodeHexString(MEMBER_SEED));
        AppChainConfig config = AppChainConfig.builder("system-topic-bounds")
                .signingKeyHex(HexUtil.encodeHexString(MEMBER_SEED))
                .memberKeysHex(Set.of(signer.publicKeyHex()))
                .proposerKeyHex(signer.publicKeyHex())
                .threshold(1)
                .stateCommitmentIdentity(TestStateCommitments.MPF)
                .build();
        subsystem = new AppChainSubsystem(config, 42, null, null,
                directory.resolve("early-ledger").toString(), null,
                LoggerFactory.getLogger(getClass()));

        byte[] blockHash = filled(91);
        byte[] prepareBody = CertifiedConsensusCodec.encodeVote(
                new CertifiedConsensusCodec.Vote(CertifiedConsensusCodec.Phase.PREPARE,
                        1, 0, new byte[32], blockHash, new byte[32], new byte[64]));
        AppMessage earlyVote = message(signer, ConsensusCodec.TOPIC_PREPARE, prepareBody, 1);
        AppMessage earlyOrdinary = message(signer, "ordinary", new byte[]{1}, 2);

        subsystem.onInboundMessages(List.of(earlyVote, earlyOrdinary));
        subsystem.start();

        assertThat(subsystem.status())
                .containsEntry("received", 1L)
                .containsEntry("duplicates", 0L)
                .containsEntry("poolSize", 0);
    }

    private void assertBodyBoundary(String topic, int limit) {
        assertThat(subsystem.validEnvelopeBodyProfile(topic, new byte[limit]))
                .as("%s exact body limit", topic)
                .isTrue();
        assertThat(subsystem.validEnvelopeBodyProfile(topic, new byte[limit + 1]))
                .as("%s body limit + 1", topic)
                .isFalse();
    }

    private static AppMessage message(AppMessageSigner signer, String topic,
                                      byte[] body, long sequence) {
        long expiresAt = System.currentTimeMillis() / 1_000 + 600;
        byte[] signedBody = AppMessage.signedBodyBytes(
                "system-topic-bounds", topic, signer.publicKey(), sequence,
                expiresAt, body);
        return AppMessage.builder()
                .version(AppMessage.ENVELOPE_VERSION)
                .messageId(AppMessage.computeMessageId(
                        "system-topic-bounds", topic, signer.publicKey(),
                        sequence, expiresAt, body))
                .chainId("system-topic-bounds")
                .topic(topic)
                .sender(signer.publicKey())
                .senderSeq(sequence)
                .expiresAt(expiresAt)
                .body(body)
                .authScheme(AuthScheme.ED25519.getValue())
                .authProof(signer.sign(signedBody))
                .build();
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
