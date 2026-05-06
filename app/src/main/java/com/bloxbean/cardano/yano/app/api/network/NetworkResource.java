package com.bloxbean.cardano.yano.app.api.network;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yano.api.NodeAPI;
import com.bloxbean.cardano.yano.api.account.AccountStateReadStore;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.api.model.GenesisParameters;
import com.bloxbean.cardano.yano.app.api.EpochUtil;
import com.bloxbean.cardano.yano.app.api.network.dto.NetworkDto;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.Map;

@Path("network")
@Produces(MediaType.APPLICATION_JSON)
public class NetworkResource {
    private static final Logger log = LoggerFactory.getLogger(NetworkResource.class);

    @Inject
    NodeAPI nodeAPI;

    @GET
    public Response getNetwork() {
        LedgerStateProvider ledgerStateProvider = nodeAPI.getLedgerStateProvider();
        if (ledgerStateProvider == null || !ledgerStateProvider.isAdaPotTrackingEnabled()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "AdaPot tracking is not enabled"))
                    .build();
        }

        GenesisParameters genesis = nodeAPI.getGenesisParameters();
        if (genesis == null || genesis.maxLovelaceSupply() == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "Genesis parameters not available"))
                    .build();
        }

        try {
            return ledgerStateProvider.getLatestAdaPot(currentEpoch())
                    .map(adaPot -> {
                        BigInteger maxSupply = new BigInteger(genesis.maxLovelaceSupply());
                        BigInteger totalSupply = maxSupply.subtract(adaPot.reserves());
                        String activeStake = activeStake(ledgerStateProvider);
                        return Response.ok(new NetworkDto(
                                new NetworkDto.SupplyDto(
                                        maxSupply.toString(),
                                        totalSupply.toString(),
                                        adaPot.treasury().toString(),
                                        adaPot.reserves().toString()),
                                activeStake != null ? new NetworkDto.StakeDto(activeStake) : null
                        )).build();
                    })
                    .orElseGet(() -> Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of("error", "No AdaPot data available"))
                            .build());
        } catch (IllegalStateException | NumberFormatException e) {
            log.warn("Network state read failed: {}", e.getMessage());
            log.debug("Network state read failed details", e);
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "Network state read failed"))
                    .build();
        }
    }

    private String activeStake(LedgerStateProvider ledgerStateProvider) {
        if (!(ledgerStateProvider instanceof AccountStateReadStore readStore)) return null;
        int epoch = ledgerStateProvider.getLatestSnapshotEpoch();
        if (epoch < 0) return null;
        return readStore.getTotalActiveStake(epoch).map(BigInteger::toString).orElse(null);
    }

    private int currentEpoch() {
        ChainState cs = nodeAPI.getChainState();
        ChainTip tip = cs != null ? cs.getTip() : null;
        if (tip == null) return 0;
        return EpochUtil.slotToEpoch(tip.getSlot(), nodeAPI.getConfig());
    }
}
