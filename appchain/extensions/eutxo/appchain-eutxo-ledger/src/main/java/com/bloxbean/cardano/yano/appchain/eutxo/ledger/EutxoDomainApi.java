package com.bloxbean.cardano.yano.appchain.eutxo.ledger;

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
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoQueryCodec;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoTransactionSummary;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Host-dispatched JSON projection over committed EUTxO summaries. */
public final class EutxoDomainApi implements DomainApi {
    private static final String LIST = "list-transactions";
    private static final String TRANSACTION = "get-transaction";
    private static final String MESSAGE = "get-transaction-by-message";
    private static final List<DomainApiRoute> ROUTES = List.of(
            route(LIST, "transactions"),
            route(TRANSACTION, "transactions/{id}"),
            route(MESSAGE, "messages/{id}"));
    private static final Set<String> QUERY =
            Set.of("chain", "limit", "before");
    private final DomainApiContext context;

    public EutxoDomainApi(DomainApiContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public List<DomainApiRoute> routes() {
        return ROUTES;
    }

    @Override
    public DomainApiResponse handle(DomainApiRequest request) {
        if (request == null || request.method() != DomainHttpMethod.GET
                || ROUTES.stream().noneMatch(route ->
                route.routeId().equals(request.routeId()))
                || !QUERY.containsAll(request.queryParameters().keySet())) {
            throw invalid();
        }
        String chain = chain(request.queryParameters());
        if (LIST.equals(request.routeId())) {
            int limit = integer(request.queryParameters(), "limit", 20, 1, 50);
            long before = longInteger(
                    request.queryParameters(), "before", 0);
            AppQueryResult result = query(chain,
                    EutxoQueryCodec.TRANSACTION_SUMMARIES_PATH,
                    EutxoQueryCodec.summaryPageRequest(before, limit));
            List<EutxoTransactionSummary> summaries =
                    EutxoTransactionSummary.decodeList(result.payload());
            String records = summaries.stream()
                    .map(EutxoDomainApi::summary)
                    .collect(Collectors.joining(",", "[", "]"));
            long nextBefore = summaries.isEmpty()
                    ? 0 : summaries.getLast().sequence();
            return json(200, envelope(
                    result, records, ",\"nextBefore\":"
                            + nextBefore));
        }
        if (request.queryParameters().containsKey("limit")
                || request.queryParameters().containsKey("before")) {
            throw invalid();
        }
        String id = request.pathParameters().get("id");
        final byte[] params;
        final String path;
        try {
            if (TRANSACTION.equals(request.routeId())) {
                params = EutxoQueryCodec.transactionRequest(id);
                path = EutxoQueryCodec.TRANSACTION_SUMMARY_PATH;
            } else if (MESSAGE.equals(request.routeId())) {
                params = HexFormat.of().parseHex(id);
                EutxoQueryCodec.attemptRequest(params);
                path = EutxoQueryCodec.MESSAGE_SUMMARY_PATH;
            } else {
                throw invalid();
            }
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
        AppQueryResult result = query(chain, path, params);
        if (result.payload().length == 0) {
            return json(404, "{\"error\":\"not-found\"}");
        }
        return json(200, envelope(
                result, summary(EutxoTransactionSummary.decode(
                        result.payload()))));
    }

    private AppQueryResult query(
            String chain,
            String path,
            byte[] params) {
        try {
            AppQueryResult result = context.queryService().query(
                    chain, path, params);
            if (!chain.equals(result.chainId())
                    || !EutxoStateMachine.ID.equals(
                    result.stateMachineId())) {
                throw new DomainApiException(
                        DomainApiException.Code.FAILED,
                        "EUTxO query identity mismatch");
            }
            return result;
        } catch (DomainApiException failure) {
            throw failure;
        } catch (AppQueryException failure) {
            throw new DomainApiException(
                    DomainApiException.Code.FAILED,
                    "EUTxO query failed", failure);
        }
    }

    private String chain(Map<String, List<String>> parameters) {
        List<String> available = context.queryService().chainIds();
        List<String> supplied = parameters.get("chain");
        if (supplied == null) {
            if (available.size() != 1) {
                throw invalid();
            }
            return available.getFirst();
        }
        if (supplied.size() != 1
                || !available.contains(supplied.getFirst())) {
            throw invalid();
        }
        return supplied.getFirst();
    }

    private static int integer(
            Map<String, List<String>> values,
            String name,
            int fallback,
            int minimum,
            int maximum) {
        List<String> supplied = values.get(name);
        if (supplied == null) {
            return fallback;
        }
        try {
            int value = supplied.size() == 1
                    ? Integer.parseInt(supplied.getFirst()) : -1;
            if (value < minimum || value > maximum) {
                throw invalid();
            }
            return value;
        } catch (NumberFormatException failure) {
            throw invalid();
        }
    }

    private static long longInteger(
            Map<String, List<String>> values,
            String name,
            long fallback) {
        List<String> supplied = values.get(name);
        if (supplied == null) {
            return fallback;
        }
        try {
            long value = supplied.size() == 1
                    ? Long.parseLong(supplied.getFirst()) : -1;
            if (value < 0) {
                throw invalid();
            }
            return value;
        } catch (NumberFormatException failure) {
            throw invalid();
        }
    }

    private static String envelope(
            AppQueryResult result,
            String data) {
        return envelope(result, data, "");
    }

    private static String envelope(
            AppQueryResult result,
            String data,
            String additionalFields) {
        return "{\"chainId\":" + string(result.chainId())
                + ",\"stateMachineId\":"
                + string(result.stateMachineId())
                + ",\"committedHeight\":" + result.committedHeight()
                + ",\"stateRoot\":"
                + string(HexFormat.of().formatHex(result.stateRoot()))
                + ",\"data\":" + data + additionalFields + "}";
    }

    private static String summary(EutxoTransactionSummary value) {
        return "{\"transactionId\":" + string(value.transactionId())
                + ",\"messageId\":" + string(value.messageId())
                + ",\"sequence\":" + value.sequence()
                + ",\"appHeight\":" + value.appHeight()
                + ",\"ordinal\":" + value.ordinal()
                + ",\"l1Slot\":" + value.l1Slot()
                + ",\"status\":" + string(value.status().name())
                + ",\"authorizationProfile\":"
                + string(value.authorizationProfile())
                + ",\"inputs\":" + entries(value.inputs())
                + ",\"outputs\":" + entries(value.outputs())
                + ",\"code\":" + string(value.code()) + "}";
    }

    private static String entries(
            List<EutxoTransactionSummary.Entry> values) {
        return values.stream().map(value ->
                        "{\"outpoint\":" + string(value.outpoint().toString())
                                + ",\"address\":"
                                + string(value.address())
                                + ",\"lovelace\":"
                                + string(value.lovelace().toString()) + "}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static DomainApiRoute route(String id, String template) {
        return new DomainApiRoute(
                id, DomainHttpMethod.GET, template,
                DomainApiAccess.READ);
    }

    private static DomainApiResponse json(int status, String body) {
        return new DomainApiResponse(
                status, DomainApiMediaType.JSON,
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static DomainApiException invalid() {
        return new DomainApiException(
                DomainApiException.Code.INVALID_REQUEST,
                "Invalid EUTxO explorer request");
    }

    private static String string(String value) {
        StringBuilder result =
                new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\') {
                result.append('\\').append(character);
            } else if (character >= 0x20 && character <= 0x7e) {
                result.append(character);
            } else {
                result.append(String.format(
                        java.util.Locale.ROOT,
                        "\\u%04x", (int) character));
            }
        }
        return result.append('"').toString();
    }
}
