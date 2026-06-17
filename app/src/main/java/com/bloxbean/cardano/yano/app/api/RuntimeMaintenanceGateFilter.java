package com.bloxbean.cardano.yano.app.api;

import com.bloxbean.cardano.yano.runtime.maintenance.RuntimeMaintenanceGate;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;
import java.util.Locale;

/**
 * Holds a storage read lease for normal REST requests so devnet maintenance
 * operations cannot replace storage handles while a request is reading them.
 */
@Provider
@Priority(Priorities.AUTHORIZATION)
public class RuntimeMaintenanceGateFilter implements ContainerRequestFilter, ContainerResponseFilter {
    private static final String READ_LEASE_PROPERTY =
            RuntimeMaintenanceGateFilter.class.getName() + ".readLease";

    @Inject
    RuntimeMaintenanceGate maintenanceGate;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (isExclusiveMaintenanceEndpoint(
                requestContext.getMethod(), requestContext.getUriInfo().getPath(false))) {
            return;
        }

        String operation = requestContext.getMethod() + " " + requestContext.getUriInfo().getPath(false);
        requestContext.setProperty(READ_LEASE_PROPERTY, maintenanceGate.enterRead(operation));
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext)
            throws IOException {
        Object lease = requestContext.getProperty(READ_LEASE_PROPERTY);
        if (lease instanceof RuntimeMaintenanceGate.ReadLease readLease) {
            readLease.close();
            requestContext.removeProperty(READ_LEASE_PROPERTY);
        }
    }

    static boolean isExclusiveMaintenanceEndpoint(String method, String requestPath) {
        boolean post = "POST".equalsIgnoreCase(method);
        boolean delete = "DELETE".equalsIgnoreCase(method);
        String path = normalizePath(requestPath);
        return (post && (path.contains("devnet/restore/")
                || path.endsWith("devnet/rollback")
                || path.endsWith("devnet/snapshot")
                || path.endsWith("devnet/fund")
                || path.endsWith("devnet/time/advance")
                || path.endsWith("devnet/epochs/shift")
                || path.endsWith("devnet/epochs/catch-up")
                || path.endsWith("node/start")
                || path.endsWith("node/stop")
                || path.endsWith("node/recover")))
                || (delete && path.contains("devnet/snapshot/"));
    }

    private static String normalizePath(String requestPath) {
        String path = requestPath == null ? "" : requestPath.toLowerCase(Locale.ROOT);
        while (path.endsWith("/") && !path.isEmpty()) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }
}
