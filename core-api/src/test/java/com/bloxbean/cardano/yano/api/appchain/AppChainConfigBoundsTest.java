package com.bloxbean.cardano.yano.api.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppChainConfigBoundsTest {

    @Test
    void acceptsTheExactV1ChainAndMembershipBounds() {
        String chainId = "é".repeat(AppChainConfig.MAX_CHAIN_ID_BYTES / 2);
        AppChainConfig config = config(chainId, members(AppChainConfig.MAX_MEMBERS));

        assertThat(config.chainId()).isEqualTo(chainId);
        assertThat(config.memberKeysHex()).hasSize(AppChainConfig.MAX_MEMBERS);
    }

    @Test
    void rejectsChainIdentitiesThatTheEvidenceAndAnchorCodecsCannotRepresent() {
        assertThatThrownBy(() -> config("é".repeat(
                AppChainConfig.MAX_CHAIN_ID_BYTES / 2 + 1), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid UTF-8 bytes");
        assertThatThrownBy(() -> config("bad\0chain", Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid UTF-8 bytes");
        assertThatThrownBy(() -> config("bad\ud800chain", Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid UTF-8 bytes");
    }

    @Test
    void rejectsMembershipBeyondTheV1FinalityAndAnchorLimit() {
        assertThatThrownBy(() -> config("bounded-chain",
                members(AppChainConfig.MAX_MEMBERS + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most " + AppChainConfig.MAX_MEMBERS);
    }

    @Test
    void acceptsExactAndRejectsOversizedV1WorkBounds() {
        Set<String> maximalMembers = members(AppChainConfig.MAX_MEMBERS);
        AppChainConfig exact = AppChainConfig.builder("bounded-chain")
                .signingKeyHex("00".repeat(32))
                .memberKeysHex(maximalMembers)
                .maxMessageBytes(AppChainConfig.MAX_MESSAGE_BYTES)
                .blockMaxBytes(AppChainConfig.MAX_BLOCK_BYTES)
                .maxBlockMessages(AppChainConfig.MAX_BLOCK_MESSAGES)
                .poolMaxMessages(AppChainConfig.MAX_BLOCK_MESSAGES)
                .build();

        assertThat(exact.maxMessageBytes()).isEqualTo(AppChainConfig.MAX_MESSAGE_BYTES);
        assertThat(exact.blockMaxBytes()).isEqualTo(AppChainConfig.MAX_BLOCK_BYTES);
        assertThat(exact.maxBlockMessages()).isEqualTo(AppChainConfig.MAX_BLOCK_MESSAGES);

        assertThatThrownBy(() -> AppChainConfig.builder("bounded-chain")
                .signingKeyHex("00".repeat(32))
                .maxMessageBytes(AppChainConfig.MAX_MESSAGE_BYTES + 1)
                .build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AppChainConfig.builder("bounded-chain")
                .signingKeyHex("00".repeat(32))
                .blockMaxBytes(AppChainConfig.MAX_BLOCK_BYTES + 1)
                .build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AppChainConfig.builder("bounded-chain")
                .signingKeyHex("00".repeat(32))
                .maxBlockMessages(AppChainConfig.MAX_BLOCK_MESSAGES + 1)
                .poolMaxMessages(AppChainConfig.MAX_BLOCK_MESSAGES + 1)
                .build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AppChainConfig.builder("bounded-chain")
                .signingKeyHex("00".repeat(32))
                .maxMessageBytes(1_024)
                .blockMaxBytes(1_024)
                .build()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("headroom");
    }

    @Test
    void worstCaseV1MessageAndCertificateFitThePinnedByteBudgets() {
        Set<String> memberKeys = members(AppChainConfig.MAX_MEMBERS);
        AppChainConfig config = AppChainConfig.builder("c".repeat(
                        AppChainConfig.MAX_CHAIN_ID_BYTES))
                .signingKeyHex("00".repeat(32))
                .memberKeysHex(memberKeys)
                .maxMessageBytes(AppChainConfig.MAX_MESSAGE_BYTES)
                .blockMaxBytes(AppChainConfig.MAX_BLOCK_BYTES)
                .build();
        AppMessage message = AppMessage.builder()
                .version(AppMessage.ENVELOPE_VERSION)
                .messageId(new byte[32])
                .chainId(config.chainId())
                .topic("t".repeat(AppChainConfig.MAX_TOPIC_BYTES))
                .sender(new byte[32])
                .senderSeq(Long.MAX_VALUE)
                .expiresAt(Long.MAX_VALUE)
                .body(new byte[AppChainConfig.MAX_MESSAGE_BYTES])
                .authScheme(FinalityCert.SCHEME_ED25519)
                .authProof(new byte[64])
                .build();
        AppBlock proposal = new AppBlock(AppBlock.BLOCK_VERSION, config.chainId(),
                Long.MAX_VALUE, new byte[32], Long.MAX_VALUE, new byte[32],
                Long.MAX_VALUE, new byte[32], new byte[32], List.of(message),
                new byte[32], FinalityCert.empty());
        List<FinalityCert.Signature> signatures = new ArrayList<>();
        for (String member : memberKeys) {
            signatures.add(new FinalityCert.Signature(
                    java.util.HexFormat.of().parseHex(member), new byte[64]));
        }
        AppBlock finalized = proposal.withCert(new FinalityCert(
                FinalityCert.SCHEME_ED25519, signatures));

        assertThat((long) AppBlockCodec.serialize(proposal).length)
                .isLessThanOrEqualTo(config.proposalMaxBytes());
        assertThat((long) AppBlockCodec.serialize(finalized).length)
                .isLessThanOrEqualTo(config.blockMaxBytes());
    }

    private static AppChainConfig config(String chainId, Set<String> members) {
        return AppChainConfig.builder(chainId)
                .signingKeyHex("00".repeat(32))
                .memberKeysHex(members)
                .build();
    }

    private static Set<String> members(int count) {
        Set<String> members = new LinkedHashSet<>();
        for (int index = 0; index < count; index++) {
            members.add(String.format("%064x", index));
        }
        return members;
    }
}
