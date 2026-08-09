package com.bloxbean.cardano.yano.app.api.plugin;

import com.bloxbean.cardano.yano.api.plugin.ui.UiExtensionAssetResponse;
import com.bloxbean.cardano.yano.api.plugin.ui.UiExtensionGateway;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Host-owned catalog and immutable asset namespace for sandboxed plugin UIs. */
@Path("/ui-plugins")
public final class UiExtensionResource {
    private static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; "
            + "img-src 'self' data:; font-src 'self'; connect-src 'none'; form-action 'none'; "
            + "base-uri 'none'; frame-ancestors 'self'";

    @Inject
    UiExtensionGateway extensions;

    @GET
    @Path("/catalog")
    @Produces(MediaType.APPLICATION_JSON)
    public Response catalog() {
        return Response.ok(extensions.catalog())
                .header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .build();
    }

    @GET
    @Path("/{bundleId}/{assetsDigest}/{assetPath:.+}")
    public Response asset(
            @PathParam("bundleId") String bundleId,
            @PathParam("assetsDigest") String assetsDigest,
            @PathParam("assetPath") String assetPath
    ) {
        UiExtensionAssetResponse asset = extensions.asset(bundleId, assetsDigest, assetPath)
                .orElseThrow(NotFoundException::new);
        return Response.ok(asset.bytes(), asset.mediaType())
                .header("ETag", "\"sha256-" + asset.sha256Hex() + "\"")
                .header("Cache-Control", "public, max-age=31536000, immutable")
                .header("Content-Security-Policy", CSP)
                .header("Referrer-Policy", "no-referrer")
                .header("X-Content-Type-Options", "nosniff")
                .header("Cross-Origin-Resource-Policy", "same-origin")
                .build();
    }
}
