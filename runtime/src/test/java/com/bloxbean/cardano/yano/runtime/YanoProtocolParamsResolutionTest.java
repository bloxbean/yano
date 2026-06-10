package com.bloxbean.cardano.yano.runtime;

import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.api.model.ProtocolParamsSnapshot;
import com.bloxbean.cardano.yano.runtime.blockproducer.ProtocolParamsMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class YanoProtocolParamsResolutionTest {

    private static final String LEDGER_PROTOCOL_PARAMS = """
            {
              "min_fee_a": 44,
              "protocol_major_ver": 10,
              "protocol_minor_ver": 2,
              "key_deposit": "2000000"
            }
            """;

    private static final String STATIC_PROTOCOL_PARAMS = """
            {
              "min_fee_a": 999,
              "protocol_major_ver": 11,
              "key_deposit": "3000000",
              "nonce": "static-nonce"
            }
            """;

    @Test
    void ledgerProtocolParamsWinWhenAvailable() {
        TestYano yano = new TestYano(
                provider(epoch -> Optional.of(snapshot(LEDGER_PROTOCOL_PARAMS, epoch))),
                STATIC_PROTOCOL_PARAMS);

        Optional<ProtocolParamsSnapshot> params = yano.getProtocolParameters(42);

        assertThat(params).isPresent();
        assertThat(params.orElseThrow().epoch()).isEqualTo(42);
        assertThat(params.orElseThrow().minFeeA()).isEqualTo(44);
        assertThat(params.orElseThrow().protocolMajorVer()).isEqualTo(10);
        assertThat(params.orElseThrow().keyDeposit()).isEqualTo(BigInteger.valueOf(2_000_000));
    }

    @Test
    void staticProtocolParamsAreUsedWhenLedgerProviderIsUnavailable() {
        TestYano yano = new TestYano(null, STATIC_PROTOCOL_PARAMS);

        Optional<ProtocolParamsSnapshot> params = yano.getProtocolParameters(43);

        assertThat(params).isPresent();
        assertThat(params.orElseThrow().epoch()).isEqualTo(43);
        assertThat(params.orElseThrow().minFeeA()).isEqualTo(999);
        assertThat(params.orElseThrow().protocolMajorVer()).isEqualTo(11);
        assertThat(params.orElseThrow().nonce()).isEqualTo("static-nonce");
    }

    @Test
    void staticProtocolParamsAreUsedWhenLedgerProviderHasNoSnapshot() {
        TestYano yano = new TestYano(
                provider(epoch -> Optional.empty()),
                STATIC_PROTOCOL_PARAMS);

        Optional<ProtocolParamsSnapshot> params = yano.getProtocolParameters(44);

        assertThat(params).isPresent();
        assertThat(params.orElseThrow().epoch()).isEqualTo(44);
        assertThat(params.orElseThrow().minFeeA()).isEqualTo(999);
    }

    @Test
    void staticProtocolParamsCacheReturnsIndependentEpochSnapshots() {
        TestYano yano = new TestYano(null, STATIC_PROTOCOL_PARAMS);

        ProtocolParamsSnapshot epoch44 = yano.getProtocolParameters(44).orElseThrow();
        ProtocolParamsSnapshot epoch45 = yano.getProtocolParameters(45).orElseThrow();

        assertThat(epoch44).isNotSameAs(epoch45);
        assertThat(epoch44.epoch()).isEqualTo(44);
        assertThat(epoch45.epoch()).isEqualTo(45);
        assertThat(epoch45.minFeeA()).isEqualTo(999);
    }

    @Test
    void emptyWhenNoLedgerOrStaticProtocolParamsExist() {
        TestYano yano = new TestYano(provider(epoch -> Optional.empty()), null);

        assertThat(yano.getProtocolParameters(42)).isEmpty();
    }

    @Test
    void negativeEpochIsRejectedBeforeStaticFallback() {
        TestYano yano = new TestYano(null, STATIC_PROTOCOL_PARAMS);

        assertThat(yano.getProtocolParameters(-1)).isEmpty();
    }

    private static ProtocolParamsSnapshot snapshot(String json, int epoch) {
        try {
            return ProtocolParamsMapper.fromNodeProtocolParamSnapshot(json, epoch);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static LedgerStateProvider provider(Function<Integer, Optional<ProtocolParamsSnapshot>> snapshots) {
        return (LedgerStateProvider) Proxy.newProxyInstance(
                LedgerStateProvider.class.getClassLoader(),
                new Class<?>[]{LedgerStateProvider.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getProtocolParameters" -> snapshots.apply((Integer) args[0]);
                    case "getRewardBalance", "getStakeDeposit", "getDelegatedPool", "getDRepDelegation",
                         "getPoolDeposit", "getPoolRetirementEpoch" -> Optional.empty();
                    case "getTotalDeposited" -> BigInteger.ZERO;
                    case "isStakeCredentialRegistered", "isPoolRegistered" -> false;
                    case "toString" -> "TestLedgerStateProvider";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }

    private static YanoConfig testConfig() {
        return YanoConfig.builder()
                .enableClient(false)
                .enableServer(false)
                .enableBlockProducer(false)
                .useRocksDB(false)
                .build();
    }

    private static class TestYano extends Yano {
        private final LedgerStateProvider ledgerStateProvider;
        private final String protocolParams;

        private TestYano(LedgerStateProvider ledgerStateProvider, String protocolParams) {
            super(testConfig());
            this.ledgerStateProvider = ledgerStateProvider;
            this.protocolParams = protocolParams;
        }

        @Override
        public LedgerStateProvider getLedgerStateProvider() {
            return ledgerStateProvider;
        }

        @Override
        public String getProtocolParameters() {
            return protocolParams;
        }
    }
}
