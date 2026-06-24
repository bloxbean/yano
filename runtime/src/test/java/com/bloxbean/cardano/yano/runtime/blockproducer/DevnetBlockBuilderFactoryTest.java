package com.bloxbean.cardano.yano.runtime.blockproducer;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.runtime.chain.InMemoryChainState;
import com.bloxbean.cardano.yano.runtime.producer.DevnetBlockBuilderFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DevnetBlockBuilderFactoryTest {
    private static final Path DEVNET_FIXTURE = Path.of("src/test/resources/devnet");

    @Test
    void createsUnsignedBuilderWhenProducerKeysAreNotConfigured() {
        AtomicLong requestedSlot = new AtomicLong(-1);
        DevnetBlockBuilderFactory factory = factory(
                YanoConfig.builder().protocolMagic(42).build(),
                new InMemoryChainState(),
                slot -> {
                    requestedSlot.set(slot);
                    return new ProtocolVersion(11, 0);
                },
                new AtomicReference<>());

        DevnetBlockBuilder builder = factory.create(true);

        assertThat(builder).isNotInstanceOf(SignedBlockBuilder.class);
        var result = builder.buildBlock(1, 77, new byte[32], List.of());
        assertThat(requestedSlot).hasValue(77);
        assertThat(BlockTestUtil.protocolVersionFromBlockCbor(result.blockCbor()))
                .isEqualTo(new ProtocolVersion(11, 0));
    }

    @Test
    void partialProducerKeyConfigurationKeepsUnsignedCompatibility() {
        YanoConfig config = YanoConfig.builder()
                .protocolMagic(42)
                .vrfSkeyFile(DEVNET_FIXTURE.resolve("vrf.skey").toString())
                .kesSkeyFile(DEVNET_FIXTURE.resolve("kes.skey").toString())
                .build();

        DevnetBlockBuilder builder = factory(
                config,
                new InMemoryChainState(),
                ProtocolVersionSupplier.fixed(10, 2),
                new AtomicReference<>())
                .create(true);

        assertThat(DevnetBlockBuilderFactory.hasConfiguredProducerKeys(config)).isFalse();
        assertThat(builder).isNotInstanceOf(SignedBlockBuilder.class);
    }

    @Test
    void createsSignedBuilderAndPropagatesProtocolVersionSupplier() {
        AtomicLong requestedSlot = new AtomicLong(-1);
        AtomicReference<NonceReplayService> replayService = new AtomicReference<>();
        DevnetBlockBuilderFactory factory = factory(
                signedProducerConfig(),
                new InMemoryChainState(),
                slot -> {
                    requestedSlot.set(slot);
                    return new ProtocolVersion(11, 0);
                },
                replayService);

        DevnetBlockBuilder builder = factory.create(true);

        assertThat(builder).isInstanceOf(SignedBlockBuilder.class);
        assertThat(replayService).hasValue(null);
        var result = builder.buildBlock(1, 5, new byte[32], List.of());
        ((SignedBlockBuilder) builder).commitPendingNonceState();
        assertThat(requestedSlot).hasValue(5);
        assertThat(BlockTestUtil.protocolVersionFromBlockCbor(result.blockCbor()))
                .isEqualTo(new ProtocolVersion(11, 0));
    }

    @Test
    void signedRestartPreparesNonceReplayServiceWhenChainSupportsNonceStore() {
        AtomicReference<NonceReplayService> replayService = new AtomicReference<>();

        DevnetBlockBuilder builder = factory(
                signedProducerConfig(),
                new InMemoryChainState(),
                ProtocolVersionSupplier.fixed(10, 2),
                replayService)
                .create(false);

        assertThat(builder).isInstanceOf(SignedBlockBuilder.class);
        assertThat(replayService.get()).isNotNull();
    }

    @Test
    void signedKeyLoadingFailureDoesNotResolveProtocolVersionFirst() {
        AtomicReference<Boolean> protocolSupplierRequested = new AtomicReference<>(false);
        GenesisConfig genesisConfig = GenesisConfig.load(
                DEVNET_FIXTURE.resolve("shelley-genesis.json").toString(),
                null,
                null);
        YanoConfig config = YanoConfig.builder()
                .protocolMagic(42)
                .vrfSkeyFile("/tmp/yano-missing-vrf.skey")
                .kesSkeyFile("/tmp/yano-missing-kes.skey")
                .opCertFile("/tmp/yano-missing-opcert.cert")
                .build();
        DevnetBlockBuilderFactory factory = new DevnetBlockBuilderFactory(
                config,
                genesisConfig,
                new DevnetBlockBuilderFactory.Dependencies(
                        new InMemoryChainState(),
                        () -> null,
                        DevnetBlockBuilderFactoryTest::genesisHash,
                        nonceState -> nonceState.setShelleyStartSlot(0),
                        (nonceState, nonceStore, replay, repairReason, modeDescription) -> {
                        },
                        () -> {
                            protocolSupplierRequested.set(true);
                            return ProtocolVersionSupplier.fixed(11, 0);
                        }));

        assertThatThrownBy(() -> factory.create(true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to initialize SignedBlockBuilder with configured producer keys");
        assertThat(protocolSupplierRequested).hasValue(false);
    }

    private static DevnetBlockBuilderFactory factory(YanoConfig config,
                                                    ChainState chainState,
                                                    ProtocolVersionSupplier protocolVersionSupplier,
                                                    AtomicReference<NonceReplayService> replayService) {
        GenesisConfig genesisConfig = GenesisConfig.load(
                DEVNET_FIXTURE.resolve("shelley-genesis.json").toString(),
                null,
                null);
        return new DevnetBlockBuilderFactory(
                config,
                genesisConfig,
                new DevnetBlockBuilderFactory.Dependencies(
                        chainState,
                        () -> null,
                        DevnetBlockBuilderFactoryTest::genesisHash,
                        nonceState -> nonceState.setShelleyStartSlot(0),
                        (nonceState, nonceStore, replay, repairReason, modeDescription) -> {
                            replayService.set(replay);
                            try {
                                nonceState.initFromGenesis(Files.readAllBytes(
                                        DEVNET_FIXTURE.resolve("shelley-genesis.json")));
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                            if (nonceStore != null) {
                                nonceStore.storeEpochNonce(
                                        nonceState.getCurrentEpoch(),
                                        nonceState.getEpochNonce());
                            }
                        },
                        () -> protocolVersionSupplier));
    }

    private static byte[] genesisHash() {
        try {
            return Blake2bUtil.blake2bHash256(Files.readAllBytes(
                    DEVNET_FIXTURE.resolve("shelley-genesis.json")));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static YanoConfig signedProducerConfig() {
        return YanoConfig.builder()
                .protocolMagic(42)
                .vrfSkeyFile(DEVNET_FIXTURE.resolve("vrf.skey").toString())
                .kesSkeyFile(DEVNET_FIXTURE.resolve("kes.skey").toString())
                .opCertFile(DEVNET_FIXTURE.resolve("opcert.cert").toString())
                .build();
    }
}
