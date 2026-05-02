package com.bloxbean.cardano.yano.app.api.governance;

import com.bloxbean.cardano.yano.api.NodeAPI;
import com.bloxbean.cardano.yano.api.account.AccountStateReadStore;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.api.util.CardanoBech32Ids;
import com.bloxbean.cardano.yano.app.api.governance.dto.GovernanceDtos.DRepDistributionDto;
import com.bloxbean.cardano.yano.app.api.governance.dto.GovernanceDtos.DRepDto;
import com.bloxbean.cardano.yano.app.api.governance.dto.GovernanceDtos.DRepListDto;
import com.bloxbean.cardano.yano.app.api.governance.dto.GovernanceDtos.ProposalDto;
import com.bloxbean.cardano.yano.app.api.governance.dto.GovernanceDtos.ProposalVoteDto;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernanceResourceTest {
    private static final String TX_HASH = "aa".repeat(32);
    private static final String DREP_HASH = "22".repeat(28);
    private static final String POOL_HASH = "33".repeat(28);

    @Test
    void listProposalsFiltersStatusAndSubmittedEpoch() {
        GovernanceResource resource = resourceWith(readProvider());

        Response response = resource.listProposals("active", 10, 1, 20, "asc");

        assertEquals(200, response.getStatus());
        ProposalDto dto = (ProposalDto) ((List<?>) response.getEntity()).get(0);
        assertEquals(TX_HASH, dto.txHash());
        assertEquals(1, dto.certIndex());
        assertEquals(CardanoBech32Ids.govActionId(TX_HASH, 1), dto.id());
        assertEquals("active", dto.status());
        assertEquals("parameter_change", dto.governanceType());
        assertEquals("1000000", dto.deposit());
        assertEquals(17, dto.expiration());
    }

    @Test
    void getProposalVotesProjectsVoterIds() {
        GovernanceResource resource = resourceWith(readProvider());

        Response response = resource.getProposalVotes(TX_HASH, 1, 1, 20, "asc");

        assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        List<ProposalVoteDto> votes = (List<ProposalVoteDto>) response.getEntity();
        assertEquals(2, votes.size());
        assertEquals("drep", votes.get(0).voterRole());
        assertTrue(votes.get(0).drepVoter().startsWith("drep1"));
        assertEquals("yes", votes.get(0).vote());
        assertEquals("spo", votes.get(1).voterRole());
        assertTrue(votes.get(1).poolVoter().startsWith("pool1"));
    }

    @Test
    void drepEndpointsReturnDRepStateAndDistribution() {
        GovernanceResource resource = resourceWith(readProvider());
        String drepId = CardanoBech32Ids.drepId(0, DREP_HASH);

        Response drepResponse = resource.getDRep(drepId);
        assertEquals(200, drepResponse.getStatus());
        DRepDto drep = (DRepDto) drepResponse.getEntity();
        assertEquals(drepId, drep.drepId());
        assertEquals(CardanoBech32Ids.drepHex(0, DREP_HASH), drep.hex());
        assertTrue(drep.active());
        assertEquals(20, drep.lastActiveEpoch());

        Response distResponse = resource.getDRepDistribution(drepId, 42);
        assertEquals(200, distResponse.getStatus());
        DRepDistributionDto distribution = (DRepDistributionDto) distResponse.getEntity();
        assertEquals(CardanoBech32Ids.drepHex(0, DREP_HASH), distribution.hex());
        assertEquals("123456", distribution.amount());
    }

    @Test
    void listDRepsFiltersActiveStatus() {
        GovernanceResource resource = resourceWith(readProvider());

        Response response = resource.listDReps("active", 1, 20, "asc");

        assertEquals(200, response.getStatus());
        assertEquals(1, ((List<?>) response.getEntity()).size());
        DRepListDto drep = (DRepListDto) ((List<?>) response.getEntity()).get(0);
        assertTrue(drep.drepId().startsWith("drep1"));
        assertEquals(CardanoBech32Ids.drepHex(0, DREP_HASH), drep.hex());
    }

    @Test
    void invalidProposalHashReturns400() {
        GovernanceResource resource = resourceWith(readProvider());

        Response response = resource.getProposal("bad", 0);

        assertEquals(400, response.getStatus());
    }

    private static GovernanceResource resourceWith(LedgerStateProvider ledgerStateProvider) {
        GovernanceResource resource = new GovernanceResource();
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

    private static LedgerStateProvider readProvider() {
        AccountStateReadStore.GovernanceProposal proposal = new AccountStateReadStore.GovernanceProposal(
                TX_HASH,
                1,
                CardanoBech32Ids.govActionId(TX_HASH, 1),
                "active",
                "PARAMETER_CHANGE_ACTION",
                BigInteger.valueOf(1_000_000),
                "stake_test1upqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqe5en6l",
                10,
                16,
                null,
                null,
                1_000L);
        AccountStateReadStore.DRepInfo drep = new AccountStateReadStore.DRepInfo(
                0,
                DREP_HASH,
                BigInteger.valueOf(500_000_000),
                null,
                null,
                20,
                null,
                50,
                true,
                2_000L,
                10,
                null);
        return (LedgerStateProvider) Proxy.newProxyInstance(LedgerStateProvider.class.getClassLoader(),
                new Class<?>[]{LedgerStateProvider.class, AccountStateReadStore.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "listGovernanceProposals" -> List.of(proposal);
                    case "getGovernanceProposal" -> Optional.of(proposal);
                    case "getGovernanceProposalVotes" -> List.of(
                            new AccountStateReadStore.GovernanceVote(2, DREP_HASH, 1),
                            new AccountStateReadStore.GovernanceVote(4, POOL_HASH, 0));
                    case "listDReps" -> List.of(drep);
                    case "getDRep" -> Optional.of(drep);
                    case "getLatestDRepDistributionEpoch" -> Optional.of(42);
                    case "getDRepDistribution" -> Optional.of(BigInteger.valueOf(123456));
                    case "toString" -> "TestGovernanceReadProvider";
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
