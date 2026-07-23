package com.bloxbean.cardano.yano.appchain.evidence.profile;

import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.AppQueryResult;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApi;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiAccess;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiException;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiMediaType;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRequest;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiResponse;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRoute;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainHttpMethod;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeCommitmentV1;
import com.bloxbean.cardano.yano.appchain.evidence.profile.contracts.RoleEvidenceKeys;
import com.bloxbean.cardano.yano.appchain.roles.RoleApprovalsDomainApi;
import com.bloxbean.cardano.yano.appchain.roles.RoleAwareApprovalsComponent;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowIdentifiers;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Generic role projections plus the evidence profile's approval-link projection. */
public final class RoleEvidenceDomainApi implements DomainApi {
    private static final String EVIDENCE_APPROVAL = "get-evidence-approval";
    private static final DomainApiRoute EVIDENCE_ROUTE = new DomainApiRoute(
            EVIDENCE_APPROVAL, DomainHttpMethod.GET,
            "evidence/{evidence_id}/versions/{version}/approval", DomainApiAccess.READ);

    private final DomainApiContext context;
    private final RoleApprovalsDomainApi generic;
    private final List<DomainApiRoute> routes;

    RoleEvidenceDomainApi(DomainApiContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.generic = new RoleApprovalsDomainApi(context, RoleEvidenceStateMachineProvider.ID);
        this.routes = java.util.stream.Stream.concat(
                generic.routes().stream(), java.util.stream.Stream.of(EVIDENCE_ROUTE)).toList();
    }

    @Override public List<DomainApiRoute> routes() { return routes; }

    @Override
    public DomainApiResponse handle(DomainApiRequest request) {
        if (request != null && RoleApprovalsDomainApi.supports(request.routeId())) {
            return generic.handle(request);
        }
        if (request == null || !EVIDENCE_APPROVAL.equals(request.routeId())
                || request.method() != DomainHttpMethod.GET
                || !request.queryParameters().keySet().stream()
                        .allMatch("chain"::equals)
                || !request.pathParameters().keySet()
                        .equals(java.util.Set.of("evidence_id", "version"))) {
            throw invalid();
        }
        String evidenceId;
        try {
            evidenceId = RoleWorkflowIdentifiers.id(
                    request.pathParameters().get("evidence_id"), "evidenceId");
        } catch (RuntimeException invalidIdentifier) {
            throw invalid();
        }
        long version = positive(request.pathParameters().get("version"));
        String chain = chain(request.queryParameters());
        AppQueryResult result = query(chain, evidenceId + "@" + version);
        if (result.payload().length == 0) return json(404, "{\"error\":\"not-found\"}");
        String proposalId = new String(result.payload(), StandardCharsets.US_ASCII);
        try {
            RoleWorkflowIdentifiers.id(proposalId, "proposalId");
        } catch (RuntimeException corrupt) {
            throw new DomainApiException(DomainApiException.Code.FAILED,
                    "Role workflow query returned an invalid record", corrupt);
        }
        byte[] proofKey = CompositeCommitmentV1.componentKey(
                RoleAwareApprovalsComponent.COMPONENT_ID,
                RoleEvidenceKeys.evidenceApproval(evidenceId, version));
        String record = "{\"type\":\"evidence-approval\",\"evidenceId\":"
                + string(evidenceId) + ",\"businessVersion\":" + version
                + ",\"proposalId\":" + string(proposalId) + "}";
        return json(200, envelope(result, proofKey, record));
    }

    private String chain(Map<String, List<String>> parameters) {
        List<String> available = context.queryService().chainIds();
        List<String> supplied = parameters.get("chain");
        if (supplied == null) {
            if (available.size() != 1) throw invalid();
            return available.getFirst();
        }
        if (supplied.size() != 1 || !available.contains(supplied.getFirst())) throw invalid();
        return supplied.getFirst();
    }

    private AppQueryResult query(String chain, String parameters) {
        final AppQueryResult result;
        try {
            result = context.queryService().query(chain,
                    "components/role-approvals/evidence-approval",
                    parameters.getBytes(StandardCharsets.US_ASCII));
        } catch (AppQueryException failure) {
            throw translate(failure);
        } catch (RuntimeException failure) {
            throw new DomainApiException(DomainApiException.Code.FAILED,
                    "Role workflow query failed", failure);
        }
        if (!chain.equals(result.chainId())
                || !RoleEvidenceStateMachineProvider.ID.equals(result.stateMachineId())) {
            throw new DomainApiException(DomainApiException.Code.FAILED,
                    "Role workflow query identity mismatch");
        }
        return result;
    }

    private static long positive(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw invalid();
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw invalid();
        }
    }

    private static String envelope(AppQueryResult result, byte[] proofKey, String record) {
        return "{\"chainId\":" + string(result.chainId())
                + ",\"stateMachineId\":" + string(result.stateMachineId())
                + ",\"committedHeight\":" + result.committedHeight()
                + ",\"stateRoot\":" + string(HexFormat.of().formatHex(result.stateRoot()))
                + ",\"proofKey\":" + string(HexFormat.of().formatHex(proofKey))
                + ",\"recordValue\":"
                + string(HexFormat.of().formatHex(result.payload()))
                + ",\"record\":" + record + "}";
    }

    private static DomainApiException translate(AppQueryException failure) {
        return new DomainApiException(switch (failure.code()) {
            case INVALID_REQUEST, REQUEST_TOO_LARGE -> DomainApiException.Code.INVALID_REQUEST;
            case UNSUPPORTED -> DomainApiException.Code.NOT_FOUND;
            case BUSY -> DomainApiException.Code.BUSY;
            case TIMEOUT -> DomainApiException.Code.TIMEOUT;
            case RESULT_TOO_LARGE -> DomainApiException.Code.RESULT_TOO_LARGE;
            case UNAVAILABLE -> DomainApiException.Code.UNAVAILABLE;
            case FAILED -> DomainApiException.Code.FAILED;
        }, "Role workflow query failed", failure);
    }

    private static DomainApiException invalid() {
        return new DomainApiException(DomainApiException.Code.INVALID_REQUEST,
                "Invalid role workflow request");
    }

    private static DomainApiResponse json(int status, String body) {
        return new DomainApiResponse(status, DomainApiMediaType.JSON,
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static String string(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\') result.append('\\').append(character);
            else if (character >= 0x20 && character <= 0x7e) result.append(character);
            else result.append(String.format("\\u%04x", (int) character));
        }
        return result.append('"').toString();
    }
}
