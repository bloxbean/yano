package com.bloxbean.cardano.yano.app.api.accounts;

import com.bloxbean.cardano.yano.api.LedgerQuery;
import com.bloxbean.cardano.yano.api.NodeLifecycle;
import com.bloxbean.cardano.yano.api.account.AccountHistoryProvider;
import com.bloxbean.cardano.yano.api.account.AccountStateReadStore;
import com.bloxbean.cardano.yano.api.account.AccountStateStore;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.api.util.CardanoBech32Ids;
import com.bloxbean.cardano.yano.app.api.EpochUtil;
import com.bloxbean.cardano.yano.app.api.accounts.dto.AccountStateDtos.*;
import com.bloxbean.cardano.yano.app.archive.HistoryArchiveService;
import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Path("accounts")
@Produces(MediaType.APPLICATION_JSON)
public class AccountStateResource {

    private static final Logger log = LoggerFactory.getLogger(AccountStateResource.class);

    @Inject
    NodeLifecycle nodeLifecycle;

    @Inject
    LedgerQuery ledgerQuery;

    @Inject
    HistoryArchiveService historyArchive;

    private AccountStateStore store() {
        LedgerStateProvider provider = ledgerQuery.getLedgerStateProvider();
        return provider instanceof AccountStateStore accountStateStore ? accountStateStore : null;
    }

    private AccountHistoryProvider historyProvider() {
        return historyArchive != null && historyArchive.enabled()
                ? historyArchive.accountHistoryProvider()
                : null;
    }

