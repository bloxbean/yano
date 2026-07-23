package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfile;
import com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfileCommitment;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
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

class ConsensusProfileGuardTest {

    @Test
    void initializesAtHeightOneAndRejectsRetainedProfileDrift(@TempDir Path directory) {
        AppChainConfig config = config();
        AppChainConsensusProfile profile = EffectsSettings.from(config).consensusProfile(config);
        ConsensusProfileGuard guard = new ConsensusProfileGuard(profile);

        try (AppLedgerStore ledger = ledger(directory)) {
            MpfTrie trie = new MpfTrie(ledger.mpfNodeStore());
            guard.apply(1, trie);
            byte[] root = trie.getRootHash();
            AppBlock block = block(root);
            try (WriteBatch batch = new WriteBatch()) {
                ledger.commitBlock(block, AppBlockCodec.blockHash(block), root, batch);
            }

            assertThatCode(() -> guard.verifyRetained(ledger, config.chainId()))
                    .doesNotThrowAnyException();
            assertThat(ledger.stateGet(AppChainConsensusProfileCommitment.markerKey()))
                    .hasValueSatisfying(actual -> assertThat(actual)
                            .isEqualTo(AppChainConsensusProfileCommitment.encode(profile)));

            AppChainConsensusProfile drifted = new AppChainConsensusProfile(
                    profile.schemaVersion(), profile.maxMessageBytes(),
                    profile.maxBlockMessages() + 1, profile.maxBlockBytes(),
                    profile.l1StabilityDepth(), profile.enforceSenderSeq(),
                    profile.effectsEnabled(), profile.effectsMaxPerBlock(),
                    profile.effectsMaxPayloadBytes(), profile.effectsMaxExpiryBlocks(),
                    profile.effectsResultWindowBlocks(), profile.effectsDefaultGate(),
                    profile.effectsOutcomeCommitment(), profile.effectsStrictReservedPrefix(),
                    profile.effectResultSigners());
            assertThatThrownBy(() -> new ConsensusProfileGuard(drifted)
                    .verifyRetained(ledger, config.chainId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("retained consensus profile is incompatible");
        }
    }

    @Test
    void rejectsLegacyRetainedStateWithoutMarker(@TempDir Path directory) {
        try (AppLedgerStore ledger = ledger(directory)) {
            AppBlock block = block(new byte[32]);
            try (WriteBatch batch = new WriteBatch()) {
                ledger.commitBlock(block, AppBlockCodec.blockHash(block), block.stateRoot(), batch);
            }

            AppChainConfig config = config();
            ConsensusProfileGuard guard = new ConsensusProfileGuard(
                    EffectsSettings.from(config).consensusProfile(config));
            assertThatThrownBy(() -> guard.verifyRetained(ledger, config.chainId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("marker is absent");
        }
    }

    @Test
    void applicationCannotWriteFrameworkProfileNamespace(@TempDir Path directory) {
        AppChainConfig config = config();
        EffectsSettings settings = EffectsSettings.from(config);
        ConsensusProfileGuard guard = new ConsensusProfileGuard(
                settings.consensusProfile(config));

        try (AppLedgerStore ledger = ledger(directory)) {
            MpfTrie trie = new MpfTrie(ledger.mpfNodeStore());
            AppStateMachine hostile = new AppStateMachine() {
                @Override
                public String id() {
                    return "hostile";
                }

                @Override
                public void apply(AppBlock block, AppStateWriter writer) {
                    writer.put("~yano/overwrite".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                            new byte[]{1});
                }
            };

            assertThatThrownBy(() -> new FxKernel(settings, guard)
                    .apply(hostile, block(new byte[32]), trie, ledger.fxReader()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("reserved '~yano/' prefix");
        }
    }

    private static AppChainConfig config() {
        String member = "11".repeat(32);
        return AppChainConfig.builder("profile-test")
                .signingKeyHex("22".repeat(32))
                .memberKeysHex(Set.of(member))
                .proposerKeyHex(member)
                .maxBlockMessages(100)
                .build();
    }

    private static AppLedgerStore ledger(Path directory) {
        return new AppLedgerStore(directory.resolve("ledger").toString(),
                LoggerFactory.getLogger(ConsensusProfileGuardTest.class));
    }

    private static AppBlock block(byte[] stateRoot) {
        return new AppBlock(AppBlock.BLOCK_VERSION, "profile-test", 1,
                AppBlock.GENESIS_PREV_HASH, 0, new byte[0], 1,
                AppBlockCodec.messagesRoot(List.of()), stateRoot, List.of(),
                new byte[32], FinalityCert.empty());
    }
}
