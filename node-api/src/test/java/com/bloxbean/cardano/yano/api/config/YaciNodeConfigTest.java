package com.bloxbean.cardano.yano.api.config;

import com.bloxbean.cardano.yaci.core.common.Constants;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class YaciNodeConfigTest {

    @Test
    void preprodDefault_shouldCreateValidConfiguration() {
        YaciNodeConfig config = YaciNodeConfig.preprodDefault();
        
        assertThat(config).isNotNull();
        assertThat(config.getProtocolMagic()).isEqualTo(Constants.PREPROD_PROTOCOL_MAGIC);
        assertThat(config.isClientEnabled()).isTrue();
        assertThat(config.isServerEnabled()).isTrue();
        assertThat(config.getRemoteHost()).isEqualTo("localhost");
        assertThat(config.getRemotePort()).isEqualTo(32000);
        assertThat(config.getServerPort()).isEqualTo(13337);
        assertThat(config.isUseRocksDB()).isTrue();
        
        assertThatCode(config::validate).doesNotThrowAnyException();
    }

    @Test
    void mainnetDefault_shouldCreateValidConfiguration() {
        YaciNodeConfig config = YaciNodeConfig.mainnetDefault();
        
        assertThat(config).isNotNull();
        assertThat(config.getProtocolMagic()).isEqualTo(Constants.MAINNET_PROTOCOL_MAGIC);
        assertThat(config.isClientEnabled()).isTrue();
        assertThat(config.isServerEnabled()).isTrue();
        assertThat(config.getRemoteHost()).isEqualTo(Constants.MAINNET_PUBLIC_RELAY_ADDR);
        assertThat(config.getRemotePort()).isEqualTo(Constants.MAINNET_PUBLIC_RELAY_PORT);
        
        assertThatCode(config::validate).doesNotThrowAnyException();
    }

    @Test
    void serverOnly_shouldCreateValidServerOnlyConfiguration() {
        YaciNodeConfig config = YaciNodeConfig.serverOnly(13337);
        
        assertThat(config).isNotNull();
        assertThat(config.isClientEnabled()).isFalse();
        assertThat(config.isServerEnabled()).isTrue();
        assertThat(config.getServerPort()).isEqualTo(13337);
        assertThat(config.isUseRocksDB()).isFalse();
        
        assertThatCode(config::validate).doesNotThrowAnyException();
    }

    @Test
    void clientOnly_shouldCreateValidClientOnlyConfiguration() {
        YaciNodeConfig config = YaciNodeConfig.clientOnly("localhost", 3001, Constants.PREPROD_PROTOCOL_MAGIC);
        
        assertThat(config).isNotNull();
        assertThat(config.isClientEnabled()).isTrue();
        assertThat(config.isServerEnabled()).isFalse();
        assertThat(config.getRemoteHost()).isEqualTo("localhost");
        assertThat(config.getRemotePort()).isEqualTo(3001);
        assertThat(config.getProtocolMagic()).isEqualTo(Constants.PREPROD_PROTOCOL_MAGIC);
        
        assertThatCode(config::validate).doesNotThrowAnyException();
    }

    @Test
    void validate_shouldThrowWhenClientEnabledButNoRemoteHost() {
        YaciNodeConfig config = YaciNodeConfig.builder()
                .enableClient(true)
                .enableServer(false)
                .remoteHost(null)
                .remotePort(3001)
                .protocolMagic(Constants.PREPROD_PROTOCOL_MAGIC)
                .build();
        
        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Remote host must be specified when client is enabled");
    }

    @Test
    void validate_shouldThrowWhenClientEnabledButInvalidPort() {
        YaciNodeConfig config = YaciNodeConfig.builder()
                .enableClient(true)
                .enableServer(false)
                .remoteHost("localhost")
                .remotePort(0)
                .protocolMagic(Constants.PREPROD_PROTOCOL_MAGIC)
                .build();
        
        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Remote port must be between 1 and 65535");
    }

    @Test
    void validate_shouldThrowWhenServerEnabledButInvalidPort() {
        YaciNodeConfig config = YaciNodeConfig.builder()
                .enableClient(false)
                .enableServer(true)
                .serverPort(-1)
                .protocolMagic(Constants.PREPROD_PROTOCOL_MAGIC)
                .build();
        
        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Server port must be between 1 and 65535");
    }

    @Test
    void validate_shouldThrowWhenNeitherClientNorServerEnabled() {
        YaciNodeConfig config = YaciNodeConfig.builder()
                .enableClient(false)
                .enableServer(false)
                .protocolMagic(Constants.PREPROD_PROTOCOL_MAGIC)
                .build();
        
        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one of client, server, or block producer must be enabled");
    }

    @Test
    void validate_shouldThrowWhenRocksDBEnabledButNoPath() {
        YaciNodeConfig config = YaciNodeConfig.builder()
                .enableClient(false)
                .enableServer(true)
                .serverPort(13337)
                .useRocksDB(true)
                .rocksDBPath(null)
                .protocolMagic(Constants.PREPROD_PROTOCOL_MAGIC)
                .build();
        
        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RocksDB path must be specified when RocksDB is enabled");
    }

    @Test
    void testConfig_shouldCreateValidTestConfiguration() {
        YaciNodeConfig config = YaciNodeConfig.testConfig("localhost", 3001, Constants.PREPROD_PROTOCOL_MAGIC, 13337, false);
        
        assertThat(config).isNotNull();
        assertThat(config.isClientEnabled()).isTrue();
        assertThat(config.isServerEnabled()).isTrue();
        assertThat(config.getFullSyncThreshold()).isEqualTo(100); // Lower threshold for testing
        assertThat(config.isEnablePipelinedSync()).isTrue();
        assertThat(config.getHeaderPipelineDepth()).isEqualTo(20); // Smaller values for testing
        
        assertThatCode(config::validate).doesNotThrowAnyException();
    }

    @Test
    void devnetDefault_shouldCreateValidConfiguration() {
        YaciNodeConfig config = YaciNodeConfig.devnetDefault(13337);

        assertThat(config).isNotNull();
        assertThat(config.isClientEnabled()).isFalse();
        assertThat(config.isServerEnabled()).isTrue();
        assertThat(config.isEnableBlockProducer()).isTrue();
        assertThat(config.isDevMode()).isTrue();
        assertThat(config.getProtocolMagic()).isEqualTo(42);
        assertThat(config.isPastTimeTravelMode()).isFalse();

        assertThatCode(config::validate).doesNotThrowAnyException();
    }

    @Test
    void validate_pastTimeTravelMode_shouldPassWhenDevModeAndBlockProducerEnabled() {
        YaciNodeConfig config = YaciNodeConfig.builder()
                .enableClient(false)
                .enableServer(true)
                .serverPort(13337)
                .enableBlockProducer(true)
                .devMode(true)
                .pastTimeTravelMode(true)
                .protocolMagic(42)
                .build();

        assertThatCode(config::validate).doesNotThrowAnyException();
    }

    @Test
    void validate_pastTimeTravelMode_shouldThrowWhenDevModeDisabled() {
        YaciNodeConfig config = YaciNodeConfig.builder()
                .enableClient(false)
                .enableServer(true)
                .serverPort(13337)
                .enableBlockProducer(true)
                .devMode(false)
                .pastTimeTravelMode(true)
                .protocolMagic(42)
                .build();

        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Past time travel mode requires dev mode");
    }

    @Test
    void validate_pastTimeTravelMode_shouldThrowWhenBlockProducerDisabled() {
        YaciNodeConfig config = YaciNodeConfig.builder()
                .enableClient(false)
                .enableServer(true)
                .serverPort(13337)
                .enableBlockProducer(false)
                .devMode(true)
                .pastTimeTravelMode(true)
                .protocolMagic(42)
                .build();

        // devMode without blockProducer fails first
        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Fail-fast epoch/slot tests ---

    @Test
    void unsetEpochLength_throwsFromGetEpochLength() {
        YaciNodeConfig config = YaciNodeConfig.builder()
                .enableClient(false)
                .enableServer(true)
                .serverPort(13337)
                .protocolMagic(1)
                .build();

        assertThatThrownBy(config::getEpochLength)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("epochLength must be loaded from Shelley genesis");
        assertThat(config.isEpochParamsInitialized()).isFalse();
    }

    @Test
    void unsetByronSlotsPerEpoch_throwsFromGetByronSlotsPerEpoch() {
        YaciNodeConfig config = YaciNodeConfig.builder()
                .enableClient(false)
                .enableServer(true)
                .serverPort(13337)
                .protocolMagic(1)
                .epochLength(432000L)
                .build();

        assertThatThrownBy(config::getByronSlotsPerEpoch)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("byronSlotsPerEpoch must be loaded");
        assertThat(config.isEpochParamsInitialized()).isFalse();
    }

    @Test
    void unsetFirstNonByronSlot_throwsFromGetFirstNonByronSlot() {
        YaciNodeConfig config = YaciNodeConfig.builder()
                .enableClient(false)
                .enableServer(true)
                .serverPort(13337)
                .protocolMagic(1)
                .epochLength(432000L)
                .byronSlotsPerEpoch(21600L)
                .build();

        assertThatThrownBy(config::getFirstNonByronSlot)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("firstNonByronSlot must be resolved");
        assertThat(config.isEpochParamsInitialized()).isFalse();
    }

    @Test
    void firstNonByronSlot_zeroIsValid() {
        YaciNodeConfig config = YaciNodeConfig.builder()
                .enableClient(false)
                .enableServer(true)
                .serverPort(13337)
                .protocolMagic(2)
                .epochLength(86400L)
                .byronSlotsPerEpoch(4320L)
                .firstNonByronSlot(0L)
                .build();

        assertThat(config.getFirstNonByronSlot()).isEqualTo(0);
        assertThat(config.isEpochParamsInitialized()).isTrue();
    }

    @Test
    void preprodDefault_epochFieldsNotSet() {
        YaciNodeConfig config = YaciNodeConfig.preprodDefault();
        // Factory methods do NOT set epoch fields — they come from genesis at runtime
        assertThat(config.isEpochParamsInitialized()).isFalse();
    }

    @Test
    void mainnetDefault_epochFieldsNotSet() {
        YaciNodeConfig config = YaciNodeConfig.mainnetDefault();
        assertThat(config.isEpochParamsInitialized()).isFalse();
    }

    @Test
    void toString_shouldIncludeKeyConfigurationDetails() {
        YaciNodeConfig config = YaciNodeConfig.preprodDefault();
        
        String configString = config.toString();
        
        assertThat(configString).contains("client=true");
        assertThat(configString).contains("server=true");
        assertThat(configString).contains("localhost:32000");
        assertThat(configString).contains("serverPort=13337");
        assertThat(configString).contains("RocksDB");
        assertThat(configString).contains("magic=" + Constants.PREPROD_PROTOCOL_MAGIC);
    }
}