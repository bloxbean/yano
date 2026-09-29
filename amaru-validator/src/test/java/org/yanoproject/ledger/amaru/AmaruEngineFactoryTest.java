package org.yanoproject.ledger.amaru;

import com.bloxbean.cardano.client.spec.NetworkId;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.config.YanoPropertyKeys;
import org.yanoproject.ledger.rules.EngineContext;
import org.yanoproject.ledger.rules.EpochProtocolParamsSupplier;
import org.yanoproject.ledger.rules.LedgerValidationEngines;
import org.yanoproject.ledger.rules.NetworkParameters;
import org.yanoproject.ledger.rules.SlotConfigSupplier;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseEvaluator;
import org.yanoproject.scalusbridge.ScalusScriptPhaseEvaluator;

import java.math.BigInteger;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-057 "What step 1d must provide": configuration mapping, discovery and network parameters. */
class AmaruEngineFactoryTest {

    private static final BigInteger MAX_SUPPLY = BigInteger.valueOf(45_000_000_000_000_000L);

    @Test
    void discoveredNextToScalus() {
        assertThat(LedgerValidationEngines.discover(getClass().getClassLoader()).available())
                .contains("amaru", "scalus");
    }

    @Test
    void defaultsMapPoolSizeZeroToTheValidationThreadsPlusTheRebuildWorkerAndTheProducer() {
        AmaruEngineConfig config = AmaruEngineFactory.config(context(Map.of(), 3, new ScalusScriptPhaseEvaluator()));

        assertThat(config.phase2()).isEqualTo(Phase2Mode.SCALUS);
        assertThat(config.poolSize()).isEqualTo(3 + AmaruEngineFactory.EXTRA_CALLERS);
        assertThat(config.timeout()).isEqualTo(Duration.ofMillis(2000));
        assertThat(config.maxAbandoned()).isEqualTo(2);
        assertThat(config.maxMemoryPages()).isEqualTo(2048);
    }

    @Test
    void explicitSettingsAreMapped() {
        AmaruEngineConfig config = AmaruEngineFactory.config(context(Map.of(
                YanoPropertyKeys.Validation.AMARU_PHASE2, "amaru",
                YanoPropertyKeys.Validation.AMARU_POOL_SIZE, "5",
                YanoPropertyKeys.Validation.AMARU_TIMEOUT_MS, "750",
                YanoPropertyKeys.Validation.AMARU_MAX_ABANDONED, "4",
                YanoPropertyKeys.Validation.AMARU_MAX_MEMORY_PAGES, "1024"), 3, null));

        assertThat(config.phase2()).isEqualTo(Phase2Mode.FULL);
        assertThat(config.poolSize()).isEqualTo(5);
        assertThat(config.timeout()).isEqualTo(Duration.ofMillis(750));
        assertThat(config.maxAbandoned()).isEqualTo(4);
        assertThat(config.maxMemoryPages()).isEqualTo(1024);
    }

    @Test
    void invalidSettingsAreRejected() {
        assertThatThrownBy(() -> AmaruEngineFactory.config(context(Map.of(
                YanoPropertyKeys.Validation.AMARU_PHASE2, "julc"), 1, null)))
                .hasMessageContaining("must be scalus or amaru");
        assertThatThrownBy(() -> new AmaruEngineFactory().create(context(Map.of(), 1, null)))
                .hasMessageContaining("phase2=scalus needs the Scalus phase-2 evaluator");
    }

