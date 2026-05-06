package com.bloxbean.cardano.yano.app.api.accounts;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.NodeAPI;
import com.bloxbean.cardano.yano.api.account.AccountHistoryProvider;
import com.bloxbean.cardano.yano.api.account.AccountStateReadStore;
import com.bloxbean.cardano.yano.api.account.AccountStateStore;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.api.util.CardanoHex;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.api.util.CardanoBech32Ids;
import com.bloxbean.cardano.yano.app.api.EpochUtil;
import com.bloxbean.cardano.yano.app.api.accounts.dto.AccountStateDtos.*;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Path("accounts")
@Produces(MediaType.APPLICATION_JSON)
public class AccountStateResource {

    private static final Logger log = LoggerFactory.getLogger(AccountStateResource.class);

    @Inject
    NodeAPI nodeAPI;

    private AccountStateStore store() {
        LedgerStateProvider provider = nodeAPI.getLedgerStateProvider();
        return provider instanceof AccountStateStore accountStateStore ? accountStateStore : null;
    }

    private AccountHistoryProvider historyProvider() {
        return nodeAPI.getAccountHistoryProvider();
    }

    private AccountStateReadStore readStore() {
        LedgerStateProvider provider = nodeAPI.getLedgerStateProvider();
        return provider instanceof AccountStateReadStore accountStateReadStore ? accountStateReadStore : null;
    }

    private Response unavailable() {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity("{\"error\":\"Account state not available\"}")
                .build();
    }

    private Response featureUnavailable(String message) {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(Map.of("error", message))
                .build();
    }

    private Response readUnavailable(String message, IllegalStateException e) {
        log.warn("{}: {}", message, e.getMessage());
        log.debug("{} details", message, e);
        return featureUnavailable(message);
    }

