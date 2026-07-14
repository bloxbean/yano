package com.bloxbean.cardano.yano.app.api.appchain;

import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiAccess;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiException;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiGateway;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiMediaType;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRequest;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiResponse;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRoute;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainHttpMethod;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Host-owned ADR-011.3 adapter for manifested domain API routes.
 *
 * <p>Bundles contribute only framework-neutral route declarations and
 * callbacks. This adapter retains ownership of the public namespace,
 * authentication, request bounds, media types and failure redaction.</p>
 */
@Path("plugins/{bundleId}")
public class PluginDomainResource {

    @Inject
    DomainApiGateway domainApis;

    @Inject
    ObjectMapper objectMapper;

    @GET
    @Path("{path: .+}")
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_OCTET_STREAM})
    public Response get(@Encoded @PathParam("bundleId") String bundleId,
                        @Encoded @PathParam("path") String path,
                        @Context UriInfo uriInfo) {
        return dispatch(bundleId, DomainHttpMethod.GET, path, uriInfo, new byte[0]);
    }

    @POST
    @Path("{path: .+}")
    @Consumes({MediaType.APPLICATION_JSON, MediaType.APPLICATION_OCTET_STREAM})
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_OCTET_STREAM})
    public Response post(@Encoded @PathParam("bundleId") String bundleId,
                         @Encoded @PathParam("path") String path,
                         @Context UriInfo uriInfo,
                         byte[] body) {
        byte[] requestBody = body != null ? body : new byte[0];
        if (requestBody.length > DomainApiRequest.MAX_BODY_BYTES) {
            return error(413, "REQUEST_TOO_LARGE",
                    "Domain API request body exceeds 65536 bytes");
        }
        return dispatch(bundleId, DomainHttpMethod.POST, path, uriInfo, requestBody);
    }

    private Response dispatch(String bundleId,
                              DomainHttpMethod method,
                              String path,
                              UriInfo uriInfo,
                              byte[] body) {
        try {
            String validatedPath = DomainApiRoute.validatePath(path);
            Map<String, List<String>> queryParameters = queryParameters(uriInfo);
            DomainApiAccess access = domainApis.access(bundleId, method, validatedPath)
                    .orElseThrow(() -> new DomainApiException(
                            DomainApiException.Code.NOT_FOUND,
                            "No domain API route matches the request"));
            // Defense in depth: INTERNAL is never an HTTP-visible capability,
            // including when this resource is invoked without the auth filter.
            if (access == DomainApiAccess.INTERNAL) {
                throw new DomainApiException(DomainApiException.Code.NOT_FOUND,
                        "No domain API route matches the request");
            }
            DomainApiResponse response = domainApis.dispatch(
                    bundleId, method, validatedPath, queryParameters, body);
            if (response.mediaType() == DomainApiMediaType.JSON) {
                requireSingleJsonValue(response.body());
            }
            return Response.status(response.status())
                    .type(response.mediaType().value())
                    .entity(response.body())
                    .build();
        } catch (IllegalArgumentException invalid) {
            return error(400, "INVALID_REQUEST", "Invalid domain API request");
        } catch (DomainApiException failure) {
            int status = switch (failure.code()) {
                case INVALID_REQUEST -> 400;
                case NOT_FOUND -> 404;
                case BUSY -> 429;
                case TIMEOUT -> 504;
                case RESULT_TOO_LARGE -> 502;
                case UNAVAILABLE -> 503;
                case FAILED -> 500;
            };
            String message = failure.code() == DomainApiException.Code.FAILED
                    ? "Domain API execution failed" : failure.getMessage();
            return error(status, failure.code().name(), message);
        } catch (RuntimeException unexpected) {
            return error(500, "FAILED", "Domain API execution failed");
        }
    }

    private void requireSingleJsonValue(byte[] body) {
        try (JsonParser parser = objectMapper.getFactory().createParser(body)) {
            JsonNode value = objectMapper.readTree(parser);
            if (value == null || parser.nextToken() != null) {
                throw new DomainApiException(DomainApiException.Code.FAILED,
                        "Domain API returned invalid JSON");
            }
        } catch (IOException invalidJson) {
            throw new DomainApiException(DomainApiException.Code.FAILED,
                    "Domain API returned invalid JSON", invalidJson);
        }
    }

    private static Map<String, List<String>> queryParameters(UriInfo uriInfo) {
        MultivaluedMap<String, String> source = uriInfo.getQueryParameters(true);
        Map<String, List<String>> copy = new LinkedHashMap<>();
        source.forEach((name, values) -> copy.put(name,
                List.copyOf(new ArrayList<>(values))));
        return Map.copyOf(copy);
    }

    private static Response error(int status, String code, String message) {
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("code", code, "error", message))
                .build();
    }
}
