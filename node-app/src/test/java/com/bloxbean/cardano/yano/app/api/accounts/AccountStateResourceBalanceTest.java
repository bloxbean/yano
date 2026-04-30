package com.bloxbean.cardano.yano.app.api.accounts;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.NodeAPI;
import com.bloxbean.cardano.yano.api.account.AccountHistoryProvider;
import com.bloxbean.cardano.yano.api.account.AccountStateStore;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.api.config.NodeConfig;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.app.api.accounts.dto.AccountStateDtos.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AccountStateResourceBalanceTest {
    private static final String BASE_ADDR =
            "addr_test1qz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3jcu5d8ps7zex2k2xt3uqxgjqnnj83ws8lhrn648jjxtwq2ytjqp";
    private static final String STAKE_ADDRESS = AddressProvider.getStakeAddress(new Address(BASE_ADDR)).toBech32();

    @Test
    void getAccountShouldCombineUtxoAndRewards() {
        AccountStateResource resource = resourceWith(
                ledgerState(true, BigInteger.valueOf(50), BigInteger.valueOf(2),
                        "11".repeat(28), new LedgerStateProvider.DRepDelegation(2, null)),
                utxoState(true, true, BigInteger.valueOf(100)));

        Response response = resource.getAccount(STAKE_ADDRESS);

        assertEquals(200, response.getStatus());
        AccountInfoDto dto = (AccountInfoDto) response.getEntity();
        assertEquals(STAKE_ADDRESS, dto.stakeAddress());
        assertTrue(dto.active());
        assertEquals("100", dto.currentUtxoBalance());
        assertEquals("50", dto.withdrawableAmount());
        assertEquals("150", dto.controlledAmount());
        assertEquals("2", dto.stakeDeposit());
        assertNotNull(dto.poolId());
        assertTrue(dto.poolId().startsWith("pool"));
        assertEquals("abstain", dto.drepType());
    }

    @Test
    void getAccountShouldReturnBlockfrostStyleCurrentAccount() {
        AccountStateResource resource = resourceWith(
                ledgerState(true, BigInteger.valueOf(50), BigInteger.valueOf(2),
                        "11".repeat(28), new LedgerStateProvider.DRepDelegation(0, "22".repeat(28))),
                utxoState(true, true, BigInteger.valueOf(100)));

        Response response = resource.getAccount(STAKE_ADDRESS);

        assertEquals(200, response.getStatus());
        AccountInfoDto dto = (AccountInfoDto) response.getEntity();
        assertEquals(STAKE_ADDRESS, dto.stakeAddress());
        assertTrue(dto.active());
        assertTrue(dto.registered());
        assertEquals(1, dto.activeEpoch());
        assertEquals(2, dto.delegationEpoch());
        assertEquals("150", dto.controlledAmount());
        assertEquals("50", dto.withdrawableAmount());
        assertEquals("100", dto.currentUtxoBalance());
        assertTrue(dto.poolId().startsWith("pool"));
        assertTrue(dto.drepId().startsWith("drep"));
        assertEquals("key_hash", dto.drepType());
    }

    @Test
    void getAccountJsonShouldOmitNullAndUnsupportedFields() throws Exception {
        AccountStateResource resource = resourceWith(
                ledgerState(false, BigInteger.ZERO, BigInteger.ZERO, null, null),
                utxoState(true, true, BigInteger.valueOf(100)));

        Response response = resource.getAccount(STAKE_ADDRESS);

        assertEquals(200, response.getStatus());
        String json = new ObjectMapper().writeValueAsString(response.getEntity());
        assertFalse(json.contains("active_epoch"));
        assertFalse(json.contains("delegation_epoch"));
        assertFalse(json.contains("rewards_sum"));
        assertFalse(json.contains("unsupported_fields"));
        assertFalse(json.contains("pool_id"));
        assertTrue(json.contains("\"registered\":false"));
        assertTrue(json.contains("\"active\":false"));
    }

    @Test
    void accountHistoryEndpointsShouldReturnIndexedRecords() {
        AccountStateResource resource = resourceWith(
                ledgerState(true, BigInteger.ZERO, BigInteger.ZERO, null, null),
                utxoState(true, true, BigInteger.ZERO),
                historyProvider(true, true, true));

        Response withdrawalsResponse = resource.getWithdrawals(STAKE_ADDRESS, 1, 20, "desc");
        assertEquals(200, withdrawalsResponse.getStatus());
        WithdrawalHistoryDto withdrawal = (WithdrawalHistoryDto) ((List<?>) withdrawalsResponse.getEntity()).get(0);
        assertEquals("aa".repeat(32), withdrawal.txHash());
        assertEquals("42", withdrawal.amount());
        assertEquals(1000, withdrawal.txSlot());

        Response delegationsResponse = resource.getDelegationHistory(STAKE_ADDRESS, 1, 20, "desc");
        assertEquals(200, delegationsResponse.getStatus());
        DelegationHistoryDto delegation = (DelegationHistoryDto) ((List<?>) delegationsResponse.getEntity()).get(0);
        assertEquals(12, delegation.activeEpoch());
        assertTrue(delegation.poolId().startsWith("pool"));

        Response registrationsResponse = resource.getRegistrationHistory(STAKE_ADDRESS, 1, 20, "desc");
        assertEquals(200, registrationsResponse.getStatus());
        RegistrationHistoryDto registration = (RegistrationHistoryDto) ((List<?>) registrationsResponse.getEntity()).get(0);
        assertEquals("registered", registration.action());
        assertEquals("2000000", registration.deposit());

        Response mirsResponse = resource.getMirs(STAKE_ADDRESS, 1, 20, "desc");
        assertEquals(200, mirsResponse.getStatus());
        MirHistoryDto mir = (MirHistoryDto) ((List<?>) mirsResponse.getEntity()).get(0);
        assertEquals("treasury", mir.pot());
        assertEquals("7", mir.amount());
    }

    @Test
    void currentStateListEndpointsShouldReturnBech32Ids() {
        AccountStateResource resource = resourceWith(
                accountStateStore(),
                utxoState(true, true, BigInteger.ZERO));

        StakeRegistrationDto registration = (StakeRegistrationDto) ((List<?>) resource.listRegistrations(1, 20)
                .getEntity()).get(0);
        assertTrue(registration.stakeAddress().startsWith("stake_test1"));
        assertEquals("22".repeat(28), registration.credential());

        PoolDelegationDto delegation = (PoolDelegationDto) ((List<?>) resource.listDelegations(1, 20)
                .getEntity()).get(0);
        assertTrue(delegation.stakeAddress().startsWith("stake_test1"));
        assertTrue(delegation.poolId().startsWith("pool1"));
        assertEquals("11".repeat(28), delegation.poolHash());

        DRepDelegationDto drepDelegation = (DRepDelegationDto) ((List<?>) resource.listDRepDelegations(1, 20)
                .getEntity()).get(0);
        assertTrue(drepDelegation.stakeAddress().startsWith("stake_test1"));
        assertTrue(drepDelegation.drepId().startsWith("drep1"));
        assertEquals("33".repeat(28), drepDelegation.drepHash());

        PoolDto pool = (PoolDto) ((List<?>) resource.listPools(1, 20).getEntity()).get(0);
        assertTrue(pool.poolId().startsWith("pool1"));
        assertEquals("11".repeat(28), pool.poolHash());

        PoolRetirementDto retirement = (PoolRetirementDto) ((List<?>) resource.listPoolRetirements(1, 20)
                .getEntity()).get(0);
        assertTrue(retirement.poolId().startsWith("pool1"));
        assertEquals("11".repeat(28), retirement.poolHash());
    }

    @Test
    void accountHistoryEndpointsShouldReturn503WhenHistoryUnavailable() {
        AccountStateResource resource = resourceWith(
                ledgerState(true, BigInteger.ZERO, BigInteger.ZERO, null, null),
                utxoState(true, true, BigInteger.ZERO));

        assertEquals(503, resource.getWithdrawals(STAKE_ADDRESS, 1, 20, "desc").getStatus());
        assertEquals(503, resource.getDelegationHistory(STAKE_ADDRESS, 1, 20, "desc").getStatus());
        assertEquals(503, resource.getRegistrationHistory(STAKE_ADDRESS, 1, 20, "desc").getStatus());
        assertEquals(503, resource.getMirs(STAKE_ADDRESS, 1, 20, "desc").getStatus());
    }

    @Test
    void accountHistoryEndpointsShouldRejectInvalidOrder() {
        AccountStateResource resource = resourceWith(
                ledgerState(true, BigInteger.ZERO, BigInteger.ZERO, null, null),
                utxoState(true, true, BigInteger.ZERO),
                historyProvider(true, true, true));

        assertEquals(400, resource.getWithdrawals(STAKE_ADDRESS, 1, 20, "latest").getStatus());
    }

    @Test
    void getAccountShouldAcceptHexRewardAddress() {
        AccountStateResource resource = resourceWith(
                ledgerState(true, BigInteger.ZERO, BigInteger.ZERO, null, null),
                utxoState(true, true, BigInteger.valueOf(10)));
        String rewardAddressHex = HexUtil.encodeHexString(new Address(STAKE_ADDRESS).getBytes());

        Response response = resource.getAccount(rewardAddressHex);

        assertEquals(200, response.getStatus());
        AccountInfoDto dto = (AccountInfoDto) response.getEntity();
        assertEquals(STAKE_ADDRESS, dto.stakeAddress());
        assertEquals("10", dto.controlledAmount());
    }

    @Test
    void getAccountShouldReturn503WhenStakeBalanceIndexIsNotReady() {
        AccountStateResource resource = resourceWith(
                ledgerState(true, BigInteger.ZERO, BigInteger.ZERO, null, null),
                utxoState(true, false, BigInteger.ZERO));

        Response response = resource.getAccount(STAKE_ADDRESS);

        assertEquals(503, response.getStatus());
    }

    @Test
    void getAccountShouldReturn503WhenStakeBalanceIndexIsDisabled() {
        AccountStateResource resource = resourceWith(
                ledgerState(true, BigInteger.ZERO, BigInteger.ZERO, null, null),
                utxoState(false, true, BigInteger.ZERO));

        Response response = resource.getAccount(STAKE_ADDRESS);

        assertEquals(503, response.getStatus());
    }

    @Test
    void getAccountShouldReturn503WhenLedgerStateReadFails() {
        AccountStateResource resource = resourceWith(
                failingLedgerState(),
                utxoState(true, true, BigInteger.ZERO));

        Response response = resource.getAccount(STAKE_ADDRESS);

        assertEquals(503, response.getStatus());
        assertEquals("Ledger state read failed", ((Map<?, ?>) response.getEntity()).get("error"));
    }

    @Test
    void getAccountShouldReturn400ForInvalidStakeAddress() {
        AccountStateResource resource = resourceWith(
                ledgerState(true, BigInteger.ZERO, BigInteger.ZERO, null, null),
                utxoState(true, true, BigInteger.ZERO));

        Response response = resource.getAccount("addr_test1vpnotstake000000000000000000000000000000000000");

        assertEquals(400, response.getStatus());
    }

    @Test
    void getAccountShouldReturn404ForUnknownZeroAccount() {
        AccountStateResource resource = resourceWith(
                ledgerState(false, BigInteger.ZERO, BigInteger.ZERO, null, null),
                utxoState(true, true, BigInteger.ZERO));

        Response response = resource.getAccount(STAKE_ADDRESS);

        assertEquals(404, response.getStatus());
    }

    private static AccountStateResource resourceWith(LedgerStateProvider ledgerStateProvider, UtxoState utxoState) {
        return resourceWith(ledgerStateProvider, utxoState, null);
    }

    private static AccountStateResource resourceWith(LedgerStateProvider ledgerStateProvider, UtxoState utxoState,
                                                     AccountHistoryProvider accountHistoryProvider) {
        AccountStateResource resource = new AccountStateResource();
        resource.nodeAPI = nodeApiWith(ledgerStateProvider, utxoState, accountHistoryProvider);
        return resource;
    }

    private static NodeAPI nodeApiWith(LedgerStateProvider ledgerStateProvider, UtxoState utxoState,
                                       AccountHistoryProvider accountHistoryProvider) {
        return (NodeAPI) Proxy.newProxyInstance(NodeAPI.class.getClassLoader(), new Class<?>[]{NodeAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getLedgerStateProvider" -> ledgerStateProvider;
                    case "getUtxoState" -> utxoState;
                    case "getAccountHistoryProvider" -> accountHistoryProvider;
                    case "getConfig" -> nodeConfig();
                    case "toString" -> "TestNodeAPI";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static AccountStateStore accountStateStore() {
        String poolHash = "11".repeat(28);
        String stakeCredHash = "22".repeat(28);
        String drepHash = "33".repeat(28);
        return (AccountStateStore) Proxy.newProxyInstance(AccountStateStore.class.getClassLoader(),
                new Class<?>[]{AccountStateStore.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isEnabled" -> true;
                    case "listStakeRegistrations" -> List.of(new AccountStateStore.StakeRegistrationEntry(
                            0, stakeCredHash, BigInteger.valueOf(42), BigInteger.valueOf(2_000_000)));
                    case "listPoolDelegations" -> List.of(new AccountStateStore.PoolDelegationEntry(
                            0, stakeCredHash, poolHash, 1000, 1, 2));
                    case "listDRepDelegations" -> List.of(new AccountStateStore.DRepDelegationEntry(
                            0, stakeCredHash, 0, drepHash, 1001, 2, 3));
                    case "listPools" -> List.of(new AccountStateStore.PoolEntry(
                            poolHash, BigInteger.valueOf(500_000_000)));
                    case "listPoolRetirements" -> List.of(new AccountStateStore.PoolRetirementEntry(
                            poolHash, 42));
                    case "toString" -> "TestAccountStateStore";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static AccountHistoryProvider historyProvider(boolean enabled, boolean healthy, boolean txEventsEnabled) {
        String poolHash = "11".repeat(28);
        return new AccountHistoryProvider() {
            @Override
            public boolean isEnabled() {
                return enabled;
            }

            @Override
            public boolean isHealthy() {
                return healthy;
            }

            @Override
            public boolean isTxEventsEnabled() {
                return txEventsEnabled;
            }

            @Override
            public boolean isRewardsHistoryEnabled() {
                return false;
            }

            @Override
            public List<WithdrawalRecord> getWithdrawals(int credType, String credHash, int page, int count) {
                return List.of(new WithdrawalRecord("aa".repeat(32), BigInteger.valueOf(42), 1000, 10, 0));
            }

            @Override
            public List<DelegationRecord> getDelegations(int credType, String credHash, int page, int count) {
                return List.of(new DelegationRecord("bb".repeat(32), poolHash, 1001, 11, 1, 2, 12));
            }

            @Override
            public List<RegistrationRecord> getRegistrations(int credType, String credHash, int page, int count) {
                return List.of(new RegistrationRecord("cc".repeat(32), "registered",
                        BigInteger.valueOf(2_000_000), 1002, 12, 0, 1));
            }

            @Override
            public List<MirRecord> getMirs(int credType, String credHash, int page, int count) {
                return List.of(new MirRecord("dd".repeat(32), "treasury", BigInteger.valueOf(7), 9, 1003, 13, 0, 3));
            }
        };
    }

    private static LedgerStateProvider ledgerState(boolean active, BigInteger reward, BigInteger deposit,
                                                   String poolHash,
                                                   LedgerStateProvider.DRepDelegation drepDelegation) {
        return ledgerStateWithDelegation(active, reward, deposit, active ? 150L : null,
                poolHash != null ? new LedgerStateProvider.PoolDelegation(poolHash, 250, 0, 0) : null,
                drepDelegation);
    }

    private static LedgerStateProvider ledgerStateWithDelegation(boolean active, BigInteger reward, BigInteger deposit,
                                                                 Long registrationSlot,
                                                                 LedgerStateProvider.PoolDelegation poolDelegation,
                                                                 LedgerStateProvider.DRepDelegation drepDelegation) {
        return (LedgerStateProvider) Proxy.newProxyInstance(LedgerStateProvider.class.getClassLoader(),
                new Class<?>[]{LedgerStateProvider.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getRewardBalance" -> Optional.ofNullable(reward);
                    case "getStakeDeposit" -> Optional.ofNullable(deposit);
                    case "isStakeCredentialRegistered" -> active;
                    case "getStakeRegistrationSlot" -> Optional.ofNullable(registrationSlot);
                    case "getDelegatedPool" -> Optional.ofNullable(poolDelegation)
                            .map(LedgerStateProvider.PoolDelegation::poolHash);
                    case "getPoolDelegation" -> Optional.ofNullable(poolDelegation);
                    case "getDRepDelegation" -> Optional.ofNullable(drepDelegation);
                    case "toString" -> "TestLedgerStateProvider";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static LedgerStateProvider failingLedgerState() {
        return (LedgerStateProvider) Proxy.newProxyInstance(LedgerStateProvider.class.getClassLoader(),
                new Class<?>[]{LedgerStateProvider.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getRewardBalance" -> throw new IllegalStateException("getRewardBalance failed");
                    case "toString" -> "FailingLedgerStateProvider";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static NodeConfig nodeConfig() {
        return new NodeConfig() {
            @Override public void validate() {}
            @Override public boolean isClientEnabled() { return false; }
            @Override public boolean isServerEnabled() { return false; }
            @Override public long getProtocolMagic() { return 0; }
            @Override public long getEpochLength() { return 100; }
            @Override public long getByronSlotsPerEpoch() { return 10; }
            @Override public long getFirstNonByronSlot() { return 0; }
        };
    }

    private static UtxoState utxoState(boolean indexEnabled, boolean indexReady, BigInteger balance) {
        return (UtxoState) Proxy.newProxyInstance(UtxoState.class.getClassLoader(), new Class<?>[]{UtxoState.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isEnabled" -> true;
                    case "isStakeBalanceIndexEnabled" -> indexEnabled;
                    case "isStakeBalanceIndexReady" -> indexReady;
                    case "getUtxoBalanceByStakeCredential" -> Optional.ofNullable(balance);
                    case "toString" -> "TestUtxoState";
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
