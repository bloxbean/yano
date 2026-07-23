package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectIntent;
import com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy;
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
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-006 E5.3: an atomic ledger snapshot restores full state on a fresh node
 * with no replay — same tip, same state root, same proofs — and the integrity
 * check accepts it.
 */
@Timeout(60)
class AppChainSnapshotTest {

    private static final Logger log = LoggerFactory.getLogger(AppChainSnapshotTest.class);
    private static final byte[] KEY_A = seed(150);
    private static final byte[] KEY_B = seed(151);

    @TempDir
    Path tempDir;

    private AppChainSubsystem source;

    @AfterEach
    void tearDown() {
        if (source != null) source.stop();
    }

    @Test
    void effectRuntimeOwnerFingerprint_isBoundedCanonicalAndUnambiguous() {
        String longIdentity = "x".repeat(512);
        String first = AppChainSubsystem.effectRuntimeOwner(
                longIdentity, Set.of("cardano.payment", "webhook"));
        String reordered = AppChainSubsystem.effectRuntimeOwner(
                longIdentity, new java.util.LinkedHashSet<>(List.of("webhook", "cardano.payment")));

        assertThat(first).isEqualTo(reordered).hasSize(68);
        assertThat(AppChainSubsystem.effectRuntimeOwner("a:types=b", Set.of("c")))
                .isNotEqualTo(AppChainSubsystem.effectRuntimeOwner("a", Set.of("b:types=c")));
    }

