package com.bloxbean.cardano.yano.api.plugin.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Host/library port for the validated ADR-011.3 domain route registry.
 *
 * <p>Adapters resolve access before dispatch. ADR-011.3 v1 reserves
 * {@link DomainApiAccess#INTERNAL} for inventory and the gateway rejects it;
 * there is no alternate public library dispatch path. Implementations own
 * route matching, admission, callback lifetime, timeouts and response
 * bounds.</p>
 */
public interface DomainApiGateway {

    /** Deterministic, secret-free route inventory. */
    List<DomainApiRouteInfo> routes();

    /** Access class of the matching route, or empty when no route matches. */
    Optional<DomainApiAccess> access(
            String bundleId,
            DomainHttpMethod method,
            String relativePath
    );

    /**
     * Invoke one matching READ or PRIVILEGED route through the host-owned
     * bounded dispatcher. INTERNAL routes are not dispatchable in v1.
     */
    DomainApiResponse dispatch(
            String bundleId,
            DomainHttpMethod method,
            String relativePath,
            Map<String, List<String>> queryParameters,
            byte[] body
    );

    static DomainApiGateway empty() {
        return Empty.INSTANCE;
    }

    enum Empty implements DomainApiGateway {
        INSTANCE;

        @Override
        public List<DomainApiRouteInfo> routes() {
            return List.of();
        }

        @Override
        public Optional<DomainApiAccess> access(
                String bundleId,
                DomainHttpMethod method,
                String relativePath
        ) {
            return Optional.empty();
        }

        @Override
        public DomainApiResponse dispatch(
                String bundleId,
                DomainHttpMethod method,
                String relativePath,
                Map<String, List<String>> queryParameters,
                byte[] body
        ) {
            throw new DomainApiException(
                    DomainApiException.Code.NOT_FOUND,
                    "No domain API route matches the request");
        }
    }
}
