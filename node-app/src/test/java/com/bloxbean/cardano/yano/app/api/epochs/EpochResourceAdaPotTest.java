package com.bloxbean.cardano.yano.app.api.epochs;

import com.bloxbean.cardano.yano.api.NodeAPI;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.app.api.epochs.dto.AdaPotDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class EpochResourceAdaPotTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void getAdaPotShouldReturnStringEncodedLovelaceFields() {
        EpochResource resource = resourceWith(provider(true, Map.of(10, snapshot(10))));

        Response response = resource.getAdaPot(10);

        assertEquals(200, response.getStatus());
        AdaPotDto dto = (AdaPotDto) response.getEntity();
        assertEquals(10, dto.epoch());
        assertEquals("100", dto.treasury());
        assertEquals("200", dto.reserves());
        assertEquals("400", dto.fees());
        assertEquals("500", dto.distributedRewards());
        assertEquals("600", dto.undistributedRewards());
        assertEquals("700", dto.rewardsPot());
        assertEquals("800", dto.poolRewardsPot());
    }

    @Test
    void getAdaPotShouldReturn404ForMissingEpoch() {
        EpochResource resource = resourceWith(provider(true, Map.of()));

        Response response = resource.getAdaPot(10);

        assertEquals(404, response.getStatus());
    }

    @Test
    void adaPotEndpointsShouldReturn503WhenTrackingIsDisabled() {
        EpochResource resource = resourceWith(provider(false, Map.of(10, snapshot(10))));

        Response response = resource.getAdaPot(10);

        assertEquals(503, response.getStatus());
    }

    @Test
    void latestAdaPotShouldReturnLatestAvailableAtCurrentEpoch() {
        EpochResource resource = resourceWith(provider(true, Map.of(
                0, snapshot(0),
                2, snapshot(2)
        )));

        Response response = resource.getLatestAdaPot();

        assertEquals(200, response.getStatus());
        AdaPotDto dto = (AdaPotDto) response.getEntity();
        assertEquals(0, dto.epoch());
    }

    @Test
    void listAdaPotsShouldPageAndOrderByEpochWindow() {
        EpochResource resource = resourceWith(provider(true, Map.of(
                1, snapshot(1),
                2, snapshot(2),
                3, snapshot(3)
        )));

        Response response = resource.listAdaPots(1, 3, 1, 2, "desc");

        assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        List<AdaPotDto> dtos = (List<AdaPotDto>) response.getEntity();
        assertEquals(2, dtos.size());
        assertEquals(3, dtos.get(0).epoch());
        assertEquals(2, dtos.get(1).epoch());
    }

    @Test
    void listAdaPotsShouldRejectInvalidCount() {
        EpochResource resource = resourceWith(provider(true, Map.of()));

        Response response = resource.listAdaPots(0, 10, 1, 101, "asc");

        assertEquals(400, response.getStatus());
    }

    @Test
    void adaPotDtoShouldSerializeBlockfrostStyleNames() throws Exception {
        String json = MAPPER.writeValueAsString(AdaPotDto.from(snapshot(10)));

        assertTrue(json.contains("\"distributed_rewards\":\"500\""));
        assertTrue(json.contains("\"undistributed_rewards\":\"600\""));
        assertTrue(json.contains("\"rewards_pot\":\"700\""));
        assertTrue(json.contains("\"pool_rewards_pot\":\"800\""));
        assertFalse(json.contains("\"deposits\""));
    }

    private static EpochResource resourceWith(LedgerStateProvider ledgerStateProvider) {
        EpochResource resource = new EpochResource();
        resource.nodeAPI = nodeApiWith(ledgerStateProvider);
        return resource;
    }

    private static NodeAPI nodeApiWith(LedgerStateProvider ledgerStateProvider) {
        return (NodeAPI) Proxy.newProxyInstance(NodeAPI.class.getClassLoader(), new Class<?>[]{NodeAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getLedgerStateProvider" -> ledgerStateProvider;
                    case "toString" -> "TestNodeAPI";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static LedgerStateProvider provider(boolean enabled, Map<Integer, LedgerStateProvider.AdaPotSnapshot> snapshots) {
        TreeMap<Integer, LedgerStateProvider.AdaPotSnapshot> ordered = new TreeMap<>(snapshots);
        return (LedgerStateProvider) Proxy.newProxyInstance(LedgerStateProvider.class.getClassLoader(),
                new Class<?>[]{LedgerStateProvider.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isAdaPotTrackingEnabled" -> enabled;
                    case "getAdaPot" -> Optional.ofNullable(ordered.get((Integer) args[0]));
                    case "getLatestAdaPot" -> latestAtOrBefore(ordered, (Integer) args[0]);
                    case "toString" -> "TestLedgerStateProvider";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Optional<LedgerStateProvider.AdaPotSnapshot> latestAtOrBefore(
            TreeMap<Integer, LedgerStateProvider.AdaPotSnapshot> snapshots, int epoch) {
        Integer latestEpoch = snapshots.floorKey(epoch);
        if (latestEpoch == null) return Optional.empty();
        return Optional.of(snapshots.get(latestEpoch));
    }

    private static LedgerStateProvider.AdaPotSnapshot snapshot(int epoch) {
        return new LedgerStateProvider.AdaPotSnapshot(
                epoch,
                BigInteger.valueOf(100),
                BigInteger.valueOf(200),
                BigInteger.valueOf(300),
                BigInteger.valueOf(400),
                BigInteger.valueOf(500),
                BigInteger.valueOf(600),
                BigInteger.valueOf(700),
                BigInteger.valueOf(800)
        );
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