    @Test
    void snapshotRestore_reproducesStateWithoutReplay() throws Exception {
        String pubA = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(KEY_A));
        AppChainConfig config = AppChainConfig.builder("snap-chain")
                .signingKeyHex(HexUtil.encodeHexString(KEY_A))
                .memberKeysHex(Set.of(pubA))
                .proposerKeyHex(pubA)
                .threshold(1)
                .blockIntervalMs(300)
                .build();
        source = new AppChainSubsystem(config, 42, null, null,
                tempDir.resolve("source").toString(), null, log);
        source.start();

        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            ids.add(source.submit("t", ("m-" + i).getBytes(StandardCharsets.UTF_8)));
        }
        awaitTrue("all finalized", () -> ids.stream()
                .allMatch(id -> source.messageHeight(HexUtil.decodeHexString(id)).isPresent()));
        long sourceTip = source.tipHeight();
        byte[] sourceRoot = source.stateRoot();

        // Snapshot the ledger to a fresh directory
        Path snapshotDir = tempDir.resolve("snapshot");
        long snapHeight = source.snapshot(snapshotDir.toString());
        assertThat(snapHeight).isEqualTo(sourceTip);

        // Open a fresh subsystem whose ledger is the restored snapshot.
        // Subsystem appends "/<chainId>" to the base path, so place the snapshot
        // where that resolves to it.
        Path restoreBase = tempDir.resolve("restore");
        java.nio.file.Files.createDirectories(restoreBase);
        copyDir(snapshotDir, restoreBase.resolve("snap-chain"));

        AppChainSubsystem restored = new AppChainSubsystem(config, 42, null, null,
                restoreBase.toString(), null, log);
        restored.start();
        try {
            // Full state present with NO replay: same tip, same root
            assertThat(restored.tipHeight()).isEqualTo(sourceTip);
            assertThat(restored.stateRoot()).isEqualTo(sourceRoot);

            // Every message is present and provable on the restored node
            for (String id : ids) {
                assertThat(restored.messageHeight(HexUtil.decodeHexString(id))).isPresent();
                assertThat(restored.stateProof(HexUtil.decodeHexString(id))).isPresent();
            }
            // Blocks are byte-identical
            assertThat(restored.block(sourceTip).orElseThrow().stateRoot())
                    .isEqualTo(source.block(sourceTip).orElseThrow().stateRoot());

            // Manifest was verified pre-open and marked (008.1 I1.7)
            assertThat(restoreBase.resolve("snap-chain").resolve(SnapshotManifest.VERIFIED_MARKER))
                    .exists();
        } finally {
            restored.stop();
        }
    }

    @Test
    void snapshotRestore_onDifferentExecutor_resetsAndQuarantinesRuntime() throws Exception {
        String pubA = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(KEY_A));
        String pubB = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(KEY_B));
        Set<String> members = Set.of(pubA, pubB);
        Map<String, String> effectSettings = Map.of(
                "effects.enabled", "true",
                "effects.executor.enabled", "true",
                "effects.external.enabled", "true");

        AppChainConfig sourceConfig = AppChainConfig.builder("snap-chain")
                .signingKeyHex(HexUtil.encodeHexString(KEY_A))
                .memberKeysHex(members)
                .proposerKeyHex(pubA)
                .threshold(1)
                .blockIntervalMs(200)
                .pluginSettings(effectSettings)
                .build();
        source = new AppChainSubsystem(sourceConfig, 42, null, snapshotEffectEmitter(),
                tempDir.resolve("source-fx").toString(), null, log);
        source.start();
        String messageId = source.submit("t", "execute-me".getBytes(StandardCharsets.UTF_8));
        byte[] messageIdBytes = HexUtil.decodeHexString(messageId);
        awaitTrue("effect finalized", () -> source.messageHeight(messageIdBytes).isPresent());
        long effectHeight = source.messageHeight(messageIdBytes).orElseThrow();
        assertThat(source.effect(effectHeight, 0)).isPresent();
        awaitTrue("effect intaken", () -> source.effectRuntimeStatus(effectHeight, 0).isPresent());
        assertThat(source.claimEffects("worker-a", Set.of(), 1, 60)).hasSize(1);
        assertThat(source.effectRuntimeStatus(effectHeight, 0).orElseThrow().get("status"))
                .isEqualTo("EXTERNAL");

        long sourceTip = source.tipHeight();
        byte[] sourceRoot = source.stateRoot();
        Path snapshotDir = tempDir.resolve("snapshot-fx");
        source.snapshot(snapshotDir.toString());

        Path restoreBase = tempDir.resolve("restore-fx");
        java.nio.file.Files.createDirectories(restoreBase);
        copyDir(snapshotDir, restoreBase.resolve("snap-chain"));

        AppChainConfig targetConfig = AppChainConfig.builder("snap-chain")
                .signingKeyHex(HexUtil.encodeHexString(KEY_B))
                .memberKeysHex(members)
                .proposerKeyHex(pubA)
                .threshold(1)
                .blockIntervalMs(200)
                .pluginSettings(effectSettings)
                .build();
        AppChainSubsystem restored = new AppChainSubsystem(targetConfig, 42, null,
                snapshotEffectEmitter(), restoreBase.toString(), null, log);
        restored.start();
        try {
            assertThat(restored.tipHeight()).isEqualTo(sourceTip);
            assertThat(restored.stateRoot()).isEqualTo(sourceRoot);
            assertThat(restored.effect(effectHeight, 0)).isPresent();
            assertThat(restored.effectRuntimeStatus(effectHeight, 0).orElseThrow().get("status"))
                    .isEqualTo("QUARANTINED");
            assertThat(restored.claimEffects("worker-b", Set.of(), 1, 60)).isEmpty();

            assertThat(restored.requeueEffect(effectHeight, 0)).isTrue();
            assertThat(restored.claimEffects("worker-b", Set.of(), 1, 60)).hasSize(1);
        } finally {
            restored.stop();
        }
    }

    @Test
    void memberKeyRotation_onSameExecutorPreservesReadyResult() throws Exception {
        String pubA = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(KEY_A));
        String pubB = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(KEY_B));
        Set<String> members = Set.of(pubA, pubB);
        Map<String, String> effectSettings = Map.of(
                "effects.enabled", "true",
                "effects.executor.enabled", "true",
                "effects.external.enabled", "true");
        Path ledgerBase = tempDir.resolve("rotation-fx");

        AppChainConfig beforeRotation = AppChainConfig.builder("snap-chain")
                .signingKeyHex(HexUtil.encodeHexString(KEY_A))
                .memberKeysHex(members)
                .proposerKeyHex(pubA)
                .threshold(1)
                .blockIntervalMs(200)
                .pluginSettings(effectSettings)
                .build();
        source = new AppChainSubsystem(beforeRotation, 42, null,
                snapshotEffectEmitter(ResultPolicy.CHAIN), ledgerBase.toString(), null, log);
        source.start();
        String messageId = source.submit("t", "pay-me".getBytes(StandardCharsets.UTF_8));
        byte[] messageIdBytes = HexUtil.decodeHexString(messageId);
        awaitTrue("effect finalized", () -> source.messageHeight(messageIdBytes).isPresent());
        long effectHeight = source.messageHeight(messageIdBytes).orElseThrow();
        awaitTrue("effect intaken", () -> source.effectRuntimeStatus(effectHeight, 0).isPresent());
        assertThat(source.claimEffects("worker-a", Set.of(), 1, 60)).hasSize(1);
        assertThat(source.reportEffect("worker-a", effectHeight, 0, true,
                "tx-rotation".getBytes(StandardCharsets.UTF_8), null)).isTrue();
        assertThat(source.effectRuntimeStatus(effectHeight, 0).orElseThrow().get("status"))
                .isEqualTo("DONE");
        long tipBeforeRotation = source.tipHeight();
        byte[] rootBeforeRotation = source.stateRoot();
        source.stop();
        source = null;

        AppChainConfig afterRotation = AppChainConfig.builder("snap-chain")
                .signingKeyHex(HexUtil.encodeHexString(KEY_B))
                .memberKeysHex(members)
                .proposerKeyHex(pubA)
                .threshold(1)
                .blockIntervalMs(200)
                .pluginSettings(effectSettings)
                .build();
        AppChainSubsystem rotated = new AppChainSubsystem(afterRotation, 42, null,
                snapshotEffectEmitter(ResultPolicy.CHAIN), ledgerBase.toString(), null, log);
        rotated.start();
        try {
            assertThat(rotated.tipHeight()).isEqualTo(tipBeforeRotation);
            assertThat(rotated.stateRoot()).isEqualTo(rootBeforeRotation);
            assertThat(rotated.effectRuntimeStatus(effectHeight, 0).orElseThrow().get("status"))
                    .isEqualTo("DONE");
            assertThat(rotated.effectStats().get("resultBacklog")).isEqualTo(1L);
            assertThat(ledgerBase.resolve("snap-chain.effect-executor-id")).exists();
        } finally {
            rotated.stop();
        }
    }

    @Test
    void tamperedSnapshot_refusesToStart() throws Exception {
        AppChainConfig config = startSourceAndFinalize();
        Path snapshotDir = tempDir.resolve("snapshot-t");
        source.snapshot(snapshotDir.toString());

        Path restoreBase = tempDir.resolve("restore-t");
        java.nio.file.Files.createDirectories(restoreBase);
        Path ledgerDir = restoreBase.resolve("snap-chain");
        copyDir(snapshotDir, ledgerDir);

        // Flip one byte in the first sst/log file covered by the manifest
        Path victim = java.nio.file.Files.walk(ledgerDir)
                .filter(java.nio.file.Files::isRegularFile)
                .filter(p -> !p.getFileName().toString().startsWith("snapshot-manifest"))
                .findFirst().orElseThrow();
        byte[] bytes = java.nio.file.Files.readAllBytes(victim);
        bytes[bytes.length / 2] ^= 0x01;
        java.nio.file.Files.write(victim, bytes);

        AppChainSubsystem restored = new AppChainSubsystem(config, 42, null, null,
                restoreBase.toString(), null, log);
        org.assertj.core.api.Assertions.assertThatThrownBy(restored::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("do not match the manifest");
    }

    @Test
    void manifestSignedByNonMember_refusesToStart() throws Exception {
        AppChainConfig config = startSourceAndFinalize();
        Path snapshotDir = tempDir.resolve("snapshot-n");
        source.snapshot(snapshotDir.toString());

        Path restoreBase = tempDir.resolve("restore-n");
        java.nio.file.Files.createDirectories(restoreBase);
        Path ledgerDir = restoreBase.resolve("snap-chain");
        copyDir(snapshotDir, ledgerDir);

        // Re-sign the manifest with a key that is NOT in the member set
        byte[] rogueSeed = seed(152);
        AppMessageSigner rogue = new AppMessageSigner(HexUtil.encodeHexString(rogueSeed));
        byte[] manifestBytes = java.nio.file.Files.readAllBytes(
                ledgerDir.resolve(SnapshotManifest.MANIFEST_FILE));
        String rogueManifest = new String(manifestBytes, StandardCharsets.UTF_8)
                .replaceAll("\"signerKey\" : \"[0-9a-f]+\"",
                        "\"signerKey\" : \"" + rogue.publicKeyHex() + "\"");
        byte[] rogueBytes = rogueManifest.getBytes(StandardCharsets.UTF_8);
        java.nio.file.Files.write(ledgerDir.resolve(SnapshotManifest.MANIFEST_FILE), rogueBytes);
        java.nio.file.Files.writeString(ledgerDir.resolve(SnapshotManifest.SIG_FILE),
                HexUtil.encodeHexString(rogue.sign(rogueBytes)));

        AppChainSubsystem restored = new AppChainSubsystem(config, 42, null, null,
                restoreBase.toString(), null, log);
        org.assertj.core.api.Assertions.assertThatThrownBy(restored::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a trusted member key");
    }

    private AppChainConfig startSourceAndFinalize() throws Exception {
        String pubA = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(KEY_A));
        AppChainConfig config = AppChainConfig.builder("snap-chain")
                .signingKeyHex(HexUtil.encodeHexString(KEY_A))
                .memberKeysHex(Set.of(pubA))
                .proposerKeyHex(pubA)
                .threshold(1)
                .blockIntervalMs(300)
                .build();
        source = new AppChainSubsystem(config, 42, null, null,
                tempDir.resolve("source").toString(), null, log);
        source.start();
        String id = source.submit("t", "data".getBytes(StandardCharsets.UTF_8));
        awaitTrue("finalized", () -> source.messageHeight(HexUtil.decodeHexString(id)).isPresent());
        return config;
    }

    private static void copyDir(Path src, Path dest) throws Exception {
        java.nio.file.Files.walk(src).forEach(path -> {
            try {
                Path target = dest.resolve(src.relativize(path));
                if (java.nio.file.Files.isDirectory(path)) {
                    java.nio.file.Files.createDirectories(target);
                } else {
                    java.nio.file.Files.createDirectories(target.getParent());
                    java.nio.file.Files.copy(path, target);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static AppStateMachine snapshotEffectEmitter() {
        return snapshotEffectEmitter(ResultPolicy.NONE);
    }

    private static AppStateMachine snapshotEffectEmitter(ResultPolicy resultPolicy) {
        return new AppStateMachine() {
            @Override public String id() { return "snapshot-effect-emitter"; }
            @Override public void apply(AppBlock block, AppStateWriter writer) { }
            @Override
            public void apply(AppBlock block, AppStateWriter writer, AppEffectEmitter effects) {
                block.messages().stream()
                        .filter(message -> !message.getTopic().startsWith("~"))
                        .forEach(message -> effects.emit(EffectIntent
                                .of("snapshot.effect", message.getBody())
                                .scope("snapshot/" + message.getMessageIdHex())
                                .result(resultPolicy)
                                .sourceMessageId(message.getMessageId())
                                .build()));
            }
        };
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