    private AccountStateReadStore readStore() {
        LedgerStateProvider provider = ledgerQuery.getLedgerStateProvider();
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
        LedgerStateProvider ledgerState = ledgerQuery.getLedgerStateProvider();
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
        Response unavailable = historyUnavailable(history, ArchiveDatasetId.ACCOUNT_EVENT);
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
        Response unavailable = historyUnavailable(history, ArchiveDatasetId.ACCOUNT_EVENT);
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
        Response unavailable = historyUnavailable(history, ArchiveDatasetId.ACCOUNT_EVENT);
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
        Response unavailable = historyUnavailable(history, ArchiveDatasetId.ACCOUNT_EVENT);
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

    /**
     * Per-epoch reward history (ADR-033 M2), Blockfrost-shaped:
     * [{epoch, amount, pool_id, type}].
     */
    @GET
    @Path("/{stakeAddress}/rewards")
    public Response getRewards(@PathParam("stakeAddress") String stakeAddress,
                               @QueryParam("page") @DefaultValue("1") int page,
                               @QueryParam("count") @DefaultValue("20") int count,
                               @QueryParam("order") @DefaultValue("desc") String order) {
        StakeCredentialRef credential = parseStakeCredential(stakeAddress);
        if (credential == null) return badRequest("Invalid stake address");
        AccountHistoryProvider history = historyProvider();
        Response unavailable = historyUnavailable(history, ArchiveDatasetId.REWARD);
        if (unavailable != null) return unavailable;
        String resolvedOrder = normalizeOrder(order);
        if (resolvedOrder == null) return badRequest("order must be asc or desc");

        page = clampPage(page);
        count = clampCount(count);
        try {
            List<RewardHistoryDto> body = history.getRewards(
                            credential.credType(), credential.credHash(), page, count, resolvedOrder)
                    .stream()
                    .map(r -> new RewardHistoryDto(
                            r.earnedEpoch(),
                            r.amount().toString(),
                            r.poolHash() != null ? CardanoBech32Ids.poolId(r.poolHash()) : null,
                            blockfrostRewardType(r.type())))
                    .toList();
            return Response.ok(body).build();
        } catch (IllegalStateException e) {
            return readUnavailable("Reward history read failed", e);
        }
    }

    private static String blockfrostRewardType(String rewardType) {
        if (rewardType == null) return null;
        return switch (rewardType) {
            case "MEMBER" -> "member";
            case "LEADER" -> "leader";
            case "REFUND" -> "pool_deposit_refund";
            default -> rewardType.toLowerCase();
        };
    }

    private record RewardHistoryDto(
            @com.fasterxml.jackson.annotation.JsonProperty("epoch") int epoch,
            @com.fasterxml.jackson.annotation.JsonProperty("amount") String amount,
            @com.fasterxml.jackson.annotation.JsonProperty("pool_id") String poolId,
            @com.fasterxml.jackson.annotation.JsonProperty("type") String type) {
    }

    /**
     * All transactions that touched any address delegated to this stake
     * credential (ADR-033 M2), from the address-tx index.
     */
    @GET
    @Path("/{stakeAddress}/transactions")
    public Response getAccountTransactions(@PathParam("stakeAddress") String stakeAddress,
                                           @QueryParam("page") @DefaultValue("1") int page,
                                           @QueryParam("count") @DefaultValue("20") int count,
                                           @QueryParam("order") @DefaultValue("asc") String order) {
        StakeCredentialRef credential = parseStakeCredential(stakeAddress);
        if (credential == null) return badRequest("Invalid stake address");
        AccountHistoryProvider history = historyProvider();
        Response unavailable = historyUnavailable(history, ArchiveDatasetId.ADDRESS_TRANSACTION);
        if (unavailable != null) return unavailable;
        String resolvedOrder = normalizeOrder(order);
        if (resolvedOrder == null) return badRequest("order must be asc or desc");

        page = clampPage(page);
        count = clampCount(count);
        try {
            // Same Blockfrost shape as /addresses/{address}/transactions.
            List<com.bloxbean.cardano.yano.app.api.addresses.dto.AddressTxDto> body =
                    history.getAddressTransactions(
                                    AccountHistoryProvider.ADDR_SCOPE_STAKE_CRED, credential.credHash(),
                                    page, count, resolvedOrder)
                            .stream()
                            .map(r -> new com.bloxbean.cardano.yano.app.api.addresses.dto.AddressTxDto(
                                    r.txHash(), r.txIdx(), r.blockNo(),
                                    ledgerQuery.slotToUnixTime(r.slot()), r.slot()))
                            .toList();
            return Response.ok(body).build();
        } catch (IllegalStateException e) {
            return readUnavailable("Account history read failed", e);
        }
    }

    private AccountLoadResult loadAccount(StakeCredentialRef credential) {
        LedgerStateProvider ledgerState = ledgerQuery.getLedgerStateProvider();
        if (ledgerState == null) {
            return AccountLoadResult.error(featureUnavailable("Account state not available"));
        }
        if (ledgerState instanceof AccountStateStore accountStateStore && !accountStateStore.isEnabled()) {
            return AccountLoadResult.error(featureUnavailable("Account state not available"));
        }

        UtxoState utxoState = ledgerQuery.getUtxoState();
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
        CardanoBech32Ids.StakeCredential credential = CardanoBech32Ids.stakeCredential(value);
        return credential == null ? null : new StakeCredentialRef(credential.stakeAddress(),
                credential.credentialType(), credential.credentialHash());
    }

    private static String drepId(LedgerStateProvider.DRepDelegation drep) {
        return drep == null ? null : CardanoBech32Ids.drepId(drep.drepType(), drep.hash());
    }

    private Integer epochForSlot(long slot) {
        var config = nodeLifecycle != null ? nodeLifecycle.getConfig() : null;
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
            var config = nodeLifecycle != null ? nodeLifecycle.getConfig() : null;
            return config != null ? config.getProtocolMagic() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private Response historyUnavailable(AccountHistoryProvider history, ArchiveDatasetId dataset) {
        if (history == null || !history.isEnabled()) {
            return featureUnavailable("Account history not available");
        }
        if (!history.isHealthy()) {
            return featureUnavailable("Account history index is not healthy");
        }
        boolean available = switch (dataset) {
            case ACCOUNT_EVENT -> history.isTxEventsEnabled();
            case ADDRESS_TRANSACTION -> history.isAddressTxEnabled();
            case REWARD -> history.isRewardsHistoryEnabled();
            default -> throw new IllegalArgumentException(
                    "Unsupported account-history dataset: " + dataset.logicalName());
        };
        if (available) {
            return null;
        }
        if (historyArchive != null && historyArchive.datasetBuilding(dataset)) {
            return featureUnavailable(switch (dataset) {
                case ACCOUNT_EVENT -> "Account tx/cert history is still building";
                case ADDRESS_TRANSACTION -> "Address transaction history is still building";
                case REWARD -> "Reward history is still building";
                default -> throw new IllegalArgumentException(
                        "Unsupported account-history dataset: " + dataset.logicalName());
            });
        }
        if (historyArchive != null && historyArchive.datasetFailed(dataset)) {
            return featureUnavailable(switch (dataset) {
                case ACCOUNT_EVENT -> "Account tx/cert history is unavailable after a non-retryable failure";
                case ADDRESS_TRANSACTION ->
                        "Address transaction history is unavailable after a non-retryable failure";
                case REWARD -> "Reward history is unavailable after a non-retryable failure";
                default -> throw new IllegalArgumentException(
                        "Unsupported account-history dataset: " + dataset.logicalName());
            });
        }
        return featureUnavailable(switch (dataset) {
            case ACCOUNT_EVENT -> "Account tx/cert history index is disabled";
            case ADDRESS_TRANSACTION -> "Address transaction history disabled "
                    + "(set yano.history.projection.enabled=true; if "
                    + "yano.history.projection.sections is set it must include "
                    + "address-transaction:v1)";
            case REWARD -> "Reward history disabled (set yano.history.projection.enabled=true; "
                    + "rewards always ship with the projection archive)";
            default -> throw new IllegalArgumentException(
                    "Unsupported account-history dataset: " + dataset.logicalName());
        });
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
