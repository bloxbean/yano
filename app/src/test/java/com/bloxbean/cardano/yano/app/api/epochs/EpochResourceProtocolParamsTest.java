package com.bloxbean.cardano.yano.app.api.epochs;

import com.bloxbean.cardano.yano.app.test.TestNodeRoles;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.api.model.ProtocolParamsSnapshot;
import com.bloxbean.cardano.yano.app.api.epochs.dto.ProtocolParamsDto;
import com.bloxbean.cardano.yano.runtime.blockproducer.ProtocolParamsMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class EpochResourceProtocolParamsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String STATIC_BLOCKFROST_PROTOCOL_PARAMS = """
            {
              "min_fee_a": 999,
              "protocol_major_ver": 11,
              "key_deposit": "2000000",
              "coins_per_utxo_size": "4310",
              "pvt_p_p_security_group": 0.51,
              "dvt_p_p_gov_group": 0.75,
              "nonce": "static-nonce"
            }
            """;

    @Test
    void parametersShouldReturnBlockfrostMappedLedgerSnapshot() throws Exception {
        EpochResource resource = resourceWith(provider(snapshot(42)), "abc123");

        Response response = resource.getParametersByEpoch(42);

        assertEquals(200, response.getStatus());
        ProtocolParamsDto dto = (ProtocolParamsDto) response.getEntity();
        assertEquals(42, dto.getEpoch());
        assertEquals(44, dto.getMinFeeA());
        assertEquals(155381, dto.getMinFeeB());
        assertEquals("2000000", dto.getKeyDeposit());
        assertEquals("500000000", dto.getPoolDeposit());
        assertEquals(10, dto.getProtocolMajorVer());
        assertEquals(2, dto.getProtocolMinorVer());
        assertEquals(new BigDecimal("0.51"), dto.getPvtppSecurityGroup());
        assertEquals(BigDecimal.ZERO, dto.getDecentralisationParam());
        assertEquals("4310", dto.getMinUtxo());
        assertEquals("4310", dto.getCoinsPerUtxoSize());
        assertEquals("4310", dto.getCoinsPerUtxoWord());
        assertEquals("abc123", dto.getNonce());

        String json = MAPPER.writeValueAsString(dto);
        assertTrue(json.contains("\"min_fee_a\":44"));
        assertTrue(json.contains("\"key_deposit\":\"2000000\""));
        assertTrue(json.contains("\"protocol_major_ver\":10"));
        assertTrue(json.contains("\"e_max\":18"));
        assertTrue(json.contains("\"n_opt\":500"));
        assertFalse(json.contains("\"emax\""));
        assertFalse(json.contains("\"nopt\""));
        assertTrue(json.contains("\"pvt_p_p_security_group\":0.51"));
        assertTrue(json.contains("\"pvtpp_security_group\":0.51"));
    }

    @Test
    void parametersShouldReturnStaticParamsWhenLedgerStateProviderIsUnavailable() throws Exception {
        EpochResource resource = new EpochResource();
        wire(resource, nodeRolesWith(null));

        Response response = resource.getParametersByEpoch(42);

        assertEquals(200, response.getStatus());
        ProtocolParamsDto dto = (ProtocolParamsDto) response.getEntity();
        assertEquals(42, dto.getEpoch());
        assertEquals(999, dto.getMinFeeA());
        assertEquals(11, dto.getProtocolMajorVer());
        assertEquals("2000000", dto.getKeyDeposit());
        assertEquals("4310", dto.getCoinsPerUtxoSize());
        assertEquals(new BigDecimal("0.51"), dto.getPvtppSecurityGroup());
        assertEquals(new BigDecimal("0.75"), dto.getDvtPPGovGroup());
        assertEquals("static-nonce", dto.getNonce());

        String json = MAPPER.writeValueAsString(dto);
        assertTrue(json.contains("\"min_fee_a\":999"));
        assertTrue(json.contains("\"protocol_major_ver\":11"));
    }

    @Test
    void staticParametersShouldReturnIndependentDtoForEachRequestedEpoch() {
        EpochResource resource = new EpochResource();
        wire(resource, nodeRolesWith(null));

        ProtocolParamsDto epoch42 = (ProtocolParamsDto) resource.getParametersByEpoch(42).getEntity();
        ProtocolParamsDto epoch43 = (ProtocolParamsDto) resource.getParametersByEpoch(43).getEntity();

        assertNotSame(epoch42, epoch43);
        assertEquals(42, epoch42.getEpoch());
        assertEquals(43, epoch43.getEpoch());
        assertEquals(999, epoch43.getMinFeeA());
    }

    @Test
    void parametersShouldReturn404WhenProtocolParamsAreUnavailable() {
        EpochResource resource = new EpochResource();
        wire(resource, nodeRolesWith(null, null, null));

        Response response = resource.getParametersByEpoch(42);

        assertEquals(404, response.getStatus());
    }

    @Test
    void parametersShouldReturn404WhenEpochParamsAreUnavailable() {
        EpochResource resource = resourceWith(provider(Optional.empty()));

        Response response = resource.getParametersByEpoch(42);

        assertEquals(404, response.getStatus());
    }

    @Test
    void parametersShouldReturn503WhenLedgerStateReadFails() {
        EpochResource resource = resourceWith(failingProvider());

        Response response = resource.getParametersByEpoch(42);

        assertEquals(503, response.getStatus());
        assertEquals("Protocol parameter state read failed", ((Map<?, ?>) response.getEntity()).get("error"));
    }

    private static EpochResource resourceWith(LedgerStateProvider ledgerStateProvider) {
        return resourceWith(ledgerStateProvider, null);
    }

    private static EpochResource resourceWith(LedgerStateProvider ledgerStateProvider, String nonce) {
        EpochResource resource = new EpochResource();
        wire(resource, nodeRolesWith(ledgerStateProvider, nonce));
        return resource;
    }

    private static void wire(EpochResource resource, TestNodeRoles nodeRoles) {
        resource.nodeLifecycle = nodeRoles;
        resource.chainQuery = nodeRoles;
        resource.ledgerQuery = nodeRoles;
    }

    private static TestNodeRoles nodeRolesWith(LedgerStateProvider ledgerStateProvider) {
        return nodeRolesWith(ledgerStateProvider, null);
    }

    private static TestNodeRoles nodeRolesWith(LedgerStateProvider ledgerStateProvider, String nonce) {
        return nodeRolesWith(ledgerStateProvider, nonce, STATIC_BLOCKFROST_PROTOCOL_PARAMS);
    }

    private static TestNodeRoles nodeRolesWith(LedgerStateProvider ledgerStateProvider, String nonce, String protocolParams) {
        return (TestNodeRoles) Proxy.newProxyInstance(TestNodeRoles.class.getClassLoader(), new Class<?>[]{TestNodeRoles.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getLedgerStateProvider" -> ledgerStateProvider;
                    case "getEpochNonce" -> nonce;
                    case "getProtocolParameters" -> {
                        if (method.getParameterCount() == 1) {
                            int epoch = (Integer) args[0];
                            if (ledgerStateProvider != null) {
                                yield ledgerStateProvider.getProtocolParameters(epoch);
                            }
                            yield protocolParams != null
                                    ? Optional.of(staticSnapshot(protocolParams, epoch))
                                    : Optional.empty();
                        }
                        yield protocolParams;
                    }
                    case "toString" -> "TestNodeRoles";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static ProtocolParamsSnapshot staticSnapshot(String protocolParams, int epoch) {
        try {
            return ProtocolParamsMapper.fromNodeProtocolParamSnapshot(protocolParams, epoch);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static LedgerStateProvider provider(ProtocolParamsSnapshot snapshot) {
        return provider(Optional.of(snapshot));
    }

    private static LedgerStateProvider provider(Optional<ProtocolParamsSnapshot> snapshot) {
        return (LedgerStateProvider) Proxy.newProxyInstance(LedgerStateProvider.class.getClassLoader(),
                new Class<?>[]{LedgerStateProvider.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getProtocolParameters" -> snapshot;
                    case "toString" -> "TestLedgerStateProvider";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static LedgerStateProvider failingProvider() {
        return (LedgerStateProvider) Proxy.newProxyInstance(LedgerStateProvider.class.getClassLoader(),
                new Class<?>[]{LedgerStateProvider.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getProtocolParameters" -> throw new IllegalStateException("getProtocolParameters failed");
                    case "toString" -> "FailingLedgerStateProvider";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static ProtocolParamsSnapshot snapshot(int epoch) {
        return new ProtocolParamsSnapshot(
                epoch,
                44,
                155381,
                90112,
                16384,
                1100,
                BigInteger.valueOf(2_000_000),
                BigInteger.valueOf(500_000_000),
                18,
                500,
                new BigDecimal("0.3"),
                new BigDecimal("0.003"),
                new BigDecimal("0.2"),
                null,
                null,
                10,
                2,
                null,
                BigInteger.valueOf(170_000_000),
                null,
                Map.of("PlutusV1", linkedMap("addInteger-cpu-arguments-intercept", 197209L)),
                Map.of("PlutusV1", List.of(197209L, 0L)),
                new BigDecimal("0.0577"),
                new BigDecimal("0.0000721"),
                BigInteger.valueOf(16_500_000),
                new BigInteger("10000000000"),
                BigInteger.valueOf(72_000_000),
                new BigInteger("20000000000"),
                BigInteger.valueOf(5000),
                150,
                3,
                BigInteger.valueOf(4310),
                null,
                new BigDecimal("0.51"),
                new BigDecimal("0.51"),
                new BigDecimal("0.51"),
                new BigDecimal("0.51"),
                new BigDecimal("0.51"),
                new BigDecimal("0.67"),
                new BigDecimal("0.67"),
                new BigDecimal("0.60"),
                new BigDecimal("0.75"),
                new BigDecimal("0.60"),
                new BigDecimal("0.67"),
                new BigDecimal("0.67"),
                new BigDecimal("0.67"),
                new BigDecimal("0.75"),
                new BigDecimal("0.67"),
                3,
                146,
                6,
                new BigInteger("100000000000"),
                BigInteger.valueOf(500_000_000),
                20,
                new BigDecimal("15")
        );
    }

    private static LinkedHashMap<String, Long> linkedMap(String key, Long value) {
        LinkedHashMap<String, Long> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            if (Optional.class.equals(returnType)) return Optional.empty();
            return null;
        }
        if (Boolean.TYPE.equals(returnType)) return false;
        if (Integer.TYPE.equals(returnType)) return 0;
        if (Long.TYPE.equals(returnType)) return 0L;
        if (Double.TYPE.equals(returnType)) return 0D;
        if (Float.TYPE.equals(returnType)) return 0F;
        if (Short.TYPE.equals(returnType)) return (short) 0;
        if (Byte.TYPE.equals(returnType)) return (byte) 0;
        if (Character.TYPE.equals(returnType)) return (char) 0;
        return null;
    }
}
