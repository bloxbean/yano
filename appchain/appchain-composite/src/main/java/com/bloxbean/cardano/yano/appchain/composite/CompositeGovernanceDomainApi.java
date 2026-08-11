package com.bloxbean.cardano.yano.appchain.composite;

import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApi;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiAccess;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiException;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiMediaType;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRequest;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiResponse;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRoute;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainHttpMethod;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeProfileGovernanceV1;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Framework-neutral domain API; the host owns routing and authentication. */
final class CompositeGovernanceDomainApi implements DomainApi {
    private static final String STATUS = "profile-governance-status";
    private static final String COMMAND = "profile-governance-command";
    private static final List<DomainApiRoute> ROUTES = List.of(
            new DomainApiRoute(
                    STATUS, DomainHttpMethod.GET,
                    "chains/{chain_id}/profile-governance",
                    DomainApiAccess.READ),
            new DomainApiRoute(
                    COMMAND, DomainHttpMethod.POST,
                    "chains/{chain_id}/profile-governance/commands",
                    DomainApiAccess.PRIVILEGED));

    private final DomainApiContext context;

    CompositeGovernanceDomainApi(DomainApiContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public List<DomainApiRoute> routes() {
        return ROUTES;
    }

    @Override
    public DomainApiResponse handle(DomainApiRequest request) {
        Objects.requireNonNull(request, "request");
        if (!request.pathParameters().keySet().equals(Set.of("chain_id"))) {
            throw invalid();
        }
        String chainId = request.pathParameters().get("chain_id");
        if (!context.queryService().chainIds().contains(chainId)) {
            return json(404, "{\"error\":\"chain-not-found\"}");
        }
        return switch (request.routeId()) {
            case STATUS -> status(request, chainId);
            case COMMAND -> command(request, chainId);
            default -> throw invalid();
        };
    }

    private DomainApiResponse status(DomainApiRequest request, String chainId) {
        if (request.method() != DomainHttpMethod.GET
                || !request.queryParameters().isEmpty()
                || request.body().length != 0) {
            throw invalid();
        }
        try {
            byte[] status = context.queryService().query(
                    chainId, "composite/governance-v1", new byte[0]).payload();
            return new DomainApiResponse(
                    200, DomainApiMediaType.OCTET_STREAM, status);
        } catch (AppQueryException unavailable) {
            if (unavailable.code() == AppQueryException.Code.UNSUPPORTED) {
                return json(404, "{\"error\":\"capability-unavailable\"}");
            }
            throw new DomainApiException(
                    DomainApiException.Code.FAILED,
                    "Composite governance query failed", unavailable);
        }
    }

    private DomainApiResponse command(DomainApiRequest request, String chainId) {
        if (request.method() != DomainHttpMethod.POST
                || !request.queryParameters().keySet().stream()
                        .allMatch("dry-run"::equals)) {
            throw invalid();
        }
        byte[] command = request.body();
        if (command.length == 0
                || command.length > CompositeProfileGovernanceV1.MAX_COMMAND_BYTES) {
            throw invalid();
        }
        try {
            CompositeProfileGovernanceV1.decode(command);
        } catch (IllegalArgumentException malformed) {
            throw invalid();
        }
        boolean dryRun = booleanParameter(request.queryParameters(), "dry-run");
        if (dryRun) {
            context.privilegedSystemMessages().validate(
                    chainId, CompositeProfileGovernanceV1.TOPIC, command);
            return json(200, "{\"validated\":true}");
        }
        String messageId = context.privilegedSystemMessages().submit(
                chainId, CompositeProfileGovernanceV1.TOPIC, command);
        return json(200, "{\"messageId\":\"" + messageId + "\"}");
    }

    private static boolean booleanParameter(
            Map<String, List<String>> parameters,
            String name
    ) {
        List<String> values = parameters.get(name);
        if (values == null) {
            return false;
        }
        if (values.size() != 1
                || !(values.getFirst().equals("true")
                || values.getFirst().equals("false"))) {
            throw invalid();
        }
        return Boolean.parseBoolean(values.getFirst());
    }

    private static DomainApiException invalid() {
        return new DomainApiException(
                DomainApiException.Code.INVALID_REQUEST,
                "Invalid composite governance request");
    }

    private static DomainApiResponse json(int status, String value) {
        return new DomainApiResponse(status, DomainApiMediaType.JSON,
                value.getBytes(StandardCharsets.UTF_8));
    }
}
