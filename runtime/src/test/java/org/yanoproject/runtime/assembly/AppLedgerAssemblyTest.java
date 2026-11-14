package org.yanoproject.runtime.assembly;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import org.yanoproject.api.appchain.AppChainGateway;
import org.yanoproject.api.appchain.codec.AppBlockCodec;
import org.yanoproject.api.appchain.evidence.EvidenceVerifier;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.yanoproject.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.api.appchain.state.StateCommitmentProfiles;
import org.yanoproject.api.config.RuntimeMode;
import org.yanoproject.appchain.config.AppChainConfigParser;
import org.yanoproject.runtime.events.PropagatingEventBus;
import org.yanoproject.runtime.appchain.AppChainSubsystem;
import org.slf4j.LoggerFactory;
import org.yanoproject.api.config.RuntimeOptions;
import org.yanoproject.api.config.YanoConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.*;

@Timeout(90)
class AppLedgerAssemblyTest {
    @TempDir Path directory;

    @Test
    void threeNodesGossipFinalizeAndLateJoinWithoutCardano() throws Exception {
        List<Integer> ports = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            try (ServerSocket socket = new ServerSocket(0)) { ports.add(socket.getLocalPort()); }
        }
        List<Yano> nodes = new ArrayList<>();
        try {
            nodes.add(node(0, ports));
            nodes.add(node(1, ports));
            nodes.forEach(Yano::start);
            AppChainGateway proposer = nodes.get(0).appChain().orElseThrow();
            AppChainGateway follower = nodes.get(1).appChain().orElseThrow();
            String message = follower.submit("orders", new byte[]{1, 2, 3});
            byte[] messageId = HexFormat.of().parseHex(message);
            await(() -> proposer.messageHeight(messageId).isPresent() && follower.messageHeight(messageId).isPresent());
            long height = proposer.messageHeight(messageId).orElseThrow();
            nodes.add(node(2, ports));
            nodes.get(2).start();
            AppChainGateway late = nodes.get(2).appChain().orElseThrow();
            await(() -> late.tipHeight() >= height);
            for (Yano node : nodes) {
                AppChainGateway chain = node.appChain().orElseThrow();
                assertThat(chain.block(height)).isPresent();
                assertThat(AppBlockCodec.blockHash(chain.block(height).orElseThrow()))
                        .isEqualTo(AppBlockCodec.blockHash(proposer.block(height).orElseThrow()));
                assertThat(chain.block(height).orElseThrow().stateRoot())
                        .isEqualTo(proposer.block(height).orElseThrow().stateRoot());
                // Certificate signatures are semantically a set; arrival order is not consensus identity.
                assertThat(signatures(chain, height)).isEqualTo(signatures(proposer, height));
                Set<String> members = IntStream.range(0, 3)
                        .mapToObj(i -> HexFormat.of().formatHex(KeyGenUtil.getPublicKeyFromPrivateKey(seed(i))))
                        .collect(Collectors.toSet());
                assertThat(EvidenceVerifier.verify(chain.evidence(messageId).orElseThrow(),
                        "standalone-test", members, 2).valid()).isTrue();
                assertThat(chain.block(height).orElseThrow().l1Slot()).isZero();
                assertThat(chain.block(height).orElseThrow().l1BlockHash()).isEmpty();
                assertThat(node.lifecycle().isSyncing()).isFalse();
                assertThat(node.kernel().orElseThrow().subsystems()).extracting(s -> s.name())
                        .doesNotContain("sync", "ledger-state", "utxo", "producer", "chain-storage");
                assertThatThrownBy(() -> node.chain().getBlock(new byte[32]))
                        .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("requires Cardano L1");
            }
            assertThat(Files.exists(directory.resolve("l1"))).isFalse();
            nodes.get(2).stop();
            nodes.get(2).start();
            assertThat(nodes.get(2).maintenanceGate()).isPresent();
            nodes.get(2).close();
            nodes.set(2, node(2, ports));
            nodes.get(2).start();
            assertThat(nodes.get(2).appChain().orElseThrow().tipHeight()).isGreaterThanOrEqualTo(height);
            String next = follower.submit("orders", new byte[]{4, 5, 6});
            byte[] nextId = HexFormat.of().parseHex(next);
            await(() -> nodes.get(2).appChain().orElseThrow().messageHeight(nextId).isPresent());
        } finally {
            for (Yano node : nodes.reversed()) node.close();
        }
    }

    @Test
    void invalidModeSettingsFailBeforeCreatingStorage() throws Exception {
        Map<String, Object> settings = settings(0, List.of(12345, 12346, 12347));
        settings.put("yano.app-chain.transport.mode", "shared");
        assertThatThrownBy(() -> YanoAssembly.fromConfig(config(0, 12345))
                .runtimeOptions(new RuntimeOptions(null, null, settings)).build())
                .hasMessageContaining("transport.mode=dedicated");
        try (var entries = Files.list(directory)) { assertThat(entries.toList()).isEmpty(); }
    }

    @Test
    void cannotReinterpretStandaloneStoreAsLegacy() {
        List<Integer> ports = List.of(12345, 12346, 12347);
        YanoConfig stoppedServer = config(0, ports.getFirst()).toBuilder().enableServer(false).build();
        Map<String, Object> standalone = settings(0, ports);
        standalone.put("yano.app-chain.peers", "");
        try (Yano node = YanoAssembly.fromConfig(stoppedServer)
                .runtimeOptions(new RuntimeOptions(null, null, standalone)).build()) {
            node.start();
        }
        // A legacy subsystem sees the same retained marker before starting its engine.
        Map<String, Object> legacy = new LinkedHashMap<>(standalone);
        var chain = AppChainConfigParser.parseChains(legacy, false).getFirst();
        try (var events = new PropagatingEventBus();
             var subsystem = new AppChainSubsystem(chain, 424242,
                     events, null, stoppedServer.getAppChainStoragePath(),
                     LoggerFactory.getLogger(getClass()))) {
            assertThatThrownBy(subsystem::start).hasMessageContaining("operating profile");
        }
    }

    private Yano node(int index, List<Integer> ports) {
        return YanoAssembly.fromConfig(config(index, ports.get(index)))
                .runtimeOptions(new RuntimeOptions(null, null, settings(index, ports))).build();
    }

    private YanoConfig config(int index, int port) {
        return YanoConfig.builder().mode(RuntimeMode.APP_LEDGER).enableServer(true).serverPort(port)
                .rocksDBPath(directory.resolve("l1").toString())
                .appChainStoragePath(directory.resolve("node-" + index).toString()).build();
    }

    private Map<String, Object> settings(int index, List<Integer> ports) {
        Map<String, Object> result = new LinkedHashMap<>();
        String prefix = "yano.app-chain.";
        result.put(prefix + "enabled", true);
        result.put(prefix + "network-magic", 424242);
        result.put(prefix + "transport.mode", "dedicated");
        result.put(prefix + "chain-id", "standalone-test");
        result.put(prefix + "signing-key", HexFormat.of().formatHex(seed(index)));
        List<String> members = new ArrayList<>();
        for (int i = 0; i < 3; i++) members.add(HexFormat.of().formatHex(KeyGenUtil.getPublicKeyFromPrivateKey(seed(i))));
        result.put(prefix + "members", String.join(",", members));
        result.put(prefix + "sequencer.proposer", members.getFirst());
        result.put(prefix + "threshold", 2);
        result.put(prefix + "block.interval-ms", 300);
        StateCommitmentIdentity.explicit(StateCommitmentProfiles.MPF, HexFormat.of().parseHex("12".repeat(32)))
                .settings().forEach((key, value) -> result.put(prefix + key, value));
        // The first two nodes never dial the late joiner: its historical block can only arrive over 103.
        result.put(prefix + "peers", String.join(",", ports.subList(0, 2).stream()
                .filter(port -> !port.equals(ports.get(index))).map(port -> "127.0.0.1:" + port).toList()));
        return result;
    }

    private static Set<String> signatures(AppChainGateway chain, long height) {
        return chain.block(height).orElseThrow().cert().signatures().stream()
                .map(signature -> HexFormat.of().formatHex(signature.signer()) + ":"
                        + HexFormat.of().formatHex(signature.signature())).collect(Collectors.toSet());
    }

    private static byte[] seed(int index) {
        byte[] seed = new byte[32];
        seed[0] = (byte) (index + 1);
        return seed;
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(35).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(100);
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
