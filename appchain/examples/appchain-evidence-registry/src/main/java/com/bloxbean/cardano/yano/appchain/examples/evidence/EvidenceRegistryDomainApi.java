package com.bloxbean.cardano.yano.appchain.examples.evidence;

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
import com.bloxbean.cardano.yano.appchain.examples.evidence.query.EvidenceGetRequestV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.query.EvidenceGetResponseV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceEffectRef;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceRecordV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceStatus;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceTerminalResultV1;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Stateless JSON projection over the root-fixed {@code evidence/get} query. */
public final class EvidenceRegistryDomainApi implements DomainApi {
    static final String GET_ROUTE = "get-evidence";
    private static final Set<String> QUERY_PARAMETERS = Set.of("chain", "version");
    private static final List<DomainApiRoute> ROUTES = List.of(new DomainApiRoute(
            GET_ROUTE, DomainHttpMethod.GET, "evidence/{evidence_id}", DomainApiAccess.READ));

    private final DomainApiContext context;

    EvidenceRegistryDomainApi(DomainApiContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public List<DomainApiRoute> routes() {
        return ROUTES;
    }

    @Override
    public DomainApiResponse handle(DomainApiRequest request) {
        if (request == null || !GET_ROUTE.equals(request.routeId())
                || request.method() != DomainHttpMethod.GET) {
            throw new DomainApiException(DomainApiException.Code.NOT_FOUND,
                    "Evidence route not found");
        }
        if (!QUERY_PARAMETERS.containsAll(request.queryParameters().keySet())) {
            throw invalidRequest();
        }

        String evidenceId = request.pathParameters().get("evidence_id");
        long version = version(request.queryParameters());
        EvidenceGetRequestV1 query;
        try {
            query = new EvidenceGetRequestV1(evidenceId, version);
        } catch (RuntimeException invalid) {
            throw invalidRequest();
        }
        String chainId = selectChain(request.queryParameters());

        final AppQueryResult result;
        try {
            result = context.queryService().query(
                    chainId, EvidenceContract.GET_QUERY_PATH, query.encode());
        } catch (AppQueryException failure) {
            throw translate(failure);
        } catch (DomainApiException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new DomainApiException(DomainApiException.Code.FAILED,
                    "Evidence query failed", failure);
        }
        if (!chainId.equals(result.chainId())
                || !EvidenceContract.STATE_MACHINE_ID.equals(result.stateMachineId())) {
            throw new DomainApiException(DomainApiException.Code.FAILED,
                    "Evidence query identity mismatch");
        }

        final EvidenceGetResponseV1 response;
        try {
            response = EvidenceGetResponseV1.decode(result.payload());
        } catch (RuntimeException malformed) {
            throw new DomainApiException(DomainApiException.Code.FAILED,
                    "Evidence query returned an invalid payload", malformed);
        }
        if (!response.found()) {
            return json(404, "{\"error\":\"not-found\"}");
        }
        EvidenceRecordV1 record = response.record();
        long expectedVersion = query.latest()
                ? response.head().latestVersion() : query.businessVersion();
        if (!record.evidenceId().equals(query.evidenceId())
                || record.businessVersion() != expectedVersion) {
            throw new DomainApiException(DomainApiException.Code.FAILED,
                    "Evidence query result does not match the request");
        }
        return json(200, foundJson(result, response));
    }

    private String selectChain(Map<String, List<String>> parameters) {
        List<String> chains;
        try {
            chains = context.queryService().chainIds();
        } catch (RuntimeException failure) {
            throw new DomainApiException(DomainApiException.Code.UNAVAILABLE,
                    "Evidence query service unavailable", failure);
        }
        if (chains.isEmpty()) {
            throw new DomainApiException(DomainApiException.Code.UNAVAILABLE,
                    "No app chains are available");
        }
        List<String> supplied = parameters.get("chain");
        if (supplied == null) {
            if (chains.size() != 1) {
                throw invalidRequest();
            }
            return chains.getFirst();
        }
        if (supplied.size() != 1 || !chains.contains(supplied.getFirst())) {
            throw invalidRequest();
        }
        return supplied.getFirst();
    }

    private static long version(Map<String, List<String>> parameters) {
        List<String> supplied = parameters.get("version");
        if (supplied == null) {
            return 0;
        }
        if (supplied.size() != 1) {
            throw invalidRequest();
        }
        String value = supplied.getFirst();
        if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) {
            throw invalidRequest();
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0 || !Long.toString(parsed).equals(value)) {
                throw invalidRequest();
            }
            return parsed;
        } catch (NumberFormatException invalid) {
            throw invalidRequest();
        }
    }

    private static String foundJson(AppQueryResult result, EvidenceGetResponseV1 response) {
        EvidenceRecordV1 record = response.record();
        StringBuilder json = new StringBuilder(1_024)
                .append('{')
                .append("\"chainId\":").append(string(result.chainId()))
                .append(",\"committedHeight\":").append(result.committedHeight())
                .append(",\"stateRoot\":").append(hexString(result.stateRoot()))
                .append(",\"evidenceId\":").append(string(record.evidenceId()))
                .append(",\"version\":").append(record.businessVersion())
                .append(",\"latestVersion\":").append(response.head().latestVersion())
                .append(",\"ownerPublicKey\":").append(hexString(record.ownerPublicKey()))
                .append(",\"status\":").append(string(EvidenceStatus.derive(record).name()))
                .append(",\"headKey\":").append(hexString(response.headKey()))
                .append(",\"headValue\":").append(hexString(response.headValue()))
                .append(",\"recordKey\":").append(hexString(response.recordKey()))
                .append(",\"recordValue\":").append(hexString(response.recordValue()))
                .append(",\"effects\":{")
                .append("\"object\":");
        appendEffect(json, record.objectEffect(), record.objectTerminal());
        json.append(",\"ipfs\":");
        appendEffect(json, record.ipfsEffect(), record.ipfsTerminal());
        json.append(",\"notification\":");
        appendEffect(json, record.notificationEffect(), record.notificationTerminal());
        return json.append("}}" ).toString();
    }

    private static void appendEffect(StringBuilder json, EvidenceEffectRef effect,
                                     EvidenceTerminalResultV1 terminal) {
        if (effect == null) {
            json.append("null");
            return;
        }
        json.append("{\"height\":").append(effect.height())
                .append(",\"ordinal\":").append(effect.ordinal())
                .append(",\"terminal\":");
        if (terminal == null) {
            json.append("null}");
            return;
        }
        json.append("{\"outcome\":").append(string(terminal.outcome().name()))
                .append(",\"externalRef\":").append(hexString(terminal.externalRef()))
                .append(",\"detailHash\":");
        if (terminal.detailHash() == null) {
            json.append("null");
        } else {
            json.append(hexString(terminal.detailHash()));
        }
        json.append(",\"resultHeight\":").append(terminal.resultHeight()).append("}}");
    }

    private static DomainApiException translate(AppQueryException failure) {
        DomainApiException.Code code = switch (failure.code()) {
            case INVALID_REQUEST, REQUEST_TOO_LARGE -> DomainApiException.Code.INVALID_REQUEST;
            case UNSUPPORTED -> DomainApiException.Code.NOT_FOUND;
            case BUSY -> DomainApiException.Code.BUSY;
            case TIMEOUT -> DomainApiException.Code.TIMEOUT;
            case RESULT_TOO_LARGE -> DomainApiException.Code.RESULT_TOO_LARGE;
            case UNAVAILABLE -> DomainApiException.Code.UNAVAILABLE;
            case FAILED -> DomainApiException.Code.FAILED;
        };
        return new DomainApiException(code, "Evidence query failed", failure);
    }

    private static DomainApiException invalidRequest() {
        return new DomainApiException(DomainApiException.Code.INVALID_REQUEST,
                "Invalid evidence request");
    }

    private static DomainApiResponse json(int status, String body) {
        return new DomainApiResponse(status, DomainApiMediaType.JSON,
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static String hexString(byte[] value) {
        return string(HexFormat.of().formatHex(value));
    }

    private static String string(String value) {
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
                                    (character >>> shift) & 0xf, 16));
                        }
                    }
                }
            }
        }
        return encoded.append('"').toString();
    }
}
