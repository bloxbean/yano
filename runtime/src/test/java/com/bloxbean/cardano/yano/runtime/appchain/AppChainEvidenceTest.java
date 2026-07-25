package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceBundle;
import com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceBundleCodec;
import com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-006 E3.4: a real chain produces an evidence bundle for a finalized
 * message; the offline verifier confirms finality (cert + inclusion), the
 * JSON round-trips, and the anchor chain-linking logic is exercised with a
 * synthetic anchor reference.
 */
@Timeout(60)
class AppChainEvidenceTest {

    private static final Logger log = LoggerFactory.getLogger(AppChainEvidenceTest.class);
    private static final byte[] KEY_A = seed(101);
    private static final byte[] KEY_B = seed(102);

    @TempDir
    Path tempDir;

    private AppChainSubsystem node;

    @Test
    void evidenceChainLimitCountsInclusiveEndpoints() {
        assertThat(AppChainSubsystem.evidenceChainFits(10, 4_105)).isTrue();
        assertThat(AppChainSubsystem.evidenceChainFits(10, 4_106)).isFalse();
        assertThat(AppChainSubsystem.evidenceChainFits(0, 1)).isFalse();
        assertThat(AppChainSubsystem.evidenceChainFits(10, 9)).isFalse();
        assertThat(AppChainSubsystem.evidenceBytesFit(0, 100, 100)).isTrue();
        assertThat(AppChainSubsystem.evidenceBytesFit(99, 1, 100)).isTrue();
        assertThat(AppChainSubsystem.evidenceBytesFit(100, 1, 100)).isFalse();
        assertThat(AppChainSubsystem.evidenceBytesFit(
                Long.MAX_VALUE, 1, Long.MAX_VALUE)).isFalse();
        assertThat(AppChainSubsystem.evidenceBytesFit(-1, 1, 100)).isFalse();
    }

    @AfterEach
    void tearDown() {
        if (node != null) node.stop();
    }

