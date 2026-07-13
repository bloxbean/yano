package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AuthScheme;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectId;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectIntent;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectOutcome;
import com.bloxbean.cardano.yano.api.appchain.effects.FxKeys;
import com.bloxbean.cardano.yano.api.appchain.effects.FxResultBody;
import com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-010 defense in depth: unauthorized results never consume proposal capacity. */
@Timeout(60)
class FxResultSignerAdmissionTest {

    private static final String CHAIN_ID = "fx-result-admission";
    private static final byte[] PROPOSER_KEY = seed(81);
    private static final byte[] EXECUTOR_KEY = seed(82);

    private AppChainSubsystem node;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop();
        }
    }

    @Test
    void proposerDropsUnauthorizedResult_andIncludesDesignatedSigner(@TempDir Path dir)
            throws Exception {
        String proposer = publicKey(PROPOSER_KEY);
        String executor = publicKey(EXECUTOR_KEY);
        AppChainConfig config = AppChainConfig.builder(CHAIN_ID)
                .signingKeyHex(HexUtil.encodeHexString(PROPOSER_KEY))
                .memberKeysHex(Set.of(proposer, executor))
                .proposerKeyHex(proposer)
                .threshold(1)
                .blockIntervalMs(150)
                .pluginSettings(Map.of(
                        "effects.enabled", "true",
                        "effects.result.signers", executor))
                .build();
        node = new AppChainSubsystem(config, 42, null, emitter(), dir.toString(), null,
                LoggerFactory.getLogger(FxResultSignerAdmissionTest.class));
        node.start();

        String emittedBy = node.submit("t", "emit-one".getBytes(StandardCharsets.UTF_8));
        byte[] emittedMessageId = HexUtil.decodeHexString(emittedBy);
        awaitTrue("effect emitted", () -> node.messageHeight(emittedMessageId).isPresent());
        long effectHeight = node.messageHeight(emittedMessageId).orElseThrow();
        EffectId effectId = new EffectId(CHAIN_ID, effectHeight, 0);
        assertThat(node.effect(effectHeight, 0)).isPresent();

        byte[] result = new FxResultBody(FxResultBody.BODY_VERSION, effectHeight, 0,
                EffectOutcome.CONFIRMED, "tx-1".getBytes(StandardCharsets.UTF_8), null).encode();
        AppMessage unauthorized = signedResult(PROPOSER_KEY, 4_000_000_000_000L, result);
        node.onInboundMessages(List.of(unauthorized));
        awaitTrue("unauthorized result removed from pool",
                () -> ((Number) node.status().get("poolSize")).intValue() == 0);

        assertThat(node.messageHeight(unauthorized.getMessageId())).isEmpty();
        assertThat(node.stateValue(FxKeys.doneKey(effectId))).isEmpty();

        AppMessage authorized = signedResult(EXECUTOR_KEY, 1, result);
        node.onInboundMessages(List.of(authorized));
        awaitTrue("authorized result finalized",
                () -> node.messageHeight(authorized.getMessageId()).isPresent());
        assertThat(node.stateValue(FxKeys.doneKey(effectId))).isPresent();
    }

    private static AppStateMachine emitter() {
        return new AppStateMachine() {
            @Override public String id() { return "fx-result-emitter"; }
            @Override public void apply(AppBlock block, AppStateWriter writer) { }
            @Override
            public void apply(AppBlock block, AppStateWriter writer, AppEffectEmitter effects) {
                block.messages().stream()
                        .filter(message -> !message.getTopic().startsWith("~"))
                        .forEach(message -> effects.emit(EffectIntent
                                .of("test.action", message.getBody())
                                .scope("admission-test")
                                .result(ResultPolicy.CHAIN)
                                .build()));
            }
        };
    }

    private static AppMessage signedResult(byte[] seed, long sequence, byte[] body) {
        AppMessageSigner signer = new AppMessageSigner(HexUtil.encodeHexString(seed));
        long expiresAt = System.currentTimeMillis() / 1000 + 600;
        byte[] signedBody = AppMessage.signedBodyBytes(CHAIN_ID, FxResultBody.TOPIC,
                signer.publicKey(), sequence, expiresAt, body);
        return AppMessage.builder()
                .messageId(AppMessage.computeMessageId(CHAIN_ID, FxResultBody.TOPIC,
                        signer.publicKey(), sequence, expiresAt, body))
                .chainId(CHAIN_ID)
                .topic(FxResultBody.TOPIC)
                .sender(signer.publicKey())
                .senderSeq(sequence)
                .expiresAt(expiresAt)
                .body(body)
                .authScheme(AuthScheme.ED25519.getValue())
                .authProof(signer.sign(signedBody))
                .build();
    }

    private static String publicKey(byte[] seed) {
        return HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(seed));
    }

    private static byte[] seed(int fill) {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) fill);
        return seed;
    }

    private static void awaitTrue(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }
}
