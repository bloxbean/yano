package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.p2p.peer.PeerClientFactory;
import com.bloxbean.cardano.yano.p2p.peer.PeerEndpoint;
import com.bloxbean.cardano.yano.runtime.appchain.SharedTransportTestFakes.FakePeerClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AppChainManager#wrapPeerClientFactory}: the shared transport engages
 * only in shared mode AND when the L1 remote is an app-group peer of a hosted
 * chain; everything else passes the factory through untouched.
 */
class AppChainManagerSharedTransportTest {

    private static final Logger log = LoggerFactory.getLogger("test");

    @TempDir
    Path tempDir;

    private AppChainManager manager(String... peerHostPorts) {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) 7);
        String pub = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(seed));
        List<AppChainConfig.AppPeer> peers = Arrays.stream(peerHostPorts)
                .map(AppChainConfig.AppPeer::parse)
                .toList();
        AppChainConfig config = AppChainConfig.builder("wrap-chain")
                .signingKeyHex(HexUtil.encodeHexString(seed))
                .memberKeysHex(Set.of(pub))
                .proposerKeyHex(pub)
                .threshold(1)
                .peers(peers)
                .build();
        AppChainSubsystem subsystem = new AppChainSubsystem(config, 42, null, null,
                tempDir.resolve("ledger").toString(), null, log);
        return new AppChainManager(List.of(subsystem), log);
    }

    @Test
    void dedicatedModeReturnsDelegateUntouched() {
        PeerClientFactory delegate = (endpoint, point) -> new FakePeerClient();
        AppChainManager appChainManager = manager("upstream:3001");

        assertThat(appChainManager.wrapPeerClientFactory(delegate, "dedicated", "upstream", 3001))
                .isSameAs(delegate);
    }

    @Test
    void sharedModeWithoutMatchingPeerReturnsDelegate() {
        PeerClientFactory delegate = (endpoint, point) -> new FakePeerClient();
        AppChainManager appChainManager = manager("otherpeer:13338");

        assertThat(appChainManager.wrapPeerClientFactory(delegate, "shared", "upstream", 3001))
                .isSameAs(delegate);
        assertThat(appChainManager.wrapPeerClientFactory(delegate, "shared", null, 3001))
                .isSameAs(delegate);
        assertThat(appChainManager.wrapPeerClientFactory(delegate, "shared", "", 3001))
                .isSameAs(delegate);
    }

    @Test
    void sharedModeWithMatchingPeerArmsSessionsToThatEndpointOnly() {
        FakePeerClient armed = new FakePeerClient();
        FakePeerClient plain = new FakePeerClient();
        PeerClientFactory delegate = (endpoint, point) ->
                endpoint.host().equals("upstream") ? armed : plain;
        AppChainManager appChainManager = manager("upstream:3001", "otherpeer:13338");

        PeerClientFactory wrapped = appChainManager.wrapPeerClientFactory(
                delegate, "shared", "upstream", 3001);
        assertThat(wrapped).isNotSameAs(delegate);

        // Session to the matching endpoint is armed with 100 + 103
        wrapped.create(new PeerEndpoint("upstream", 3001, 42), null);
        assertThat(armed.appMsgEnabled.get()).isEqualTo(1);
        assertThat(armed.appChainSyncEnabled.get()).isEqualTo(1);

        // Any other L1 dial passes through untouched
        wrapped.create(new PeerEndpoint("somerelay", 3001, 42), null);
        assertThat(plain.appMsgEnabled.get()).isZero();
        assertThat(plain.appChainSyncEnabled.get()).isZero();
    }
}