    @Test
    void evidence_verifiesOffline_andRoundTripsJson() throws Exception {
        String pubA = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(KEY_A));
        String pubB = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(KEY_B));
        // Two members, threshold 1 (single node signs its own proposals) — the
        // cert carries the proposer's signature; both keys are in the member set.
        AppChainConfig config = AppChainConfig.builder("evidence-chain")
                .signingKeyHex(HexUtil.encodeHexString(KEY_A))
                .memberKeysHex(Set.of(pubA, pubB))
                .proposerKeyHex(pubA)
                .threshold(1)
                .blockIntervalMs(300)
                .build();
        node = new AppChainSubsystem(config, 42, null, null,
                tempDir.resolve("ledger").toString(), null, log);
        node.start();

        String id = node.submit("orders", "order #1".getBytes(StandardCharsets.UTF_8));
        awaitTrue("finalized", () -> node.messageHeight(HexUtil.decodeHexString(id)).isPresent());

        // Node produces the bundle (not anchored in a unit test — no L1)
        EvidenceBundle bundle = node.evidence(HexUtil.decodeHexString(id)).orElseThrow();
        assertThat(bundle.chainId()).isEqualTo("evidence-chain");
        assertThat(bundle.blocks()).hasSize(1);
        assertThat(bundle.anchor()).isNull();

        // Offline verification succeeds against pinned membership and proves
        // both signed message-id inclusion and retained envelope content.
        EvidenceVerifier.Result result = EvidenceVerifier.verify(
                bundle, "evidence-chain", Set.of(pubA, pubB), 1);
        assertThat(result.valid()).isTrue();
        assertThat(result.messageContentVerified()).isTrue();
        assertThat(result.certSignatures()).isGreaterThanOrEqualTo(1);
        assertThat(result.anchoredToL1()).isFalse();

        // JSON round-trips and re-verifies signed header/message commitments.
        String json = EvidenceBundleCodec.toJson(bundle);
        EvidenceBundle reparsed = EvidenceBundleCodec.fromJson(json);
        assertThat(EvidenceVerifier.verify(
                reparsed, "evidence-chain", Set.of(pubA, pubB), 1).valid()).isTrue();

        // Tamper detection: a wrong member set fails the cert check
        EvidenceBundle wrongMembers = new EvidenceBundle(bundle.chainId(), bundle.messageIdHex(),
                bundle.blocks(), List.of(pubB), 1, null);
        assertThat(EvidenceVerifier.verify(
                wrongMembers, "evidence-chain", Set.of(pubA, pubB), 1).valid()).isFalse();

        // Threshold enforcement: a bundle claiming a threshold above the actual
        // signature count is rejected (a single member can't forge evidence).
        EvidenceBundle overThreshold = new EvidenceBundle(bundle.chainId(), bundle.messageIdHex(),
                bundle.blocks(), bundle.memberKeysHex(), 2, null);
        assertThat(EvidenceVerifier.verify(
                overThreshold, "evidence-chain", Set.of(pubA, pubB), 1).valid()).isFalse();
    }

    @Test
    void evidence_withSyntheticAnchor_verifiesChainLink() throws Exception {
        // Produce two finalized blocks, then hand-craft an anchor pointing at
        // the second block's hash to exercise the prev-hash chain verification.
        String pubA = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(KEY_A));
        AppChainConfig config = AppChainConfig.builder("anchor-evidence-chain")
                .signingKeyHex(HexUtil.encodeHexString(KEY_A))
                .memberKeysHex(Set.of(pubA))
                .proposerKeyHex(pubA)
                .threshold(1)
                .blockIntervalMs(300)
                .build();
        node = new AppChainSubsystem(config, 42, null, null,
                tempDir.resolve("ledger2").toString(), null, log);
        node.start();

        String id1 = node.submit("t", "m1".getBytes(StandardCharsets.UTF_8));
        awaitTrue("m1 finalized", () -> node.messageHeight(HexUtil.decodeHexString(id1)).isPresent());
        long h1 = node.messageHeight(HexUtil.decodeHexString(id1)).orElseThrow();
        // A second submission produces the next block (the proposer only builds
        // a block when messages are pending).
        String id2 = node.submit("t", "m2".getBytes(StandardCharsets.UTF_8));
        awaitTrue("m2 finalized in a later block",
                () -> node.messageHeight(HexUtil.decodeHexString(id2)).map(h -> h > h1).orElse(false));
        long anchorHeight = node.tipHeight();
        assertThat(anchorHeight).isGreaterThan(h1);

        // Build the evidence bundle for m1 with a synthetic anchor at the tip
        List<AppBlock> chain = new ArrayList<>();
        for (long h = h1; h <= anchorHeight; h++) {
            chain.add(node.block(h).orElseThrow());
        }
        byte[] anchoredHash = AppBlockCodec.blockHash(chain.get(chain.size() - 1));
        EvidenceBundle bundle = new EvidenceBundle("anchor-evidence-chain",
                id1, chain, List.of(pubA), 1,
                new EvidenceBundle.AnchorRef(anchorHeight, HexUtil.encodeHexString(anchoredHash),
                        "de".repeat(32), 12345L));

        EvidenceVerifier.Result result = EvidenceVerifier.verify(
                bundle, "anchor-evidence-chain", Set.of(pubA), 1);
        assertThat(result.valid()).isTrue();
        assertThat(result.anchoredToL1()).isTrue();
        assertThat(result.anchorTxHash()).isEqualTo("de".repeat(32));

        // A broken anchor hash fails
        EvidenceBundle badAnchor = new EvidenceBundle("anchor-evidence-chain", id1, chain, List.of(pubA), 1,
                new EvidenceBundle.AnchorRef(anchorHeight, HexUtil.encodeHexString(new byte[32]),
                        "de".repeat(32), 12345L));
        assertThat(EvidenceVerifier.verify(
                badAnchor, "anchor-evidence-chain", Set.of(pubA), 1).valid()).isFalse();
    }

    @Test
    void evidence_oversizedAnchoredSegment_fallsBackToSingleUnanchoredBlock() throws Exception {
        String pubA = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(KEY_A));
        AppChainConfig config = AppChainConfig.builder("bounded-evidence-chain")
                .signingKeyHex(HexUtil.encodeHexString(KEY_A))
                .memberKeysHex(Set.of(pubA))
                .proposerKeyHex(pubA)
                .threshold(1)
                .maxMessageBytes(3_000)
                .blockMaxBytes(8_192)
                .blockIntervalMs(100)
                .build();
        node = new AppChainSubsystem(config, 42, null, null,
                tempDir.resolve("ledger-bounded").toString(), null, log);
        node.start();

        byte[] body = new byte[config.maxMessageBytes()];
        Arrays.fill(body, (byte) 0x5a);
        String targetId = node.submit("evidence", body);
        byte[] targetIdBytes = HexUtil.decodeHexString(targetId);
        awaitTrue("target finalized", () -> node.messageHeight(targetIdBytes).isPresent());
        long targetHeight = node.messageHeight(targetIdBytes).orElseThrow();

        long canonicalSegmentBytes = AppBlockCodec.serialize(
                node.block(targetHeight).orElseThrow()).length;
        int additionalBlocks = 0;
        while (canonicalSegmentBytes <= config.blockMaxBytes() && additionalBlocks < 8) {
            long previousTip = node.tipHeight();
            byte[] nextBody = Arrays.copyOf(body, body.length);
            nextBody[0] = (byte) additionalBlocks;
            String nextId = node.submit("evidence", nextBody);
            byte[] nextIdBytes = HexUtil.decodeHexString(nextId);
            awaitTrue("next evidence block finalized", () -> node.messageHeight(nextIdBytes)
                    .map(height -> height > previousTip)
                    .orElse(false));
            long nextHeight = node.messageHeight(nextIdBytes).orElseThrow();
            canonicalSegmentBytes += AppBlockCodec.serialize(
                    node.block(nextHeight).orElseThrow()).length;
            additionalBlocks++;
        }
        assertThat(canonicalSegmentBytes).isGreaterThan(config.blockMaxBytes());

        long anchoredHeight = node.tipHeight();
        byte[] anchoredBlockHash = AppBlockCodec.blockHash(
                node.block(anchoredHeight).orElseThrow());
        AppLedgerStore ledger = ledgerOf(node);
        ledger.metaPutAll(
                Map.of("anchor_last_height", anchoredHeight,
                        "anchor_last_slot", 12_345L),
                Map.of("anchor_last_block_hash", anchoredBlockHash,
                        "anchor_last_tx", "de".repeat(32).getBytes(StandardCharsets.UTF_8)));

        EvidenceBundle bundle = node.evidence(targetIdBytes).orElseThrow();
        assertThat(bundle.blocks()).hasSize(1);
        assertThat(bundle.blocks().get(0).height()).isEqualTo(targetHeight);
        assertThat(bundle.anchor()).isNull();

        var commitment = node.latestAnchorCommitment().orElseThrow();
        assertThat(commitment.chainId()).isEqualTo(config.chainId());
        assertThat(commitment.mode()).isEqualTo(AppChainConfig.AnchorConfig.MODE_METADATA);
        assertThat(commitment.anchoredHeight()).isEqualTo(anchoredHeight);
        assertThat(commitment.stateRoot())
                .containsExactly(node.block(anchoredHeight).orElseThrow().stateRoot());
        assertThat(commitment.blockHash()).containsExactly(anchoredBlockHash);
        assertThat(commitment.transactionHash()).isEqualTo("de".repeat(32));
        assertThat(commitment.l1Slot()).isEqualTo(12_345L);

        ledger.metaPutBytes("anchor_last_block_hash", new byte[32]);
        assertThat(node.latestAnchorCommitment()).isEmpty();
    }

    private static AppLedgerStore ledgerOf(AppChainSubsystem subsystem)
            throws ReflectiveOperationException {
        Field field = AppChainSubsystem.class.getDeclaredField("ledger");
        field.setAccessible(true);
        return (AppLedgerStore) field.get(subsystem);
    }

    private static void awaitTrue(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean())
                return;
            Thread.sleep(200);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }

    private static byte[] seed(int fill) {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) fill);
        return seed;
    }
}
