package com.bloxbean.cardano.yano.app.api.network;

import com.bloxbean.cardano.yano.api.NodeAPI;
import com.bloxbean.cardano.yano.api.account.AccountStateReadStore;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.api.model.GenesisParameters;
import com.bloxbean.cardano.yano.app.api.network.dto.NetworkDto;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NetworkResourceTest {

    @Test
    void networkReturnsSupportedSupplyAndStakeFields() {
        NetworkResource resource = resourceWith(provider(true, true));

        Response response = resource.getNetwork();

        assertEquals(200, response.getStatus());
        NetworkDto dto = (NetworkDto) response.getEntity();
        assertEquals("45000000000000000", dto.supply().max());
        assertEquals("44999999999999800", dto.supply().total());
        assertEquals("100", dto.supply().treasury());
        assertEquals("200", dto.supply().reserves());
        assertEquals("12345", dto.stake().active());
    }

    @Test
    void networkOmitsStakeWhenSnapshotUnavailable() {
        NetworkResource resource = resourceWith(provider(true, false));

        Response response = resource.getNetwork();

        assertEquals(200, response.getStatus());
        NetworkDto dto = (NetworkDto) response.getEntity();
        assertEquals("44999999999999800", dto.supply().total());
        assertEquals(null, dto.stake());
    }

    @Test
    void networkReturns503WhenAdaPotTrackingUnavailable() {
        NetworkResource resource = resourceWith(provider(false, true));

        Response response = resource.getNetwork();

        assertEquals(503, response.getStatus());
    }

    private static NetworkResource resourceWith(LedgerStateProvider ledgerStateProvider) {
        NetworkResource resource = new NetworkResource();
        resource.nodeAPI = (NodeAPI) Proxy.newProxyInstance(NodeAPI.class.getClassLoader(), new Class<?>[]{NodeAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getLedgerStateProvider" -> ledgerStateProvider;
                    case "getGenesisParameters" -> genesis();
                    case "toString" -> "TestNodeAPI";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        return resource;
    }

    private static LedgerStateProvider provider(boolean adaPotEnabled, boolean stakeAvailable) {
        return (LedgerStateProvider) Proxy.newProxyInstance(LedgerStateProvider.class.getClassLoader(),
                new Class<?>[]{LedgerStateProvider.class, AccountStateReadStore.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isAdaPotTrackingEnabled" -> adaPotEnabled;
                    case "getLatestAdaPot" -> Optional.of(new LedgerStateProvider.AdaPotSnapshot(
                            10,
                            BigInteger.valueOf(100),
                            BigInteger.valueOf(200),
                            BigInteger.ZERO,
                            BigInteger.ZERO,
                            BigInteger.ZERO,
                            BigInteger.ZERO,
                            BigInteger.ZERO,
                            BigInteger.ZERO));
                    case "getLatestSnapshotEpoch" -> stakeAvailable ? 42 : -1;
                    case "getTotalActiveStake" -> stakeAvailable
                            ? Optional.of(BigInteger.valueOf(12345))
                            : Optional.empty();
                    case "toString" -> "TestNetworkProvider";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static GenesisParameters genesis() {
        return new GenesisParameters(0.05, 5, "45000000000000000", 1, 432000,
                "2022-06-01T00:00:00Z", 129600, 1, 62, 2160);
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            if (Optional.class.equals(returnType)) return Optional.empty();
            if (Map.class.equals(returnType)) return Map.of();
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
