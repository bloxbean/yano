package com.bloxbean.cardano.yano.appchain.client;

import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.evidence.MessageInclusionProof;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PortableProofBundleTest {
    private static final byte[] SEED = filled(11);

    @Test
    void distinguishesFinalizationContentBindingAndAvailability() {
        AppMessage message = signedMessage();
        AppBlock block = block(message);
        MessageInclusionProof proof = MessageInclusionProof.fromBlock(
                block, message.getMessageId()).orElseThrow();
        var trusted = new PortableProofBundle.TrustedBlock(
                block.chainId(), block.height(), AppBlockCodec.blockHash(block),
                block.messagesRoot(), PortableProofBundle.TrustedBlock.Source.CARDANO_ANCHOR);

        var idOnly = new PortableProofBundle<>(proof).verify(trusted, null);
        assertThat(idOnly.finalization())
                .isEqualTo(PortableProofBundle.FinalizationStatus.VERIFIED);
        assertThat(idOnly.content()).isEqualTo(PortableProofBundle.ContentStatus.ID_ONLY);
        assertThat(idOnly.availability())
                .isEqualTo(PortableProofBundle.AvailabilityStatus.NOT_PROVEN);

        var withBody = new PortableProofBundle<>(1, proof, message, null, null)
                .verify(trusted, null);
        assertThat(withBody.valid()).isTrue();
        assertThat(withBody.content())
                .isEqualTo(PortableProofBundle.ContentStatus.SUPPLIED_CONTENT_VERIFIED);
        assertThat(withBody.availability())
                .isEqualTo(PortableProofBundle.AvailabilityStatus.NOT_PROVEN);
    }

    @Test
    void rejectsBlockAndContentSubstitution() {
        AppMessage message = signedMessage();
        AppBlock block = block(message);
        MessageInclusionProof proof = MessageInclusionProof.fromBlock(
                block, message.getMessageId()).orElseThrow();
        var wrongBlock = new PortableProofBundle.TrustedBlock(
                block.chainId(), block.height(), filled(90), block.messagesRoot(),
                PortableProofBundle.TrustedBlock.Source.CALLER_PINNED);
        AppMessage other = signedMessage("other".getBytes(StandardCharsets.UTF_8));

        var result = new PortableProofBundle<>(1, proof, other, null, null)
                .verify(wrongBlock, null);
        assertThat(result.finalization())
                .isEqualTo(PortableProofBundle.FinalizationStatus.INVALID);
        assertThat(result.content())
                .isEqualTo(PortableProofBundle.ContentStatus.SUPPLIED_CONTENT_INVALID);
        assertThat(result.valid()).isFalse();
    }

    private static AppMessage signedMessage() {
        return signedMessage("portable".getBytes(StandardCharsets.UTF_8));
    }

    private static AppMessage signedMessage(byte[] body) {
        String chain = "portable-chain";
        String topic = "records";
        byte[] sender = KeyGenUtil.getPublicKeyFromPrivateKey(SEED);
        long sequence = 1;
        long expires = 2_000_000_000L;
        byte[] id = AppMessage.computeMessageId(chain, topic, sender, sequence, expires, body);
        byte[] signature = CryptoConfiguration.INSTANCE.getSigningProvider().sign(
                AppMessage.signedBodyBytes(chain, topic, sender, sequence, expires, body), SEED);
        return AppMessage.builder().version(AppMessage.ENVELOPE_VERSION).messageId(id)
                .chainId(chain).topic(topic).sender(sender).senderSeq(sequence)
                .expiresAt(expires).body(body).authScheme(0).authProof(signature).build();
    }

    private static AppBlock block(AppMessage message) {
        List<AppMessage> messages = List.of(message);
        return new AppBlock(AppBlock.BLOCK_VERSION, message.getChainId(), 3,
                filled(1), 0, new byte[0], 123, AppBlockCodec.messagesRoot(messages),
                filled(2), messages, filled(3), FinalityCert.empty());
    }

    private static byte[] filled(int value) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) value);
        return result;
    }
}
