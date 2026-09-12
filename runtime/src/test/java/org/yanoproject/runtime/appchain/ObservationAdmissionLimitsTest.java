package org.yanoproject.runtime.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.yanoproject.api.appchain.AppBlock;
import org.yanoproject.api.appchain.AppChainConfig;
import org.yanoproject.api.appchain.FinalityCert;
import org.yanoproject.api.appchain.codec.AppBlockCodec;
import org.yanoproject.api.appchain.observation.ObservationAnchorType;
import org.yanoproject.api.appchain.observation.ObservationTick;
import org.yanoproject.api.appchain.observation.ObservationTopics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ObservationAdmissionLimitsTest {
    @Test
    void strictSenderSequenceValidationKeepsObservationAndOrdinaryDomainsSeparate(@TempDir Path directory) {
        String seed = "21".repeat(32);
        AppMessageSigner signer = new AppMessageSigner(seed);
        AppChainConfig config = AppChainConfig.builder("limits").signingKeyHex(seed)
                .memberKeysHex(Set.of(signer.publicKeyHex())).proposerKeyHex(signer.publicKeyHex())
                .enforceSenderSeq(true).stateCommitmentIdentity(TestStateCommitments.MPF).build();
        var logger = LoggerFactory.getLogger(getClass());
        try (AppLedgerStore ledger = new AppLedgerStore(directory.toString(), logger, TestStateCommitments.MPF);
             AppChainEngine engine = new AppChainEngine(config, ledger, new AppMsgPool(16),
                     new OrderedLogStateMachine(), signer, new MemberGroup(Set.of(signer.publicKeyHex()), 1),
                     new FixedSequencerMode(), 60_000, 16, config.blockMaxBytes(), (topic, body) -> null, logger)) {
            AppMessage result = message(1, 1000, ObservationTopics.RESULT, new byte[]{1});
            AppMessage effect = message(1, 1, "~fx/result", new byte[]{1});
            AppMessage vote = message(1, 2, "~governance/member", new byte[]{1});
            assertThat(engine.verifySenderSeqs(block(List.of(result, effect, vote)), "test")).isTrue();
            assertThat(engine.verifySenderSeqs(block(List.of(result, vote, effect)), "test")).isFalse();
            assertThat(engine.verifySenderSeqs(block(List.of(result, result, effect)), "test")).isFalse();
        }
    }

    private static AppBlock block(List<AppMessage> messages) {
        return new AppBlock(AppBlock.BLOCK_VERSION, "limits", 1, new byte[32], 0, new byte[0], 1,
                AppBlockCodec.messagesRoot(messages), new byte[32], messages, new byte[32], FinalityCert.empty());
    }

    @Test
    void tickDedupIgnoresEnvelopeSequenceAndReservesOrdinaryPoolCapacity() {
        AtomicLong time = new AtomicLong();
        AppMsgPool pool = new AppMsgPool(16, 4, time::get);
        assertThat(pool.add(tick(1, 1, 100))).isEqualTo(AppMsgPool.AddResult.ADDED);
        assertThat(pool.add(tick(1, 2, 100))).isEqualTo(AppMsgPool.AddResult.DUPLICATE);
        assertThat(pool.add(tick(1, 3, 99))).isEqualTo(AppMsgPool.AddResult.DUPLICATE);
        assertThat(pool.add(tick(1, 4, 101))).isEqualTo(AppMsgPool.AddResult.ADDED);
        assertThat(pool.size()).isEqualTo(1);
        for (int sender = 2; sender <= 4; sender++) {
            assertThat(pool.add(tick(sender, 1, 100))).isEqualTo(AppMsgPool.AddResult.ADDED);
        }
        assertThat(pool.add(tick(5, 1, 100))).isEqualTo(AppMsgPool.AddResult.FULL);
        AppMessage ordinary = message(5, 2, "ordinary", new byte[0]);
        assertThat(pool.add(ordinary)).isEqualTo(AppMsgPool.AddResult.ADDED);
        pool.remove(List.of(tick(1, 4, 101)));
        assertThat(pool.add(tick(1, 5, 101))).isEqualTo(AppMsgPool.AddResult.DUPLICATE);
        time.set(60_000_000_001L);
        pool.sweepExpired();
        assertThat(pool.size()).isEqualTo(1); // Only the ordinary message remains.
        assertThat(pool.add(tick(5, 2, 100))).isEqualTo(AppMsgPool.AddResult.ADDED);
    }

    @Test
    void diffusionIsRateBoundedDeduplicatedAndRetriesAfterCooldown() {
        AtomicLong time = new AtomicLong();
        List<String> sent = new ArrayList<>();
        ObservationDiffusionLimiter limiter = new ObservationDiffusionLimiter(2, 16,
                time::get, (topic, body) -> sent.add(topic));
        limiter.offer("report", new byte[]{1});
        limiter.offer("report", new byte[]{1});
        limiter.offer("certificate", new byte[]{2});
        limiter.offer("next", new byte[]{3});
        assertThat(sent).containsExactly("report", "certificate");
        time.addAndGet(1_000_000_000L);
        limiter.drain();
        assertThat(sent).containsExactly("report", "certificate", "next");
        limiter.offer("report", new byte[]{1});
        assertThat(sent).hasSize(3);
        time.addAndGet(10_000_000_000L);
        limiter.offer("report", new byte[]{1});
        assertThat(sent).hasSize(4);
        limiter.offer("oversized", new byte[17]);
        assertThat(sent).hasSize(4);
    }

    private static AppMessage tick(int sender, long sequence, long anchor) {
        return message(sender, sequence, ObservationTopics.TICK,
                new ObservationTick(1, ObservationAnchorType.VERIFIED_L1_SLOT, anchor).encode());
    }

    private static AppMessage message(int sender, long sequence, String topic, byte[] body) {
        byte[] key = new byte[32];
        key[0] = (byte) sender;
        return AppMessage.builder().chainId("limits").topic(topic).sender(key).senderSeq(sequence)
                .expiresAt(Long.MAX_VALUE).body(body).authScheme(0).authProof(new byte[64])
                .messageId(AppMessage.computeMessageId("limits", topic, key, sequence, Long.MAX_VALUE, body)).build();
    }
}
