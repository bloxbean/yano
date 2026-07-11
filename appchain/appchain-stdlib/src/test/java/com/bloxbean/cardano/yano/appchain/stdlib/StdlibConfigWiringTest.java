package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.runtime.appchain.AppChainSubsystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR app-layer/008.1 I1.4: stdlib machine settings flow through
 * {@code yano.app-chain.machines.<id>.*} (AppStateMachineContext), fixing the
 * dead balances-minter config and adding the kv-registry value-format check.
 */
@Timeout(90)
class StdlibConfigWiringTest {

    private static final Logger log = LoggerFactory.getLogger(StdlibConfigWiringTest.class);
    private static final byte[] KEY_A = seed(83);

    @TempDir
    Path tempDir;

    private final List<AppChainSubsystem> nodes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (AppChainSubsystem node : nodes) {
            try {
                node.stop();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void balances_configuredMinter_restrictsMinting() throws Exception {
        String self = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(KEY_A));
        String otherMinter = "ab".repeat(32); // a member key that is NOT this node

        // Chain where someone else is the minter — our mint must be a no-op
        AppChainSubsystem restricted = startNode("restricted", BalancesStateMachine.ID,
                Map.of("machines.balances.minter", otherMinter));
        long tip = restricted.tipHeight();
        restricted.submit("ledger", BalancesStateMachine.mint(self, BigInteger.valueOf(100)));
        awaitTrue("block after unauthorized mint", () -> restricted.tipHeight() > tip);
        assertThat(balance(restricted, self)).isEqualTo(BigInteger.ZERO);

        // Chain where we are the minter — mint applies
        AppChainSubsystem allowed = startNode("allowed", BalancesStateMachine.ID,
                Map.of("machines.balances.minter", self.toUpperCase(java.util.Locale.ROOT)));
        allowed.submit("ledger", BalancesStateMachine.mint(self, BigInteger.valueOf(42)));
        awaitTrue("authorized mint finalized",
                () -> balance(allowed, self).equals(BigInteger.valueOf(42)));
    }

    @Test
    void balances_invalidMinterConfig_failsFast() {
        assertThatThrownBy(() -> new BalancesStateMachine("not-a-key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("machines.balances.minter");
    }

    @Test
    void kvRegistry_valueFormatCbor_nonConformingPutIsNoOp() throws Exception {
        AppChainSubsystem node = startNode("kvcbor", KvRegistryStateMachine.ID,
                Map.of("machines.kv-registry.value-format", "cbor"));

        // Valid CBOR value applies
        byte[] goodKey = "k1".getBytes(StandardCharsets.UTF_8);
        byte[] cborValue = BalancesStateMachine.mint("x", BigInteger.ONE); // any well-formed CBOR
        node.submit("reg", KvRegistryStateMachine.put(goodKey, cborValue));
        awaitTrue("conforming PUT finalized", () -> node.stateValue(goodKey).isPresent());

        // Malformed value is rejected at admission (filtered before proposal,
        // same pattern as malformed commands) — key never appears
        byte[] badKey = "k2".getBytes(StandardCharsets.UTF_8);
        long tip = node.tipHeight();
        // 0x82 = definite-length array(2) with only one element — truncated CBOR
        node.submit("reg", KvRegistryStateMachine.put(badKey, new byte[]{(byte) 0x82, 0x01}));
        Thread.sleep(1500);
        assertThat(node.tipHeight()).isEqualTo(tip);
        assertThat(node.stateValue(badKey)).isEmpty();
    }

    @Test
    void kvRegistry_valueFormatParse() {
        assertThat(KvRegistryStateMachine.ValueFormat.parse(null))
                .isEqualTo(KvRegistryStateMachine.ValueFormat.RAW);
        assertThat(KvRegistryStateMachine.ValueFormat.parse(""))
                .isEqualTo(KvRegistryStateMachine.ValueFormat.RAW);
        assertThat(KvRegistryStateMachine.ValueFormat.parse("CBOR"))
                .isEqualTo(KvRegistryStateMachine.ValueFormat.CBOR);
        assertThat(KvRegistryStateMachine.ValueFormat.parse(" utf8 "))
                .isEqualTo(KvRegistryStateMachine.ValueFormat.UTF8);
        assertThatThrownBy(() -> KvRegistryStateMachine.ValueFormat.parse("json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("value-format");
    }

    private static BigInteger balance(AppChainSubsystem node, String account) {
        return node.stateValue(BalancesStateMachine.accountKey(account))
                .map(BalancesStateMachine::decodeBalance).orElse(BigInteger.ZERO);
    }

    private AppChainSubsystem startNode(String name, String stateMachineId, Map<String, String> settings) {
        String pubA = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(KEY_A));
        AppChainConfig config = AppChainConfig.builder("cfg-" + name)
                .signingKeyHex(HexUtil.encodeHexString(KEY_A))
                .memberKeysHex(Set.of(pubA))
                .proposerKeyHex(pubA)
                .threshold(1)
                .blockIntervalMs(300)
                .stateMachineId(stateMachineId)
                .pluginSettings(settings)
                .build();
        AppChainSubsystem node = new AppChainSubsystem(config, 42, null, null,
                tempDir.resolve("ledger-" + name).toString(), null, log);
        nodes.add(node);
        node.start();
        return node;
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
