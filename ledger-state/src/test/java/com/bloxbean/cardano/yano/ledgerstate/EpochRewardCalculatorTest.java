package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.api.era.EraProvider;
import com.bloxbean.cardano.yano.ledgerstate.test.TestRocksDBHelper;
import org.cardanofoundation.rewards.calculation.config.NetworkConfig;
import org.cardanofoundation.rewards.calculation.domain.ProtocolParameters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EpochRewardCalculatorTest {
    private static final int SNAPSHOT_EPOCH = 9;

    @TempDir
    Path tempDir;

    @Test
    void exposesNormalizedRewardModeForBoundaryTelemetry() {
        var calculator = new EpochRewardCalculator(null, null, null, true);

        assertThat(calculator.rewardMode()).isEqualTo("legacy");

        calculator.setRewardMode(" LEGACY ");

        assertThat(calculator.rewardMode()).isEqualTo("legacy");
    }

    @Test
    void legacyModeFailsClosedWhenStreamingProgressExists() throws Exception {
        var calculator = new EpochRewardCalculator(null, null, null, true);
        Field progress = EpochRewardCalculator.class.getDeclaredField("rewardResumeAfterPool");
        progress.setAccessible(true);
        progress.set(calculator, "42".repeat(28));

        assertThatThrownBy(() -> calculator.validateRewardResumePath(177, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must remain streaming");

        calculator.setRewardMode("streaming");
        assertThatThrownBy(() -> calculator.validateRewardResumePath(177, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pool-major snapshot");
    }

    @Test
    void rewardProtocolParamsNormalizeRemovedDecentralizationToZero() {
        EpochParamProvider provider = new EpochParamProvider() {
            @Override
            public BigInteger getKeyDeposit(long epoch) {
                return BigInteger.valueOf(2_000_000);
            }

            @Override
            public BigInteger getPoolDeposit(long epoch) {
                return BigInteger.valueOf(500_000_000);
            }

            @Override
            public BigDecimal getDecentralization(long epoch) {
                return null;
            }
        };

        var calculator = new EpochRewardCalculator(null, null, null, true);
        var params = calculator.buildProtocolParameters(provider, 40);

        assertThat(params.getDecentralisation()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void postVasilRewardRulesStartAfterBabbageTransitionEpoch() {
        var calculator = new EpochRewardCalculator(null, null, null, true);
        calculator.setEraProvider(firstBabbageEpoch(2));
        var networkConfig = NetworkConfig.builder()
                .vasilHardforkEpoch(999)
                .build();

        assertThat(calculator.usesPostVasilRewardRules(2, networkConfig)).isFalse();
        assertThat(calculator.usesPostVasilRewardRules(3, networkConfig)).isTrue();
    }

    @Test
    void postVasilRewardRulesIgnoreInferredEraStartEpoch() {
        var calculator = new EpochRewardCalculator(null, null, null, true);
        calculator.setEraProvider(new EraProvider() {
            @Override
            public Integer resolveFirstEpochOrNull(int eraValue) {
                return eraValue == Era.Babbage.getValue() ? 0 : null;
            }
        });
        var networkConfig = NetworkConfig.builder()
                .vasilHardforkEpoch(3)
                .build();

        assertThat(calculator.usesPostVasilRewardRules(2, networkConfig)).isFalse();
    }

    @Test
    void rewardNetworkConfigUsesKnownBabbageEpochForCfRules() {
        var calculator = new EpochRewardCalculator(null, null, null, true);
        calculator.setEraProvider(firstBabbageEpoch(2));
        var configured = NetworkConfig.builder()
                .networkMagic(4)
                .vasilHardforkEpoch(3)
                .build();

        var effective = calculator.resolveEffectiveRewardNetworkConfig(configured);

        assertThat(effective).isNotSameAs(configured);
        assertThat(effective.getNetworkMagic()).isEqualTo(4);
        assertThat(effective.getVasilHardforkEpoch()).isEqualTo(2);
        assertThat(configured.getVasilHardforkEpoch()).isEqualTo(3);
    }

    @Test
    void rewardNetworkConfigIgnoresInferredBabbageEpochForKnownNetworkCfRules() {
        var calculator = new EpochRewardCalculator(null, null, null, true);
        calculator.setEraProvider(new EraProvider() {
            @Override
            public Integer resolveFirstEpochOrNull(int eraValue) {
                return eraValue == Era.Babbage.getValue() ? 0 : null;
            }
        });
        var configured = NetworkConfig.builder()
                .networkMagic(2)
                .vasilHardforkEpoch(3)
                .build();

        assertThat(calculator.resolveEffectiveRewardNetworkConfig(configured)).isSameAs(configured);
    }

    @Test
    void rewardNetworkConfigUsesInferredBabbageEpochZeroForCustomNetworkCfRules() {
        var calculator = new EpochRewardCalculator(null, null, null, true);
        calculator.setEraProvider(new EraProvider() {
            @Override
            public Integer resolveFirstEpochOrNull(int eraValue) {
                return eraValue == Era.Babbage.getValue() ? 0 : null;
            }
        });
        var configured = NetworkConfig.builder()
                .networkMagic(42)
                .vasilHardforkEpoch(Integer.MAX_VALUE)
                .build();

        var effective = calculator.resolveEffectiveRewardNetworkConfig(configured);

        assertThat(effective).isNotSameAs(configured);
        assertThat(effective.getNetworkMagic()).isEqualTo(42);
        assertThat(effective.getVasilHardforkEpoch()).isZero();
    }

    @Test
    void rewardNetworkConfigTreatsSanchonetAsDerivedNetworkForCfRules() {
        var calculator = new EpochRewardCalculator(null, null, null, true);
        calculator.setEraProvider(new EraProvider() {
            @Override
            public Integer resolveFirstEpochOrNull(int eraValue) {
                return eraValue == Era.Babbage.getValue() ? 0 : null;
            }
        });
        var configured = NetworkConfig.builder()
                .networkMagic(4)
                .vasilHardforkEpoch(Integer.MAX_VALUE)
                .build();

        var effective = calculator.resolveEffectiveRewardNetworkConfig(configured);

        assertThat(effective).isNotSameAs(configured);
        assertThat(effective.getNetworkMagic()).isEqualTo(4);
        assertThat(effective.getVasilHardforkEpoch()).isZero();
    }

    @Test
    void postVasilRewardRulesIgnoreObsoleteDecentralizationAndUsePoolBlockCount() {
        var calculator = new EpochRewardCalculator(null, null, null, true);
        calculator.setEraProvider(firstBabbageEpoch(2));
        calculator.setLedgerStateProvider(new PoolParamsProvider(Set.of("pool1")));

        var protocolParams = ProtocolParameters.builder()
                .decentralisation(BigDecimal.ONE)
                .treasuryGrowRate(new BigDecimal("0.2"))
                .monetaryExpandRate(new BigDecimal("0.003"))
                .optimalPoolCount(150)
                .poolOwnerInfluence(new BigDecimal("0.3"))
                .build();
        var blockCounts = Map.of(
                "genesis-delegate", 4_319L,
                "pool1", 1L);
        var networkConfig = NetworkConfig.builder()
                .vasilHardforkEpoch(999)
                .build();

        var transitionEpoch = calculator.resolveRewardRuleContext(
                2, 0, protocolParams, blockCounts, 4_320L, networkConfig);
        assertThat(transitionEpoch.protocolParameters().getDecentralisation()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(transitionEpoch.blockCount()).isEqualTo(4_320L);
        assertThat(transitionEpoch.nonOBFTBlockCount()).isZero();

        var postVasil = calculator.resolveRewardRuleContext(
                3, 1, protocolParams, blockCounts, 4_320L, networkConfig);
        assertThat(postVasil.protocolParameters().getDecentralisation()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(postVasil.blockCount()).isEqualTo(1L);
        assertThat(postVasil.nonOBFTBlockCount()).isEqualTo(1L);
    }

    @Test
    void sequentialRewardFlagsMatchLegacyCutoffAndRegistrationSemantics() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var store = new DefaultAccountStateStore(rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(getClass()), true);
            var calculator = new EpochRewardCalculator(
                    rocks.db(), rocks.cfState(), rocks.cfSnapshot(), true);
            calculator.setBatchLimits(2, 256);

            List<CredentialFixture> fixtures = List.of(
                    new CredentialFixture(0, "11".repeat(28), "a1".repeat(28), false,
                            List.of(event(10, 0, 0, true), event(30, 0, 0, false))),
                    new CredentialFixture(0, "22".repeat(28), "a2".repeat(28), false,
                            List.of(event(10, 0, 0, true), event(20, 0, 0, false))),
                    new CredentialFixture(0, "33".repeat(28), "a3".repeat(28), false,
                            List.of()),
                    new CredentialFixture(0, "44".repeat(28), "a4".repeat(28), true,
                            List.of()),
                    new CredentialFixture(0, "55".repeat(28), "a5".repeat(28), false,
                            List.of(event(10, 0, 0, true))),
                    new CredentialFixture(0, "66".repeat(28), "a6".repeat(28), false,
                            List.of(event(20, 0, 0, true), event(20, 1, 0, false))),
                    new CredentialFixture(1, "11".repeat(28), "a7".repeat(28), true,
                            List.of(event(10, 0, 0, true))));
            for (CredentialFixture fixture : fixtures) {
                putCredentialSnapshot(rocks, fixture);
                if (fixture.registeredNow()) {
                    rocks.db().put(rocks.cfState(),
                            DefaultAccountStateStore.accountKey(
                                    fixture.credentialType(), fixture.credentialHash()),
                            AccountStateCborCodec.encodeStakeAccount(
                                    BigInteger.ZERO, BigInteger.valueOf(2_000_000)));
                }
                for (StakeEvent event : fixture.events()) {
                    rocks.db().put(rocks.cfState(),
                            DefaultAccountStateStore.credentialStakeEventKey(
                                    fixture.credentialType(), fixture.credentialHash(), event.slot(),
                                    event.txIndex(), event.certIndex()),
                            AccountStateCborCodec.encodeStakeEvent(event.registration()
                                    ? AccountStateCborCodec.EVENT_REGISTRATION
                                    : AccountStateCborCodec.EVENT_DEREGISTRATION));
                }
            }

            try (var prepared = calculator.prepareRewardCredentialFlags(
                    SNAPSHOT_EPOCH, 25, 35, 15, 35)) {
                assertThat(prepared.rows()).isEqualTo(fixtures.size());
                for (CredentialFixture fixture : fixtures) {
                    var summary = store.getCredentialEventSummary(
                            fixture.credentialType() + ":" + fixture.credentialHash(),
                            25, 35, 15, 35);
                    int expected = legacyFlags(summary, fixture.registeredNow());
                    byte[] actual = rocks.db().get(rocks.cfSnapshot(),
                            EpochRewardCalculator.rewardFlagsKey(
                                    SNAPSHOT_EPOCH,
                                    HexUtil.decodeHexString(fixture.poolHash()),
                                    credentialSuffix(
                                            fixture.credentialType(), fixture.credentialHash())));
                    assertThat(actual)
                            .as("flags for %s", fixture.credentialHash())
                            .containsExactly((byte) expected);
                }
            }

            byte[] prefix = EpochRewardCalculator.rewardFlagsPrefix(SNAPSHOT_EPOCH);
            try (var iterator = rocks.db().newIterator(rocks.cfSnapshot())) {
                iterator.seek(prefix);
                assertThat(iterator.isValid() && startsWith(iterator.key(), prefix)).isFalse();
            }
        }
    }

    private static int legacyFlags(
            DefaultAccountStateStore.CredentialEventSummary summary,
            boolean registeredNow) {
        boolean atStability = summary.deregisteredAtStability();
        boolean atBoundary = summary.deregisteredAtBoundary();
        if (!registeredNow && !atStability && !atBoundary) {
            atStability = true;
            atBoundary = true;
        }
        int flags = 0;
        if (atStability) flags |= EpochRewardCalculator.REWARD_FLAG_DEREGISTERED_AT_STABILITY;
        if (atBoundary) flags |= EpochRewardCalculator.REWARD_FLAG_DEREGISTERED_AT_BOUNDARY;
        if (summary.registeredSince()) flags |= EpochRewardCalculator.REWARD_FLAG_REGISTERED_SINCE;
        if (summary.registeredUntil()) flags |= EpochRewardCalculator.REWARD_FLAG_REGISTERED_UNTIL;
        if (registeredNow) flags |= EpochRewardCalculator.REWARD_FLAG_REGISTERED_NOW;
        return flags;
    }

    private static void putCredentialSnapshot(
            TestRocksDBHelper rocks, CredentialFixture fixture) throws Exception {
        byte[] key = ByteBuffer.allocate(33).order(ByteOrder.BIG_ENDIAN)
                .putInt(SNAPSHOT_EPOCH).put((byte) fixture.credentialType())
                .put(HexUtil.decodeHexString(fixture.credentialHash())).array();
        rocks.db().put(rocks.cfSnapshot(), key,
                AccountStateCborCodec.encodeEpochDelegSnapshot(
                        fixture.poolHash(), BigInteger.valueOf(1_000)));
    }

    private static byte[] credentialSuffix(int credentialType, String credentialHash) {
        return ByteBuffer.allocate(29).put((byte) credentialType)
                .put(HexUtil.decodeHexString(credentialHash)).array();
    }

    private static StakeEvent event(
            long slot, int txIndex, int certIndex, boolean registration) {
        return new StakeEvent(slot, txIndex, certIndex, registration);
    }

    private static boolean startsWith(byte[] key, byte[] prefix) {
        if (key.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (key[index] != prefix[index]) return false;
        }
        return true;
    }

    private record CredentialFixture(
            int credentialType, String credentialHash, String poolHash, boolean registeredNow,
            List<StakeEvent> events) {
    }

    private record StakeEvent(
            long slot, int txIndex, int certIndex, boolean registration) {
    }

    private static EraProvider firstBabbageEpoch(int epoch) {
        return new EraProvider() {
            @Override
            public Integer resolveFirstEpochOrNull(int eraValue) {
                return eraValue == Era.Babbage.getValue() ? epoch : null;
            }

            @Override
            public Integer resolveKnownFirstEpochOrNull(int eraValue) {
                return eraValue == Era.Babbage.getValue() ? epoch : null;
            }
        };
    }

    private static final class PoolParamsProvider implements LedgerStateProvider {
        private final Set<String> registeredPools;

        private PoolParamsProvider(Set<String> registeredPools) {
            this.registeredPools = registeredPools;
        }

        @Override
        public Optional<BigInteger> getRewardBalance(int credType, String credentialHash) {
            return Optional.empty();
        }

        @Override
        public Optional<BigInteger> getStakeDeposit(int credType, String credentialHash) {
            return Optional.empty();
        }

        @Override
        public Optional<String> getDelegatedPool(int credType, String credentialHash) {
            return Optional.empty();
        }

        @Override
        public Optional<DRepDelegation> getDRepDelegation(int credType, String credentialHash) {
            return Optional.empty();
        }

        @Override
        public boolean isStakeCredentialRegistered(int credType, String credentialHash) {
            return false;
        }

        @Override
        public BigInteger getTotalDeposited() {
            return BigInteger.ZERO;
        }

        @Override
        public boolean isPoolRegistered(String poolHash) {
            return registeredPools.contains(poolHash);
        }

        @Override
        public Optional<BigInteger> getPoolDeposit(String poolHash) {
            return Optional.empty();
        }

        @Override
        public Optional<Long> getPoolRetirementEpoch(String poolHash) {
            return Optional.empty();
        }

        @Override
        public Optional<PoolParams> getPoolParams(String poolHash, int epoch) {
            if (!registeredPools.contains(poolHash)) {
                return Optional.empty();
            }
            return Optional.of(new PoolParams(
                    BigInteger.valueOf(500_000_000),
                    0.0,
                    BigInteger.ZERO,
                    BigInteger.ZERO,
                    "e0" + "00".repeat(28),
                    Set.of()));
        }
    }
}