    @Test
    void publicNetworksGetAmarusEraHistory() {
        AmaruNetworkParameters mainnet = AmaruNetworks.from(network(764824073L, 432_000, 4_492_800, 21_600, 2160));
        assertThat(mainnet.stabilityWindow()).isEqualTo(129_600);
        assertThat(mainnet.global().epochLengthScaleFactor()).isEqualTo(10);
        assertThat(mainnet.global().activeSlotCoeffInverse()).isEqualTo(20);
        assertThat(mainnet.eras()).hasSize(7);
        assertThat(mainnet.eras().getFirst().end()).isEqualTo(new AmaruNetworkParameters.EraBound(89_856_000_000L,
                4_492_800, 208));
        AmaruNetworkParameters.EraSummary conway = mainnet.eras().getLast();
        assertThat(conway.eraTag()).isEqualTo(AmaruNetworkParameters.EraSummary.CONWAY);
        assertThat(conway.start()).isEqualTo(new AmaruNetworkParameters.EraBound(219_024_000_000L, 133_660_800, 507));
        assertThat(conway.end()).isNull();
        assertThat(mainnet.eras().get(5).start()).isEqualTo(
                new AmaruNetworkParameters.EraBound(157_680_000_000L, 72_316_800, 365));

        AmaruNetworkParameters preprod = AmaruNetworks.from(network(1L, 432_000, 86_400, 21_600, 2160));
        assertThat(preprod.eras().getLast().start()).isEqualTo(
                new AmaruNetworkParameters.EraBound(70_416_000_000L, 68_774_400, 163));
        assertThat(preprod.eras().get(1).start()).isEqualTo(new AmaruNetworkParameters.EraBound(1_728_000_000L,
                86_400, 4));

        AmaruNetworkParameters preview = AmaruNetworks.from(network(2L, 86_400, 0, 4_320, 432));
        assertThat(preview.eras().getFirst().epochSizeSlots()).isEqualTo(4_320);
        assertThat(preview.eras().get(5).start()).isEqualTo(new AmaruNetworkParameters.EraBound(259_200_000L,
                259_200, 3));
        assertThat(preview.eras().getLast().start()).isEqualTo(
                new AmaruNetworkParameters.EraBound(55_814_400_000L, 55_814_400, 646));
    }

    @Test
    void devnetsAreASingleConwayEra() {
        AmaruNetworkParameters devnet = AmaruNetworks.from(new NetworkParameters(42, NetworkId.TESTNET, 100, 0.05,
                MAX_SUPPLY, 129_600, 62, 1_700_000_000_000L, 0, 0, 0, 500, 1000));

        assertThat(devnet.networkMagic()).isEqualTo(42);
        assertThat(devnet.eras()).singleElement().satisfies(e -> {
            assertThat(e.eraTag()).isEqualTo(AmaruNetworkParameters.EraSummary.CONWAY);
            assertThat(e.epochSizeSlots()).isEqualTo(500);
            assertThat(e.end()).isNull();
        });
        assertThat(devnet.stabilityWindow()).isEqualTo(6_000);
        assertThat(devnet.global().epochLengthScaleFactor()).isEqualTo(1);
        assertThat(devnet.global().systemStartMs()).isEqualTo(1_700_000_000_000L);
    }

    private static NetworkParameters network(long magic, long epochLength, long firstNonByronSlot,
                                             long byronEpochLength, long k) {
        return new NetworkParameters(magic, magic == 764824073L ? NetworkId.MAINNET : NetworkId.TESTNET, k, 0.05,
                MAX_SUPPLY, 129_600, 62, 1_506_203_091_000L, byronEpochLength, 20_000, firstNonByronSlot,
                epochLength, 1000);
    }

    private static EngineContext context(Map<String, String> config, int threads, ScriptPhaseEvaluator evaluator) {
        return new EngineContext() {
            @Override
            public Optional<String> config(String key) {
                return Optional.ofNullable(config.get(key));
            }

            @Override
            public Supplier<NetworkParameters> network() {
                return () -> AmaruEngineFactoryTest.network(42, 500, 0, 0, 100);
            }

            @Override
            public EpochProtocolParamsSupplier protocolParams() {
                return slot -> null;
            }

            @Override
            public SlotConfigSupplier slotConfig() {
                return () -> null;
            }

            @Override
            public LongSupplier currentSlot() {
                return () -> 0;
            }

            @Override
            public ScriptPhaseEvaluator scriptPhaseEvaluator() {
                return evaluator;
            }

            @Override
            public int validationThreads() {
                return threads;
            }
        };
    }

}