    private Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", message))
                .build();
    }

    private static int clampCount(int count) {
        if (count <= 0) return 20;
        return Math.min(count, 100);
    }

    private static int clampPage(int page) {
        return page < 1 ? 1 : page;
    }

    private static String normalizeOrder(String order) {
        if (order == null || order.isBlank() || "desc".equalsIgnoreCase(order)) return "desc";
        if ("asc".equalsIgnoreCase(order)) return "asc";
        return null;
    }

    private static String credTypeLabel(int credType) {
        return credType == 0 ? "key" : "script";
    }

    private static String drepTypeLabel(int drepType) {
        return switch (drepType) {
            case 0 -> "key_hash";
            case 1 -> "script_hash";
            case 2 -> "abstain";
            case 3 -> "no_confidence";
            default -> "unknown";
        };
    }

    @GET
    @Path("/{stakeAddress}")
    public Response getAccount(@PathParam("stakeAddress") String stakeAddress) {
        StakeCredentialRef credential = parseStakeCredential(stakeAddress);
        if (credential == null) {
            return badRequest("Invalid stake address");
        }

        AccountLoadResult result;
        try {
            result = loadAccount(credential);
            if (result.error() != null) return result.error();
        } catch (IllegalStateException e) {
            return readUnavailable("Ledger state read failed", e);
        }

        AccountState state = result.state();
        LedgerStateProvider.DRepDelegation drep = state.drepDelegation();
        AccountInfoDto dto = new AccountInfoDto(
                credential.stakeAddress(),
                state.poolHash() != null,
                state.registered(),
                state.activeEpoch(),
                state.delegationEpoch(),
                state.controlledAmount().toString(),
                state.withdrawableAmount().toString(),
                CardanoBech32Ids.poolId(state.poolHash()),
                drepId(drep),
                state.currentUtxoBalance().toString(),
                state.stakeDeposit().toString(),
                state.poolHash(),
                drep != null ? drepTypeLabel(drep.drepType()) : null,
                drep != null ? drep.hash() : null
        );

        return Response.ok(dto).build();
    }

    @GET
    @Path("/{stakeAddress}/stake")
    public Response getCurrentStake(@PathParam("stakeAddress") String stakeAddress) {
        LedgerStateProvider ledgerState = nodeAPI.getLedgerStateProvider();
        if (ledgerState == null) return featureUnavailable("Account state not available");

        int epoch = ledgerState.getLatestSnapshotEpoch();
        if (epoch < 0) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "No active stake snapshot available"))
                    .build();
        }
        return getStakeByEpoch(stakeAddress, epoch);
    }

    @GET
    @Path("/{stakeAddress}/stake/{epoch}")
    public Response getStakeByEpoch(@PathParam("stakeAddress") String stakeAddress,
                                    @PathParam("epoch") int epoch) {
        if (epoch < 0) return badRequest("epoch must be greater than or equal to 0");
        StakeCredentialRef credential = parseStakeCredential(stakeAddress);
        if (credential == null) return badRequest("Invalid stake address");

        AccountStateReadStore readStore = readStore();
        if (readStore == null) return featureUnavailable("Account state read store not available");

        try {
            return readStore.getEpochStake(epoch, credential.credType(), credential.credHash())
                    .map(stake -> new AccountStakeDto(
                            credential.stakeAddress(),
                            stake.epoch(),
                            stake.amount().toString(),
                            CardanoBech32Ids.poolId(stake.poolHash()),
                            stake.poolHash(),
                            credential.credHash(),
                            credTypeLabel(credential.credType())))
                    .map(dto -> Response.ok(dto).build())
                    .orElseGet(() -> Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of("error", "No active stake snapshot for account at epoch " + epoch))
                            .build());
        } catch (IllegalStateException e) {
            return readUnavailable("Account state read failed", e);
        }
    }

    @GET
    @Path("/{stakeAddress}/withdrawals")
    public Response getWithdrawals(@PathParam("stakeAddress") String stakeAddress,
                                   @QueryParam("page") @DefaultValue("1") int page,
                                   @QueryParam("count") @DefaultValue("20") int count,
                                   @QueryParam("order") @DefaultValue("desc") String order) {
        StakeCredentialRef credential = parseStakeCredential(stakeAddress);
        if (credential == null) return badRequest("Invalid stake address");
        AccountHistoryProvider history = historyProvider();
        Response unavailable = historyUnavailable(history, true);
        if (unavailable != null) return unavailable;
        String resolvedOrder = normalizeOrder(order);
        if (resolvedOrder == null) return badRequest("order must be asc or desc");

        page = clampPage(page);
        count = clampCount(count);
        try {
            List<WithdrawalHistoryDto> body = history.getWithdrawals(credential.credType(), credential.credHash(), page, count, resolvedOrder)
                    .stream()
                    .map(r -> new WithdrawalHistoryDto(
                            r.txHash(), r.amount().toString(), r.slot(), r.blockNo(), r.txIdx()))
                    .toList();
            return Response.ok(body).build();
        } catch (IllegalStateException e) {
            return readUnavailable("Account history read failed", e);
        }
    }

    @GET
    @Path("/{stakeAddress}/delegations")
    public Response getDelegationHistory(@PathParam("stakeAddress") String stakeAddress,
                                         @QueryParam("page") @DefaultValue("1") int page,
                                         @QueryParam("count") @DefaultValue("20") int count,
                                         @QueryParam("order") @DefaultValue("desc") String order) {
        StakeCredentialRef credential = parseStakeCredential(stakeAddress);
        if (credential == null) return badRequest("Invalid stake address");
        AccountHistoryProvider history = historyProvider();
        Response unavailable = historyUnavailable(history, true);
        if (unavailable != null) return unavailable;
        String resolvedOrder = normalizeOrder(order);
        if (resolvedOrder == null) return badRequest("order must be asc or desc");

        page = clampPage(page);
        count = clampCount(count);
        try {
            List<DelegationHistoryDto> body = history.getDelegations(credential.credType(), credential.credHash(), page, count, resolvedOrder)
                    .stream()
                    .map(r -> new DelegationHistoryDto(
                            r.activeEpoch(), r.txHash(), null, CardanoBech32Ids.poolId(r.poolHash()), r.poolHash(),
                            r.slot(), r.blockNo(), r.txIdx(), r.certIdx()))
                    .toList();
            return Response.ok(body).build();
        } catch (IllegalStateException e) {
            return readUnavailable("Account history read failed", e);
        }
    }

    @GET
    @Path("/{stakeAddress}/registrations")
    public Response getRegistrationHistory(@PathParam("stakeAddress") String stakeAddress,
                                           @QueryParam("page") @DefaultValue("1") int page,
                                           @QueryParam("count") @DefaultValue("20") int count,
                                           @QueryParam("order") @DefaultValue("desc") String order) {
        StakeCredentialRef credential = parseStakeCredential(stakeAddress);
        if (credential == null) return badRequest("Invalid stake address");
        AccountHistoryProvider history = historyProvider();
        Response unavailable = historyUnavailable(history, true);
        if (unavailable != null) return unavailable;
        String resolvedOrder = normalizeOrder(order);
        if (resolvedOrder == null) return badRequest("order must be asc or desc");

        page = clampPage(page);
        count = clampCount(count);
        try {
            List<RegistrationHistoryDto> body = history.getRegistrations(credential.credType(), credential.credHash(), page, count, resolvedOrder)
                    .stream()
                    .map(r -> new RegistrationHistoryDto(
                            r.txHash(), r.action(), r.deposit().toString(),
                            r.slot(), r.blockNo(), r.txIdx(), r.certIdx()))
                    .toList();
            return Response.ok(body).build();
        } catch (IllegalStateException e) {
            return readUnavailable("Account history read failed", e);
        }
    }

    @GET
    @Path("/{stakeAddress}/mirs")
    public Response getMirs(@PathParam("stakeAddress") String stakeAddress,
                            @QueryParam("page") @DefaultValue("1") int page,
                            @QueryParam("count") @DefaultValue("20") int count,
                            @QueryParam("order") @DefaultValue("desc") String order) {
        StakeCredentialRef credential = parseStakeCredential(stakeAddress);
        if (credential == null) return badRequest("Invalid stake address");
        AccountHistoryProvider history = historyProvider();
        Response unavailable = historyUnavailable(history, true);
        if (unavailable != null) return unavailable;
        String resolvedOrder = normalizeOrder(order);
        if (resolvedOrder == null) return badRequest("order must be asc or desc");

        page = clampPage(page);
        count = clampCount(count);
        try {
            List<MirHistoryDto> body = history.getMirs(credential.credType(), credential.credHash(), page, count, resolvedOrder)
                    .stream()
                    .map(r -> new MirHistoryDto(
                            r.txHash(), r.pot(), r.amount().toString(), r.earnedEpoch(),
                            r.slot(), r.blockNo(), r.txIdx(), r.certIdx()))
                    .toList();
            return Response.ok(body).build();
        } catch (IllegalStateException e) {
            return readUnavailable("Account history read failed", e);
        }
    }

    private AccountLoadResult loadAccount(StakeCredentialRef credential) {
        LedgerStateProvider ledgerState = nodeAPI.getLedgerStateProvider();
        if (ledgerState == null) {
            return AccountLoadResult.error(featureUnavailable("Account state not available"));
        }
        if (ledgerState instanceof AccountStateStore accountStateStore && !accountStateStore.isEnabled()) {
            return AccountLoadResult.error(featureUnavailable("Account state not available"));
        }

        UtxoState utxoState = nodeAPI.getUtxoState();
        if (utxoState == null || !utxoState.isEnabled()) {
            return AccountLoadResult.error(featureUnavailable("UTXO state not available"));
        }
        if (!utxoState.isStakeBalanceIndexEnabled()) {
            return AccountLoadResult.error(featureUnavailable("Stake balance index is disabled"));
        }
        if (!utxoState.isStakeBalanceIndexReady()) {
            return AccountLoadResult.error(featureUnavailable("Stake balance index is not ready; rebuild is required"));
        }

        Optional<BigInteger> currentUtxoBalance = utxoState.getUtxoBalanceByStakeCredential(
                credential.credType(), credential.credHash());
        if (currentUtxoBalance.isEmpty()) {
            return AccountLoadResult.error(featureUnavailable("Stake balance index is not available"));
        }

        BigInteger utxo = currentUtxoBalance.get();
        BigInteger withdrawable = ledgerState.getRewardBalance(credential.credType(), credential.credHash())
                .orElse(BigInteger.ZERO);
        BigInteger stakeDeposit = ledgerState.getStakeDeposit(credential.credType(), credential.credHash())
                .orElse(BigInteger.ZERO);
        boolean active = ledgerState.isStakeCredentialRegistered(credential.credType(), credential.credHash());
        Optional<Long> registrationSlot = active
                ? ledgerState.getStakeRegistrationSlot(credential.credType(), credential.credHash())
                : Optional.empty();
        Optional<LedgerStateProvider.PoolDelegation> poolDelegation =
                ledgerState.getPoolDelegation(credential.credType(), credential.credHash());
        Optional<String> poolHash = poolDelegation.map(LedgerStateProvider.PoolDelegation::poolHash);
        if (poolHash.isEmpty()) {
            poolHash = ledgerState.getDelegatedPool(credential.credType(), credential.credHash());
        }
        Optional<LedgerStateProvider.DRepDelegation> drepDelegation =
                ledgerState.getDRepDelegation(credential.credType(), credential.credHash());

        if (!active && utxo.signum() == 0 && withdrawable.signum() == 0
                && stakeDeposit.signum() == 0 && poolHash.isEmpty() && drepDelegation.isEmpty()) {
            return AccountLoadResult.error(Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Account not found"))
                    .build());
        }

        return AccountLoadResult.ok(new AccountState(
                active,
                utxo,
                withdrawable,
                utxo.add(withdrawable),
                stakeDeposit,
                poolHash.orElse(null),
                registrationSlot.map(this::epochForSlot).orElse(null),
                poolDelegation.map(d -> epochForSlot(d.slot())).orElse(null),
                drepDelegation.orElse(null)
        ));
    }

    @GET
    @Path("/registrations")
    public Response listRegistrations(@QueryParam("page") @DefaultValue("1") int page,
                                      @QueryParam("count") @DefaultValue("20") int count) {
        AccountStateStore s = store();
        if (s == null || !s.isEnabled()) return unavailable();
        page = clampPage(page);
        count = clampCount(count);
        long protocolMagic = protocolMagic();

        try {
            List<StakeRegistrationDto> body = s.listStakeRegistrations(page, count).stream()
                    .map(e -> new StakeRegistrationDto(
                            CardanoBech32Ids.stakeAddress(e.credType(), e.credentialHash(), protocolMagic),
                            e.credentialHash(), credTypeLabel(e.credType()),
                            e.reward().toString(), e.deposit().toString()))
                    .toList();
            return Response.ok(body).build();
        } catch (IllegalStateException e) {
            return readUnavailable("Account state read failed", e);
        }
    }

    private static StakeCredentialRef parseStakeCredential(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            Address address;
            if (CardanoHex.isHex(value)) {
                address = new Address(HexUtil.decodeHexString(value));
            } else {
                address = new Address(value);
            }

            byte[] bytes = address.getBytes();
            if (bytes.length < 29) return null;
            int addressType = ((bytes[0] & 0xFF) >> 4) & 0x0F;
            if (addressType != 0x0E && addressType != 0x0F) return null;

            int credType = addressType == 0x0F ? 1 : 0;
            String credHash = HexUtil.encodeHexString(Arrays.copyOfRange(bytes, 1, 29));
            return new StakeCredentialRef(address.toBech32(), credType, credHash);
        } catch (Exception e) {
            return null;
        }
    }

    private static String drepId(LedgerStateProvider.DRepDelegation drep) {
        return drep == null ? null : CardanoBech32Ids.drepId(drep.drepType(), drep.hash());
    }

    private Integer epochForSlot(long slot) {
        var config = nodeAPI != null ? nodeAPI.getConfig() : null;
        if (slot < 0 || config == null) {
            return null;
        }
        try {
            return EpochUtil.slotToEpoch(slot, config);
        } catch (Exception e) {
            return null;
        }
    }

    private long protocolMagic() {
        try {
            var config = nodeAPI != null ? nodeAPI.getConfig() : null;
            return config != null ? config.getProtocolMagic() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private Response historyUnavailable(AccountHistoryProvider history, boolean requireTxEvents) {
        if (history == null || !history.isEnabled()) {
            return featureUnavailable("Account history not available");
        }
        if (!history.isHealthy()) {
            return featureUnavailable("Account history index is not healthy");
        }
        if (requireTxEvents && !history.isTxEventsEnabled()) {
            return featureUnavailable("Account tx/cert history index is disabled");
        }
        return null;
    }

    private record StakeCredentialRef(String stakeAddress, int credType, String credHash) {}
    private record AccountState(boolean registered, BigInteger currentUtxoBalance,
                                BigInteger withdrawableAmount, BigInteger controlledAmount,
                                BigInteger stakeDeposit, String poolHash, Integer activeEpoch,
                                Integer delegationEpoch,
                                LedgerStateProvider.DRepDelegation drepDelegation) {}
    private record AccountLoadResult(AccountState state, Response error) {
        static AccountLoadResult ok(AccountState state) {
            return new AccountLoadResult(state, null);
        }

        static AccountLoadResult error(Response error) {
            return new AccountLoadResult(null, error);
        }
    }

    @GET
    @Path("/delegations")
    public Response listDelegations(@QueryParam("page") @DefaultValue("1") int page,
                                    @QueryParam("count") @DefaultValue("20") int count) {
        AccountStateStore s = store();
        if (s == null || !s.isEnabled()) return unavailable();
        page = clampPage(page);
        count = clampCount(count);
        long protocolMagic = protocolMagic();

        try {
            List<PoolDelegationDto> body = s.listPoolDelegations(page, count).stream()
                    .map(e -> new PoolDelegationDto(
                            CardanoBech32Ids.stakeAddress(e.credType(), e.credentialHash(), protocolMagic),
                            e.credentialHash(), credTypeLabel(e.credType()),
                            CardanoBech32Ids.poolId(e.poolHash()), e.poolHash(),
                            e.slot(), e.txIdx(), e.certIdx()))
                    .toList();
            return Response.ok(body).build();
        } catch (IllegalStateException e) {
            return readUnavailable("Account state read failed", e);
        }
    }

    @GET
    @Path("/drep-delegations")
    public Response listDRepDelegations(@QueryParam("page") @DefaultValue("1") int page,
                                        @QueryParam("count") @DefaultValue("20") int count) {
        AccountStateStore s = store();
        if (s == null || !s.isEnabled()) return unavailable();
        page = clampPage(page);
        count = clampCount(count);
        long protocolMagic = protocolMagic();

        try {
            List<DRepDelegationDto> body = s.listDRepDelegations(page, count).stream()
                    .map(e -> new DRepDelegationDto(
                            CardanoBech32Ids.stakeAddress(e.credType(), e.credentialHash(), protocolMagic),
                            e.credentialHash(), credTypeLabel(e.credType()),
                            CardanoBech32Ids.drepId(e.drepType(), e.drepHash()),
                            drepTypeLabel(e.drepType()), e.drepHash(),
                            e.slot(), e.txIdx(), e.certIdx()))
                    .toList();
            return Response.ok(body).build();
        } catch (IllegalStateException e) {
            return readUnavailable("Account state read failed", e);
        }
    }

    @GET
    @Path("/pools")
    public Response listPools(@QueryParam("page") @DefaultValue("1") int page,
                              @QueryParam("count") @DefaultValue("20") int count) {
        AccountStateStore s = store();
        if (s == null || !s.isEnabled()) return unavailable();
        page = clampPage(page);
        count = clampCount(count);

        try {
            List<PoolDto> body = s.listPools(page, count).stream()
                    .map(e -> new PoolDto(CardanoBech32Ids.poolId(e.poolHash()), e.poolHash(), e.deposit().toString()))
                    .toList();
            return Response.ok(body).build();
        } catch (IllegalStateException e) {
            return readUnavailable("Account state read failed", e);
        }
    }

    @GET
    @Path("/pool-retirements")
    public Response listPoolRetirements(@QueryParam("page") @DefaultValue("1") int page,
                                        @QueryParam("count") @DefaultValue("20") int count) {
        AccountStateStore s = store();
        if (s == null || !s.isEnabled()) return unavailable();
        page = clampPage(page);
        count = clampCount(count);

        try {
            List<PoolRetirementDto> body = s.listPoolRetirements(page, count).stream()
                    .map(e -> new PoolRetirementDto(CardanoBech32Ids.poolId(e.poolHash()), e.poolHash(), e.retirementEpoch()))
                    .toList();
            return Response.ok(body).build();
        } catch (IllegalStateException e) {
            return readUnavailable("Account state read failed", e);
        }
    }
}
