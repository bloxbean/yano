package org.yanoproject.app.api.tx;

import org.yanoproject.api.MempoolAdminGateway;
import org.yanoproject.api.config.YanoPropertyKeys;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.yanoproject.app.api.ApiGroup;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.extensions.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Extension(name = ApiGroup.ADMIN, value = "")
@Path("admin/mempool/transactions")
@Produces(MediaType.APPLICATION_JSON)
public class MempoolAdminResource {
    private static final Logger LOG = LoggerFactory.getLogger(MempoolAdminResource.class);
    private static final Pattern HASH = Pattern.compile("[0-9a-fA-F]{64}");
    static final String WARNING = "Local eviction only: queued or relayed transactions may still propagate "
            + "and confirm, peers may announce them again, and a locally selected block may still include them. "
            + "This does not cancel a transaction on the network.";

    @Inject
    MempoolAdminGateway gateway;

    @ConfigProperty(name = YanoPropertyKeys.Tx.MEMPOOL_ADMIN_ENABLED, defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = YanoPropertyKeys.Tx.MEMPOOL_ADMIN_API_KEY)
    Optional<String> apiKey;

    @RegisterForReflection
    public record EvictionResponse(String txHash, List<String> evictedTxHashes, String warning) { }

    @DELETE
    @Path("{txHash}")
    @Operation(summary = "Evict a local mempool transaction and its pending descendants",
            description = "Requires explicit enablement and X-Admin-API-Key. Does not cancel network submission.")
    public Response evict(@PathParam("txHash") String txHash,
                          @HeaderParam("X-Admin-API-Key") String suppliedKey) {
        if (!enabled) return error(404, "Mempool administration is disabled");
        if (apiKey.isEmpty() || apiKey.get().trim().length() < 32) {
            return error(503, "Mempool administration requires an admin API key of at least 32 characters");
        }
        if (suppliedKey == null || !MessageDigest.isEqual(
                apiKey.get().getBytes(StandardCharsets.UTF_8), suppliedKey.getBytes(StandardCharsets.UTF_8))) {
            return error(401, "Missing or invalid admin API key");
        }
        if (txHash == null || !HASH.matcher(txHash).matches()) {
            return error(400, "Transaction hash must contain exactly 64 hexadecimal characters");
        }
        String normalizedHash = txHash.toLowerCase(Locale.ROOT);
        try {
            List<String> evicted = gateway.evictTransaction(normalizedHash);
            LOG.info("Administrative local mempool eviction: requested={} count={} sample={}",
                    normalizedHash, evicted.size(), evicted.stream().limit(10).toList());
            return Response.ok(new EvictionResponse(normalizedHash, evicted, WARNING)).build();
        } catch (UnsupportedOperationException | IllegalStateException e) {
            return error(503, "Mempool administration is currently unavailable");
        }
    }

    private static Response error(int status, String message) {
        return Response.status(status).entity(Map.of("error", message)).build();
    }
}
