package com.bloxbean.cardano.yano.runtime.config;

import com.bloxbean.cardano.yano.api.config.ChainSelectionConfig;
import com.bloxbean.cardano.yano.api.config.UpstreamConfig;
import com.bloxbean.cardano.yano.api.config.UpstreamDiscoveryConfig;
import com.bloxbean.cardano.yano.api.config.UpstreamGovernorConfig;
import com.bloxbean.cardano.yano.api.config.UpstreamPeerConfig;
import com.bloxbean.cardano.yano.api.config.UpstreamPreset;
import com.bloxbean.cardano.yano.api.config.UpstreamSyncConfig;
import com.bloxbean.cardano.yano.api.config.UpstreamTxConfig;
import com.bloxbean.cardano.yano.api.config.UpstreamValidationConfig;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpstreamConfigTest {
    @Test
    void legacyRemoteMapsToTrustedSingleUpstream() {
        YanoConfig config = YanoConfig.builder()
                .enableClient(true)
                .enableServer(false)
                .remoteHost("relay.example.com")
                .remotePort(3001)
                .protocolMagic(42)
                .useRocksDB(false)
                .fullSyncThreshold(0)
                .enablePipelinedSync(false)
                .build();

        UpstreamConfig upstream = config.effectiveUpstream();

        assertThat(upstream.getMode()).isEqualTo(UpstreamPreset.TRUSTED_SINGLE);
        assertThat(upstream.getPeers()).hasSize(1);
        assertThat(upstream.getPeers().getFirst().getHost()).isEqualTo("relay.example.com");
    }

    @Test
    void explicitUpstreamPeersSatisfyClientRemoteRequirement() {
        YanoConfig config = YanoConfig.builder()
                .enableClient(true)
                .enableServer(false)
                .remoteHost(null)
                .remotePort(0)
                .protocolMagic(42)
                .upstream(UpstreamConfig.builder()
                        .mode(UpstreamPreset.TRUSTED_FAILOVER)
                        .peers(List.of(peer("a", "relay-a", 3001)))
                        .build())
                .useRocksDB(false)
                .fullSyncThreshold(0)
                .enablePipelinedSync(false)
                .build();

        config.validate();
    }

    @Test
    void discoveryBootstrapSatisfiesClientRemoteRequirement() {
        YanoConfig config = clientConfigWith(UpstreamConfig.builder()
                .mode(UpstreamPreset.P2P_RELAY)
                .discovery(UpstreamDiscoveryConfig.builder()
                        .enabled(true)
                        .peerSnapshotUrls(List.of("https://example.com/peer-snapshot.json"))
                        .build())
                .build());

        config.validate();
    }

    @Test
    void singleActiveModeStillRequiresRemoteOrPeer() {
        YanoConfig config = clientConfigWith(UpstreamConfig.builder()
                .mode(UpstreamPreset.TRUSTED_SINGLE)
                .discovery(UpstreamDiscoveryConfig.builder()
                        .enabled(true)
                        .build())
                .build());

        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Remote host");
    }

    @Test
    void structuralValidatedTrustPolicyFailsFast() {
        YanoConfig config = YanoConfig.builder()
                .enableClient(true)
                .enableServer(false)
                .remoteHost(null)
                .remotePort(0)
                .protocolMagic(42)
                .upstream(UpstreamConfig.builder()
                        .mode(UpstreamPreset.STATIC_MULTI)
                        .peers(List.of(peer("a", "relay-a", 3001), peer("b", "relay-b", 3001)))
                        .selection(ChainSelectionConfig.builder()
                                .trustPolicy("validated")
                                .build())
                        .build())
                .useRocksDB(false)
                .fullSyncThreshold(0)
                .enablePipelinedSync(false)
                .build();

        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("validated trust policy");
    }

    @Test
    void headerSignatureValidationLevelIsSupported() {
        YanoConfig config = clientConfigWith(UpstreamConfig.builder()
                .mode(UpstreamPreset.STATIC_MULTI)
                .peers(List.of(peer("a", "relay-a", 3001), peer("b", "relay-b", 3002)))
                .validation(UpstreamValidationConfig.builder()
                        .level("header-signature")
                        .build())
                .build());

        config.validate();
    }

    @Test
    void validationDefaultsAreDisabled() {
        UpstreamValidationConfig validation = UpstreamValidationConfig.builder().build();

        assertThat(validation.normalizedLevel()).isEqualTo("none");
        assertThat(validation.normalizedBodyLevel()).isEqualTo("none");
    }

    @Test
    void zeroRollbackWindowMeansGenesisDerivedDefault() {
        YanoConfig config = clientConfigWith(UpstreamConfig.builder()
                .mode(UpstreamPreset.STATIC_MULTI)
                .peers(List.of(peer("a", "relay-a", 3001), peer("b", "relay-b", 3002)))
                .selection(ChainSelectionConfig.builder()
                        .rollbackWindowSlots(0L)
                        .build())
                .build());

        config.validate();
    }

    @Test
    void negativeRollbackWindowFailsFast() {
        YanoConfig config = clientConfigWith(UpstreamConfig.builder()
                .mode(UpstreamPreset.STATIC_MULTI)
                .peers(List.of(peer("a", "relay-a", 3001), peer("b", "relay-b", 3002)))
                .selection(ChainSelectionConfig.builder()
                        .rollbackWindowSlots(-1L)
                        .build())
                .build());

        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rollback-window-slots");
    }

    @Test
    void unsupportedBodyValidationLevelFailsFast() {
        YanoConfig config = clientConfigWith(UpstreamConfig.builder()
                .mode(UpstreamPreset.TRUSTED_SINGLE)
                .peers(List.of(peer("a", "relay-a", 3001)))
                .validation(UpstreamValidationConfig.builder()
                        .bodyLevel("body-integrity")
                        .build())
                .build());

        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("body-level");
    }

    @Test
    void headerSignatureValidatedTrustPolicyStillFailsFast() {
        YanoConfig config = clientConfigWith(UpstreamConfig.builder()
                .mode(UpstreamPreset.STATIC_MULTI)
                .peers(List.of(peer("a", "relay-a", 3001), peer("b", "relay-b", 3002)))
                .validation(UpstreamValidationConfig.builder()
                        .level("header-signature")
                        .build())
                .selection(ChainSelectionConfig.builder()
                        .trustPolicy("validated")
                        .build())
                .build());

        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("validated trust policy");
    }

    @Test
    void invalidUpstreamPolicyValuesFailFast() {
        assertThatThrownBy(() -> clientConfigWith(UpstreamConfig.builder()
                .mode(UpstreamPreset.STATIC_MULTI)
                .peers(List.of(peer("a", "relay-a", 3001), peer("b", "relay-b", 3002)))
                .sync(UpstreamSyncConfig.builder()
                        .fanInStart("early")
                        .build())
                .build()).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fan-in-start");

        assertThatThrownBy(() -> clientConfigWith(UpstreamConfig.builder()
                .mode(UpstreamPreset.STATIC_MULTI)
                .peers(List.of(peer("a", "relay-a", 3001), peer("b", "relay-b", 3002)))
                .tx(UpstreamTxConfig.builder()
                        .forwarding("all-hot")
                        .build())
                .build()).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tx.forwarding");

        assertThatThrownBy(() -> clientConfigWith(UpstreamConfig.builder()
                .mode(UpstreamPreset.STATIC_MULTI)
                .peers(List.of(peer("a", "relay-a", 3001), peer("b", "relay-b", 3002)))
                .governor(UpstreamGovernorConfig.builder()
                        .targetHot(0)
                        .build())
                .build()).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targets.hot");
    }

    private static UpstreamPeerConfig peer(String id, String host, int port) {
        return UpstreamPeerConfig.builder()
                .id(id)
                .host(host)
                .port(port)
                .trust("trusted")
                .build();
    }

    private static YanoConfig clientConfigWith(UpstreamConfig upstream) {
        return YanoConfig.builder()
                .enableClient(true)
                .enableServer(false)
                .remoteHost(null)
                .remotePort(0)
                .protocolMagic(42)
                .upstream(upstream)
                .useRocksDB(false)
                .fullSyncThreshold(0)
                .enablePipelinedSync(false)
                .build();
    }
}
