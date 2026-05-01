package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.era.EraProvider;
import com.bloxbean.cardano.yano.api.events.GenesisBlockEvent;
import com.bloxbean.cardano.yaci.core.model.DrepVoteThresholds;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.PoolVotingThresholds;
import com.bloxbean.cardano.yaci.core.types.NonNegativeInterval;
import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import com.bloxbean.cardano.yano.ledgerstate.test.TestRocksDBHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAccountStateStoreProtocolParamsTest {

    @TempDir
    Path tempDir;

    @Test
    void protocolParametersReturnEmptyWhenTrackerHasNoFinalizedSnapshot() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            EpochParamProvider provider = provider();
            var store = new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(DefaultAccountStateStoreProtocolParamsTest.class),
                    true, provider);
            store.setParamTracker(new EpochParamTracker(provider, true, rocks.db(),
                    rocks.cf(AccountStateCfNames.EPOCH_PARAMS)));

            assertThat(store.getProtocolParameters(42)).isEmpty();
        }
    }

    @Test
    void protocolParametersReturnFinalizedTrackerSnapshotWhenAvailable() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            EpochParamProvider provider = provider();
            var tracker = new EpochParamTracker(provider, true, rocks.db(),
                    rocks.cf(AccountStateCfNames.EPOCH_PARAMS));
            var store = new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(DefaultAccountStateStoreProtocolParamsTest.class),
                    true, provider);
            store.setParamTracker(tracker);

            tracker.finalizeEpoch(42);

            assertThat(store.getProtocolParameters(42))
                    .isPresent()
                    .get()
                    .extracting(snapshot -> snapshot.protocolMajorVer())
                    .isEqualTo(5);
        }
    }

    @Test
    void genesisBlockBootstrapsEpoch0ProtocolParameters() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            EpochParamProvider provider = provider();
            var tracker = new EpochParamTracker(provider, true, rocks.db(),
                    rocks.cf(AccountStateCfNames.EPOCH_PARAMS));
            var store = new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(DefaultAccountStateStoreProtocolParamsTest.class),
                    true, provider);
            store.setParamTracker(tracker);

            assertThat(store.getProtocolParameters(0)).isEmpty();

            store.handleGenesisBlock(new GenesisBlockEvent(Era.Conway, 0, 0, 0, "00"));

            assertThat(store.getProtocolParameters(0))
                    .isPresent()
                    .get()
                    .extracting(snapshot -> snapshot.protocolMajorVer())
                    .isEqualTo(5);
        }
    }

    @Test
    void protocolParametersDoNotFallbackToFutureEraGenesisFields() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            EpochParamProvider provider = providerWithFutureEraFields();
            var tracker = new EpochParamTracker(provider, true, rocks.db(),
                    rocks.cf(AccountStateCfNames.EPOCH_PARAMS));
            tracker.setEraProvider(preAlonzoEraProvider());
            var store = new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(DefaultAccountStateStoreProtocolParamsTest.class),
                    true, provider);
            store.setParamTracker(tracker);

            tracker.finalizeEpoch(42);

            var params = store.getProtocolParameters(42).orElseThrow();
            assertThat(params.costModels()).isNull();
            assertThat(params.costModelsRaw()).isNull();
            assertThat(params.priceMem()).isNull();
            assertThat(params.priceStep()).isNull();
            assertThat(params.maxTxExMem()).isNull();
            assertThat(params.maxTxExSteps()).isNull();
            assertThat(params.maxBlockExMem()).isNull();
            assertThat(params.maxBlockExSteps()).isNull();
            assertThat(params.maxValSize()).isNull();
            assertThat(params.collateralPercent()).isNull();
            assertThat(params.maxCollateralInputs()).isNull();
            assertThat(params.coinsPerUtxoWord()).isNull();
            assertThat(params.pvtMotionNoConfidence()).isNull();
            assertThat(params.dvtMotionNoConfidence()).isNull();
            assertThat(params.committeeMinSize()).isNull();
            assertThat(params.committeeMaxTermLength()).isNull();
            assertThat(params.govActionLifetime()).isNull();
            assertThat(params.govActionDeposit()).isNull();
            assertThat(params.drepDeposit()).isNull();
            assertThat(params.drepActivity()).isNull();
            assertThat(params.minFeeRefScriptCostPerByte()).isNull();
        }
    }

    @Test
    void protocolParametersUseProviderFallbackWhenTrackerIsDisabled() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            EpochParamProvider provider = provider();
            var store = new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(DefaultAccountStateStoreProtocolParamsTest.class),
                    true, provider);

            assertThat(store.getProtocolParameters(42))
                    .isPresent()
                    .get()
                    .extracting(snapshot -> snapshot.protocolMajorVer())
                    .isEqualTo(5);
        }
    }

    private static EpochParamProvider provider() {
        return new EpochParamProvider() {
            @Override
            public BigInteger getKeyDeposit(long epoch) {
                return BigInteger.valueOf(2_000_000);
            }

            @Override
            public BigInteger getPoolDeposit(long epoch) {
                return BigInteger.valueOf(500_000_000);
            }

            @Override
            public int getProtocolMajor(long epoch) {
                return 5;
            }
        };
    }

    private static EpochParamProvider providerWithFutureEraFields() {
        return new EpochParamProvider() {
            @Override
            public BigInteger getKeyDeposit(long epoch) {
                return BigInteger.valueOf(2_000_000);
            }

            @Override
            public BigInteger getPoolDeposit(long epoch) {
                return BigInteger.valueOf(500_000_000);
            }

            @Override
            public int getProtocolMajor(long epoch) {
                return 3;
            }

            @Override
            public Map<String, Object> getAlonzoCostModels(long epoch) {
                return Map.of("PlutusV1", java.util.List.of(1L, 2L));
            }

            @Override
            public BigDecimal getPriceMem(long epoch) {
                return new BigDecimal("0.0577");
            }

            @Override
            public NonNegativeInterval getPriceMemInterval(long epoch) {
                return new NonNegativeInterval(BigInteger.valueOf(577), BigInteger.valueOf(10_000));
            }

            @Override
            public BigDecimal getPriceStep(long epoch) {
                return new BigDecimal("0.0000721");
            }

            @Override
            public NonNegativeInterval getPriceStepInterval(long epoch) {
                return new NonNegativeInterval(BigInteger.valueOf(721), BigInteger.valueOf(10_000_000));
            }

            @Override public BigInteger getMaxTxExMem(long epoch) { return BigInteger.valueOf(10_000_000); }
            @Override public BigInteger getMaxTxExSteps(long epoch) { return new BigInteger("10000000000"); }
            @Override public BigInteger getMaxBlockExMem(long epoch) { return BigInteger.valueOf(50_000_000); }
            @Override public BigInteger getMaxBlockExSteps(long epoch) { return new BigInteger("40000000000"); }
            @Override public BigInteger getMaxValSize(long epoch) { return BigInteger.valueOf(5000); }
            @Override public Integer getCollateralPercent(long epoch) { return 150; }
            @Override public Integer getMaxCollateralInputs(long epoch) { return 3; }
            @Override public BigInteger getCoinsPerUtxoWord(long epoch) { return BigInteger.valueOf(34_482); }

            @Override
            public PoolVotingThresholds getPoolVotingThresholds(long epoch) {
                return PoolVotingThresholds.builder()
                        .pvtMotionNoConfidence(new UnitInterval(BigInteger.ONE, BigInteger.valueOf(2)))
                        .build();
            }

            @Override
            public DrepVoteThresholds getDrepVotingThresholds(long epoch) {
                return DrepVoteThresholds.builder()
                        .dvtMotionNoConfidence(new UnitInterval(BigInteger.ONE, BigInteger.valueOf(2)))
                        .build();
            }

            @Override public int getCommitteeMinSize(long epoch) { return 7; }
            @Override public int getCommitteeMaxTermLength(long epoch) { return 146; }
            @Override public int getGovActionLifetime(long epoch) { return 6; }
            @Override public BigInteger getGovActionDeposit(long epoch) { return new BigInteger("100000000000"); }
            @Override public BigInteger getDRepDeposit(long epoch) { return BigInteger.valueOf(500_000_000); }
            @Override public int getDRepActivity(long epoch) { return 20; }

            @Override
            public BigDecimal getMinFeeRefScriptCostPerByte(long epoch) {
                return BigDecimal.valueOf(15);
            }

            @Override
            public NonNegativeInterval getMinFeeRefScriptCostPerByteInterval(long epoch) {
                return new NonNegativeInterval(BigInteger.valueOf(15), BigInteger.ONE);
            }
        };
    }

    private static EraProvider preAlonzoEraProvider() {
        return new EraProvider() {
            @Override
            public Integer resolveFirstEpochOrNull(int eraValue) {
                return switch (eraValue) {
                    case 5 -> 100;
                    case 6 -> 200;
                    case 7 -> 300;
                    default -> 0;
                };
            }
        };
    }
}
