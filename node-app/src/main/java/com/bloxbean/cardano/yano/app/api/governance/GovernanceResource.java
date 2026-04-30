package com.bloxbean.cardano.yano.app.api.governance;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yano.api.NodeAPI;
import com.bloxbean.cardano.yano.api.account.AccountStateReadStore;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.api.util.CardanoBech32Ids;
import com.bloxbean.cardano.yano.api.util.CardanoHex;
import com.bloxbean.cardano.yano.app.api.EpochUtil;
import com.bloxbean.cardano.yano.app.api.governance.dto.GovernanceDtos.DRepDistributionDto;
import com.bloxbean.cardano.yano.app.api.governance.dto.GovernanceDtos.DRepDto;
import com.bloxbean.cardano.yano.app.api.governance.dto.GovernanceDtos.DRepListDto;
import com.bloxbean.cardano.yano.app.api.governance.dto.GovernanceDtos.ProposalDto;
import com.bloxbean.cardano.yano.app.api.governance.dto.GovernanceDtos.ProposalVoteDto;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Path("governance")
@Produces(MediaType.APPLICATION_JSON)
public class GovernanceResource {
    private static final Logger log = LoggerFactory.getLogger(GovernanceResource.class);

    @Inject
    NodeAPI nodeAPI;

    @GET
    @Path("/proposals")
    public Response listProposals(@QueryParam("status") @DefaultValue("active") String status,
                                  @QueryParam("submitted_epoch") Integer submittedEpoch,
                                  @QueryParam("page") @DefaultValue("1") int page,
                                  @QueryParam("count") @DefaultValue("100") int count,
                                  @QueryParam("order") @DefaultValue("asc") String order) {
        PageRequest pageRequest = pageRequest(page, count, order);
        if (pageRequest.error() != null) return badRequest(pageRequest.error());

        String normalizedStatus = normalizeProposalStatus(status);
        if (normalizedStatus == null) {
            return badRequest("status must be active, pending_ratified, pending_expired, or all");
        }
        if (submittedEpoch != null && submittedEpoch < 0) {
            return badRequest("submitted_epoch must be greater than or equal to 0");
        }

        AccountStateReadStore readStore = readStore();
        if (readStore == null) return unavailable();

        try {
            List<ProposalDto> proposals = readStore.listGovernanceProposals().stream()
                    .filter(p -> "all".equals(normalizedStatus) || normalizedStatus.equals(p.status()))
                    .filter(p -> submittedEpoch == null || p.proposedInEpoch() == submittedEpoch)
                    .sorted(proposalComparator(pageRequest.descending()))
                    .skip(pageRequest.offset())
                    .limit(pageRequest.count())
                    .map(this::toProposalDto)
                    .toList();
            return Response.ok(proposals).build();
        } catch (IllegalStateException e) {
            return readUnavailable("Governance state read failed", e);
        }
    }

