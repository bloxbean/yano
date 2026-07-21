package com.example.appchain;

import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.AppQueryResult;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApi;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiAccess;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiException;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiMediaType;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiProvider;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRequest;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiResponse;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRoute;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRouteSet;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainHttpMethod;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bundle-owned domain API exposed only through Yano's host router and auth policy. */
public final class CounterDomainApiProvider implements DomainApiProvider {
    public static final String BUNDLE_ID = "com.example.appchain.counter";

    @Override
    public String id() {
        return BUNDLE_ID;
    }

    @Override
    public DomainApi create(DomainApiContext context) {
        // Production ADR-011.3 v1 intentionally supplies empty bundleConfig().
        // Use only the constrained query facade; do not retain runtime internals.
        return new CounterDomainApi(context);
    }

    private static final class CounterDomainApi implements DomainApi {
        private static final List<DomainApiRoute> ROUTES =
                DomainApiRouteSet.validateAndOrder(List.of(
                        new DomainApiRoute("status", DomainHttpMethod.GET,
                                "status", DomainApiAccess.READ),
                        new DomainApiRoute("counter.read", DomainHttpMethod.GET,
                                "counters/{key}", DomainApiAccess.READ),
                        new DomainApiRoute("operator", DomainHttpMethod.GET,
                                "operator", DomainApiAccess.PRIVILEGED),
                        new DomainApiRoute("internal", DomainHttpMethod.GET,
                                "internal", DomainApiAccess.INTERNAL)));

        private final DomainApiContext context;
        private final AtomicBoolean closed = new AtomicBoolean();

        private CounterDomainApi(DomainApiContext context) {
            this.context = context;
        }

        @Override
        public List<DomainApiRoute> routes() {
            return ROUTES;
        }

        @Override
        public DomainApiResponse handle(DomainApiRequest request) {
            if (closed.get()) {
                throw new DomainApiException(DomainApiException.Code.UNAVAILABLE,
                        "counter domain API is stopped");
            }
            return switch (request.routeId()) {
                case "status" -> json(200, "{\"status\":\"ok\"}");
                case "counter.read" -> readCounter(request);
                case "operator" -> json(200, "{\"operator\":true}");
                // INTERNAL is inventory-only and is never dispatched in v1.
                default -> throw new DomainApiException(DomainApiException.Code.NOT_FOUND,
                        "counter route is not available");
            };
        }

        private DomainApiResponse readCounter(DomainApiRequest request) {
            List<String> chainIds = request.queryParameters().get("chain");
            if (chainIds == null || chainIds.size() != 1) {
                throw new DomainApiException(DomainApiException.Code.INVALID_REQUEST,
                        "exactly one chain query parameter is required");
            }
            try {
                AppQueryResult result = context.queryService().query(
                        chainIds.getFirst(),
                        "counter/read",
                        request.pathParameters().get("key").getBytes(StandardCharsets.US_ASCII));
                String body = "{\"chainId\":" + JsonSupport.string(result.chainId())
                        + ",\"height\":" + result.committedHeight()
                        + ",\"stateRoot\":"
                        + JsonSupport.string(HexFormat.of().formatHex(result.stateRoot()))
                        + ",\"valueHex\":"
                        + JsonSupport.string(HexFormat.of().formatHex(result.payload()))
                        + "}";
                return json(200, body);
            } catch (AppQueryException failure) {
                throw translateQueryFailure(failure);
            }
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    /** Translate stable codes only; never copy a plugin/runtime failure message to HTTP. */
    static DomainApiException translateQueryFailure(AppQueryException failure) {
        DomainApiException.Code code = switch (failure.code()) {
            case INVALID_REQUEST, REQUEST_TOO_LARGE -> DomainApiException.Code.INVALID_REQUEST;
            case UNSUPPORTED -> DomainApiException.Code.NOT_FOUND;
            case BUSY -> DomainApiException.Code.BUSY;
            case TIMEOUT -> DomainApiException.Code.TIMEOUT;
            case RESULT_TOO_LARGE -> DomainApiException.Code.RESULT_TOO_LARGE;
            case UNAVAILABLE -> DomainApiException.Code.UNAVAILABLE;
            case FAILED -> DomainApiException.Code.FAILED;
        };
        return new DomainApiException(code, "counter query failed", failure);
    }

    private static DomainApiResponse json(int status, String body) {
        return new DomainApiResponse(status, DomainApiMediaType.JSON,
                body.getBytes(StandardCharsets.UTF_8));
    }
}
