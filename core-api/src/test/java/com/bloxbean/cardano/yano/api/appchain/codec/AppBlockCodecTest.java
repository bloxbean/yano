package com.bloxbean.cardano.yano.api.appchain.codec;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppBlockCodecTest {

    private static AppMessage message(long seq, String body) {
        byte[] sender = new byte[32];
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        long expiresAt = 1900000000L;
        byte[] id = AppMessage.computeMessageId("codec-chain", "t", sender, seq, expiresAt, payload);
        return AppMessage.builder()
                .messageId(id).chainId("codec-chain").topic("t").sender(sender)
                .senderSeq(seq).expiresAt(expiresAt).body(payload)
                .authScheme(0).authProof(new byte[]{1, 2, 3})
                .build();
    }

    private static AppBlock block(List<AppMessage> messages, FinalityCert cert) {
        return new AppBlock(AppBlock.BLOCK_VERSION, "codec-chain", 7,
                new byte[32], 1234, new byte[]{9, 9}, 1710000000000L,
                AppBlockCodec.messagesRoot(messages), new byte[32],
                messages, new byte[32], cert);
    }

    @Test
    void roundTrip_fullBlockWithCert() {
        FinalityCert cert = new FinalityCert(FinalityCert.SCHEME_ED25519, List.of(
                new FinalityCert.Signature(new byte[32], new byte[64]),
                new FinalityCert.Signature(new byte[]{1}, new byte[]{2})));
        AppBlock original = block(List.of(message(1, "a"), message(2, "b")), cert);

        AppBlock decoded = AppBlockCodec.deserialize(AppBlockCodec.serialize(original));

        assertThat(decoded.chainId()).isEqualTo(original.chainId());
        assertThat(decoded.height()).isEqualTo(original.height());
        assertThat(decoded.prevHash()).isEqualTo(original.prevHash());
        assertThat(decoded.l1Slot()).isEqualTo(original.l1Slot());
        assertThat(decoded.messagesRoot()).isEqualTo(original.messagesRoot());
        assertThat(decoded.stateRoot()).isEqualTo(original.stateRoot());
        assertThat(decoded.messages()).hasSize(2);
        assertThat(decoded.messages().get(0).getMessageId())
                .isEqualTo(original.messages().get(0).getMessageId());
        assertThat(decoded.cert().signatures()).hasSize(2);

        // Hash is deterministic and cert-independent (header binds messages via root)
        assertThat(AppBlockCodec.blockHash(decoded)).isEqualTo(AppBlockCodec.blockHash(original));
        assertThat(AppBlockCodec.blockHash(original.withCert(FinalityCert.empty())))
                .isEqualTo(AppBlockCodec.blockHash(original));
    }

    @Test
    void messagesRoot_orderSensitive_andEmptyIsZero() {
        AppMessage m1 = message(1, "a");
        AppMessage m2 = message(2, "b");
        AppMessage m3 = message(3, "c");

        byte[] root123 = AppBlockCodec.messagesRoot(List.of(m1, m2, m3));
        byte[] root213 = AppBlockCodec.messagesRoot(List.of(m2, m1, m3));

        assertThat(root123).isNotEqualTo(root213);
        assertThat(AppBlockCodec.messagesRoot(List.of())).isEqualTo(new byte[32]);
        assertThat(AppBlockCodec.messagesRoot(List.of(m1, m2, m3)))
                .isEqualTo(root123); // deterministic
    }

    @Test
    void certCodec_roundTrip() {
        FinalityCert cert = new FinalityCert(FinalityCert.SCHEME_ED25519, List.of(
                new FinalityCert.Signature(new byte[]{5}, new byte[]{6, 7})));
        FinalityCert decoded = AppBlockCodec.deserializeCert(AppBlockCodec.serializeCert(cert));
        assertThat(decoded.scheme()).isEqualTo(cert.scheme());
        assertThat(decoded.signatures()).hasSize(1);
        assertThat(decoded.signatures().get(0).signer()).isEqualTo(new byte[]{5});
    }

    @Test
    void boundedCanonicalDecodersRejectDeepAndNonCanonicalPeerCbor() {
        AppBlock original = block(List.of(message(1, "a")), FinalityCert.empty());
        byte[] canonicalBlock = AppBlockCodec.serialize(original);
        byte[] nonCanonicalBlock = nonMinimalFirstUnsignedInteger(canonicalBlock);

        assertThat(AppBlockCodec.deserializeCanonical(
                canonicalBlock, canonicalBlock.length).height()).isEqualTo(7);
        assertThatThrownBy(() -> AppBlockCodec.deserializeCanonical(
                nonCanonicalBlock, nonCanonicalBlock.length))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AppBlockCodec.deserializeCanonical(
                nestedIndefiniteArrays(3_000), 16 * 1024))
                .isInstanceOf(IllegalArgumentException.class);

        FinalityCert cert = new FinalityCert(FinalityCert.SCHEME_ED25519,
                List.of(new FinalityCert.Signature(new byte[32], new byte[64])));
        byte[] canonicalCert = AppBlockCodec.serializeCert(cert);
        byte[] nonCanonicalCert = nonMinimalFirstUnsignedInteger(canonicalCert);
        assertThat(AppBlockCodec.deserializeCertCanonical(canonicalCert).signatures())
                .hasSize(1);
        assertThatThrownBy(() -> AppBlockCodec.deserializeCertCanonical(nonCanonicalCert))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AppBlockCodec.deserializeCertCanonical(
                nestedIndefiniteArrays(1_000)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] nonMinimalFirstUnsignedInteger(byte[] canonical) {
        assertThat((canonical[0] & 0xff) >>> 5).isEqualTo(4);
        assertThat(canonical[1] & 0xff).isLessThan(24);
        byte[] rewritten = new byte[canonical.length + 1];
        rewritten[0] = canonical[0];
        rewritten[1] = 0x18;
        rewritten[2] = canonical[1];
        System.arraycopy(canonical, 2, rewritten, 3, canonical.length - 2);
        return rewritten;
    }

    private static byte[] nestedIndefiniteArrays(int depth) {
        byte[] bytes = new byte[depth * 2 + 1];
        Arrays.fill(bytes, 0, depth, (byte) 0x9f);
        bytes[depth] = 0;
        Arrays.fill(bytes, depth + 1, bytes.length, (byte) 0xff);
        return bytes;
    }
}
