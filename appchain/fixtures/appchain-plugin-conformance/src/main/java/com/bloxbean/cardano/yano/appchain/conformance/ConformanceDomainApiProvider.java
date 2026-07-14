package com.bloxbean.cardano.yano.appchain.conformance;

import com.bloxbean.cardano.yano.api.appchain.AppQueryResult;
import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApi;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiAccess;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiException;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiMediaType;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiProvider;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRequest;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiResponse;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRoute;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainHttpMethod;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Read-only domain API fixture used by packaged JVM and native smoke tests. */
public final class ConformanceDomainApiProvider implements DomainApiProvider {
    public static final String ID = NativePluginConformanceVerifier.BUNDLE_ID;

    @Override
    public String id() {
        ConformanceTcclProbe.requireCatalogFacade("domain-api provider identity");
        return ID;
    }

    @Override
    public DomainApi create(DomainApiContext context) {
        DomainApi api = new DomainApi() {
            private final AtomicBoolean firstCallback = new AtomicBoolean(true);

            @Override
            public List<DomainApiRoute> routes() {
                ConformanceTcclProbe.productCallback(firstCallback,
                        "domain-api route publication");
                return List.of(
                        new DomainApiRoute("status", DomainHttpMethod.GET,
                                "status", DomainApiAccess.READ),
                        new DomainApiRoute("query", DomainHttpMethod.POST,
                                "query/{path}", DomainApiAccess.READ),
                        new DomainApiRoute("operator", DomainHttpMethod.GET,
                                "operator", DomainApiAccess.PRIVILEGED),
                        new DomainApiRoute("internal", DomainHttpMethod.GET,
                                "internal", DomainApiAccess.INTERNAL));
            }

            @Override
            public DomainApiResponse handle(DomainApiRequest request) {
                ConformanceTcclProbe.productCallback(firstCallback,
                        "domain-api request handling");
                return switch (request.routeId()) {
                    case "status" -> json(200, "{\"status\":\"ok\"}");
                    case "operator" -> json(200, "{\"operator\":true}");
                    case "internal" -> json(200, "{\"internal\":true}");
                    case "query" -> query(context, request);
                    default -> json(404, "{\"error\":\"not-found\"}");
                };
            }

            @Override
            public void close() {
                ConformanceTcclProbe.productCallback(firstCallback,
                        "domain-api close");
            }
        };
        ConformanceTcclProbe.poisonProviderCallback();
        return api;
    }

    private static DomainApiResponse query(DomainApiContext context,
                                           DomainApiRequest request) {
        try {
            String chainId = context.queryService().chainIds().getFirst();
            AppQueryResult result = context.queryService().query(
                    chainId, request.pathParameters().get("path"), request.body());
            String body = "{\"chainId\":" + jsonString(result.chainId())
                    + ",\"height\":" + result.committedHeight()
                    + ",\"stateRoot\":"
                    + jsonString(HexFormat.of().formatHex(result.stateRoot()))
                    + ",\"payloadHex\":"
                    + jsonString(HexFormat.of().formatHex(result.payload()))
                    + "}";
            return json(200, body);
        } catch (AppQueryException failure) {
            throw translateQueryFailure(failure);
        }
    }

    /** Translate only stable reason codes and never expose a query failure message. */
    private static DomainApiException translateQueryFailure(AppQueryException failure) {
        DomainApiException.Code code = switch (failure.code()) {
            case INVALID_REQUEST, REQUEST_TOO_LARGE -> DomainApiException.Code.INVALID_REQUEST;
            case UNSUPPORTED -> DomainApiException.Code.NOT_FOUND;
            case BUSY -> DomainApiException.Code.BUSY;
            case TIMEOUT -> DomainApiException.Code.TIMEOUT;
            case RESULT_TOO_LARGE -> DomainApiException.Code.RESULT_TOO_LARGE;
            case UNAVAILABLE -> DomainApiException.Code.UNAVAILABLE;
            case FAILED -> DomainApiException.Code.FAILED;
        };
        return new DomainApiException(code, "Conformance query failed", failure);
    }

    /** Minimal deterministic JSON string encoder for caller-controlled text. */
    private static String jsonString(String value) {
        StringBuilder encoded = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> encoded.append("\\\"");
                case '\\' -> encoded.append("\\\\");
                case '\b' -> encoded.append("\\b");
                case '\f' -> encoded.append("\\f");
                case '\n' -> encoded.append("\\n");
                case '\r' -> encoded.append("\\r");
                case '\t' -> encoded.append("\\t");
                default -> {
                    if (character >= 0x20 && character <= 0x7e) {
                        encoded.append(character);
                    } else {
                        encoded.append("\\u");
                        for (int shift = 12; shift >= 0; shift -= 4) {
                            encoded.append(Character.forDigit(
                                    (character >>> shift) & 0x0f, 16));
                        }
                    }
                }
            }
        }
        return encoded.append('"').toString();
    }

    private static DomainApiResponse json(int status, String body) {
        return new DomainApiResponse(status, DomainApiMediaType.JSON,
                body.getBytes(StandardCharsets.UTF_8));
    }
}
