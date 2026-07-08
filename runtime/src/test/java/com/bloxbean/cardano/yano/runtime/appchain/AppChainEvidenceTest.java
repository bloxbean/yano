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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

        // Offline verification succeeds (cert valid, message included)
        EvidenceVerifier.Result result = EvidenceVerifier.verify(bundle);
        assertThat(result.valid()).isTrue();
        assertThat(result.certSignatures()).isGreaterThanOrEqualTo(1);
        assertThat(result.anchoredToL1()).isFalse();

        // JSON round-trips and re-verifies byte-for-byte
        String json = EvidenceBundleCodec.toJson(bundle);
        EvidenceBundle reparsed = EvidenceBundleCodec.fromJson(json);
        assertThat(EvidenceVerifier.verify(reparsed).valid()).isTrue();

        // Tamper detection: a wrong member set fails the cert check
        EvidenceBundle wrongMembers = new EvidenceBundle(bundle.chainId(), bundle.messageIdHex(),
                bundle.blocks(), List.of(pubB), null);
        assertThat(EvidenceVerifier.verify(wrongMembers).valid()).isFalse();
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
                id1, chain, List.of(pubA),
                new EvidenceBundle.AnchorRef(anchorHeight, HexUtil.encodeHexString(anchoredHash),
                        "deadbeefTxHash", 12345L));

        EvidenceVerifier.Result result = EvidenceVerifier.verify(bundle);
        assertThat(result.valid()).isTrue();
        assertThat(result.anchoredToL1()).isTrue();
        assertThat(result.anchorTxHash()).isEqualTo("deadbeefTxHash");

        // A broken anchor hash fails
        EvidenceBundle badAnchor = new EvidenceBundle("anchor-evidence-chain", id1, chain, List.of(pubA),
                new EvidenceBundle.AnchorRef(anchorHeight, HexUtil.encodeHexString(new byte[32]),
                        "deadbeefTxHash", 12345L));
        assertThat(EvidenceVerifier.verify(badAnchor).valid()).isFalse();
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
