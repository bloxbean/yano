package com.bloxbean.cardano.yano.app.api.wallet;

import com.bloxbean.cardano.yano.api.LedgerQuery;
import com.bloxbean.cardano.yano.api.ChainQuery;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.utxo.model.AssetAmount;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;
import com.bloxbean.cardano.yano.api.wallet.AddressFirstSeen;
import com.bloxbean.cardano.yano.api.wallet.WalletChainPoint;
import com.bloxbean.cardano.yano.api.wallet.WalletCredential;
import com.bloxbean.cardano.yano.api.wallet.WalletIndexCoverage;
import com.bloxbean.cardano.yano.api.wallet.WalletIndexUnavailableException;
import com.bloxbean.cardano.yano.api.wallet.WalletScan;
import com.bloxbean.cardano.yano.api.wallet.WalletScanEvent;
import com.bloxbean.cardano.yano.api.wallet.WalletScanRequest;
import com.bloxbean.cardano.yano.api.wallet.WalletScanRollbackException;
import com.bloxbean.cardano.yano.app.api.ApiGroup;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.common.annotation.Blocking;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.extensions.Extension;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.Semaphore;

@Path("/scan")
@Blocking
@ApplicationScoped
@Extension(name = ApiGroup.CORE, value = "")
@RegisterForReflection(targets = {WalletScanRequest.class, WalletScanEvent.class,
        WalletCredential.class, WalletChainPoint.class, WalletIndexCoverage.class,
        AddressFirstSeen.class, Utxo.class, Outpoint.class, AssetAmount.class})
public class WalletScanResource {
    @Inject LedgerQuery ledgerQuery;
    @Inject ChainQuery chainQuery;
    @Inject ObjectMapper mapper;
    @ConfigProperty(name = "yano.scan.max-concurrent", defaultValue = "2")
    int maxConcurrent = 2;
    private Semaphore permits;

    @PostConstruct
    void initialize() {
        if (maxConcurrent < 1 || maxConcurrent > 16) throw new IllegalArgumentException("Scan concurrency must be between 1 and 16");
        permits = new Semaphore(maxConcurrent);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces("application/x-ndjson")
    public Response scan(WalletScanRequest request) {
        var state = ledgerQuery.getUtxoState();
        if (state == null || !state.isEnabled()) return error(503, "Wallet scan requires UTxO state");
        if (request == null) return error(400, "Scan request required");
        // Preflight returns HTTP errors before streaming. No native iterator survives this call.
        try (WalletScan ignored = state.openWalletScan(request)) {
            // A new session below revalidates coverage after any intervening rollback.
        } catch (WalletScanRollbackException rollback) {
            return error(409, rollback.getMessage());
        } catch (WalletIndexUnavailableException unavailable) {
            return Response.status(503).type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("error", unavailable.getMessage(), "coverage", unavailable.coverage())).build();
        } catch (IllegalArgumentException invalid) {
            return error(400, invalid.getMessage());
        } catch (IllegalStateException unavailable) {
            return error(503, unavailable.getMessage());
        }
        StreamingOutput stream = output -> {
            if (!permits.tryAcquire()) throw new WebApplicationException(error(503, "Scan concurrency limit reached"));
            try (WalletScan scan = state.openWalletScan(request)) {
                while (!scan.finished()) {
                    for (WalletScanEvent event : scan.next()) write(output, event);
                    output.flush();
                }
            } catch (WalletScanRollbackException rollback) {
                write(output, new WalletScanEvent("rollback", null, null, null, null, null, null, rollback.getMessage()));
            } catch (IllegalStateException failure) {
                write(output, new WalletScanEvent("error", null, null, null, null, null, null, failure.getMessage()));
            } finally {
                permits.release();
            }
        };
        return Response.ok(stream).type("application/x-ndjson").header("Cache-Control", "no-store").build();
    }

    private void write(OutputStream output, WalletScanEvent event) throws IOException {
        ObjectNode json = mapper.valueToTree(event);
        if ("transaction".equals(event.type())) json.put("blockTime", ledgerQuery.slotToUnixTime(event.point().slot()));
        if ("ready".equals(event.type()) && chainQuery != null) {
            var tip = chainQuery.getLocalTip();
            WalletChainPoint point = tip == null ? WalletChainPoint.ORIGIN
                    : new WalletChainPoint(tip.getBlockNumber(), tip.getSlot(), HexUtil.encodeHexString(tip.getBlockHash()));
            json.set("liveTip", mapper.valueToTree(point));
        }
        output.write(mapper.writeValueAsBytes(json));
        output.write('\n');
    }

    private static Response error(int status, String message) {
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(Map.of("error", message)).build();
    }
}
