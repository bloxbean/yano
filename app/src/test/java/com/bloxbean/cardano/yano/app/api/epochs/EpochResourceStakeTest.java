package com.bloxbean.cardano.yano.app.api.epochs;

import com.bloxbean.cardano.yano.api.NodeAPI;
import com.bloxbean.cardano.yano.api.account.AccountStateReadStore;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.api.util.CardanoBech32Ids;
import com.bloxbean.cardano.yano.app.api.epochs.dto.StakeDtos.PoolStakeDelegatorDto;
import com.bloxbean.cardano.yano.app.api.epochs.dto.StakeDtos.PoolStakeDto;
import com.bloxbean.cardano.yano.app.api.epochs.dto.StakeDtos.TotalStakeDto;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EpochResourceStakeTest {

    @Test
    void latestTotalStakeUsesLatestSnapshotEpoch() {
        EpochResource resource = resourceWith(readProvider(42, BigInteger.valueOf(12345), null));

        Response response = resource.getLatestTotalStake();

        assertEquals(200, response.getStatus());
        TotalStakeDto dto = (TotalStakeDto) response.getEntity();
        assertEquals(42, dto.epoch());
        assertEquals("12345", dto.activeStake());
    }

    @Test
    void totalStakeByEpochReturns404WhenSnapshotMissing() {
        EpochResource resource = resourceWith(readProvider(42, null, null));

        Response response = resource.getTotalStake(42);

        assertEquals(404, response.getStatus());
    }

    @Test
    void poolStakeByEpochAcceptsBech32PoolId() {
        String poolHash = "11".repeat(28);
        EpochResource resource = resourceWith(readProvider(42, BigInteger.ZERO,
                new AccountStateReadStore.PoolStake(42, poolHash, BigInteger.valueOf(999))));

        Response response = resource.getPoolStake(42, CardanoBech32Ids.poolId(poolHash));

        assertEquals(200, response.getStatus());
        PoolStakeDto dto = (PoolStakeDto) response.getEntity();
        assertEquals(42, dto.epoch());
        assertEquals(poolHash, dto.poolHash());
        assertTrue(dto.poolId().startsWith("pool1"));
        assertEquals("999", dto.activeStake());
    }

    @Test
    void poolStakeDelegatorsUsesBlockfrostStyleShape() {
        String poolHash = "11".repeat(28);
        EpochResource resource = resourceWith(readProvider(42, BigInteger.ZERO,
                new AccountStateReadStore.PoolStake(42, poolHash, BigInteger.valueOf(999))));

        Response response = resource.getPoolStakeDelegators(42, CardanoBech32Ids.poolId(poolHash),
                1, 100, "asc");

        assertEquals(200, response.getStatus());
        PoolStakeDelegatorDto dto = (PoolStakeDelegatorDto) ((java.util.List<?>) response.getEntity()).get(0);
        assertTrue(dto.stakeAddress().startsWith("stake_test1"));
        assertTrue(dto.poolId().startsWith("pool1"));
        assertEquals("55", dto.amount());
    }

    @Test
    void poolStakeByEpochRejectsInvalidPoolId() {
        EpochResource resource = resourceWith(readProvider(42, BigInteger.ZERO, null));

        Response response = resource.getPoolStake(42, "not-a-pool");

        assertEquals(400, response.getStatus());
    }

    private static EpochResource resourceWith(LedgerStateProvider ledgerStateProvider) {
        EpochResource resource = new EpochResource();
        resource.nodeAPI = (NodeAPI) Proxy.newProxyInstance(NodeAPI.class.getClassLoader(), new Class<?>[]{NodeAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getLedgerStateProvider" -> ledgerStateProvider;
                    case "toString" -> "TestNodeAPI";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        return resource;
    }

    private static LedgerStateProvider readProvider(int latestSnapshotEpoch,
                                                    BigInteger totalStake,
                                                    AccountStateReadStore.PoolStake poolStake) {
        return (LedgerStateProvider) Proxy.newProxyInstance(LedgerStateProvider.class.getClassLoader(),
                new Class<?>[]{LedgerStateProvider.class, AccountStateReadStore.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getLatestSnapshotEpoch" -> latestSnapshotEpoch;
                    case "getTotalActiveStake" -> Optional.ofNullable(totalStake);
                    case "getPoolActiveStake" -> Optional.ofNullable(poolStake);
                    case "listPoolStakeDelegators" -> java.util.List.of(new AccountStateReadStore.PoolStakeDelegator(
                            (Integer) args[0], 0, "22".repeat(28), (String) args[1], BigInteger.valueOf(55)));
                    case "toString" -> "TestReadProvider";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
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
