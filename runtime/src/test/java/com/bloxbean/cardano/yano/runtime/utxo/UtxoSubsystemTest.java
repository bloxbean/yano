package com.bloxbean.cardano.yano.runtime.utxo;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.events.impl.NoopEventBus;
import com.bloxbean.cardano.yano.api.config.RuntimeOptions;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.RollbackEvent;
import com.bloxbean.cardano.yano.api.plugin.StorageFilter;
import com.bloxbean.cardano.yano.runtime.chain.DirectRocksDBChainState;
import com.bloxbean.cardano.yano.runtime.chain.InMemoryChainState;
import com.bloxbean.cardano.yano.runtime.blockproducer.GenesisConfig;
import com.bloxbean.cardano.yano.runtime.genesis.ShelleyGenesisData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

class UtxoSubsystemTest {

    private static final String BASE_ADDRESS =
            "addr_test1qz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3jcu5d8ps7zex2k2xt3uqxgjqnnj83ws8lhrn648jjxtwq2ytjqp";

    @TempDir
    Path tempDir;

    @Test
    void startsAndPausesBackgroundServicesIdempotently() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        FakeUtxoStore store = new FakeUtxoStore();
        UtxoSubsystem subsystem = testSubsystem(store, scheduler);

        try {
            subsystem.startBackgroundServices();
            subsystem.startBackgroundServices();

            assertThat(subsystem.isPruneServiceRunning()).isTrue();

            subsystem.pauseBackgroundServices();
            subsystem.pauseBackgroundServices();

            assertThat(subsystem.isPruneServiceRunning()).isFalse();
        } finally {
            subsystem.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void productionConstructorDefersBackgroundServicesUntilStart() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        RuntimeOptions options = new RuntimeOptions(null, null, Map.of(
                "yano.utxo.enabled", true,
                "yano.utxo.prune.schedule.seconds", 60,
                "yano.utxo.metrics.lag.logSeconds", 60));

        try (DirectRocksDBChainState chain = new DirectRocksDBChainState(
                tempDir.resolve("chainstate").toString())) {
            UtxoSubsystem subsystem = new UtxoSubsystem(
                    YanoConfig.serverOnly(0),
                    options,
                    chain,
                    chain,
                    new NoopEventBus(),
                    scheduler,
                    LoggerFactory.getLogger(UtxoSubsystemTest.class));

            try {
                assertThat(subsystem.isPruneServiceRunning()).isFalse();

                subsystem.start();

                assertThat(subsystem.isPruneServiceRunning()).isTrue();
            } finally {
                subsystem.close();
            }
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void emptyFilterSnapshotClearsStartCycleFilterFromPreviousRun() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        RuntimeOptions options = new RuntimeOptions(null, null, Map.of(
                "yano.utxo.enabled", true,
                YanoPropertyKeys.UtxoFilter.ENABLED, true,
                "yano.utxo.prune.schedule.seconds", 60,
                "yano.utxo.metrics.lag.logSeconds", 60));

        try (DirectRocksDBChainState chain = new DirectRocksDBChainState(
                tempDir.resolve("filter-chainstate").toString())) {
            UtxoSubsystem subsystem = new UtxoSubsystem(
                    YanoConfig.serverOnly(0),
                    options,
                    chain,
                    chain,
                    new NoopEventBus(),
                    scheduler,
                    LoggerFactory.getLogger(UtxoSubsystemTest.class));

            try {
                DefaultUtxoStore store = (DefaultUtxoStore) subsystem.store();

                subsystem.initializeFilterChain(List.of(new StorageFilter() { }));
                assertThat(store.activeStorageFilterCount()).isOne();

                subsystem.initializeFilterChain(List.of());
                assertThat(store.activeStorageFilterCount()).isZero();
            } finally {
                subsystem.close();
            }
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void reinitializesAndReconcilesAfterSnapshotRestore() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        FakeUtxoStore store = new FakeUtxoStore();
        UtxoSubsystem subsystem = testSubsystem(store, scheduler);

        try {
            subsystem.reinitializeAndReconcileAfterSnapshotRestore();

            assertThat(store.reinitializeCalls).isEqualTo(1);
            assertThat(store.reconcileCalls).isEqualTo(1);
        } finally {
            subsystem.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void pausePruneReturnsTrueWhenServiceStops() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        UtxoSubsystem subsystem = testSubsystem(new FakeUtxoStore(), scheduler);

        try {
            subsystem.startBackgroundServices();

            assertThat(subsystem.pausePruneServiceAndAwait(Duration.ofSeconds(1))).isTrue();
            assertThat(subsystem.isPruneServiceRunning()).isFalse();
        } finally {
            subsystem.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void terminalCloseReleasesStoreResources() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        FakeUtxoStore store = new FakeUtxoStore();
        UtxoSubsystem subsystem = testSubsystem(store, scheduler);

        try {
            subsystem.startBackgroundServices();

            subsystem.close();
            subsystem.close();

            assertThat(subsystem.isPruneServiceRunning()).isFalse();
            assertThat(store.closeCalls).isEqualTo(1);
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void freshFullStateMarkerInitializationIsIndependentOfRuntimeRoleAndGenesisSource() throws Exception {
        Path shelley = Path.of(getClass().getResource("/genesis/test-shelley-genesis.json").toURI());
        Path byron = Path.of(getClass().getResource("/genesis/test-byron-genesis.json").toURI());
        GenesisConfig fileGenesis = GenesisConfig.load(
                shelley.toString(), byron.toString(), null);
        GenesisConfig memoryGenesis = GenesisConfig.fromInMemory(
                fileGenesis.getShelleyGenesisData(), fileGenesis.getByronGenesisData(), null);
        record Scenario(String name, YanoConfig config, GenesisConfig genesis) {}
        List<Scenario> scenarios = List.of(
                new Scenario("relay", YanoConfig.serverOnly(0), fileGenesis),
                new Scenario("client", YanoConfig.clientOnly("localhost", 3001, 42L), fileGenesis),
                new Scenario("producer-file", YanoConfig.devnetDefault(0), fileGenesis),
                new Scenario("producer-memory", YanoConfig.devnetDefault(0), memoryGenesis),
                new Scenario("empty-distribution", YanoConfig.serverOnly(0), null));

        for (Scenario scenario : scenarios) {
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
            try (DirectRocksDBChainState chain = new DirectRocksDBChainState(
                    tempDir.resolve(scenario.name()).toString())) {
                RuntimeOptions options = new RuntimeOptions(null, null, Map.of(
                        "yano.utxo.enabled", true,
                        "yano.metrics.enabled", false));
                UtxoSubsystem subsystem = new UtxoSubsystem(
                        scenario.config(), options, chain, chain, new NoopEventBus(), scheduler,
                        LoggerFactory.getLogger(UtxoSubsystemTest.class));
                try {
                    DefaultUtxoStore store = (DefaultUtxoStore) subsystem.store();
                    store.wireAllegraBootstrapRemoval(chain);

                    subsystem.initializeOrValidateFullStateGenesis(
                            scenario.genesis(), scenario.config().getProtocolMagic());

                    assertThat(store.hasByronMainApplyCapability())
                            .as(scenario.name()).isTrue();
                } finally {
                    subsystem.close();
                }
            } finally {
                scheduler.shutdownNow();
            }
        }
    }

    @Test
    void producerBootstrapAndGenesisBlockPathSeedShelleyStakeExactlyOnce() throws Exception {
        Path shelleyFile = Path.of(getClass().getResource("/genesis/test-shelley-genesis.json").toURI());
        GenesisConfig parsed = GenesisConfig.load(shelleyFile.toString(), null, null);
        String genesisHexAddress = com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString(
                new Address(BASE_ADDRESS).getBytes());
        BigInteger amount = BigInteger.valueOf(42_000_000L);
        Map<String, BigInteger> funds = Map.of(genesisHexAddress, amount);
        GenesisConfig producerGenesis = GenesisConfig.fromInMemory(
                withInitialFunds(parsed.getShelleyGenesisData(), funds), null, null);
        YanoConfig config = YanoConfig.devnetDefault(0);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        try (DirectRocksDBChainState chain = new DirectRocksDBChainState(
                tempDir.resolve("producer-genesis-composition").toString())) {
            RuntimeOptions options = new RuntimeOptions(null, null, Map.of(
                    YanoPropertyKeys.Utxo.ENABLED, true,
                    YanoPropertyKeys.Metrics.ENABLED, false));
            UtxoSubsystem subsystem = new UtxoSubsystem(
                    config, options, chain, chain, new NoopEventBus(), scheduler,
                    LoggerFactory.getLogger(UtxoSubsystemTest.class));
            try {
                DefaultUtxoStore store = (DefaultUtxoStore) subsystem.store();
                store.wireAllegraBootstrapRemoval(chain);

                // Stage 64: capability/Byron initialization only in producer mode.
                subsystem.initializeOrValidateFullStateGenesis(
                        producerGenesis, config.getProtocolMagic());
                assertThat(store.hasByronMainApplyCapability()).isTrue();
                assertThat(store.computeTotalUtxoLovelace()).isZero();

                // Stage 68: the producer has now stored its real genesis block.
                String genesisBlockHash = "ab".repeat(32);
                store.storeGenesisUtxos(funds, config.getProtocolMagic(), 0L, 0L, genesisBlockHash);

                assertThat(store.computeTotalUtxoLovelace()).isEqualTo(amount);
                Address parsedAddress = new Address(BASE_ADDRESS);
                String stakeHash = com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString(
                        parsedAddress.getDelegationCredentialHash().orElseThrow());
                assertThat(store.getUtxoBalanceByStakeCredential(0, stakeHash)).contains(amount);
                assertThat(store.getUtxosByAddress(BASE_ADDRESS, 1, 10))
                        .singleElement()
                        .satisfies(utxo -> assertThat(utxo.blockHash()).isEqualTo(genesisBlockHash));
            } finally {
                subsystem.close();
            }
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void operatorCanRebuildAnUnmarkedDatabaseAtStartupWithOneShotProperty() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try (DirectRocksDBChainState chain = new DirectRocksDBChainState(
                tempDir.resolve("operator-rebuild").toString())) {
            byte[] block = emptyBabbageBlockCbor(1L, 10L);
            byte[] hash = com.bloxbean.cardano.yaci.core.util.HexUtil.decodeHexString("cd".repeat(32));
            chain.storeBlockHeader(hash, 1L, 10L, block);
            chain.storeBlock(hash, 1L, 10L, block);
            RuntimeOptions options = new RuntimeOptions(null, null, Map.of(
                    YanoPropertyKeys.Utxo.ENABLED, true,
                    YanoPropertyKeys.Utxo.REBUILD_UNMARKED_FROM_GENESIS, true,
                    YanoPropertyKeys.Metrics.ENABLED, false));
            UtxoSubsystem subsystem = new UtxoSubsystem(
                    YanoConfig.serverOnly(0), options, chain, chain, new NoopEventBus(), scheduler,
                    LoggerFactory.getLogger(UtxoSubsystemTest.class));
            try {
                DefaultUtxoStore store = (DefaultUtxoStore) subsystem.store();
                store.wireAllegraBootstrapRemoval(chain);

                subsystem.initializeOrValidateFullStateGenesis(null, 42L);

                assertThat(store.hasByronMainApplyCapability()).isTrue();
                assertThat(store.getLastAppliedBlock()).isEqualTo(1L);
                assertThat(store.getLatestAppliedSlot()).isEqualTo(10L);
            } finally {
                subsystem.close();
            }
        } finally {
            scheduler.shutdownNow();
        }
    }

    private static ShelleyGenesisData withInitialFunds(
            ShelleyGenesisData source, Map<String, BigInteger> initialFunds) {
        return new ShelleyGenesisData(
                initialFunds,
                source.networkMagic(), source.epochLength(), source.slotLength(), source.systemStart(),
                source.maxLovelaceSupply(), source.activeSlotsCoeff(), source.securityParam(),
                source.maxKESEvolutions(), source.slotsPerKESPeriod(), source.updateQuorum(),
                source.protocolMajor(), source.protocolMinor(), source.rho(), source.tau(), source.a0(),
                source.nOpt(), source.minPoolCost(), source.keyDeposit(), source.poolDeposit(),
                source.decentralisationParam(), source.minFeeA(), source.minFeeB(),
                source.maxBlockBodySize(), source.maxTxSize(), source.maxBlockHeaderSize(),
                source.eMax(), source.extraEntropy(), source.minUTxOValue(), source.bootstrap());
    }

    private static byte[] emptyBabbageBlockCbor(long blockNumber, long slot) {
        var headerBody = new co.nstant.in.cbor.model.Array();
        headerBody.add(new co.nstant.in.cbor.model.UnsignedInteger(blockNumber));
        headerBody.add(new co.nstant.in.cbor.model.UnsignedInteger(slot));
        headerBody.add(co.nstant.in.cbor.model.SimpleValue.NULL);
        headerBody.add(new co.nstant.in.cbor.model.ByteString(new byte[32]));
        headerBody.add(new co.nstant.in.cbor.model.ByteString(new byte[32]));
        var vrf = new co.nstant.in.cbor.model.Array();
        vrf.add(new co.nstant.in.cbor.model.ByteString(new byte[32]));
        vrf.add(new co.nstant.in.cbor.model.ByteString(new byte[64]));
        headerBody.add(vrf);
        headerBody.add(new co.nstant.in.cbor.model.UnsignedInteger(0));
        headerBody.add(new co.nstant.in.cbor.model.ByteString(new byte[32]));
        var opCert = new co.nstant.in.cbor.model.Array();
        opCert.add(new co.nstant.in.cbor.model.ByteString(new byte[32]));
        opCert.add(new co.nstant.in.cbor.model.UnsignedInteger(0));
        opCert.add(new co.nstant.in.cbor.model.UnsignedInteger(0));
        opCert.add(new co.nstant.in.cbor.model.ByteString(new byte[64]));
        headerBody.add(opCert);
        var protocolVersion = new co.nstant.in.cbor.model.Array();
        protocolVersion.add(new co.nstant.in.cbor.model.UnsignedInteger(7));
        protocolVersion.add(new co.nstant.in.cbor.model.UnsignedInteger(0));
        headerBody.add(protocolVersion);
        var header = new co.nstant.in.cbor.model.Array();
        header.add(headerBody);
        header.add(new co.nstant.in.cbor.model.ByteString(new byte[64]));
        var block = new co.nstant.in.cbor.model.Array();
        block.add(header);
        block.add(new co.nstant.in.cbor.model.Array());
        block.add(new co.nstant.in.cbor.model.Array());
        block.add(new co.nstant.in.cbor.model.Map());
        block.add(new co.nstant.in.cbor.model.Array());
        var outer = new co.nstant.in.cbor.model.Array();
        outer.add(new co.nstant.in.cbor.model.UnsignedInteger(
                com.bloxbean.cardano.yaci.core.model.Era.Babbage.getValue()));
        outer.add(block);
        return com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.serialize(outer, true);
    }

    private static UtxoSubsystem testSubsystem(FakeUtxoStore store, ScheduledExecutorService scheduler) {
        RuntimeOptions options = new RuntimeOptions(null, null, Map.of(
                "yano.utxo.prune.schedule.seconds", 60,
                "yano.utxo.metrics.lag.logSeconds", 60));
        ChainState chainState = new InMemoryChainState();
        return new UtxoSubsystem(
                YanoConfig.serverOnly(0),
                options,
                chainState,
                store,
                new NoopEventBus(),
                scheduler,
                LoggerFactory.getLogger(UtxoSubsystemTest.class));
    }

    private static final class FakeUtxoStore implements UtxoStoreWriter, Prunable, AutoCloseable {
        private int reinitializeCalls;
        private int reconcileCalls;
        private int closeCalls;

        @Override
        public void applyBlock(BlockAppliedEvent e) {
        }

        @Override
        public void rollbackTo(RollbackEvent e) {
        }

        @Override
        public void reconcile(ChainState chainState) {
            reconcileCalls++;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void reinitialize() {
            reinitializeCalls++;
        }

        @Override
        public void pruneOnce() {
        }

        @Override
        public void close() {
            closeCalls++;
        }
    }
}