    @GET
    @Path("/proposals/{txHash}/{certIndex}")
    public Response getProposal(@PathParam("txHash") String txHash,
                                @PathParam("certIndex") int certIndex) {
        if (!isTxHash(txHash)) return badRequest("Invalid governance action transaction hash");
        if (certIndex < 0) return badRequest("cert_index must be greater than or equal to 0");

        AccountStateReadStore readStore = readStore();
        if (readStore == null) return unavailable();

        try {
            return readStore.getGovernanceProposal(txHash, certIndex)
                    .map(this::toProposalDto)
                    .map(dto -> Response.ok(dto).build())
                    .orElseGet(() -> Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of("error", "Governance proposal not found"))
                            .build());
        } catch (IllegalStateException e) {
            return readUnavailable("Governance state read failed", e);
        }
    }

    @GET
    @Path("/proposals/{txHash}/{certIndex}/votes")
    public Response getProposalVotes(@PathParam("txHash") String txHash,
                                     @PathParam("certIndex") int certIndex,
                                     @QueryParam("page") @DefaultValue("1") int page,
                                     @QueryParam("count") @DefaultValue("100") int count,
                                     @QueryParam("order") @DefaultValue("asc") String order) {
        if (!isTxHash(txHash)) return badRequest("Invalid governance action transaction hash");
        if (certIndex < 0) return badRequest("cert_index must be greater than or equal to 0");
        PageRequest pageRequest = pageRequest(page, count, order);
        if (pageRequest.error() != null) return badRequest(pageRequest.error());

        AccountStateReadStore readStore = readStore();
        if (readStore == null) return unavailable();

        try {
            List<ProposalVoteDto> votes = readStore.getGovernanceProposalVotes(txHash, certIndex).stream()
                    .sorted(voteComparator(pageRequest.descending()))
                    .skip(pageRequest.offset())
                    .limit(pageRequest.count())
                    .map(v -> toVoteDto(txHash, certIndex, v))
                    .toList();
            return Response.ok(votes).build();
        } catch (IllegalStateException e) {
            return readUnavailable("Governance vote state read failed", e);
        }
    }

    @GET
    @Path("/dreps")
    public Response listDReps(@QueryParam("status") @DefaultValue("all") String status,
                              @QueryParam("page") @DefaultValue("1") int page,
                              @QueryParam("count") @DefaultValue("100") int count,
                              @QueryParam("order") @DefaultValue("asc") String order) {
        PageRequest pageRequest = pageRequest(page, count, order);
        if (pageRequest.error() != null) return badRequest(pageRequest.error());

        String normalizedStatus = normalizeDRepStatus(status);
        if (normalizedStatus == null) return badRequest("status must be active, inactive, or all");

        AccountStateReadStore readStore = readStore();
        if (readStore == null) return unavailable();

        try {
            Comparator<AccountStateReadStore.DRepInfo> comparator =
                    Comparator.comparing(AccountStateReadStore.DRepInfo::drepHash)
                            .thenComparingInt(AccountStateReadStore.DRepInfo::drepType);
            if (pageRequest.descending()) comparator = comparator.reversed();

            List<DRepListDto> dreps = readStore.listDReps().stream()
                    .filter(d -> "all".equals(normalizedStatus)
                            || ("active".equals(normalizedStatus) && isRegistered(d))
                            || ("inactive".equals(normalizedStatus) && !isRegistered(d)))
                    .sorted(comparator)
                    .skip(pageRequest.offset())
                    .limit(pageRequest.count())
                    .map(drep -> new DRepListDto(
                            CardanoBech32Ids.drepId(drep.drepType(), drep.drepHash()),
                            CardanoBech32Ids.drepHex(drep.drepType(), drep.drepHash())))
                    .toList();
            return Response.ok(dreps).build();
        } catch (IllegalStateException e) {
            return readUnavailable("DRep state read failed", e);
        }
    }

    @GET
    @Path("/dreps/{drepId}")
    public Response getDRep(@PathParam("drepId") String drepId) {
        AccountStateReadStore readStore = readStore();
        if (readStore == null) return unavailable();

        try {
            DRepRef ref = resolveDRepRef(drepId, readStore);
            if (ref == null) return badRequest("Invalid drep id");
            return readStore.getDRep(ref.drepType(), ref.drepHash())
                    .map(this::toDRepDto)
                    .map(dto -> Response.ok(dto).build())
                    .orElseGet(() -> Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of("error", "DRep not found"))
                            .build());
        } catch (IllegalStateException e) {
            return readUnavailable("DRep state read failed", e);
        }
    }

    @GET
    @Path("/dreps/{drepId}/distribution")
    public Response getCurrentDRepDistribution(@PathParam("drepId") String drepId) {
        AccountStateReadStore readStore = readStore();
        if (readStore == null) return unavailable();

        try {
            DRepRef ref = resolveDRepRef(drepId, readStore);
            if (ref == null) return badRequest("Invalid drep id");
            int maxEpoch = currentEpoch();
            return readStore.getLatestDRepDistributionEpoch(maxEpoch)
                    .map(epoch -> drepDistributionResponse(readStore, ref, epoch))
                    .orElseGet(() -> Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of("error", "No DRep distribution snapshot available"))
                            .build());
        } catch (IllegalStateException e) {
            return readUnavailable("DRep distribution state read failed", e);
        }
    }

    @GET
    @Path("/dreps/{drepId}/distribution/{epoch}")
    public Response getDRepDistribution(@PathParam("drepId") String drepId,
                                        @PathParam("epoch") int epoch) {
        if (epoch < 0) return badRequest("epoch must be greater than or equal to 0");

        AccountStateReadStore readStore = readStore();
        if (readStore == null) return unavailable();

        try {
            DRepRef ref = resolveDRepRef(drepId, readStore);
            if (ref == null) return badRequest("Invalid drep id");
            return drepDistributionResponse(readStore, ref, epoch);
        } catch (IllegalStateException e) {
            return readUnavailable("DRep distribution state read failed", e);
        }
    }

    private Response drepDistributionResponse(AccountStateReadStore readStore, DRepRef ref, int epoch) {
        return readStore.getDRepDistribution(epoch, ref.drepType(), ref.drepHash())
                .map(amount -> Response.ok(new DRepDistributionDto(
                        CardanoBech32Ids.drepId(ref.drepType(), ref.drepHash()),
                        CardanoBech32Ids.drepHex(ref.drepType(), ref.drepHash()),
                        drepTypeLabel(ref.drepType()),
                        epoch,
                        amount.toString())).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "No DRep distribution for epoch " + epoch))
                        .build());
    }

    private AccountStateReadStore readStore() {
        LedgerStateProvider provider = nodeAPI.getLedgerStateProvider();
        return provider instanceof AccountStateReadStore accountStateReadStore ? accountStateReadStore : null;
    }

    private ProposalDto toProposalDto(AccountStateReadStore.GovernanceProposal proposal) {
        return new ProposalDto(
                proposal.govActionId(),
                proposal.txHash(),
                proposal.certIndex(),
                governanceType(proposal.actionType()),
                null,
                proposal.deposit() != null ? proposal.deposit().toString() : null,
                returnAddress(proposal.returnAddress()),
                blockfrostExpiration(proposal),
                null,
                null,
                null,
                "pending_expired".equals(proposal.status()) ? blockfrostExpiration(proposal) : null,
                proposal.status(),
                proposal.proposedInEpoch(),
                proposal.expiresAfterEpoch(),
                proposal.proposalSlot(),
                proposal.prevActionTxHash(),
                proposal.prevActionIndex()
        );
    }

    private DRepDto toDRepDto(AccountStateReadStore.DRepInfo drep) {
        String amount = currentDRepAmount(drep);
        int currentEpoch = currentEpoch();
        boolean registered = isRegistered(drep);
        Integer lastActiveEpoch = drep.lastInteractionEpoch() != null
                ? drep.lastInteractionEpoch()
                : drep.registeredAtEpoch();
        return new DRepDto(
                CardanoBech32Ids.drepId(drep.drepType(), drep.drepHash()),
                CardanoBech32Ids.drepHex(drep.drepType(), drep.drepHash()),
                drepTypeLabel(drep.drepType()),
                amount,
                registered,
                drep.registeredAtEpoch(),
                drep.drepType() == CardanoBech32Ids.SCRIPT_HASH,
                !registered,
                currentEpoch > drep.expiryEpoch(),
                lastActiveEpoch,
                drep.registeredAtEpoch(),
                drep.expiryEpoch(),
                drep.lastInteractionEpoch(),
                drep.deposit() != null ? drep.deposit().toString() : null,
                drep.anchorUrl(),
                drep.anchorHash(),
                drep.registeredAtSlot(),
                drep.protocolVersionAtRegistration(),
                drep.previousDeregistrationSlot()
        );
    }

    private static boolean isRegistered(AccountStateReadStore.DRepInfo drep) {
        return drep.previousDeregistrationSlot() == null
                || drep.registeredAtSlot() > drep.previousDeregistrationSlot();
    }

    private static int blockfrostExpiration(AccountStateReadStore.GovernanceProposal proposal) {
        return proposal.expiresAfterEpoch() + 1;
    }

    private ProposalVoteDto toVoteDto(String txHash, int certIndex, AccountStateReadStore.GovernanceVote vote) {
        String committeeVoter = null;
        String drepVoter = null;
        String poolVoter = null;
        if (vote.voterType() == 0 || vote.voterType() == 1) {
            committeeVoter = CardanoBech32Ids.committeeHotId(vote.voterType(), vote.voterHash());
        } else if (vote.voterType() == 2 || vote.voterType() == 3) {
            drepVoter = CardanoBech32Ids.drepId(vote.voterType() - 2, vote.voterHash());
        } else if (vote.voterType() == 4) {
            poolVoter = CardanoBech32Ids.poolId(vote.voterHash());
        }
        return new ProposalVoteDto(
                txHash,
                certIndex,
                voterRole(vote.voterType()),
                committeeVoter,
                drepVoter,
                poolVoter,
                voterTypeLabel(vote.voterType()),
                vote.voterHash(),
                voteLabel(vote.vote())
        );
    }

    private DRepRef resolveDRepRef(String drepId, AccountStateReadStore readStore) {
        var parsed = CardanoBech32Ids.parseDRepId(drepId);
        if (parsed != null) return new DRepRef(parsed.drepType(), parsed.drepHash());
        if (!CardanoHex.isHash28Bytes(drepId)) return null;

        boolean keyExists = readStore.getDRep(CardanoBech32Ids.KEY_HASH, drepId).isPresent();
        boolean scriptExists = readStore.getDRep(CardanoBech32Ids.SCRIPT_HASH, drepId).isPresent();
        if (keyExists == scriptExists) return null;
        return new DRepRef(keyExists ? CardanoBech32Ids.KEY_HASH : CardanoBech32Ids.SCRIPT_HASH, drepId.toLowerCase());
    }

    private static Comparator<AccountStateReadStore.GovernanceProposal> proposalComparator(boolean descending) {
        Comparator<AccountStateReadStore.GovernanceProposal> comparator =
                Comparator.comparingLong(AccountStateReadStore.GovernanceProposal::proposalSlot)
                        .thenComparing(AccountStateReadStore.GovernanceProposal::txHash)
                        .thenComparingInt(AccountStateReadStore.GovernanceProposal::certIndex);
        return descending ? comparator.reversed() : comparator;
    }

    private static Comparator<AccountStateReadStore.GovernanceVote> voteComparator(boolean descending) {
        Comparator<AccountStateReadStore.GovernanceVote> comparator =
                Comparator.comparingInt(AccountStateReadStore.GovernanceVote::voterType)
                        .thenComparing(AccountStateReadStore.GovernanceVote::voterHash);
        return descending ? comparator.reversed() : comparator;
    }

    private static String normalizeProposalStatus(String status) {
        if (status == null || status.isBlank()) return "active";
        String value = status.toLowerCase(Locale.ROOT);
        return switch (value) {
            case "active", "pending_ratified", "pending_expired", "all" -> value;
            default -> null;
        };
    }

    private static String normalizeDRepStatus(String status) {
        if (status == null || status.isBlank()) return "all";
        String value = status.toLowerCase(Locale.ROOT);
        return switch (value) {
            case "active", "inactive", "all" -> value;
            default -> null;
        };
    }

    private static String voterRole(int voterType) {
        return switch (voterType) {
            case 0, 1 -> "constitutional_committee";
            case 2, 3 -> "drep";
            case 4 -> "spo";
            default -> "unknown";
        };
    }

    private String currentDRepAmount(AccountStateReadStore.DRepInfo drep) {
        AccountStateReadStore readStore = readStore();
        if (readStore == null) return null;
        return readStore.getLatestDRepDistributionEpoch(currentEpoch())
                .flatMap(epoch -> readStore.getDRepDistribution(epoch, drep.drepType(), drep.drepHash()))
                .map(BigInteger::toString)
                .orElse(null);
    }

    private static String returnAddress(String returnAddress) {
        String bech32 = CardanoBech32Ids.bech32Address(returnAddress);
        return bech32 != null ? bech32 : returnAddress;
    }

    private static String governanceType(String actionType) {
        if (actionType == null) return null;
        return switch (actionType) {
            case "PARAMETER_CHANGE_ACTION" -> "parameter_change";
            case "HARD_FORK_INITIATION_ACTION" -> "hard_fork_initiation";
            case "TREASURY_WITHDRAWALS_ACTION" -> "treasury_withdrawals";
            case "NO_CONFIDENCE" -> "no_confidence";
            case "UPDATE_COMMITTEE" -> "update_committee";
            case "NEW_CONSTITUTION" -> "new_constitution";
            case "INFO_ACTION" -> "info_action";
            default -> actionType.toLowerCase(Locale.ROOT);
        };
    }

    private static String voterTypeLabel(int voterType) {
        return switch (voterType) {
            case 0 -> "constitutional_committee_hot_key_hash";
            case 1 -> "constitutional_committee_hot_script_hash";
            case 2 -> "drep_key_hash";
            case 3 -> "drep_script_hash";
            case 4 -> "staking_pool_key_hash";
            default -> "unknown";
        };
    }

    private static String voteLabel(int vote) {
        return switch (vote) {
            case 0 -> "no";
            case 1 -> "yes";
            case 2 -> "abstain";
            default -> "unknown";
        };
    }

    private static String drepTypeLabel(int drepType) {
        return switch (drepType) {
            case 0 -> "key_hash";
            case 1 -> "script_hash";
            default -> "unknown";
        };
    }

    private PageRequest pageRequest(int page, int count, String order) {
        if (page < 1) return PageRequest.error("page must be greater than or equal to 1");
        if (count < 1 || count > 100) return PageRequest.error("count must be between 1 and 100");
        boolean descending;
        if ("asc".equalsIgnoreCase(order)) {
            descending = false;
        } else if ("desc".equalsIgnoreCase(order)) {
            descending = true;
        } else {
            return PageRequest.error("order must be asc or desc");
        }
        return new PageRequest(page, count, descending, null);
    }

    private Response unavailable() {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(Map.of("error", "Governance state read store not available"))
                .build();
    }

    private Response readUnavailable(String message, IllegalStateException e) {
        log.warn("{}: {}", message, e.getMessage());
        log.debug("{} details", message, e);
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(Map.of("error", message))
                .build();
    }

    private Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", message))
                .build();
    }

    private int currentEpoch() {
        ChainState cs = nodeAPI.getChainState();
        ChainTip tip = cs != null ? cs.getTip() : null;
        if (tip == null) return 0;
        return EpochUtil.slotToEpoch(tip.getSlot(), nodeAPI.getConfig());
    }

    private static boolean isTxHash(String value) {
        return CardanoHex.isTxHash(value);
    }

    private record DRepRef(int drepType, String drepHash) {}

    private record PageRequest(int page, int count, boolean descending, String error) {
        static PageRequest error(String error) {
            return new PageRequest(1, 100, false, error);
        }

        long offset() {
            return (long) (page - 1) * count;
        }
    }
}
