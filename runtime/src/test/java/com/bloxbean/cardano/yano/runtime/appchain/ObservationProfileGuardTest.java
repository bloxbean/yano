package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfile;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationKeys;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationProfileV1;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.WriteBatch;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservationProfileGuardTest {

    @Test
    void initializesCanonicalDisabledProfileAndRejectsLegacyState(@TempDir Path directory) {
        ObservationProfileV1 profile = ObservationProfileV1.disabled();
        ObservationProfileGuard guard = new ObservationProfileGuard(profile);

        try (AppLedgerStore ledger = ledger(directory.resolve("current"))) {
            MpfTrie trie = new MpfTrie(ledger.mpfNodeStore());
            guard.apply(1, TestStateCommitments.writer(trie));
            byte[] root = trie.getRootHash();
            AppBlock block = block(root);
            try (WriteBatch batch = new WriteBatch()) {
                ledger.commitBlock(block, AppBlockCodec.blockHash(block), root, batch);
            }

            assertThatCode(() -> guard.verifyRetained(ledger, "profile-test"))
                    .doesNotThrowAnyException();
            assertThat(ledger.stateGet(ObservationKeys.profile())).hasValue(profile.encode());
            assertThat(HexUtil.encodeHexString(root))
                    .isEqualTo("d5e3be48f87a539a133133f71bb05cc560d7a88ce734161bc8c4dae45a70c089");
        }

        try (AppLedgerStore legacy = ledger(directory.resolve("legacy"))) {
            AppBlock block = block(new byte[32]);
            try (WriteBatch batch = new WriteBatch()) {
                legacy.commitBlock(block, AppBlockCodec.blockHash(block), block.stateRoot(), batch);
            }
            assertThatThrownBy(() -> guard.verifyRetained(legacy, "profile-test"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("marker is absent");
        }
    }

    @Test
    void systemKernelRejectsObservationInputsWhileDisabled(@TempDir Path directory) {
        AppChainConfig config = config();
        EffectsSettings effects = EffectsSettings.from(config);
        AppChainConsensusProfile consensus = effects.consensusProfile(config);
        SystemInputKernel kernel = new SystemInputKernel(effects,
                new ConsensusProfileGuard(consensus),
                new ObservationProfileGuard(ObservationProfileV1.disabled()));

        try (AppLedgerStore ledger = ledger(directory)) {
            MpfTrie trie = new MpfTrie(ledger.mpfNodeStore());
            AppMessage message = message("~obs/result/v1", new byte[]{1});
            AppBlock block = new AppBlock(AppBlock.BLOCK_VERSION, "profile-test", 1,
                    AppBlock.GENESIS_PREV_HASH, 0, new byte[0], 1,
                    AppBlockCodec.messagesRoot(List.of(message)), new byte[32],
                    List.of(message),
                    new byte[32], FinalityCert.empty());

            assertThatThrownBy(() -> kernel.apply(machine(),
                    AppBlockExecutionContext.fromValidatedBlock(block),
                    TestStateCommitments.writer(trie), ledger.fxReader()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("disabled");
        }
    }

    private static AppStateMachine machine() {
        return new AppStateMachine() {
            @Override public String id() { return "noop"; }
            @Override public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                                        AppEffectEmitter effects) { }
        };
    }

    private static AppChainConfig config() {
        String member = "11".repeat(32);
        return AppChainConfig.builder("profile-test")
                .signingKeyHex("22".repeat(32))
                .memberKeysHex(Set.of(member))
                .proposerKeyHex(member)
                .maxBlockMessages(100)
                .stateCommitmentIdentity(TestStateCommitments.MPF)
                .build();
    }

    private static AppLedgerStore ledger(Path directory) {
        return new AppLedgerStore(directory.resolve("ledger").toString(),
                LoggerFactory.getLogger(ObservationProfileGuardTest.class));
    }

    private static AppBlock block(byte[] stateRoot) {
        return new AppBlock(AppBlock.BLOCK_VERSION, "profile-test", 1,
                AppBlock.GENESIS_PREV_HASH, 0, new byte[0], 1,
                AppBlockCodec.messagesRoot(List.of()), stateRoot, List.of(),
                new byte[32], FinalityCert.empty());
    }

    private static AppMessage message(String topic, byte[] body) {
        byte[] sender = new byte[32];
        long sequence = 1;
        long expiry = 4_000_000_000L;
        return AppMessage.builder()
                .messageId(AppMessage.computeMessageId(
                        "profile-test", topic, sender, sequence, expiry, body))
                .chainId("profile-test").topic(topic).sender(sender)
                .senderSeq(sequence).expiresAt(expiry).body(body)
                .authScheme(0).authProof(new byte[64]).build();
    }
}
